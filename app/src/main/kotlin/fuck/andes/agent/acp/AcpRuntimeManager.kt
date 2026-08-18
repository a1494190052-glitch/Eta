@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package fuck.andes.agent.acp

import android.content.Context
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.PlanCapabilities
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.agent.runtime.AgentEvent
import fuck.andes.agent.runtime.AgentRunController
import fuck.andes.agent.runtime.AgentRuntimeWire
import fuck.andes.core.AndroidAgentLogger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * ACP Agent 运行时（Eta 版，移植自 OpenOmniBot LocalAcpRuntime 的核心思想）。
 *
 * 职责：
 *  - 按 profile spawn 外部 ACP agent 进程（[AcpProcessConnection]），
 *    经 [StdioTransport] 建立 JSON-RPC 会话；
 *  - 维护按 profile 复用的 [ClientSession]（MVP：同一 profile 全局单会话，
 *    后续可扩展为按对话多会话）；
 *  - 把一次 Eta run（prompt）翻译成 ACP `session/prompt`，并把
 *    SessionUpdate 流映射为 [AgentEvent] 时间线。
 *
 * 与 OmniBot 的差异：Eta 是「每次 run 独立」模型，这里用全局常驻进程 +
 * 复用 session 来保持 ACP agent 的上下文连续性；权限策略 MVP 直接按
 * profile 配置放行/拒绝，不弹 UI 审批。
 */
internal object AcpRuntimeManager {

    private data class Connection(
        val profileId: String,
        val process: AcpProcessConnection,
        val protocol: Protocol,
        val client: Client,
        val agentInfo: AgentInfo,
    )

