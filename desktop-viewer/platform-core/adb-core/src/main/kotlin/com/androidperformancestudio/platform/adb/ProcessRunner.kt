package com.androidperformancestudio.platform.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

interface ProcessRunner {
    suspend fun executeText(command: AdbCommand): AdbTextResult

    suspend fun executeBinary(command: AdbCommand): AdbBinaryResult
}

class JvmProcessRunner(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val terminationGracePeriod: Duration = 250.milliseconds,
) : ProcessRunner {
    override suspend fun executeText(command: AdbCommand): AdbTextResult {
        val result = executeBinary(command)
        return AdbTextResult(
            exitCode = result.exitCode,
            stdout = result.stdout.toString(command.charset),
            stderr = result.stderr.toString(command.charset),
            duration = result.duration,
            stdoutTruncated = result.stdoutTruncated,
            stderrTruncated = result.stderrTruncated,
            pid = result.pid,
        )
    }

    override suspend fun executeBinary(command: AdbCommand): AdbBinaryResult =
        withContext(ioDispatcher) {
            val started = TimeSource.Monotonic.markNow()
            val process =
                try {
                    ProcessBuilder(command.commandLine)
                        .apply {
                            command.workingDirectory?.let { directory(it.toFile()) }
                            environment().putAll(command.environmentOverrides)
                            redirectErrorStream(false)
                        }.start()
                } catch (error: IOException) {
                    throw AdbProcessStartException(command.commandLine, error)
                }
            val terminating = AtomicBoolean(false)
            try {
                coroutineScope {
                    val stdout = async { process.inputStream.capture(command.maxOutputBytesPerStream) }
                    val stderr = async { process.errorStream.capture(command.maxOutputBytesPerStream) }
                    try {
                        awaitExit(process, command)
                    } catch (error: CancellationException) {
                        terminateProcessTree(process, terminating)
                        throw AdbCommandCancelledException(command.commandLine, process.pid()).also {
                            it.initCause(error)
                        }
                    } catch (error: AdbCommandTimeoutException) {
                        terminateProcessTree(process, terminating)
                        throw error
                    }
                    val standardOutput = stdout.await()
                    val standardError = stderr.await()
                    AdbBinaryResult(
                        exitCode = process.exitValue(),
                        stdout = standardOutput.bytes,
                        stderr = standardError.bytes,
                        duration = started.elapsedNow(),
                        stdoutTruncated = standardOutput.truncated,
                        stderrTruncated = standardError.truncated,
                        pid = process.pid(),
                    )
                }
            } finally {
                if (process.isAlive) terminateProcessTree(process, terminating)
                process.inputStream.close()
                process.errorStream.close()
                process.outputStream.close()
            }
        }

    private suspend fun awaitExit(
        process: Process,
        command: AdbCommand,
    ) {
        val deadline = System.nanoTime() + command.timeout.inWholeNanoseconds
        while (process.isAlive) {
            currentCoroutineContext().ensureActive()
            if (command.isCancellationRequested()) {
                throw AdbCommandCancelledException(command.commandLine, process.pid())
            }
            if (System.nanoTime() >= deadline) {
                throw AdbCommandTimeoutException(command.commandLine, command.timeout, process.pid())
            }
            delay(POLL_INTERVAL)
        }
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
        const val READ_BUFFER_SIZE = 8 * 1024
    }
}
