package com.androidperformancestudio.winscope.app

import com.androidperformancestudio.adb.SystemAdbLocator
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbDevice
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import com.androidperformancestudio.platform.toolchain.SystemHostPlatformDetector
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal data class WinscopeDeviceOption(
    val serial: String,
    val label: String,
)

internal data class WinscopeDeviceDiscoveryResult(
    val adbExecutable: Path,
    val devices: List<WinscopeDeviceOption>,
)

internal class WinscopeDeviceDiscovery(
    private val locateAdb: () -> StudioResult<Path> = ::locateSystemAdb,
    private val listDevices: suspend (Path) -> List<AdbDevice> = { executable ->
        DefaultAdbClient(executable).listDevices()
    },
) {
    suspend fun discover(configuredAdbPath: String?): StudioResult<WinscopeDeviceDiscoveryResult> {
        val executable = configuredAdbPath.toConfiguredPathResult() ?: locateAdb()
        return when (executable) {
            is StudioResult.Failure -> executable
            is StudioResult.Success ->
                try {
                    StudioResult.Success(
                        WinscopeDeviceDiscoveryResult(
                            adbExecutable = executable.value,
                            devices =
                                listDevices(executable.value)
                                    .asSequence()
                                    .filter { it.state == AdbDeviceState.ONLINE }
                                    .map { device ->
                                        WinscopeDeviceOption(
                                            serial = device.serial,
                                            label = device.model?.replace('_', ' ') ?: device.serial,
                                        )
                                    }.toList(),
                        ),
                    )
                } catch (error: Exception) {
                    deviceDiscoveryFailure(
                        code = "WINSCOPE_DEVICE_DISCOVERY_FAILED",
                        message = error.message ?: "Unable to list Android devices",
                        cause = error,
                    )
                }
        }
    }
}

private fun locateSystemAdb(): StudioResult<Path> =
    when (val platform = SystemHostPlatformDetector().detect()) {
        is StudioResult.Failure -> platform
        is StudioResult.Success ->
            when (val located = SystemAdbLocator(platform.value).locate()) {
                is StudioResult.Failure -> located
                is StudioResult.Success -> StudioResult.Success(located.value.executable)
            }
    }

private fun String?.toConfiguredPathResult(): StudioResult<Path>? {
    val configuredPath = this?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    return try {
        StudioResult.Success(Path.of(configuredPath))
    } catch (error: InvalidPathException) {
        deviceDiscoveryFailure(
            code = "WINSCOPE_ADB_PATH_INVALID",
            message = "Invalid ADB path: $configuredPath",
            cause = error,
        )
    }
}

private fun <T> deviceDiscoveryFailure(
    code: String,
    message: String,
    cause: Throwable,
): StudioResult<T> =
    StudioResult.Failure(
        StudioError(
            category = ErrorCategory.CONFIGURATION,
            code = code,
            message = message,
            cause = cause,
        ),
    )
