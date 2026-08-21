package fuck.andes.agent.acp

import android.content.Context
import fuck.andes.agent.terminal.AlpineEnvironmentInstaller
import fuck.andes.agent.terminal.AlpineEnvironmentPaths
import fuck.andes.agent.terminal.InstallerShellRunner
import fuck.andes.agent.terminal.TerminalEnvironment
import fuck.andes.core.AndroidAgentLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ACP Agent 一键配置引擎（移植自 OpenOmniBot 的 managed-agent 目录）。
 *
 * OmniBot 的 Agent 模式维护一份「官方 agent 目录」，每个 agent 对应一个
 * 可自动安装的 CLI 运行时，并提供 discover/install/health 三个操作。
 * 安装目标固定为 Eta 自己的 Alpine 工具环境
 * （[AlpineEnvironmentPaths.rootfsDir]），全部经 chroot 执行。
 *
 * 安装逻辑对齐 OpenOmniBot `EnvironmentSetupLogic.buildInstallCommands`：
 *  - 系统包用 apk（含 node-gyp 所需的 build-base，deepseek 需 python3/uv）
 *  - npm 全局前缀固定 /root/.npm-global，再 ln -s 到 /usr/local/bin
 *    （/usr/local/bin 已在 chroot PATH 内，保证 ACP 进程能直接启动）
 *  - apk 失败时用 `apk fix` 修复中断包后重试一次
 *
 * 与 OmniBot 的差异：
 *  - 小万（xiaowan）是 OmniBot 内置 loopback agent，不依赖外部进程，
 *    Eta 未移植内置实现，故官方目录不包含它；
 *  - ACP 进程本身由 [AcpProcessConnection] 经 Alpine chroot 启动，
 *    因此 profile.command 是 Alpine 内的命令名，linuxRootfsPath 记录宿主路径。
 */
internal data class AcpOfficialAgent(
    val id: String,
    val name: String,
    val description: String,
    /** Alpine rootfs 内可见的启动命令（经 chroot 执行）。 */
    val command: String,
    val arguments: List<String> = emptyList(),
    /** npm 全局包 spec（@scope/pkg@version），安装到 /root/.npm-global。 */
    val npmPackages: List<String>,
    /** 安装前必须存在的 Alpine 系统包（apk add）。 */
    val apkPackages: List<String> = emptyList(),
    /** 安装成功后自动保存为内置 profile。 */
    val builtIn: Boolean = true,
)

/**
 * Alpine apk 包管理的预置依赖（对齐 OmniBot alpineInstallPackageMap）。
 * codex/claude/opencode 只需 nodejs+npm+git+bash+curl+ripgrep；
 * deepseek_harness 额外需要 build-base（gcc/g++/make）+ python3。
 */
private object AcpAlpinePackages {
    val COMMON = listOf("nodejs", "npm", "git", "bash", "curl", "ripgrep")
    val DEEPSEEK = COMMON + listOf("build-base", "python3")
}

/** 官方 agent 目录：与 OpenOmniBot OFFICIAL_AGENTS 对齐（去掉内置小万）。 */
internal object AcpOfficialAgents {
    val ALL: List<AcpOfficialAgent> = listOf(
        AcpOfficialAgent(
            id = "codex-acp",
            name = "Codex",
            description = "OpenAI Codex 官方 ACP 适配器（npm: @openai/codex + @agentclientprotocol/codex-acp）",
            command = "codex-acp",
            arguments = listOf("--stdio"),
            npmPackages = listOf(
                "@openai/codex@latest",
                "@agentclientprotocol/codex-acp@latest",
            ),
            apkPackages = AcpAlpinePackages.COMMON,
        ),
        AcpOfficialAgent(
            id = "gemini-cli",
            name = "Gemini CLI",
            description = "Google Gemini CLI 内置 ACP server（npm: @google/gemini-cli）",
            command = "gemini",
            arguments = listOf("--acp"),
            npmPackages = listOf("@google/gemini-cli@latest"),
            apkPackages = AcpAlpinePackages.COMMON,
        ),
        AcpOfficialAgent(
            id = "deepseek-harness",
            name = "DeepSeek Harness",
            description = "DeepSeek Harness 官方 ACP server（npm: @deepseek-ai/dsh-acp-demo）",
            command = "dsh-acp-demo",
            arguments = listOf("--config", "cordis.yml"),
            npmPackages = listOf("@deepseek-ai/dsh-acp-demo@latest"),
            apkPackages = AcpAlpinePackages.DEEPSEEK,
        ),
        AcpOfficialAgent(
            id = "claude-code-acp",
            name = "Claude Code",
            description = "Claude Code ACP 适配器（npm: @anthropic-ai/claude-code + @agentclientprotocol/claude-agent-acp）",
            command = "claude-agent-acp",
            npmPackages = listOf(
                "@anthropic-ai/claude-code@latest",
                "@agentclientprotocol/claude-agent-acp@latest",
            ),
            apkPackages = AcpAlpinePackages.COMMON,
        ),
        AcpOfficialAgent(
            id = "opencode-acp",
            name = "OpenCode",
            description = "OpenCode ACP server（npm: opencode-ai）",
            command = "opencode",
            arguments = listOf("acp"),
            npmPackages = listOf("opencode-ai@latest"),
            apkPackages = AcpAlpinePackages.COMMON,
        ),
    )

