package com.androidperformancestudio.platform.adb

import com.androidperformancestudio.platform.toolchain.HostProcessBinaryResult
import com.androidperformancestudio.platform.toolchain.HostProcessCancelledException
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRunner
import com.androidperformancestudio.platform.toolchain.HostProcessStartException
import com.androidperformancestudio.platform.toolchain.HostProcessTextResult
import com.androidperformancestudio.platform.toolchain.HostProcessTimeoutException
import com.androidperformancestudio.platform.toolchain.JvmHostProcessRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface AdbClient {
    suspend fun listDevices(): List<AdbDevice>

    suspend fun shell(
        serial: String,
        arguments: List<String>,
        timeout: Duration = DEFAULT_TIMEOUT,
        maxOutputBytesPerStream: Int = AdbCommand.DEFAULT_MAX_OUTPUT_BYTES,
        isCancellationRequested: () -> Boolean = { false },
    ): AdbTextResult

    suspend fun execOut(
        serial: String,
        arguments: List<String>,
        timeout: Duration = DEFAULT_TIMEOUT,
        maxOutputBytesPerStream: Int = AdbCommand.DEFAULT_MAX_OUTPUT_BYTES,
        isCancellationRequested: () -> Boolean = { false },
    ): AdbBinaryResult

    suspend fun push(
        serial: String,
        localPath: Path,
        remotePath: String,
        timeout: Duration = DEFAULT_TIMEOUT,
        maxOutputBytesPerStream: Int = AdbCommand.DEFAULT_MAX_OUTPUT_BYTES,
        isCancellationRequested: () -> Boolean = { false },
    ): AdbTextResult

    suspend fun pull(
        serial: String,
        remotePath: String,
        localPath: Path,
        timeout: Duration = DEFAULT_TIMEOUT,
        maxOutputBytesPerStream: Int = AdbCommand.DEFAULT_MAX_OUTPUT_BYTES,
        isCancellationRequested: () -> Boolean = { false },
    ): AdbTextResult

    suspend fun forward(
        serial: String,
        local: String,
        remote: String,
        timeout: Duration = DEFAULT_TIMEOUT,
        maxOutputBytesPerStream: Int = AdbCommand.DEFAULT_MAX_OUTPUT_BYTES,
        isCancellationRequested: () -> Boolean = { false },
    ): AdbTextResult

    suspend fun removeForward(
        serial: String,
        local: String,
        timeout: Duration = DEFAULT_TIMEOUT,
        maxOutputBytesPerStream: Int = AdbCommand.DEFAULT_MAX_OUTPUT_BYTES,
        isCancellationRequested: () -> Boolean = { false },
    ): AdbTextResult

    suspend fun bugreport(
        serial: String,
        outputPath: Path,
        timeout: Duration = DEFAULT_TIMEOUT,
        maxOutputBytesPerStream: Int = AdbCommand.DEFAULT_MAX_OUTPUT_BYTES,
        isCancellationRequested: () -> Boolean = { false },
    ): AdbTextResult

    companion object {
        val DEFAULT_TIMEOUT: Duration = 30.seconds
    }
}

