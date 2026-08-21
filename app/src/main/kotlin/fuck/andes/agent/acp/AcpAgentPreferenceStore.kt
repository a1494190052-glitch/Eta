package fuck.andes.agent.acp

import android.content.Context

/** ACP 智能体定义（移植自 OpenOmniBot 的 AcpAgentProfileStore 精简版）。 */
internal data class AcpAgentProfile(
    val id: String,
    val name: String,
    val description: String = "",
    val command: String,
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val builtIn: Boolean = true,
)

internal object AcpAgentProfiles {
    val CODEX_ACP = AcpAgentProfile(
        id = "codex-acp",
        name = "Codex",
        description = "OpenAI Codex 通过 ACP 桥接（DeepSeek/OmniMind responses API）",
        command = "/usr/local/bin/codex-agent",
        arguments = emptyList(),
        environment = emptyMap(),
    )

    val all: List<AcpAgentProfile> = listOf(CODEX_ACP)
}

/**
 * Eta 的 ACP 智能体偏好存储。
 * 文件名与 key 沿用 OmniBot 的约定：eta_acp_preferences.xml
 * （acp_agent_enabled / acp_agent_selected_id）。
 */
internal class AcpAgentPreferenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    var selectedId: String
        get() = prefs.getString(KEY_SELECTED_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: AcpAgentProfiles.CODEX_ACP.id
        set(value) {
            prefs.edit().putString(KEY_SELECTED_ID, value).apply()
        }

    fun selectedProfile(): AcpAgentProfile =
        AcpAgentProfiles.all.firstOrNull { it.id == selectedId }
            ?: AcpAgentProfiles.CODEX_ACP

    companion object {
        const val PREFERENCES_NAME = "eta_acp_preferences"
        const val KEY_ENABLED = "acp_agent_enabled"
        const val KEY_SELECTED_ID = "acp_agent_selected_id"
    }
}
