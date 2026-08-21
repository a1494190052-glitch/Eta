package fuck.andes.agent.acp

import android.content.Context
import fuck.andes.agent.terminal.AlpineEnvironmentPaths
import fuck.andes.agent.terminal.ShellProcessSupervisor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import com.agentclientprotocol.transport.StdioTransport
import com.agentclientprotocol.transport.Transport
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * ACP stdio 进程连接：在 Eta Linux 环境（Alpine chroot）中启动 ACP 智能体进程，
 * 并将进程 stdout/stderr 接入 agentclientprotocol 的 StdioTransport。
 *
 * 移植自 OpenOmniBot 的 AcpProcessConnection / LocalAcpRuntime。
 */
internal interface AcpRuntimeConnection {
    val exitSignal: CompletableDeferred<Int?>
    val isRunning: Boolean
    fun createTransport(parentScope: kotlinx.coroutines.CoroutineScope): Transport
    suspend fun start()
    suspend fun close()
    fun diagnosticSummary(): String
    fun exitDescription(exitCode: Int?): String
}

internal class AcpProcessConnection(
    private val context: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val profile: AcpAgentProfile,
    private val environment: Map<String, String> = emptyMap(),
) : AcpRuntimeConnection {
    private val inputChannel = Channel<String>(Channel.UNLIMITED)
    private val writeMutex = Mutex()
    private val stderrLock = Any()
    private val stderrTail = ArrayDeque<String>()
    private var process: Process? = null
    private var stderrJob: Job? = null
    private var waitJob: Job? = null
    private var readerJob: Job? = null
    private var writer: OutputStreamWriter? = null

    @Volatile
    private var closing = false

    private val input: Flow<String> = inputChannel.receiveAsFlow()

    override val exitSignal = CompletableDeferred<Int?>()
    override val isRunning: Boolean
        get() = process?.isAlive == true

    override fun createTransport(parentScope: kotlinx.coroutines.CoroutineScope): Transport {
        return StdioTransport(
            parentScope = parentScope,
            ioDispatcher = Dispatchers.IO,
            input = input,
            output = ::writeLine,
            name = "eta-acp-${profile.id}",
        )
    }

    override suspend fun start() {
        if (isRunning) return
        closing = false
        val rootfs = AlpineEnvironmentPaths.rootfsDir(context).absolutePath
        val supervisor = ShellProcessSupervisor()
        val command = buildString {
            // 统一 ACP 工作目录
            append("cd /workspace && ")
            append("export CODEX_HOME=/root/.codex && ")
            append(profile.command)
            profile.arguments.forEach {
                append(' ')
                append(shellQuote(it))
            }
        }
        val started = supervisor.startShellProcess(
            identity = "root",
            command = command,
            mergeStderr = false,
            environment = fuck.andes.agent.terminal.TerminalEnvironment.LINUX,
            linuxRootfsPath = rootfs,
        ) ?: throw IllegalStateException("Failed to start ACP process for ${profile.name}")
        process = started
        writer = OutputStreamWriter(started.outputStream, StandardCharsets.UTF_8)
        readerJob = scope.launch(Dispatchers.IO) {
            try {
                lineFlow(started).collect { inputChannel.send(it) }
            } catch (error: Exception) {
                if (!closing && process === started) {
                    inputChannel.close(error)
                }
            }
        }
        stderrJob = scope.launch(Dispatchers.IO) {
            try {
                started.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            synchronized(stderrLock) {
                                stderrTail.addLast(line)
                                while (
                                    stderrTail.size > MAX_STDERR_LINES ||
                                    stderrTail.sumOf(String::length) > MAX_STDERR_CHARS
                                ) {
                                    stderrTail.removeFirstOrNull()
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // stdio 关闭时 stderr 也会结束
            }
        }
        waitJob = scope.launch(Dispatchers.IO) {
            val exitCode = runCatching { started.waitFor() }.getOrNull()
            exitSignal.complete(exitCode)
            if (process === started) {
                process = null
                inputChannel.close(
                    IllegalStateException("ACP agent ${profile.name} exited with code $exitCode.")
                )
            }
        }
    }

    private fun lineFlow(process: Process): Flow<String> = flow {
        process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) {
                    emit(line)
                }
            }
        }
    }

    private suspend fun writeLine(line: String) {
        writeMutex.withLock {
            val output = writer
                ?: throw IllegalStateException("ACP agent stdin is closed.")
            withContext(Dispatchers.IO) {
                output.write(line)
                output.write("\n")
                output.flush()
            }
        }
    }

    override fun diagnosticSummary(): String {
        val stderr = synchronized(stderrLock) {
            stderrTail.joinToString("\n").trim()
        }
        return if (stderr.isBlank()) "" else "Adapter stderr: ${stderr.takeLast(MAX_STDERR_CHARS)}"
    }

    override fun exitDescription(exitCode: Int?): String {
        val summary = diagnosticSummary()
        return buildString {
            append("ACP process exited before initialize completed")
            if (exitCode != null) {
                append(" with code ")
                append(exitCode)
            }
            if (summary.isNotBlank()) {
                append(". ")
                append(summary)
            }
        }
    }

    override suspend fun close() {
        closing = true
        val current = process
        process = null
        readerJob?.cancel()
        stderrJob?.cancel()
        waitJob?.cancel()
        runCatching { current?.destroy() }
        if (current != null) {
            val exited = withContext(Dispatchers.IO) {
                runCatching { current.waitFor(500, TimeUnit.MILLISECONDS) }
                    .getOrDefault(false)
            }
            if (!exited) {
                runCatching { current.destroyForcibly() }
                withContext(Dispatchers.IO) {
                    runCatching { current.waitFor(500, TimeUnit.MILLISECONDS) }
                }
            }
        }
        withContext(Dispatchers.IO) {
            runCatching { writer?.close() }
            runCatching { current?.inputStream?.close() }
            runCatching { current?.errorStream?.close() }
        }
        writer = null
        readerJob = null
        stderrJob = null
        waitJob = null
        inputChannel.close()
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val MAX_STDERR_LINES = 60
        const val MAX_STDERR_CHARS = 12_000
    }
}