class DefaultAdbClient(
    private val executable: Path,
    private val processRunner: HostProcessRunner = JvmHostProcessRunner(),
    private val devicesParser: AdbDevicesParser = AdbDevicesParser(),
) : AdbClient {
    override suspend fun listDevices(): List<AdbDevice> {
        val command = command(listOf("devices", "-l"), AdbClient.DEFAULT_TIMEOUT)
        return devicesParser.parse(executeText(command).requireSuccess(command).stdout)
    }

    override suspend fun shell(
        serial: String,
        arguments: List<String>,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbTextResult =
        executeText(
            adbDeviceArguments(serial, "shell", arguments),
            timeout,
            maxOutputBytesPerStream,
            isCancellationRequested,
        )

    override suspend fun execOut(
        serial: String,
        arguments: List<String>,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbBinaryResult =
        executeBinary(
            adbDeviceArguments(serial, "exec-out", arguments),
            timeout,
            maxOutputBytesPerStream,
            isCancellationRequested,
        )

    override suspend fun push(
        serial: String,
        localPath: Path,
        remotePath: String,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbTextResult {
        if (!Files.isRegularFile(localPath)) {
            throw AdbInputException("Local push source is not a file: $localPath")
        }
        return executeText(
            adbDeviceArguments(
                serial,
                "push",
                listOf(localPath.toString(), AdbInputValidator.requireRemotePath(remotePath)),
            ),
            timeout,
            maxOutputBytesPerStream,
            isCancellationRequested,
        )
    }

    override suspend fun pull(
        serial: String,
        remotePath: String,
        localPath: Path,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbTextResult =
        executeText(
            adbDeviceArguments(
                serial,
                "pull",
                listOf(AdbInputValidator.requireRemotePath(remotePath), localPath.toString()),
            ),
            timeout,
            maxOutputBytesPerStream,
            isCancellationRequested,
        )

    override suspend fun forward(
        serial: String,
        local: String,
        remote: String,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbTextResult =
        executeText(
            adbDeviceArguments(
                serial,
                "forward",
                listOf(
                    AdbInputValidator.requireForwardEndpoint(local),
                    AdbInputValidator.requireForwardEndpoint(remote),
                ),
            ),
            timeout,
            maxOutputBytesPerStream,
            isCancellationRequested,
        )

    override suspend fun removeForward(
        serial: String,
        local: String,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbTextResult =
        executeText(
            adbDeviceArguments(
                serial,
                "forward",
                listOf("--remove", AdbInputValidator.requireForwardEndpoint(local)),
            ),
            timeout,
            maxOutputBytesPerStream,
            isCancellationRequested,
        )

    override suspend fun bugreport(
        serial: String,
        outputPath: Path,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbTextResult =
        executeText(
            adbDeviceArguments(serial, "bugreport", listOf(outputPath.toAbsolutePath().toString())),
            timeout,
            maxOutputBytesPerStream,
            isCancellationRequested,
        )

    private suspend fun executeText(
        arguments: List<String>,
        timeout: Duration,
        maxOutputBytesPerStream: Int = AdbCommand.DEFAULT_MAX_OUTPUT_BYTES,
        isCancellationRequested: () -> Boolean = { false },
    ): AdbTextResult {
        val command = command(arguments, timeout, maxOutputBytesPerStream, isCancellationRequested)
        return executeText(command).requireSuccess(command)
    }

    private suspend fun executeBinary(
        arguments: List<String>,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbBinaryResult {
        val command = command(arguments, timeout, maxOutputBytesPerStream, isCancellationRequested)
        return executeBinary(command).requireSuccess(command)
    }

    private suspend fun executeText(command: AdbCommand): AdbTextResult =
        mapFailures(command) { processRunner.executeText(command.toHostRequest()).toAdbResult() }

    private suspend fun executeBinary(command: AdbCommand): AdbBinaryResult =
        mapFailures(command) { processRunner.executeBinary(command.toHostRequest()).toAdbResult() }

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

    private fun command(
        arguments: List<String>,
        timeout: Duration,
        maxOutputBytesPerStream: Int = AdbCommand.DEFAULT_MAX_OUTPUT_BYTES,
        isCancellationRequested: () -> Boolean = { false },
    ): AdbCommand =
        AdbCommand(
            executable = executable,
            arguments = arguments,
            timeout = timeout,
            maxOutputBytesPerStream = maxOutputBytesPerStream,
            isCancellationRequested = isCancellationRequested,
        )
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

private fun HostProcessTextResult.toAdbResult(): AdbTextResult =
    AdbTextResult(
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        duration = duration,
        stdoutTruncated = stdoutTruncated,
        stderrTruncated = stderrTruncated,
        pid = pid,
    )

private fun HostProcessBinaryResult.toAdbResult(): AdbBinaryResult =
    AdbBinaryResult(
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        duration = duration,
        stdoutTruncated = stdoutTruncated,
        stderrTruncated = stderrTruncated,
        pid = pid,
    )

private fun adbDeviceArguments(
    serial: String,
    operation: String,
    arguments: List<String>,
): List<String> = listOf("-s", AdbInputValidator.requireSerial(serial), operation) + arguments
