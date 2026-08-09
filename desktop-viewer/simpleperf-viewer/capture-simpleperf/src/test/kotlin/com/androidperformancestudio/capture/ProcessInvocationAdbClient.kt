@file:Suppress("MaxLineLength")

package com.androidperformancestudio.capture

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.platform.adb.AdbBinaryResult
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbCommandCancelledException
import com.androidperformancestudio.platform.adb.AdbCommandFailedException
import com.androidperformancestudio.platform.adb.AdbCommandTimeoutException
import com.androidperformancestudio.platform.adb.AdbDevice
import com.androidperformancestudio.platform.adb.AdbTextResult
import com.androidperformancestudio.platform.toolchain.HostCancellationSignal
import com.androidperformancestudio.platform.toolchain.HostCommandResult
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.nio.file.Path
import kotlin.time.Duration

internal class ProcessInvocationAdbClient(
    private val invocation: suspend (HostProcessRequest, HostCancellationSignal) -> HostCommandResult,
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
    ): AdbTextResult = execute(serial, listOf("forward", local, remote), timeout, isCancellationRequested)

    override suspend fun removeForward(
        serial: String,
        local: String,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbTextResult = execute(serial, listOf("forward", "--remove", local), timeout, isCancellationRequested)

    override suspend fun bugreport(
        serial: String,
        outputPath: Path,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbTextResult = execute(serial, listOf("bugreport", outputPath.toString()), timeout, isCancellationRequested)

    private suspend fun execute(
        serial: String,
        arguments: List<String>,
        timeout: Duration,
        isCancellationRequested: () -> Boolean,
    ): AdbTextResult =
        supervisorScope {
            val signal = HostCancellationSignal()
            val watcher =
                launch {
                    while (currentCoroutineContext().isActive && !isCancellationRequested()) delay(1)
                    if (currentCoroutineContext().isActive) signal.cancel()
                }
            val request =
                HostProcessRequest(
                    executable = Path.of("adb"),
                    arguments = listOf("-s", serial) + arguments,
                    timeout = timeout,
                )
            try {
                when (val result = invocation(request, signal)) {
                    is HostCommandResult.Completed -> result.output.toAdbResult()
                    is HostCommandResult.Failed -> throw result.toAdbException(request, timeout)
                }
            } finally {
                watcher.cancel()
            }
        }
}

private fun com.androidperformancestudio.platform.toolchain.HostCommandOutput.toAdbResult(): AdbTextResult =
    AdbTextResult(
        exitCode = exitCode ?: 0,
        stdout = stdout.text,
        stderr = stderr.text,
        duration = Duration.ZERO,
        stdoutTruncated = stdout.truncated,
        stderrTruncated = stderr.truncated,
        pid = pid,
    )

private fun HostCommandResult.Failed.toAdbException(
    request: HostProcessRequest,
    timeout: Duration,
): RuntimeException =
    when (error.category) {
        ErrorCategory.PROCESS_CANCELLED -> AdbCommandCancelledException(request.command)
        ErrorCategory.PROCESS_TIMEOUT -> AdbCommandTimeoutException(request.command, timeout)
        else ->
            AdbCommandFailedException(
                request.command,
                output?.exitCode ?: 1,
                output
                    ?.stderr
                    ?.text
                    .orEmpty()
                    .ifBlank { error.message },
            )
    }
