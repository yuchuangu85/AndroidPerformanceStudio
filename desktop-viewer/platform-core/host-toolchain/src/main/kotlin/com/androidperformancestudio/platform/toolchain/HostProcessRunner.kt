package com.androidperformancestudio.platform.toolchain

import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class HostProcessRequest(
    val executable: Path,
    val arguments: List<String> = emptyList(),
    val workingDirectory: Path? = null,
    val environmentOverrides: Map<String, String> = emptyMap(),
    val timeout: Duration = 30.seconds,
    val charset: Charset = StandardCharsets.UTF_8,
    val maxOutputBytesPerStream: Int = DEFAULT_MAX_OUTPUT_BYTES,
    val isCancellationRequested: () -> Boolean = { false },
) {
    init {
        require(timeout.isPositive() && timeout.isFinite()) { "timeout must be positive and finite" }
        require(maxOutputBytesPerStream > 0) { "output limit must be positive" }
    }

    val command: List<String> = listOf(executable.toString()) + arguments

    companion object {
        const val DEFAULT_MAX_OUTPUT_BYTES: Int = 16 * 1024 * 1024
    }
}

data class HostProcessLaunchRequest(
    val executable: Path,
    val arguments: List<String> = emptyList(),
    val workingDirectory: Path? = null,
    val environmentOverrides: Map<String, String> = emptyMap(),
    val outputFile: Path? = null,
) {
    val command: List<String> = listOf(executable.toString()) + arguments
}

interface RunningHostProcess : AutoCloseable {
    val pid: Long
    val isAlive: Boolean

    fun terminate()

    override fun close() = terminate()
}

data class HostProcessTextResult(
    val pid: Long,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val duration: Duration,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
)

data class HostProcessBinaryResult(
    val pid: Long,
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val duration: Duration,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is HostProcessBinaryResult &&
            pid == other.pid &&
            exitCode == other.exitCode &&
            stdout.contentEquals(other.stdout) &&
            stderr.contentEquals(other.stderr) &&
            duration == other.duration &&
            stdoutTruncated == other.stdoutTruncated &&
            stderrTruncated == other.stderrTruncated

    override fun hashCode(): Int {
        var result = pid.hashCode()
        result = 31 * result + exitCode
        result = 31 * result + stdout.contentHashCode()
        result = 31 * result + stderr.contentHashCode()
        result = 31 * result + duration.hashCode()
        result = 31 * result + stdoutTruncated.hashCode()
        return 31 * result + stderrTruncated.hashCode()
    }
}

sealed class HostProcessException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class HostProcessStartException(
    val command: List<String>,
    cause: Throwable,
) : HostProcessException("Failed to start host process: ${command.joinToString(" ")}", cause)

class HostProcessTimeoutException(
    val command: List<String>,
    val timeout: Duration,
    val pid: Long,
) : HostProcessException("Host process timed out after $timeout: ${command.joinToString(" ")}")

class HostProcessCancelledException(
    val command: List<String>,
    val pid: Long,
) : CancellationException("Host process was cancelled: ${command.joinToString(" ")}")

interface HostProcessRunner {
    suspend fun executeText(request: HostProcessRequest): HostProcessTextResult

    suspend fun executeBinary(request: HostProcessRequest): HostProcessBinaryResult

    fun launch(request: HostProcessLaunchRequest): RunningHostProcess
}

