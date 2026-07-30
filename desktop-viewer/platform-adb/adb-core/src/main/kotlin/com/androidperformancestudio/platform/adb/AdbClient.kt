package com.androidperformancestudio.platform.adb

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
    ): AdbTextResult

    suspend fun execOut(
        serial: String,
        arguments: List<String>,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): AdbBinaryResult

    suspend fun push(
        serial: String,
        localPath: Path,
        remotePath: String,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): AdbTextResult

    suspend fun pull(
        serial: String,
        remotePath: String,
        localPath: Path,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): AdbTextResult

    suspend fun forward(
        serial: String,
        local: String,
        remote: String,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): AdbTextResult

    suspend fun removeForward(
        serial: String,
        local: String,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): AdbTextResult

    companion object {
        val DEFAULT_TIMEOUT: Duration = 30.seconds
    }
}

class DefaultAdbClient(
    private val executable: Path,
    private val processRunner: ProcessRunner = JvmProcessRunner(),
    private val devicesParser: AdbDevicesParser = AdbDevicesParser(),
) : AdbClient {
    override suspend fun listDevices(): List<AdbDevice> {
        val command = command(listOf("devices", "-l"), AdbClient.DEFAULT_TIMEOUT)
        return devicesParser.parse(processRunner.executeText(command).requireSuccess(command).stdout)
    }

    override suspend fun shell(
        serial: String,
        arguments: List<String>,
        timeout: Duration,
    ): AdbTextResult = executeText(adbDeviceArguments(serial, "shell", arguments), timeout)

    override suspend fun execOut(
        serial: String,
        arguments: List<String>,
        timeout: Duration,
    ): AdbBinaryResult = executeBinary(adbDeviceArguments(serial, "exec-out", arguments), timeout)

    override suspend fun push(
        serial: String,
        localPath: Path,
        remotePath: String,
        timeout: Duration,
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
        )
    }

    override suspend fun pull(
        serial: String,
        remotePath: String,
        localPath: Path,
        timeout: Duration,
    ): AdbTextResult =
        executeText(
            adbDeviceArguments(
                serial,
                "pull",
                listOf(AdbInputValidator.requireRemotePath(remotePath), localPath.toString()),
            ),
            timeout,
        )

    override suspend fun forward(
        serial: String,
        local: String,
        remote: String,
        timeout: Duration,
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
        )

    override suspend fun removeForward(
        serial: String,
        local: String,
        timeout: Duration,
    ): AdbTextResult =
        executeText(
            adbDeviceArguments(
                serial,
                "forward",
                listOf("--remove", AdbInputValidator.requireForwardEndpoint(local)),
            ),
            timeout,
        )

    private suspend fun executeText(
        arguments: List<String>,
        timeout: Duration,
    ): AdbTextResult {
        val command = command(arguments, timeout)
        return processRunner.executeText(command).requireSuccess(command)
    }

    private suspend fun executeBinary(
        arguments: List<String>,
        timeout: Duration,
    ): AdbBinaryResult {
        val command = command(arguments, timeout)
        return processRunner.executeBinary(command).requireSuccess(command)
    }

    private fun command(
        arguments: List<String>,
        timeout: Duration,
    ): AdbCommand = AdbCommand(executable = executable, arguments = arguments, timeout = timeout)
}

private fun adbDeviceArguments(
    serial: String,
    operation: String,
    arguments: List<String>,
): List<String> = listOf("-s", AdbInputValidator.requireSerial(serial), operation) + arguments
