package com.androidperformancestudio.toolchain

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class ProcessRequest(
    val executable: Path,
    val arguments: List<String> = emptyList(),
    val workingDirectory: Path? = null,
    val environmentOverrides: Map<String, String> = emptyMap(),
    val timeout: Duration = 30.seconds,
    val charset: Charset = StandardCharsets.UTF_8,
    val maxCapturedCharactersPerStream: Int = DEFAULT_CAPTURE_LIMIT,
) {
    init {
        require(timeout.isPositive() && timeout.isFinite()) { "timeout must be positive and finite" }
        require(maxCapturedCharactersPerStream > 0) { "capture limit must be positive" }
    }

    val command: List<String> = listOf(executable.toString()) + arguments

    companion object {
        const val DEFAULT_CAPTURE_LIMIT = 1_048_576
    }
}

data class CapturedProcessText(
    val text: String,
    val truncated: Boolean,
)

data class ProcessOutput(
    val pid: Long,
    val command: List<String>,
    val exitCode: Int?,
    val stdout: CapturedProcessText,
    val stderr: CapturedProcessText,
    val startedAt: Instant,
    val finishedAt: Instant,
)

sealed interface ProcessRunResult {
    data class Completed(
        val output: ProcessOutput,
    ) : ProcessRunResult

    data class Failed(
        val error: StudioError,
        val output: ProcessOutput? = null,
    ) : ProcessRunResult
}

class ProcessCancellationSignal {
    private val cancelled = AtomicBoolean(false)
    private val callbacks = CopyOnWriteArrayList<() -> Unit>()

    val isCancelled: Boolean
        get() = cancelled.get()

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) callbacks.forEach { callback -> callback() }
    }

    internal fun invokeOnCancel(callback: () -> Unit): AutoCloseable {
        callbacks += callback
        if (isCancelled) callback()
        return AutoCloseable { callbacks -= callback }
    }
}