    private data class SessionEntry(
        val conversationKey: String,
        val session: ClientSession,
        val cwd: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var connection: Connection? = null

    private val sessions = ConcurrentHashMap<String, SessionEntry>()
    private val connectMutex = Mutex()
    private val sessionMutex = Mutex()

    val isConnected: Boolean
        get() = connection?.process?.isRunning == true

    fun protocolVersion(): Int? = connection?.agentInfo?.protocolVersion

    fun agentVersion(): String? = connection?.agentInfo?.implementation?.version

    /** 断开当前 ACP agent（进程终止 + session 清空）。 */
    suspend fun disconnect() {
        connectMutex.withLock {
            disconnectLocked()
        }
    }

    private suspend fun disconnectLocked() {
        sessions.clear()
        val current = connection
        connection = null
        current?.protocol?.close()
        current?.process?.close()
    }

    private suspend fun connectIfNeeded(
        profile: AcpAgentProfile,
    ) = connectMutex.withLock {
        val existing = connection
        if (existing != null && existing.profileId == profile.id && existing.process.isRunning) {
            return@withLock
        }
        disconnectLocked()
        val process = AcpProcessConnection(
            scope = scope,
            profile = profile,
        )
        val transport = StdioTransport(
            parentScope = scope,
            ioDispatcher = Dispatchers.IO,
            input = process.input,
            output = process::writeLine,
            name = "eta-acp-${profile.id}",
        )
        val protocol = Protocol(scope, transport)
        val client = Client(protocol)
        try {
            process.start()
            protocol.start()
            val agentInfo = withTimeout(INITIALIZE_TIMEOUT_MS) {
                client.initialize(
                    ClientInfo(
                        capabilities = ClientCapabilities(
                            fs = FileSystemCapability(
                                readTextFile = false,
                                writeTextFile = false,
                            ),
                            terminal = false,
                            planCapabilities = PlanCapabilities(),
                        ),
                        implementation = Implementation(
                            name = "eta",
                            version = "eta-acp-0.1",
                            title = "Eta",
                        ),
                    )
                )
            }
            connection = Connection(
                profileId = profile.id,
                process = process,
                protocol = protocol,
                client = client,
                agentInfo = agentInfo,
            )
            AndroidAgentLogger.info(
                "ACP agent ${profile.id} connected: " +
                    "impl=${agentInfo.implementation?.name ?: "?"} " +
                    "v=${agentInfo.implementation?.version ?: "?"} " +
                    "proto=${agentInfo.protocolVersion}"
            )
        } catch (error: Throwable) {
            runCatching { protocol.close() }
            runCatching { process.close() }
            throw IllegalStateException(
                "ACP agent ${profile.name} 初始化失败: " +
                    (error.message ?: error.javaClass.simpleName) +
                    process.diagnosticSummary().let { if (it.isNotBlank()) "\n$it" else "" },
                error,
            )
        }
    }

    private suspend fun ensureSession(
        profile: AcpAgentProfile,
    ): SessionEntry {
        val conn = connection
            ?: throw IllegalStateException("ACP agent 未连接")
        val existing = sessions[profile.id]
        if (existing != null) return existing
        return sessionMutex.withLock {
            sessions[profile.id] ?: run {
                val cwd = profile.cwd.ifBlank { DEFAULT_CWD }
                val created = conn.client.newSession(
                    SessionCreationParameters(cwd, emptyList()),
                    operationsFactory(profile),
                )
                val entry = SessionEntry(
                    conversationKey = profile.id,
                    session = created,
                    cwd = cwd,
                )
                sessions[profile.id] = entry
                entry
            }
        }
    }

    /**
     * 阻塞执行一次 ACP run（由 AgentRuntimeService 的后台线程调用）。
     *
     * [request] 携带 prompt 与配置；[onEvent] 同步接收事件流；[isCancelled]
     * 供外层取消检测。返回与现有 LLM run 对齐的 [AgentRuntimeWire.RunResult]。
     */
    fun execute(
        context: Context,
        request: AgentRuntimeWire.RunRequest,
        onEvent: (AgentEvent) -> Unit,
        isCancelled: () -> Boolean,
    ): AgentRuntimeWire.RunResult = runBlocking {
        val profile = AcpProfileStore.selected(context)
            ?: return@runBlocking AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = false,
                content = "",
                error = "未配置 ACP agent（请在设置中选择或创建 ACP Agent 配置）",
            )
        if (!profile.isUsable()) {
            return@runBlocking AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = false,
                content = "",
                error = "ACP agent「${profile.shellDisplayName()}」不可用（命令为空或已禁用）",
            )
        }
        AndroidAgentLogger.info(
            "ACP run start: agent=${profile.id} prompt_chars=${request.prompt.length}"
        )
        onEvent(
            AgentEvent.RunStarted(
                initialImages = 0,
                initialImageBytes = 0,
                toolCount = 0,
                terminalTools = false,
            )
        )
        val translator = AcpEventTranslator()
        var failure: String? = null
        try {
            connectIfNeeded(profile)
            val entry = ensureSession(profile)
            AndroidAgentLogger.info(
                "ACP session ready: id=${entry.session.sessionId.value} cwd=${entry.cwd}"
            )
            onEvent(AgentEvent.RoundStarted(round = 1, messageCount = 1))
            try {
                withTimeout(PROMPT_TIMEOUT_MS) {
                    entry.session.prompt(listOf(ContentBlock.Text(request.prompt)))
                        .collect { event ->
                            if (isCancelled()) {
                                throw kotlinx.coroutines.CancellationException(
                                    "cancelled by user"
                                )
                            }
                            when (event) {
                                is Event.SessionUpdateEvent -> {
                                    translator.onUpdate(event.update, round = 1)
                                        .forEach(onEvent)
                                }
                                is Event.PromptResponseEvent -> Unit
                            }
                        }
                }
            } finally {
                translator.finish(round = 1).forEach(onEvent)
            }
            val content = translator.accumulatedText()
            onEvent(AgentEvent.RunFinished(round = 1, contentChars = content.length))
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = true,
                content = content,
                reasoningContent = "",
                transcript = listOf(
                    AgentModelClient.ConversationMessage(
                        role = "user",
                        content = request.prompt,
                    ),
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = content,
                    ),
                ),
            )
        } catch (error: Throwable) {
            val cancelled = error is kotlinx.coroutines.CancellationException
            failure = if (cancelled) {
                "已停止"
            } else {
                error.message ?: error.javaClass.simpleName
            }
            if (!cancelled) {
                AndroidAgentLogger.error("ACP run failed: $failure", error)
                onEvent(AgentEvent.RunFailed(failure))
            }
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = false,
                content = "",
                error = failure,
            )
        }
    }

    private fun operationsFactory(
        profile: AcpAgentProfile,
    ): ClientOperationsFactory = ClientOperationsFactory { _, _ ->
        AcpClientOperations(profile)
    }

    private class AcpClientOperations(
        private val profile: AcpAgentProfile,
    ) : ClientSessionOperations {

        override suspend fun requestPermissions(
            toolCall: com.agentclientprotocol.model.SessionUpdate.ToolCallUpdate,
            permissions: List<PermissionOption>,
            _meta: kotlinx.serialization.json.JsonElement?,
        ): RequestPermissionResponse {
            if (profile.allowToolsWithoutPrompt) {
                val selected = permissions.firstOrNull {
                    it.kind == PermissionOptionKind.ALLOW_ALWAYS
                } ?: permissions.firstOrNull {
                    it.kind == PermissionOptionKind.ALLOW_ONCE
                }
                return RequestPermissionResponse(
                    outcome = selected?.let {
                        RequestPermissionOutcome.Selected(it.optionId)
                    } ?: RequestPermissionOutcome.Cancelled
                )
            }
            // MVP：未启用自动放行时直接拒绝，避免阻塞 turn。
            AndroidAgentLogger.warn(
                "ACP 权限请求被拒绝（未开启自动放行）: " +
                    "${toolCall.title ?: toolCall.toolCallId.value}"
            )
            return RequestPermissionResponse(
                outcome = RequestPermissionOutcome.Cancelled
            )
        }

        override suspend fun notify(
            notification: com.agentclientprotocol.model.SessionUpdate,
            _meta: kotlinx.serialization.json.JsonElement?,
        ) {
            // 无 prompt 在途时的 session 通知：MVP 忽略（turn 内的事件已在
            // prompt flow 中处理）。
        }

        override suspend fun fsReadTextFile(
            path: String,
            line: UInt?,
            limit: UInt?,
            _meta: kotlinx.serialization.json.JsonElement?,
        ): com.agentclientprotocol.model.ReadTextFileResponse {
            error("Eta ACP 桥未启用文件系统能力（fs=false）")
        }

        override suspend fun fsWriteTextFile(
            path: String,
            content: String,
            _meta: kotlinx.serialization.json.JsonElement?,
        ): com.agentclientprotocol.model.WriteTextFileResponse {
            error("Eta ACP 桥未启用文件系统能力（fs=false）")
        }
    }

    private const val INITIALIZE_TIMEOUT_MS = 30_000L
    private const val PROMPT_TIMEOUT_MS = 20 * 60_000L
    private const val DEFAULT_CWD = "/"
}
