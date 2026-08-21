package fuck.andes.agent.acp

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * ACP Agent 配置。
 *
 * 一个 profile 描述一个可通过 stdio 启动的 ACP 兼容 agent 进程：
 * codex-acp、Gemini CLI、DeepSeek Harness 的 ACP 插件等都属于这一类。
 * 移植自 OpenOmniBot 的 AcpAgentProfile 概念，按 Eta 的存储与
 * 单次 run 模型做了适配与精简。
 */
@Serializable
internal data class AcpAgentProfile(
    val id: String,
    val name: String,
    val description: String = "",
    /** 完整启动命令（含可执行文件路径）；由 ACP 进程直接 spawn。 */
    val command: String,
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    /** 会话工作目录；为空时使用命令自身默认目录。 */
    val cwd: String = "",
    /**
     * true = 通过 su -c 以 root 启动命令（Termux/Alpine 等私有目录下的
     * 可执行文件需要 root 才能被本进程访问）。
     */
    val useRoot: Boolean = false,
    /**
     * 工具权限策略：
     * true = agent 请求工具权限时直接选择 ALLOW_ALWAYS（不弹审批，配合
     * root/终端类工具使用需自行评估风险）；
     * false = 直接取消（MVP 尚不提供运行时审批 UI，先保证流程不断）。
     */
    val allowToolsWithoutPrompt: Boolean = true,
    /**
     * 非空时表示命令运行在 Alpine rootfs 内：进程经
     * [fuck.andes.agent.terminal.InstallerShellRunner] 的 chroot 启动，
     * command/arguments 为 rootfs 内路径。一键配置引擎写出的官方 profile
     * 都会带上该字段。
     */
    val linuxRootfsPath: String = "",
) {
    fun isUsable(): Boolean = enabled && command.isNotBlank()

    fun shellDisplayName(): String = if (name.isBlank()) command else name
}

/** Agent 健康状态（MVP：仅用于 UI 展示与错误定位）。 */
internal enum class AcpAgentStatus {
    UNCHECKED,
    ONLINE,
    OFFLINE,
    MISSING,
}

internal data class AcpAgentHealth(
    val status: AcpAgentStatus = AcpAgentStatus.UNCHECKED,
    val error: String? = null,
    val checkedAt: Long = 0L,
)

/** 内置 agent 模板。命令仅作示例，用户需按本机安装路径修改。 */
internal object AcpBuiltinProfiles {
    fun templates(): List<AcpAgentProfile> = listOf(
        AcpAgentProfile(
            id = "codex-acp",
            name = "Codex (codex-acp)",
            description = "OpenAI Codex 的 ACP 适配器；需 Termux 中已安装 node + codex-acp",
            command = "/data/user/0/com.termux/files/usr/bin/codex-acp",
            arguments = listOf("--stdio"),
            useRoot = true,
        ),
        AcpAgentProfile(
            id = "gemini-cli",
            name = "Gemini CLI",
            description = "Google Gemini CLI（内置 ACP server）；需 Termux 中已安装 gemini-cli",
            command = "/data/user/0/com.termux/files/usr/bin/gemini",
            arguments = listOf("--acp"),
            useRoot = true,
        ),
        AcpAgentProfile(
            id = "deepseek-harness",
            name = "DeepSeek Harness (omnibot-acp-demo)",
            description = "DeepSeek Harness 的 ACP 插件示例；需 node 与 omnibot-acp-demo.mjs",
            command = "/data/user/0/com.termux/files/usr/bin/node",
            arguments = listOf("omnibot-acp-demo.mjs"),
            useRoot = true,
        ),
    )
}

/**
 * Profile 持久化。
 *
 * 使用独立 SharedPreferences（MODE_MULTI_PROCESS）而非 [fuck.andes.config.Prefs]：
 * App 设置进程与 AgentRuntimeService 模块进程需要都能读写，且要绕过
 * Prefs object 的进程内缓存，保证一侧写入另一侧立即可见。
 */
internal object AcpProfileStore {
    const val GROUP = "eta_acp_preferences"

    private const val KEY_PROFILES = "acp_agent_profiles_json"
    private const val KEY_SELECTED = "acp_agent_selected_id"
    private const val KEY_ENABLED = "acp_agent_enabled"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(GROUP, Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun selectedId(context: Context): String =
        prefs(context).getString(KEY_SELECTED, DEFAULT_PROFILE_ID).orEmpty()

    fun select(context: Context, id: String) {
        prefs(context).edit().putString(KEY_SELECTED, id).apply()
    }

    fun list(context: Context): List<AcpAgentProfile> {
        val raw = prefs(context).getString(KEY_PROFILES, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<AcpAgentProfile>>(raw)
        }.getOrElse { emptyList() }
    }

    /** 返回全部可选 profile：内置模板（未自定义时）+ 用户保存的。 */
    fun candidates(context: Context): List<AcpAgentProfile> {
        val saved = list(context)
        return if (saved.isEmpty()) {
            AcpBuiltinProfiles.templates()
        } else {
            saved
        }
    }

    fun selected(context: Context): AcpAgentProfile? =
        candidates(context).firstOrNull { it.id == selectedId(context) && it.isUsable() }
            ?: candidates(context).firstOrNull { it.isUsable() }

    fun save(context: Context, profile: AcpAgentProfile) {
        val updated = list(context).filterNot { it.id == profile.id } + profile
        prefs(context).edit().putString(KEY_PROFILES, json.encodeToString(updated)).apply()
    }

    fun delete(context: Context, id: String) {
        val updated = list(context).filterNot { it.id == id }
        prefs(context).edit().putString(KEY_PROFILES, json.encodeToString(updated)).apply()
        if (selectedId(context) == id) {
            prefs(context).edit().putString(KEY_SELECTED, "").apply()
        }
    }

    const val DEFAULT_PROFILE_ID = "custom"
}
