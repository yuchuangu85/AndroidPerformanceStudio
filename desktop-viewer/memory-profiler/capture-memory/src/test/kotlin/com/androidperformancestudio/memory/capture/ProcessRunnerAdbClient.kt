@file:Suppress("MaxLineLength")

package com.androidperformancestudio.memory.capture

import com.androidperformancestudio.platform.adb.AdbBinaryResult
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbCommandFailedException
import com.androidperformancestudio.platform.adb.AdbDevice
import com.androidperformancestudio.platform.adb.AdbTextResult
import com.androidperformancestudio.platform.toolchain.HostCancellationSignal
import com.androidperformancestudio.platform.toolchain.HostCommandResult
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import java.nio.file.Path
import kotlin.time.Duration

internal class ProcessRunnerAdbClient(
    private val invocation: MemoryHostProcessRunner,
) : AdbClient {
    override suspend fun listDevices(): List<AdbDevice> = emptyList()

    override suspend fun shell(
        serial: String,
        arguments: List<String>,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbTextResult = execute(serial, listOf("shell") + arguments, timeout, isCancellationRequested)

    override suspend fun execOut(
        serial: String,
        arguments: List<String>,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbBinaryResult = error("Not used")

    override suspend fun push(
        serial: String,
        localPath: Path,
        remotePath: String,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbTextResult = execute(serial, listOf("push", localPath.toString(), remotePath), timeout, isCancellationRequested)

    override suspend fun pull(
        serial: String,
        remotePath: String,
        localPath: Path,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbTextResult = execute(serial, listOf("pull", remotePath, localPath.toString()), timeout, isCancellationRequested)

    override suspend fun forward(
        serial: String,
        local: String,
        remote: String,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbTextResult = error("Not used")

    override suspend fun removeForward(
        serial: String,
        local: String,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbTextResult = error("Not used")

    override suspend fun bugreport(
        serial: String,
        outputPath: Path,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbTextResult = error("Not used")

    private suspend fun execute(
        serial: String,
        arguments: List<String>,
        timeout: Duration,
        isCancellationRequested: () -> Boolean,
    ): AdbTextResult {
        val cancellation = HostCancellationSignal().also { if (isCancellationRequested()) it.cancel() }
        val request =
            HostProcessRequest(
                executable = Path.of("adb"),
                arguments = listOf("-s", serial) + arguments,
                timeout = timeout,
            )
        return when (val result = invocation(request, cancellation)) {
            is HostCommandResult.Completed ->
                AdbTextResult(
                    exitCode = result.output.exitCode ?: 0,
                    stdout = result.output.stdout.text,
                    stderr = result.output.stderr.text,
                    duration = Duration.ZERO,
                    stdoutTruncated = result.output.stdout.truncated,
                    stderrTruncated = result.output.stderr.truncated,
                    pid = result.output.pid,
                )
            is HostCommandResult.Failed ->
                throw AdbCommandFailedException(
                    request.command,
                    result.output?.exitCode ?: 1,
                    result.output
                        ?.stderr
                        ?.text
                        .orEmpty()
                        .ifBlank { result.error.message },
                )
        }
    }
}
