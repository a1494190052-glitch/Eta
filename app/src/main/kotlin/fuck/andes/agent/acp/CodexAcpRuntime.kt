package fuck.andes.agent.acp

import android.content.Context
import fuck.andes.agent.runtime.AgentEvent
import fuck.andes.agent.runtime.AgentRuntimeWire
import fuck.andes.agent.terminal.AlpineEnvironmentPaths
import fuck.andes.agent.terminal.RootShellTerminalController
import fuck.andes.core.AndroidAgentLogger
import org.json.JSONObject

/**
 * 本地 ACP 运行时：在 Eta Linux 环境（Alpine chroot）中启动 codex-agent 桥接，
 * 将 stdout 增量映射为 Eta 的 AgentEvent，供聊天界面流式展示。
 *
 * 移植自 OpenOmniBot 的 LocalAcpRuntime / tools/codex-bridge 设计。
 */
internal class CodexAcpRuntime(
    context: Context,
    private val terminal: RootShellTerminalController = RootShellTerminalController(
        logger = AndroidAgentLogger,
        linuxRootfsPath = AlpineEnvironmentPaths.rootfsDir(context).absolutePath,
    ),
) {
    fun run(
        runId: String,
        prompt: String,
        profile: AcpAgentProfile = AcpAgentProfiles.CODEX_ACP,
        onEvent: (AgentEvent) -> Unit,
    ): AgentRuntimeWire.RunResult {
        onEvent(
            AgentEvent.RunStarted(
                initialImages = 0,
                initialImageBytes = 0,
                toolCount = 1,
                terminalTools = true,
            )
        )
        onEvent(
            AgentEvent.AssistantBlockStart(
                round = 0,
                kind = AgentEvent.AssistantBlockKind.TEXT,
                index = 0,
                blockId = "acp-${runId}",
                name = profile.name,
            )
        )

        val command = buildCommand(prompt, profile)
        val startRaw = terminal.terminalAction(
            action = "open_and_exec",
            command = command,
            cwd = "/workspace",
            timeoutMs = 180_000,
            identity = "root",
            mergeStderr = false,
            sessionId = null,
            jobId = null,
            async = true,
            offsetChars = 0,
            maxChars = 16_000,
            closeIfDone = false,
            environment = "linux",
        )
        val startJson = runCatching { JSONObject(startRaw) }.getOrNull()
        if (startJson == null || !startJson.optBoolean("ok", false)) {
            val error = startJson?.optString("error").orEmpty()
                .ifBlank { "无法启动 Codex ACP 进程" }
            onEvent(AgentEvent.RunFailed(error))
            return AgentRuntimeWire.RunResult(
                runId = runId,
                ok = false,
                content = "",
                error = error,
            )
        }

        val jobId = startJson.optString("job_id")
        val output = StringBuilder()
        var offset = 0
        var running = true
        var timedOut = false
        var exitCode: Int? = null

        while (running) {
            Thread.sleep(1_000)
            val raw = terminal.terminalAction(
                action = "read_async_result",
                command = "",
                cwd = "/workspace",
                timeoutMs = 0,
                identity = "root",
                mergeStderr = false,
                sessionId = null,
                jobId = jobId,
                async = false,
                offsetChars = offset,
                maxChars = 8_000,
                closeIfDone = false,
                environment = "linux",
            )
            val json = runCatching { JSONObject(raw) }.getOrNull()
            if (json == null || !json.optBoolean("ok", false)) {
                running = false
                continue
            }
            running = json.optBoolean("running", false)
            timedOut = json.optBoolean("timed_out", false)
            if (json.has("exit_code") && !json.isNull("exit_code")) {
                exitCode = json.optInt("exit_code")
            }
            val chunk = json.optString("stdout")
            if (chunk.isNotEmpty()) {
                output.append(chunk)
                offset = json.optInt("next_offset_chars", offset + chunk.length)
                onEvent(
                    AgentEvent.AssistantBlockDelta(
                        round = 0,
                        kind = AgentEvent.AssistantBlockKind.TEXT,
                        index = 0,
                        deltaChars = chunk.length,
                        delta = chunk,
                    )
                )
            }
        }

        terminal.terminalAction(
            action = "close",
            command = "",
            cwd = "/workspace",
            timeoutMs = 0,
            identity = "root",
            mergeStderr = false,
            sessionId = null,
            jobId = jobId,
            async = false,
            offsetChars = 0,
            maxChars = 0,
            closeIfDone = false,
            environment = "linux",
        )

        val content = output.toString()
        onEvent(
            AgentEvent.AssistantBlockEnd(
                round = 0,
                kind = AgentEvent.AssistantBlockKind.TEXT,
                index = 0,
                blockId = "acp-${runId}",
                name = profile.name,
                contentChars = content.length,
            )
        )
        val ok = !timedOut && (exitCode == null || exitCode == 0) && content.isNotBlank()
        if (ok) {
            onEvent(AgentEvent.RunFinished(round = 0, contentChars = content.length))
            return AgentRuntimeWire.RunResult(
                runId = runId,
                ok = true,
                content = content,
                error = null,
            )
        }
        val reason = when {
            timedOut -> "Codex 任务执行超时（180 秒）"
            exitCode != null && exitCode != 0 -> "Codex 进程退出码 $exitCode"
            else -> "Codex 没有返回内容"
        }
        onEvent(AgentEvent.RunFailed(reason))
        return AgentRuntimeWire.RunResult(
            runId = runId,
            ok = false,
            content = content,
            error = reason,
        )
    }

    private fun buildCommand(prompt: String, profile: AcpAgentProfile): String {
        val args = buildList {
            add("--approve")
            add("once")
            add("--cwd")
            add("/workspace")
            addAll(profile.arguments)
            add(prompt.shellQuote())
        }
        return (listOf(profile.command) + args).joinToString(" ")
    }

    private fun String.shellQuote(): String = "'" + replace("'", "'\\''") + "'"
}
