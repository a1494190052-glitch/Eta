package fuck.andes.agent.acp

import android.content.Context
import org.json.JSONObject

/**
 * ACP 智能体定义（移植自 OpenOmniBot 的 AcpAgentProfileStore）。
 * 驱动 Codex / Claude Code / OpenCode / DeepSeek Harness 等 ACP 智能体。
 */
internal data class AcpAgentProfile(
    val id: String,
    val name: String,
    val description: String = "",
    val command: String,
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
) {
    fun toPayload(
        selected: Boolean = false,
        health: AcpAgentHealth = AcpAgentHealth(),
    ): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "name" to name,
        "description" to description,
        "command" to command,
        "arguments" to arguments,
        "environment" to environment,
        "enabled" to enabled,
        "builtIn" to builtIn,
        "source" to if (builtIn) "official" else "custom",
        "selected" to selected,
        "installed" to health.installed,
        "status" to health.status,
        "lastCheckError" to health.error,
        "lastCheckLatencyMs" to health.latencyMs,
        "lastCheckAt" to health.checkedAt,
        "capabilities" to health.capabilities,
    )
}

internal data class AcpAgentHealth(
    val status: String = STATUS_UNCHECKED,
    val installed: Boolean? = null,
    val error: String? = null,
    val latencyMs: Long? = null,
    val checkedAt: Long? = null,
    val capabilities: Map<String, Any?> = emptyMap(),
) {
    companion object {
        const val STATUS_ONLINE = "online"
        const val STATUS_OFFLINE = "offline"
        const val STATUS_MISSING = "missing"
        const val STATUS_UNCHECKED = "unchecked"
    }
}

internal object AcpAgentProfiles {
    const val CODEX_AGENT_ID = "codex-acp"
    const val CLAUDE_CODE_AGENT_ID = "claude-code-acp"
    const val OPENCODE_AGENT_ID = "opencode-acp"
    const val DEEPSEEK_HARNESS_AGENT_ID = "deepseek-harness-acp"
    const val XIAOWAN_AGENT_ID = "xiaowan-acp"
    const val DEFAULT_AGENT_ID = XIAOWAN_AGENT_ID

    val OFFICIAL_AGENTS = listOf(
        AcpAgentProfile(
            id = XIAOWAN_AGENT_ID,
            name = "小万",
            description = "小万内置能力通过官方 ACP Agent 接口提供",
            command = "omnibot-xiaowan-acp",
            builtIn = true,
        ),
        AcpAgentProfile(
            id = CODEX_AGENT_ID,
            name = "Codex",
            description = "OpenAI Codex through its managed ACP adapter",
            command = "codex-agent",
            builtIn = true,
        ),
        AcpAgentProfile(
            id = CLAUDE_CODE_AGENT_ID,
            name = "Claude Code",
            description = "Claude Code through the ACP adapter",
            command = "claude-agent-acp",
            builtIn = true,
        ),
        AcpAgentProfile(
            id = OPENCODE_AGENT_ID,
            name = "OpenCode",
            description = "OpenCode ACP server",
            command = "opencode",
            arguments = listOf("acp"),
            builtIn = true,
        ),
        AcpAgentProfile(
            id = DEEPSEEK_HARNESS_AGENT_ID,
            name = "DeepSeek Harness",
            description = "DeepSeek Harness through the existing streaming ACP adapter",
            command = "dsh-acp",
            builtIn = true,
        ),
    )

    val CODEX_ACP = OFFICIAL_AGENTS.first { it.id == CODEX_AGENT_ID }
}

/**
 * Eta 的 ACP 智能体偏好存储。
 * 文件名与 key 沿用 OmniBot 约定：eta_acp_preferences.xml。
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
            ?: AcpAgentProfiles.CODEX_AGENT_ID
        set(value) {
            prefs.edit().putString(KEY_SELECTED_ID, value).apply()
        }

    fun selectedProfile(): AcpAgentProfile {
        val profiles = list()
        val id = selectedId
        return profiles.firstOrNull { it.id == id && it.enabled }
            ?: profiles.firstOrNull { it.enabled }
            ?: profiles.first()
    }

    fun list(): List<AcpAgentProfile> = AcpAgentProfiles.OFFICIAL_AGENTS

    fun health(agentId: String): AcpAgentHealth {
        val stored = prefs.getString(KEY_HEALTH, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val entry = stored?.optJSONObject(agentId) ?: return AcpAgentHealth()
        return AcpAgentHealth(
            status = entry.optString("status", AcpAgentHealth.STATUS_UNCHECKED),
            installed = if (entry.has("installed") && !entry.isNull("installed")) {
                entry.optBoolean("installed")
            } else null,
            error = entry.optString("error").takeIf { it.isNotBlank() },
            latencyMs = if (entry.has("latencyMs") && !entry.isNull("latencyMs")) {
                entry.optLong("latencyMs")
            } else null,
            checkedAt = if (entry.has("checkedAt") && !entry.isNull("checkedAt")) {
                entry.optLong("checkedAt")
            } else null,
        )
    }

    fun saveHealth(agentId: String, health: AcpAgentHealth) {
        val current = prefs.getString(KEY_HEALTH, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject()
        val entry = JSONObject()
            .put("status", health.status)
        if (health.installed != null) entry.put("installed", health.installed)
        if (health.error != null) entry.put("error", health.error)
        if (health.latencyMs != null) entry.put("latencyMs", health.latencyMs)
        if (health.checkedAt != null) entry.put("checkedAt", health.checkedAt)
        current.put(agentId, entry)
        prefs.edit().putString(KEY_HEALTH, current.toString()).apply()
    }

    companion object {
        const val PREFERENCES_NAME = "eta_acp_preferences"
        const val KEY_ENABLED = "acp_agent_enabled"
        const val KEY_SELECTED_ID = "acp_agent_selected_id"
        const val KEY_HEALTH = "acp_agent_health"
    }
}
