package com.androidperformancestudio.platform.adb

import com.androidperformancestudio.platform.toolchain.HostProcessBinaryResult
import com.androidperformancestudio.platform.toolchain.HostProcessCancelledException
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRunner
import com.androidperformancestudio.platform.toolchain.HostProcessStartException
import com.androidperformancestudio.platform.toolchain.HostProcessTextResult
import com.androidperformancestudio.platform.toolchain.HostProcessTimeoutException
import com.androidperformancestudio.platform.toolchain.JvmHostProcessRunner
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Shared typed execution boundary for feature-specific ADB argument vectors.
 *
 * Features may own their domain command construction, but they must not create a second host
 * process runner. Standard [AdbClient] operations remain preferred when an operation is common.
 */
public interface AdbCommandExecutor {
    public suspend fun executeText(
        arguments: List<String>,
        timeout: Duration = AdbClient.DEFAULT_TIMEOUT,
        maxOutputBytesPerStream: Int = AdbCommand.DEFAULT_MAX_OUTPUT_BYTES,
        isCancellationRequested: () -> Boolean = { false },
    ): AdbTextResult

    public suspend fun executeBinary(
        arguments: List<String>,
        timeout: Duration = AdbClient.DEFAULT_TIMEOUT,
        maxOutputBytesPerStream: Int = AdbCommand.DEFAULT_MAX_OUTPUT_BYTES,
        isCancellationRequested: () -> Boolean = { false },
    ): AdbBinaryResult
}

/** Default [AdbCommandExecutor] backed by the sole shared host-process implementation. */
public class DefaultAdbCommandExecutor(
    private val executable: Path,
    private val processRunner: HostProcessRunner = JvmHostProcessRunner(),
) : AdbCommandExecutor {
    override suspend fun executeText(
        arguments: List<String>,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbTextResult {
        val command = command(arguments, timeout, maxOutputBytesPerStream, isCancellationRequested)
        return mapFailures(command) {
            processRunner.executeText(command.toHostRequest()).toAdbTextResult()
        }
    }

    override suspend fun executeBinary(
        arguments: List<String>,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbBinaryResult {
        val command = command(arguments, timeout, maxOutputBytesPerStream, isCancellationRequested)
        return mapFailures(command) {
            processRunner.executeBinary(command.toHostRequest()).toAdbBinaryResult()
        }
    }

    private fun command(
        arguments: List<String>,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbCommand =
        AdbCommand(
            executable = executable,
            arguments = arguments,
            timeout = timeout,
            maxOutputBytesPerStream = maxOutputBytesPerStream,
            isCancellationRequested = isCancellationRequested,
        )

    private suspend fun <T> mapFailures(
        command: AdbCommand,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (error: HostProcessStartException) {
            throw AdbProcessStartException(command.commandLine, error.cause ?: error)
        } catch (error: HostProcessTimeoutException) {
            throw AdbCommandTimeoutException(command.commandLine, command.timeout, error.pid)
        } catch (error: HostProcessCancelledException) {
            throw AdbCommandCancelledException(command.commandLine, error.pid).also { it.initCause(error) }
        }
}

private fun AdbCommand.toHostRequest(): HostProcessRequest =
    HostProcessRequest(
        executable = executable,
        arguments = arguments,
        timeout = timeout,
        maxOutputBytesPerStream = maxOutputBytesPerStream,
        isCancellationRequested = isCancellationRequested,
        workingDirectory = workingDirectory,
        environmentOverrides = environmentOverrides,
        charset = charset,
    )

private fun HostProcessTextResult.toAdbTextResult(): AdbTextResult =
    AdbTextResult(
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        duration = duration,
        stdoutTruncated = stdoutTruncated,
        stderrTruncated = stderrTruncated,
        pid = pid,
    )

private fun HostProcessBinaryResult.toAdbBinaryResult(): AdbBinaryResult =
    AdbBinaryResult(
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        duration = duration,
        stdoutTruncated = stdoutTruncated,
        stderrTruncated = stderrTruncated,
        pid = pid,
    )
