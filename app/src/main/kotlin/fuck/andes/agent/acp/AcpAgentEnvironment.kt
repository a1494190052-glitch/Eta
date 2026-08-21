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
 * 可自动安装的 CLI 运行时（npm 全局包），并提供 discover/install/health
 * 三个操作。Eta 移植版把安装目标固定为 Eta 自己的 Alpine 工具环境
 * （[AlpineEnvironmentPaths.rootfsDir]），全部经 chroot 执行：
 *
 *  - discover   = 在 Alpine 里 `command -v <cmd>` 探测是否已装
 *  - install    = 确保 Alpine 就绪 → apk add nodejs npm → npm install -g <pkgs>
 *  - profile    = 安装成功后自动生成对应的 [AcpAgentProfile] 并写入 [AcpProfileStore]
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
    /** npm 全局包 spec（@scope/pkg@version）。 */
    val packages: List<String>,
    /** 安装前必须存在的 Alpine 系统包（nodejs/npm 由引擎统一保证）。 */
    val extraApkPackages: List<String> = emptyList(),
    /** 安装成功后建议自动保存为内置 profile。 */
    val builtIn: Boolean = true,
)

/** 官方 agent 目录：与 OpenOmniBot OFFICIAL_AGENTS 对齐（去掉内置小万）。 */
internal object AcpOfficialAgents {
    val ALL: List<AcpOfficialAgent> = listOf(
        AcpOfficialAgent(
            id = "codex-acp",
            name = "Codex",
            description = "OpenAI Codex 官方 ACP 适配器（npm: @openai/codex + @agentclientprotocol/codex-acp）",
            command = "codex-acp",
            arguments = listOf("--stdio"),
            packages = listOf(
                "@openai/codex@latest",
                "@agentclientprotocol/codex-acp@latest",
            ),
        ),
        AcpOfficialAgent(
            id = "gemini-cli",
            name = "Gemini CLI",
            description = "Google Gemini CLI 内置 ACP server（npm: @google/gemini-cli）",
            command = "gemini",
            arguments = listOf("--acp"),
            packages = listOf("@google/gemini-cli@latest"),
        ),
        AcpOfficialAgent(
            id = "deepseek-harness",
            name = "DeepSeek Harness",
            description = "DeepSeek Harness 官方 ACP server（npm: @deepseek-ai/dsh-acp-demo）",
            command = "dsh-acp-demo",
            arguments = listOf("--config", "cordis.yml"),
            packages = listOf("@deepseek-ai/dsh-acp-demo@latest"),
            extraApkPackages = listOf("git"),
        ),
        AcpOfficialAgent(
            id = "claude-code-acp",
            name = "Claude Code",
            description = "Claude Code ACP 适配器（npm: @anthropic-ai/claude-code + @agentclientprotocol/claude-agent-acp）",
            command = "claude-agent-acp",
            packages = listOf(
                "@anthropic-ai/claude-code@latest",
                "@agentclientprotocol/claude-agent-acp@latest",
            ),
        ),
        AcpOfficialAgent(
            id = "opencode-acp",
            name = "OpenCode",
            description = "OpenCode ACP server（npm: opencode-ai）",
            command = "opencode",
            arguments = listOf("acp"),
            packages = listOf("opencode-ai@latest"),
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
            ensureNodePackages(onStage)
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
                ensureNodePackages(onStage)
                true
            }
            else -> {
                AndroidAgentLogger.warn("acp-setup alpine install failed: $result")
                false
            }
        }
    }

    /** Alpine 就绪但缺 nodejs/npm 时补装，并配置 npm 镜像源与 node-gyp 构建链。 */
    private suspend fun ensureNodePackages(onStage: (AcpSetupStage) -> Unit) {
        val result = InstallerShellRunner.run(
            command = """
                # 1) 环境依赖：nodejs/npm + node-gyp 原生编译链
                if ! command -v node >/dev/null 2>&1; then
                  apk add --no-cache nodejs npm
                else
                  command -v npm >/dev/null 2>&1 || apk add --no-cache npm
                fi
                apk add --no-cache python3 make gcc g++ linux-headers >/dev/null 2>&1 || true
                # 2) npm 全局镜像源（npmmirror 秒下；可被用户 .npmrc 覆盖）
                npm config set registry https://registry.npmmirror.com
                npm config set prefer-offline true
                # 3) 二进制镜像（node-gyp / prebuilt 下载走 npmmirror，避免连不上 GitHub）
                npm config set disturl https://npmmirror.com/mirrors/node
                npm config set electron_mirror https://npmmirror.com/mirrors/electron/
                npm config set sass_binary_site https://npmmirror.com/mirrors/node-sass/
                command -v node >/dev/null 2>&1
            """.trimIndent(),
            timeoutSeconds = 900,
            environment = TerminalEnvironment.LINUX,
            linuxRootfsPath = rootfsPath(),
        )
        AndroidAgentLogger.info(
            "acp-setup node outcome=${if (result.exitCode == 0) "ok" else "failed"} " +
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
        onStage(AcpSetupStage.InstallingAgent(agent.id, agent.packages.firstOrNull()))
        val apkPart = if (agent.extraApkPackages.isEmpty()) {
            ""
        } else {
            "apk add --no-cache ${agent.extraApkPackages.joinToString(" ")}\n"
        }
        val installCommand = buildString {
            append("set -e\n")
            append(apkPart)
            append("export NPM_CONFIG_REGISTRY=https://registry.npmmirror.com\n")
            append("export NPM_CONFIG_PREFER_OFFLINE=true\n")
            append("export NPM_CONFIG_AUDIT=false NPM_CONFIG_FUND=false\n")
            // 全局前缀固定为 /usr/local，让 bin 落在 /usr/local/bin（已在 chroot PATH 内）
            append("export NPM_CONFIG_PREFIX=/usr/local\n")
            append("npm install -g --prefix /usr/local --no-audit --no-fund ")
            append(agent.packages.joinToString(" "))
            append("\n")
            append("command -v ")
            append(shellQuote(agent.command))
        }
        val result = InstallerShellRunner.run(
            command = installCommand,
            timeoutSeconds = NPM_INSTALL_TIMEOUT_SECONDS,
            environment = TerminalEnvironment.LINUX,
            linuxRootfsPath = rootfsPath(),
        )
        if (result.exitCode != 0) {
            AndroidAgentLogger.warn(
                "acp-setup agent=${agent.id} npm failed exit=${result.exitCode} " +
                    "out=${result.output.takeLast(1200)}"
            )
            return@withContext "npm install 失败: ${result.output.takeLast(300)}"
        }
        onStage(AcpSetupStage.Verifying(agent.id))
        if (!probeAgent(agent)) {
            return@withContext "安装完成但命令 ${agent.command} 未找到"
        }
        writeProfileIfNeeded(agent)
        AndroidAgentLogger.info("acp-setup agent=${agent.id} installed")
        null
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

    companion object {
        private const val NPM_INSTALL_TIMEOUT_SECONDS = 1_200L

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