class JvmHostProcessRunner(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val terminationGracePeriod: Duration = 250.milliseconds,
) : HostProcessRunner {
    init {
        require(terminationGracePeriod.isPositive() && terminationGracePeriod.isFinite()) {
            "termination grace period must be positive and finite"
        }
    }

    override suspend fun executeText(request: HostProcessRequest): HostProcessTextResult {
        val result = executeBinary(request)
        return HostProcessTextResult(
            pid = result.pid,
            exitCode = result.exitCode,
            stdout = result.stdout.toString(request.charset),
            stderr = result.stderr.toString(request.charset),
            duration = result.duration,
            stdoutTruncated = result.stdoutTruncated,
            stderrTruncated = result.stderrTruncated,
        )
    }

    override suspend fun executeBinary(request: HostProcessRequest): HostProcessBinaryResult =
        withContext(ioDispatcher) {
            val started = TimeSource.Monotonic.markNow()
            val process = startProcess(request.command, request.workingDirectory, request.environmentOverrides)
            val terminating = AtomicBoolean(false)
            try {
                coroutineScope {
                    val stdout = async { process.inputStream.capture(request.maxOutputBytesPerStream) }
                    val stderr = async { process.errorStream.capture(request.maxOutputBytesPerStream) }
                    try {
                        awaitExit(process, request)
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        terminateProcessTree(process, terminating)
                        if (error is HostProcessCancelledException) throw error
                        throw HostProcessCancelledException(request.command, process.pid()).also { it.initCause(error) }
                    } catch (error: HostProcessTimeoutException) {
                        terminateProcessTree(process, terminating)
                        throw error
                    }
                    val standardOutput = stdout.await()
                    val standardError = stderr.await()
                    HostProcessBinaryResult(
                        pid = process.pid(),
                        exitCode = process.exitValue(),
                        stdout = standardOutput.bytes,
                        stderr = standardError.bytes,
                        duration = started.elapsedNow(),
                        stdoutTruncated = standardOutput.truncated,
                        stderrTruncated = standardError.truncated,
                    )
                }
            } finally {
                if (process.isAlive) terminateProcessTree(process, terminating)
                closeStreams(process)
            }
        }

    override fun launch(request: HostProcessLaunchRequest): RunningHostProcess {
        val process =
            startProcess(request.command, request.workingDirectory, request.environmentOverrides) {
                redirectErrorStream(true)
                request.outputFile?.let { redirectOutput(it.toFile()) } ?: redirectOutput(ProcessBuilder.Redirect.DISCARD)
            }
        return ManagedJvmHostProcess(process)
    }

    private fun closeStreams(process: Process) {
        listOf(process.inputStream, process.errorStream, process.outputStream).forEach {
            runCatching { it.close() }
        }
    }

    private fun startProcess(
        command: List<String>,
        workingDirectory: Path?,
        environmentOverrides: Map<String, String>,
        configure: ProcessBuilder.() -> Unit = {},
    ): Process =
        try {
            ProcessBuilder(command)
                .apply {
                    workingDirectory?.let { directory(it.toFile()) }
                    environment().putAll(environmentOverrides)
                    redirectErrorStream(false)
                    configure()
                }.start()
        } catch (error: IOException) {
            throw HostProcessStartException(command, error)
        }

    private inner class ManagedJvmHostProcess(
        private val process: Process,
    ) : RunningHostProcess {
        private val terminating = AtomicBoolean(false)

        override val pid: Long
            get() = process.pid()

        override val isAlive: Boolean
            get() = process.isAlive

        override fun terminate() {
            terminateProcessTree(process, terminating)
            closeStreams(process)
        }
    }

    private suspend fun awaitExit(
        process: Process,
        request: HostProcessRequest,
    ) {
        val deadline = System.nanoTime() + request.timeout.inWholeNanoseconds
        while (process.isAlive) {
            currentCoroutineContext().ensureActive()
            if (request.isCancellationRequested()) {
                throw HostProcessCancelledException(request.command, process.pid())
            }
            if (System.nanoTime() >= deadline) {
                throw HostProcessTimeoutException(request.command, request.timeout, process.pid())
            }
            delay(POLL_INTERVAL)
        }
    }

    private fun terminateProcessTree(
        process: Process,
        terminating: AtomicBoolean,
    ) {
        if (!terminating.compareAndSet(false, true)) return
        val handles = process.descendants().toList().asReversed() + process.toHandle()
        handles.forEach(ProcessHandle::destroy)
        awaitTermination(handles)
        handles.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly)
        awaitTermination(handles)
    }

    private fun awaitTermination(handles: List<ProcessHandle>) {
        val deadline = System.nanoTime() + terminationGracePeriod.inWholeNanoseconds
        while (handles.any(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            Thread.sleep(TERMINATION_POLL_MILLIS)
        }
    }

    private fun InputStream.capture(maximumBytes: Int): CapturedBytes {
        val output = ByteArray(maximumBytes)
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var count = 0
        var truncated = false
        use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                val remaining = maximumBytes - count
                if (remaining > 0) {
                    val copied = min(remaining, read)
                    buffer.copyInto(output, destinationOffset = count, endIndex = copied)
                    count += copied
                }
                if (read > remaining) truncated = true
            }
        }
        return CapturedBytes(output.copyOf(count), truncated)
    }

    private data class CapturedBytes(
        val bytes: ByteArray,
        val truncated: Boolean,
    )

    private companion object {
        val POLL_INTERVAL = 20.milliseconds
        const val TERMINATION_POLL_MILLIS = 10L
        const val READ_BUFFER_SIZE = 8 * 1024
    }
}