    fun byId(id: String): AcpOfficialAgent? = ALL.firstOrNull { it.id == id }
}

/** 一键安装的逐步状态。 */
internal sealed interface AcpSetupStage {
    data object Idle : AcpSetupStage
    data object CheckingAlpine : AcpSetupStage
    data object InstallingAlpine : AcpSetupStage
    data class InstallingAgent(val agentId: String, val packageName: String?) : AcpSetupStage
    data class Verifying(val agentId: String) : AcpSetupStage
    data class Done(val installed: List<String>, val failed: List<String>) : AcpSetupStage
}

internal data class AcpAgentEnvironmentStatus(
    val alpineReady: Boolean,
    val alpineInstalling: Boolean = false,
    /** agentId -> 是否已安装（Alpine 内 command -v 命中）。 */
    val installed: Map<String, Boolean> = emptyMap(),
)

/**
 * 一键配置引擎。所有命令都在 Alpine rootfs 内（chroot）执行，
 * 安装过程不会扩大到 App 私有环境目录之外。
 */
internal class AcpAgentEnvironmentInstaller(
    private val context: Context,
) {
    private val alpineInstaller = AlpineEnvironmentInstaller(context.applicationContext)

    fun rootfsPath(): String = AlpineEnvironmentPaths.rootfsDir(context).absolutePath

    /**
     * 轻量状态：只检查 Alpine 是否就绪（不探测各 agent，避免阻塞 UI）。
     * 完整探测用 [refreshStatus]。
     */
    fun status(): AcpAgentEnvironmentStatus {
        val rootfs = AlpineEnvironmentPaths.rootfsDir(context)
        val ready = AlpineEnvironmentPaths.commonToolsReady(rootfs.absolutePath)
        return AcpAgentEnvironmentStatus(alpineReady = ready)
    }

    /** 完整探测：Alpine 就绪时逐个 command -v 探测官方 agent。 */
    suspend fun refreshStatus(): AcpAgentEnvironmentStatus = withContext(Dispatchers.IO) {
        val rootfs = AlpineEnvironmentPaths.rootfsDir(context)
        if (!AlpineEnvironmentPaths.commonToolsReady(rootfs.absolutePath)) {
            return@withContext AcpAgentEnvironmentStatus(alpineReady = false)
        }
        val installed = AcpOfficialAgents.ALL.associate { agent ->
            agent.id to probeAgent(agent)
        }
        AcpAgentEnvironmentStatus(alpineReady = true, installed = installed)
    }

    private suspend fun probeAgent(agent: AcpOfficialAgent): Boolean = runCatching {
        val result = InstallerShellRunner.run(
            command = "command -v ${shellQuote(agent.command)} >/dev/null 2>&1",
            timeoutSeconds = 20,
            environment = TerminalEnvironment.LINUX,
            linuxRootfsPath = rootfsPath(),
        )
        result.exitCode == 0
    }.getOrDefault(false)

    /**
     * 确保 Alpine 工具环境就绪（含 nodejs/npm）。返回 false 表示失败。
     */
    suspend fun ensureAlpine(
        onStage: (AcpSetupStage) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        onStage(AcpSetupStage.CheckingAlpine)
        val rootfs = AlpineEnvironmentPaths.rootfsDir(context)
        if (AlpineEnvironmentPaths.commonToolsReady(rootfs.absolutePath)) {
            ensureBasePackages(onStage)
            return@withContext true
        }
        onStage(AcpSetupStage.InstallingAlpine)
        val result = alpineInstaller.install { progress ->
            AndroidAgentLogger.debug { "acp-setup alpine progress=$progress" }
        }
        when (result) {
            is fuck.andes.agent.terminal.AlpineInstallResult.AlreadyReady,
            is fuck.andes.agent.terminal.AlpineInstallResult.Installed,
            -> {
                ensureBasePackages(onStage)
                true
            }
            else -> {
                AndroidAgentLogger.warn("acp-setup alpine install failed: $result")
                false
            }
        }
    }

    /** 配置 npm 镜像源 + 通用 base 工具，幂等可重复执行。 */
    private suspend fun ensureBasePackages(onStage: (AcpSetupStage) -> Unit) {
        val result = InstallerShellRunner.run(
            command = """
                set -e
                # 1) 常用工具（幂等）
                apk add --no-cache nodejs npm git bash curl ripgrep >/dev/null 2>&1 || true
                # 2) node-gyp 构建链（node-pty 等原生模块需要）
                apk add --no-cache build-base python3 >/dev/null 2>&1 || true
                # 3) npm 镜像源（npmmirror 秒下；可被用户覆盖），并固定全局前缀
                npm config set registry https://registry.npmmirror.com
                npm config set prefer-offline true
                npm config set prefix /root/.npm-global
                npm config set disturl https://npmmirror.com/mirrors/node
                npm config set electron_mirror https://npmmirror.com/mirrors/electron/
                npm config set sass_binary_site https://npmmirror.com/mirrors/node-sass/
                # 4) 确保 PATH 含 npm-global bin（为后续 npm install -g 的可见性）
                mkdir -p /root/.npm-global/bin
                command -v node >/dev/null 2>&1
            """.trimIndent(),
            timeoutSeconds = 900,
            environment = TerminalEnvironment.LINUX,
            linuxRootfsPath = rootfsPath(),
        )
        AndroidAgentLogger.info(
            "acp-setup base outcome=${if (result.exitCode == 0) "ok" else "failed"} " +
                "exit=${result.exitCode} out=${result.output.takeLast(400)}"
        )
    }

    /**
     * 安装单个官方 agent。返回 null 表示成功，否则返回错误信息。
     */
    suspend fun installAgent(
        agent: AcpOfficialAgent,
        onStage: (AcpSetupStage) -> Unit = {},
    ): String? = withContext(Dispatchers.IO) {
        val ready = ensureAlpine { stage ->
            if (stage is AcpSetupStage.InstallingAlpine) onStage(stage)
        }
        if (!ready) return@withContext "Alpine 工具环境安装失败"
        if (probeAgent(agent)) {
            // 已安装则直接落 profile
            writeProfileIfNeeded(agent)
            return@withContext null
        }
        onStage(AcpSetupStage.InstallingAgent(agent.id, agent.npmPackages.firstOrNull()))
        val installCommand = buildInstallCommand(agent)
        val result = InstallerShellRunner.run(
            command = installCommand,
            timeoutSeconds = NPM_INSTALL_TIMEOUT_SECONDS,
            environment = TerminalEnvironment.LINUX,
            linuxRootfsPath = rootfsPath(),
        )
        if (result.exitCode != 0) {
            AndroidAgentLogger.warn(
                "acp-setup agent=${agent.id} install failed exit=${result.exitCode} " +
                    "out=${result.output.takeLast(1500)}"
            )
            return@withContext "安装失败: ${result.output.takeLast(300)}"
        }
        onStage(AcpSetupStage.Verifying(agent.id))
        if (!probeAgent(agent)) {
            return@withContext "安装完成但命令 ${agent.command} 未找到"
        }
        writeProfileIfNeeded(agent)
        AndroidAgentLogger.info("acp-setup agent=${agent.id} installed")
        null
    }

    /**
     * 生成安装脚本，对齐 OmniBot buildInstallCommands：
     *  1. 系统包 apk add（含 apk fix 重试）
     *  2. npm config prefix=/root/.npm-global + PATH
     *  3. npm install -g 各包
     *  4. ln -s 把 npm-global/bin/<cmd> 链接到 /usr/local/bin（已在 chroot PATH）
     *  5. command -v 校验
     */
    private fun buildInstallCommand(agent: AcpOfficialAgent): String = buildString {
        append("set -e\n")
        // apk 系统包（含修复重试）
        if (agent.apkPackages.isNotEmpty()) {
            append(ALPINE_APK_INSTALL_WITH_REPAIR)
            append("\nomnibot_apk_add ")
            append(agent.apkPackages.joinToString(" "))
            append("\n")
        }
        // npm 配置
        append("mkdir -p /root/.npm-global/bin\n")
        append("export npm_config_registry=https://registry.npmmirror.com\n")
        append("export npm_config_prefer_offline=true\n")
        append("export npm_config_audit=false npm_config_fund=false\n")
        append("export npm_config_prefix=/root/.npm-global\n")
        append("export PATH=/root/.npm-global/bin:$PATH\n")
        append("npm install -g --prefix /root/.npm-global --no-audit --no-fund ")
        append(agent.npmPackages.joinToString(" "))
        append("\n")
        // 链接到 /usr/local/bin（chroot PATH 内），保证 ACP 进程能找到
        append("ln -sf /root/.npm-global/bin/${shellQuote(agent.command)} /usr/local/bin/${shellQuote(agent.command)} || true\n")
        append("command -v ")
        append(shellQuote(agent.command))
    }

    /** 安装 dsh 时同时生成 cordis.yml（与 RikkaHub proot 工作区配置对齐）。 */
    private fun writeDshCordisIfNeeded(agent: AcpOfficialAgent) {
        if (agent.id != "deepseek-harness") return
        val rootfs = AlpineEnvironmentPaths.rootfsDir(context)
        val cordis = File(rootfs, "root/cordis.yml")
        if (cordis.isFile) return
        runCatching {
            cordis.parentFile?.mkdirs()
            cordis.writeText(DSH_CORDIS_YML)
            AndroidAgentLogger.info("acp-setup dsh cordis.yml written")
        }
    }

    /** 安装成功后自动保存/更新内置 profile。 */
    private fun writeProfileIfNeeded(agent: AcpOfficialAgent) {
        writeDshCordisIfNeeded(agent)
        val rootfs = AlpineEnvironmentPaths.rootfsDir(context)
        val profile = AcpAgentProfile(
            id = agent.id,
            name = agent.name,
            description = agent.description,
            command = agent.command,
            arguments = agent.arguments,
            cwd = if (agent.id == "deepseek-harness") "/root" else "",
            environment = mapOf(
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            ),
            enabled = true,
            allowToolsWithoutPrompt = true,
            useRoot = true,
            linuxRootfsPath = rootfs.absolutePath,
        )
        AcpProfileStore.save(context, profile)
    }

    private companion object {
        const val NPM_INSTALL_TIMEOUT_SECONDS = 1_200L

        /** Alpine apk 安装 + 中断修复重试（对齐 OmniBot）。 */
        const val ALPINE_APK_INSTALL_WITH_REPAIR = """
            omnibot_apk_add() {
              omnibot_apk_status=0
              apk add --no-cache "${'$'}@" || omnibot_apk_status=${'$'}?
              if [ "${'$'}omnibot_apk_status" -eq 0 ]; then
                return 0
              fi
              apk fix --no-cache || apk fix --no-cache --upgrade || true
              apk add --no-cache "${'$'}@"
            }
        """.trimIndent()

        internal const val DSH_CORDIS_YML = """# Generated by Eta ACP setup for DeepSeek Harness.
# Matches the RikkaHub /workspace/acp configuration (danger-full-access).
permissionMode: danger-full-access
sandbox-policy:
  mode: danger-full-access
approval:
  policy: never
persistence:
  compression: none
"""
    }
}

private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"
