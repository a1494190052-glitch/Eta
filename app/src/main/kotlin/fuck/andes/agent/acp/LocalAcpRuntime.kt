@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package fuck.andes.agent.acp

import android.content.Context
import fuck.andes.agent.runtime.AgentEvent
import fuck.andes.agent.runtime.AgentRuntimeWire
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.PlanVariant
import com.agentclientprotocol.model.PlanCapabilities
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.MethodName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * Eta 的本地 ACP 运行时：通过 agentclientprotocol SDK 与 ACP 智能体构建
 * 完整的 stdio 会话，支持 initialize → session/new → session/prompt →
 * session/update（工具调用、文本流、错误）→ 权限请求。
 *
 * 移植自 OpenOmniBot 的 LocalAcpRuntime.kt 核心逻辑，简化了
 * OmniBot 内部的 Room 持久化 / RemoteCodexBridge / Subagent 等宿主层。
 */
internal class LocalAcpRuntime(
    context: Context,
    private val scope: CoroutineScope,
    private val profileStore: AcpAgentPreferenceStore,
    private val onEvent: suspend (AgentEvent) -> Unit,
) {
    private val appContext = context.applicationContext
    private val sessions = ConcurrentHashMap<String, ClientSession>()
    private val sessionCwds = ConcurrentHashMap<String, String>()
    private val activeTurnIds = ConcurrentHashMap<String, String>()
    private val connectMutex = Mutex()

    @Volatile
    private var connection: AcpRuntimeConnection? = null
    @Volatile
    private var protocol: Protocol? = null
    @Volatile
    private var client: Client? = null
    @Volatile
    private var agentInfo: AgentInfo? = null
    @Volatile
    private var activeProfile: AcpAgentProfile? = null

    val isConnected: Boolean
        get() = connection?.isRunning == true && client != null && agentInfo != null

    fun activeAgentId(): String = (activeProfile ?: profileStore.selectedProfile()).id

    fun activeAgentName(): String = (activeProfile ?: profileStore.selectedProfile()).name

    suspend fun connect(profile: AcpAgentProfile = profileStore.selectedProfile()) {
        connectMutex.withLock {
            if (isConnected && activeProfile?.id == profile.id) return
            disconnectLocked()
            val nextConnection = AcpProcessConnection(
                context = appContext,
                scope = scope,
                profile = profile,
                environment = profile.environment,
            )
            val transport = nextConnection.createTransport(scope)
            val nextProtocol = Protocol(scope, transport)
            val nextClient = Client(nextProtocol)
            try {
                nextConnection.start()
                nextProtocol.start()
                val initialized = initializeAgent(
                    client = nextClient,
                    connection = nextConnection,
                    clientInfo = ClientInfo(
                        capabilities = ClientCapabilities(
                            fs = FileSystemCapability(
                                readTextFile = true,
                                writeTextFile = true,
                            ),
                            terminal = false,
                            planCapabilities = PlanCapabilities(),
                        ),
                        implementation = Implementation(
                            name = "eta-app",
                            version = "2.6.2",
                            title = "Eta",
                        ),
                    ),
                )
                connection = nextConnection
                protocol = nextProtocol
                client = nextClient
                agentInfo = initialized
                activeProfile = profile
                profileStore.saveHealth(
                    profile.id,
                    AcpAgentHealth(
                        status = AcpAgentHealth.STATUS_ONLINE,
                        installed = true,
                        checkedAt = System.currentTimeMillis(),
                    ),
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                nextProtocol.close()
                nextConnection.close()
                profileStore.saveHealth(
                    profile.id,
                    AcpAgentHealth(
                        status = AcpAgentHealth.STATUS_OFFLINE,
                        installed = false,
                        error = error.message ?: error.javaClass.simpleName,
                        checkedAt = System.currentTimeMillis(),
                    ),
                )
                throw error
            }
        }
    }

    private suspend fun initializeAgent(
        client: Client,
        connection: AcpRuntimeConnection,
        clientInfo: ClientInfo,
    ): AgentInfo = withTimeout(INITIALIZE_TIMEOUT_MS) {
        coroutineScope {
            val initialize = async { client.initialize(clientInfo) }
            val exitJob = launch {
                val exitCode = connection.exitSignal.await()
                initialize.cancel()
                throw IllegalStateException(connection.exitDescription(exitCode))
            }
            try {
                initialize.await()
            } finally {
                exitJob.cancel()
            }
        }
    }

    private suspend fun disconnectLocked() {
        connection?.close()
        connection = null
        protocol?.close()
        protocol = null
        client = null
        agentInfo = null
        activeProfile = null
        sessions.clear()
        sessionCwds.clear()
        activeTurnIds.clear()
    }

    suspend fun disconnect() {
        connectMutex.withLock { disconnectLocked() }
    }

    /**
     * 运行一个 prompt 并通过 AgentEvent 流式展示结果。
     * 阻塞直到 prompt 完成或超时。
     */
    suspend fun runPrompt(
        runId: String,
        prompt: String,
        profile: AcpAgentProfile = profileStore.selectedProfile(),
    ): AgentRuntimeWire.RunResult {
        onEvent(
            AgentEvent.RunStarted(
                initialImages = 0,
                initialImageBytes = 0,
                toolCount = 1,
                terminalTools = true,
            )
        )
        try {
            connect(profile)
        } catch (error: Throwable) {
            val msg = error.message ?: error.javaClass.simpleName
            onEvent(AgentEvent.RunFailed(msg))
            return AgentRuntimeWire.RunResult(
                runId = runId,
                ok = false,
                content = "",
                error = msg,
            )
        }

        val currentClient = client ?: run {
            val msg = "ACP client is not connected"
            onEvent(AgentEvent.RunFailed(msg))
            return AgentRuntimeWire.RunResult(runId = runId, ok = false, content = "", error = msg)
        }
        val currentConnection = connection ?: run {
            val msg = "ACP connection is not available"
            onEvent(AgentEvent.RunFailed(msg))
            return AgentRuntimeWire.RunResult(runId = runId, ok = false, content = "", error = msg)
        }

        val threadId = "eta-$runId"
        val output = StringBuilder()
        var started = false
        var finished = false
        var stopReason: String? = null
        var failure: Throwable? = null

        var currentBlockKind = AgentEvent.AssistantBlockKind.TEXT
        var currentBlockIndex = 0
        var currentBlockContentChars = 0
        onEvent(
            AgentEvent.AssistantBlockStart(
                round = 0,
                kind = currentBlockKind,
                index = currentBlockIndex,
                blockId = "acp-${runId}-$currentBlockIndex",
                name = profile.name,
            )
        )

        try {
            val sessionInfo = withTimeout(SESSION_NEW_TIMEOUT_MS) {
                currentClient.newSession(
                    SessionCreationParameters(
                        cwd = "/workspace",
                        mcpServers = emptyList(),
                    ),
                    operationsFactory(),
                )
            }
            val session = sessionInfo
            sessions[threadId] = session
            sessionCwds[threadId] = "/workspace"
            activeTurnIds[threadId] = threadId

            suspend fun switchBlock(kind: AgentEvent.AssistantBlockKind) {
                if (kind == currentBlockKind) return
                onEvent(
                    AgentEvent.AssistantBlockEnd(
                        round = 0,
                        kind = currentBlockKind,
                        index = currentBlockIndex,
                        blockId = "acp-${runId}-$currentBlockIndex",
                        name = profile.name,
                        contentChars = currentBlockContentChars,
                    )
                )
                currentBlockKind = kind
                currentBlockIndex++
                currentBlockContentChars = 0
                onEvent(
                    AgentEvent.AssistantBlockStart(
                        round = 0,
                        kind = currentBlockKind,
                        index = currentBlockIndex,
                        blockId = "acp-${runId}-$currentBlockIndex",
                        name = profile.name,
                    )
                )
            }

            session.prompt(
                listOf(ContentBlock.Text(prompt)),
                createPromptJson(),
            ).takeWhile { event ->
                when (event) {
                    is Event.SessionUpdateEvent -> {
                        val update = event.update
                        val targetKind = when (update) {
                            is SessionUpdate.AgentThoughtChunk -> AgentEvent.AssistantBlockKind.THINKING
                            is SessionUpdate.ToolCall, is SessionUpdate.ToolCallUpdate -> AgentEvent.AssistantBlockKind.TOOL_CALL
                            else -> AgentEvent.AssistantBlockKind.TEXT
                        }
                        switchBlock(targetKind)
                        handleSessionUpdate(threadId, update) { delta ->
                            output.append(delta)
                            currentBlockContentChars += delta.length
                            onEvent(
                                AgentEvent.AssistantBlockDelta(
                                    round = 0,
                                    kind = currentBlockKind,
                                    index = currentBlockIndex,
                                    deltaChars = delta.length,
                                    delta = delta,
                                )
                            )
                        }
                        true
                    }
                    is Event.PromptResponseEvent -> {
                        stopReason = event.response.stopReason.name.lowercase()
                        false
                    }
                }
            }.collect()

            // ACP prompt 完成后读最终消息
            val finalOutput = output.toString().ifBlank {
                // 某些 adapter 只通过 session/update 流式输出，没有最终消息
                ""
            }
            at(started = true)
            finished = true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            failure = error
            finished = true
        } finally {
            activeTurnIds.remove(threadId, threadId)
            onEvent(
                AgentEvent.AssistantBlockEnd(
                    round = 0,
                    kind = currentBlockKind,
                    index = currentBlockIndex,
                    blockId = "acp-${runId}-$currentBlockIndex",
                    name = profile.name,
                    contentChars = currentBlockContentChars,
                )
            )
        }

        if (failure != null) {
            val msg = failure?.message ?: failure?.javaClass?.simpleName ?: "Unknown error"
            onEvent(AgentEvent.RunFailed(msg))
            return AgentRuntimeWire.RunResult(
                runId = runId,
                ok = false,
                content = output.toString(),
                error = msg,
            )
        }

        onEvent(AgentEvent.RunFinished(round = 0, contentChars = output.length))
        return AgentRuntimeWire.RunResult(
            runId = runId,
            ok = true,
            content = output.toString(),
            error = null,
        )
    }

    private fun createPromptJson(): JsonElement = JsonObject(emptyMap())

    private data class SessionDelta(
        val text: String,
        val kind: AgentEvent.AssistantBlockKind,
    )

    private suspend fun handleSessionUpdate(
        threadId: String,
        update: SessionUpdate,
        onDelta: suspend (String) -> Unit,
    ) {
        when (update) {
            is SessionUpdate.AgentMessageChunk -> {
                val text = (update.content as? ContentBlock.Text)?.text ?: return
                onDelta(text)
            }
            is SessionUpdate.AgentThoughtChunk -> {
                val text = (update.content as? ContentBlock.Text)?.text
                    ?.takeIf { it.isNotBlank() } ?: return
                onDelta("\n[thinking] $text\n")
            }
            is SessionUpdate.ToolCall -> {
                val title = update.title ?: update.toolCallId.value
                val status = update.status?.name?.lowercase() ?: "in_progress"
                val args = update.rawInput?.toString().orEmpty()
                onDelta("\n\n[tool_call] $title [$status]\n")
                if (args.isNotBlank()) onDelta("$args\n")
            }
            is SessionUpdate.ToolCallUpdate -> {
                val title = update.title ?: update.toolCallId.value
                val status = update.status?.name?.lowercase() ?: "unknown"
                onDelta("\n[tool_status] $title: $status\n")
            }
            is SessionUpdate.PlanUpdate -> {
                val planUpdate = update as SessionUpdate.PlanUpdate
                val entries = planUpdate.entries.joinToString("\n") { "- ${it.content}" }
                onDelta("\n\n[plan]\n$entries\n")
            }
            is SessionUpdate.PlanUpdateV2 -> {
                val planUpdateV2 = update as SessionUpdate.PlanUpdateV2
                val markdown = when (val variant = planUpdateV2.plan) {
                    is PlanVariant.Items -> variant.entries.joinToString("\n") { "- ${it.content}" }
                    is PlanVariant.Markdown -> variant.content
                    is PlanVariant.File -> variant.uri
                    else -> ""
                }
                onDelta("\n\n[plan]\n$markdown\n")
            }
            is SessionUpdate.PlanRemoved -> {
                onDelta("\n\n[plan removed]\n\n")
            }
            is SessionUpdate.UsageUpdate -> {
                onDelta("\n[usage] used=${update.used}, size=${update.size}\n")
            }
            else -> Unit
        }
    }

    private fun operationsFactory() =
        com.agentclientprotocol.client.ClientOperationsFactory { _, _ ->
            object : com.agentclientprotocol.common.ClientSessionOperations {
                override suspend fun requestPermissions(
                    toolCallUpdate: SessionUpdate.ToolCallUpdate,
                    permissions: List<PermissionOption>,
                    meta: JsonElement?
                ): RequestPermissionResponse = RequestPermissionResponse(
                    outcome = RequestPermissionOutcome.Cancelled
                )

                override suspend fun notify(
                    update: SessionUpdate,
                    meta: JsonElement?
                ): Unit = Unit
            }
        }

    private fun at(started: Boolean) {
        // placeholder for future streaming sync
    }

    private companion object {
        const val INITIALIZE_TIMEOUT_MS = 120_000L
        const val SESSION_NEW_TIMEOUT_MS = 60_000L
    }
}
