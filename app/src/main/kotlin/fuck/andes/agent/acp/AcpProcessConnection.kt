package fuck.andes.agent.acp

import fuck.andes.agent.terminal.ShellProcessSupervisor
import fuck.andes.agent.terminal.TerminalEnvironment
import fuck.andes.core.AndroidAgentLogger
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * ACP agent 子进程连接。
 *
 * 直接以 ProcessBuilder 在本进程内 spawn 外部 ACP agent（codex-acp /
 * Gemini CLI / node 脚本等），持有双向 stdio 管道：
 * stdout 按行解析成 [Flow] 供 StdioTransport 消费，stdin 通过 [writeLine] 写入。
 *
 * 参考 OpenOmniBot LocalAcpRuntime.AcpProcessConnection 的设计，但不再依赖
 * TerminalManager / Alpine 环境 —— Eta 模块进程直接 fork exec 命令，命令路径
 * 由 profile 完整指定（可指向 Termux / Alpine chroot 内的可执行文件）。
 */
internal class AcpProcessConnection(
    private val scope: CoroutineScope,
    private val profile: AcpAgentProfile,
    private val extraEnvironment: Map<String, String> = emptyMap(),
) {
    private val inputChannel = Channel<String>(Channel.UNLIMITED)
    private val writeMutex = Mutex()
    private val stderrLock = Any()
    private val stderrTail = ArrayDeque<String>()
    private var process: Process? = null
    private var supervisor: ShellProcessSupervisor? = null
    private var readerJob: Job? = null
    private var stderrJob: Job? = null
    private var waitJob: Job? = null
    private var writer: OutputStreamWriter? = null

    @Volatile
    private var closing = false

    val input: Flow<String> = inputChannel.receiveAsFlow()
    val exitSignal = CompletableDeferred<Int?>()
    val isRunning: Boolean
        get() = process?.isAlive == true

    @Volatile
    var exitCode: Int? = null
        private set

    suspend fun start() {
        if (isRunning) return
        closing = false
        val linuxRootfs = profile.linuxRootfsPath.trim()
        val started = try {
            withContext(Dispatchers.IO) {
                if (linuxRootfs.isNotEmpty()) {
                    // Alpine rootfs 内启动：命令为 rootfs 内路径，经 chroot 运行。
                    val supervisor = ShellProcessSupervisor()
                    this@AcpProcessConnection.supervisor = supervisor
                    val inner = buildString {
                        if (profile.cwd.isNotBlank()) {
                            append("cd ")
                            append(shellQuote(profile.cwd))
                            append(" && ")
                        }
                        append("exec ")
                        append((listOf(profile.command) + profile.arguments)
                            .joinToString(" ") { shellQuote(it) })
                    }
                    supervisor.startShellProcess(
                        identity = "root",
                        command = inner,
                        mergeStderr = false,
                        environment = TerminalEnvironment.LINUX,
                        linuxRootfsPath = linuxRootfs,
                    ) ?: throw IOException("failed to start ACP agent inside Linux rootfs")
                } else {
                    val command = if (profile.useRoot) {
                        // Termux/Alpine 等私有目录需要 root 才能访问，经 su -c 启动。
                        val joined = (listOf(profile.command) + profile.arguments)
                            .joinToString(" ") { shellQuote(it) }
                        listOf("su", "-c", "exec $joined")
                    } else {
                        buildList {
                            add(profile.command)
                            addAll(profile.arguments)
                        }
                    }
                    AndroidAgentLogger.info(
                        "Starting ACP agent ${profile.id}: " +
                            "${command.joinToString(" ").take(160)}"
                    )
                    val builder = ProcessBuilder(command)
                    builder.redirectErrorStream(false)
                    if (profile.cwd.isNotBlank()) {
                        runCatching { builder.directory(java.io.File(profile.cwd)) }
                    }
                    builder.environment().putAll(profile.environment)
                    builder.environment().putAll(extraEnvironment)
                    builder.start()
                }
            }
        } catch (error: Throwable) {
            appendDiagnostic("failed to start: ${error.message ?: error.javaClass.simpleName}")
            throw error
        }
        process = started
        writer = OutputStreamWriter(started.outputStream, StandardCharsets.UTF_8)
        readerJob = scope.launch {
            try {
                lineFlow(started).collect { inputChannel.send(it) }
            } catch (error: IOException) {
                handleStreamReadFailure("stdout", error, started, terminateProcess = true)
            }
        }
        stderrJob = scope.launch(Dispatchers.IO) {
            try {
                started.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            appendDiagnostic(line)
                            AndroidAgentLogger.debug { "[acp:${profile.id}] $line" }
                        }
                    }
                }
            } catch (error: IOException) {
                handleStreamReadFailure("stderr", error, started, terminateProcess = false)
            }
        }
        waitJob = scope.launch(Dispatchers.IO) {
            val code = runCatching { started.waitFor() }.getOrNull()
            exitCode = code
            exitSignal.complete(code)
            if (process === started) {
                process = null
                inputChannel.close(
                    IllegalStateException(
                        "ACP agent ${profile.name} exited with code $code."
                    )
                )
            }
        }
    }

    private fun appendDiagnostic(message: String) {
        synchronized(stderrLock) {
            stderrTail.addLast(message)
            while (
                stderrTail.size > MAX_STDERR_LINES ||
                stderrTail.sumOf(String::length) > MAX_STDERR_CHARS
            ) {
                stderrTail.removeFirstOrNull()
            }
        }
    }

    private fun handleStreamReadFailure(
        streamName: String,
        error: IOException,
        started: Process,
        terminateProcess: Boolean
    ) {
        if (closing || process !== started || !started.isAlive) {
            return
        }
        val detail = "$streamName reader failed: " +
            (error.message ?: error.javaClass.simpleName)
        appendDiagnostic(detail)
        AndroidAgentLogger.warn("ACP ${profile.id} $detail")
        if (terminateProcess) {
            exitSignal.complete(null)
            runCatching { started.destroy() }
        }
    }

    fun diagnosticSummary(): String {
        val stderr = synchronized(stderrLock) {
            stderrTail.joinToString("\n").trim()
        }
        return if (stderr.isBlank()) {
            ""
        } else {
            "Adapter stderr: ${stderr.takeLast(MAX_STDERR_CHARS)}"
        }
    }

    fun exitDescription(exitCode: Int?): String = buildString {
        append("ACP process exited before initialize completed")
        if (exitCode != null) {
            append(" with code ")
            append(exitCode)
        }
        val summary = diagnosticSummary()
        if (summary.isNotBlank()) {
            append(". ")
            append(summary)
        }
    }

    suspend fun writeLine(line: String) {
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

    suspend fun close() {
        closing = true
        val current = process
        process = null
        readerJob?.cancel()
        stderrJob?.cancel()
        waitJob?.cancel()
        runCatching { writer?.close() }
        writer = null
        runCatching { current?.inputStream?.close() }
        runCatching { current?.errorStream?.close() }
        val currentSupervisor = supervisor
        supervisor = null
        if (current != null && currentSupervisor != null) {
            // Alpine chroot 进程：supervisor 负责整棵进程树的终止。
            runCatching { currentSupervisor.terminateProcessTree(current) }
            runCatching { currentSupervisor.unregisterProcess(current) }
        } else {
            runCatching { current?.destroy() }
        }
        readerJob?.cancelAndJoin()
        stderrJob?.cancelAndJoin()
        waitJob?.cancelAndJoin()
        readerJob = null
        stderrJob = null
        waitJob = null
        inputChannel.close()
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
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val MAX_STDERR_LINES = 60
        const val MAX_STDERR_CHARS = 6_000
    }
}

private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"