class JvmProcessRunner(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val terminationGracePeriod: Duration = 500.milliseconds,
) {
    suspend fun run(
        request: ProcessRequest,
        cancellationSignal: ProcessCancellationSignal = ProcessCancellationSignal(),
    ): ProcessRunResult =
        withContext(ioDispatcher) {
            val startedAt = Instant.now()
            val process = startProcess(request) ?: return@withContext startFailure(request)
            runStartedProcess(process, request, cancellationSignal, startedAt)
        }

    private fun startProcess(request: ProcessRequest): Process? =
        try {
            ProcessBuilder(request.command)
                .apply {
                    request.workingDirectory?.let { directory(it.toFile()) }
                    environment().putAll(request.environmentOverrides)
                }.start()
        } catch (_: IOException) {
            null
        }

    private fun startFailure(request: ProcessRequest): ProcessRunResult.Failed =
        ProcessRunResult.Failed(
            error =
                StudioError(
                    category = ErrorCategory.PROCESS_START,
                    code = "PROCESS_START_FAILED",
                    message = "Failed to start executable: ${request.executable}",
                ),
        )

    private suspend fun runStartedProcess(
        process: Process,
        request: ProcessRequest,
        cancellationSignal: ProcessCancellationSignal,
        startedAt: Instant,
    ): ProcessRunResult {
        val terminating = AtomicBoolean(false)
        val registration =
            cancellationSignal.invokeOnCancel {
                terminateProcessTree(process, terminating)
            }
        try {
            return coroutineScope {
                val stdout =
                    async {
                        capture(process.inputStream, request.charset, request.maxCapturedCharactersPerStream)
                    }
                val stderr =
                    async {
                        capture(process.errorStream, request.charset, request.maxCapturedCharactersPerStream)
                    }
                val termination = awaitTermination(process, request.timeout, cancellationSignal)
                if (termination != ProcessTermination.EXITED) terminateProcessTree(process, terminating)
                val output =
                    ProcessOutput(
                        pid = process.pid(),
                        command = request.command,
                        exitCode = process.exitCodeOrNull(),
                        stdout = stdout.await(),
                        stderr = stderr.await(),
                        startedAt = startedAt,
                        finishedAt = Instant.now(),
                    )
                toRunResult(termination, output)
            }
        } finally {
            registration.close()
            if (process.isAlive) terminateProcessTree(process, terminating)
        }
    }

    private suspend fun awaitTermination(
        process: Process,
        timeout: Duration,
        cancellationSignal: ProcessCancellationSignal,
    ): ProcessTermination {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        var termination: ProcessTermination? = null
        while (process.isAlive && termination == null) {
            currentCoroutineContext().ensureActive()
            termination =
                when {
                    cancellationSignal.isCancelled -> ProcessTermination.CANCELLED
                    System.nanoTime() >= deadline -> ProcessTermination.TIMED_OUT
                    else -> null
                }
            if (termination == null) {
                val remainingMillis = ((deadline - System.nanoTime()) / NANOS_PER_MILLISECOND).coerceAtLeast(1)
                process.waitFor(min(POLL_INTERVAL_MILLIS, remainingMillis), TimeUnit.MILLISECONDS)
            }
        }
        return termination
            ?: if (cancellationSignal.isCancelled) ProcessTermination.CANCELLED else ProcessTermination.EXITED
    }

    private fun terminateProcessTree(
        process: Process,
        terminating: AtomicBoolean,
    ) {
        if (!terminating.compareAndSet(false, true)) return
        val descendants = process.descendants().toList().asReversed()
        descendants.forEach(ProcessHandle::destroy)
        process.destroy()
        process.waitFor(terminationGracePeriod.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        descendants.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly)
        if (process.isAlive) process.destroyForcibly()
    }

    private fun toRunResult(
        termination: ProcessTermination,
        output: ProcessOutput,
    ): ProcessRunResult =
        when {
            termination == ProcessTermination.TIMED_OUT ->
                failure(ErrorCategory.PROCESS_TIMEOUT, "PROCESS_TIMED_OUT", "Process timed out", output)
            termination == ProcessTermination.CANCELLED ->
                failure(ErrorCategory.PROCESS_CANCELLED, "PROCESS_CANCELLED", "Process was cancelled", output)
            output.exitCode != 0 ->
                failure(
                    ErrorCategory.PROCESS_EXIT,
                    "PROCESS_EXIT_${output.exitCode}",
                    "Process exited with code ${output.exitCode}",
                    output,
                )
            else -> ProcessRunResult.Completed(output)
        }

    private fun failure(
        category: ErrorCategory,
        code: String,
        message: String,
        output: ProcessOutput,
    ): ProcessRunResult.Failed =
        ProcessRunResult.Failed(
            error = StudioError(category = category, code = code, message = message),
            output = output,
        )

    private fun capture(
        stream: InputStream,
        charset: Charset,
        maximumCharacters: Int,
    ): CapturedProcessText {
        val captured = StringBuilder(min(maximumCharacters, INITIAL_CAPTURE_CAPACITY))
        val buffer = CharArray(READ_BUFFER_SIZE)
        var truncated = false
        stream.bufferedReader(charset).use { reader ->
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                val remaining = maximumCharacters - captured.length
                if (remaining > 0) captured.append(buffer, 0, min(count, remaining))
                if (count > remaining) truncated = true
            }
        }
        return CapturedProcessText(captured.toString(), truncated)
    }

    private fun Process.exitCodeOrNull(): Int? = if (isAlive) null else exitValue()

    private enum class ProcessTermination {
        EXITED,
        TIMED_OUT,
        CANCELLED,
    }

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val POLL_INTERVAL_MILLIS = 50L
        private const val READ_BUFFER_SIZE = 8_192
        private const val INITIAL_CAPTURE_CAPACITY = 16_384
    }
}
