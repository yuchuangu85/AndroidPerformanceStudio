package com.androidperformancestudio.desktop

import com.androidperformancestudio.adb.AdbConfiguration
import com.androidperformancestudio.adb.AdbTargetSnapshot
import com.androidperformancestudio.adb.AndroidTargetMonitor
import com.androidperformancestudio.adb.AndroidTargetMonitors
import com.androidperformancestudio.adb.defaultAdbExecutable
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.ui.TitleBarDeviceOption
import com.androidperformancestudio.ui.TitleBarProcessOption
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Discovers the devices and processes shown by the fixed title-bar selectors.
 *
 * It only reads the shared ADB target monitor; capture sessions and per-page target state stay in
 * the feature controllers. Discovery failure keeps the previous list so the title bar never flickers
 * empty while a device is briefly unreachable.
 */
internal class DesktopTargetBarSource(
    private val configuredSdkPath: String?,
    private val locateAdb: (String?) -> Path? = { configured ->
        defaultAdbExecutable(
            AdbConfiguration(androidSdkPath = configured?.let { Path.of(it) }),
        )
    },
    private val monitor: (Path) -> AndroidTargetMonitor = AndroidTargetMonitors::shared,
) {
    suspend fun loadDevices(): List<TitleBarDeviceOption>? = io {
        when (val result = monitor(adbExecutable()).refreshDevices()) {
            is StudioResult.Success ->
                result.value.map { device ->
                    TitleBarDeviceOption(
                        serial = device.serial,
                        label = device.displayName,
                        online = device.state == AdbDeviceState.ONLINE,
                    )
                }
            is StudioResult.Failure -> null
        }
    }

    suspend fun loadProcesses(serial: String): List<TitleBarProcessOption>? = io {
        when (val result = monitor(adbExecutable()).refreshTargets(serial)) {
            is StudioResult.Success -> result.value.toProcessOptions()
            is StudioResult.Failure -> null
        }
    }

    private fun adbExecutable(): Path = locateAdb(configuredSdkPath) ?: error("ADB is not available")

    private suspend fun <T> io(block: suspend () -> T): T? =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

    private fun AdbTargetSnapshot.toProcessOptions(): List<TitleBarProcessOption> {
        val packageNames = packages.map { it.packageName }
        return processes.map { process ->
            TitleBarProcessOption(
                pid = process.pid,
                name = process.name,
                packageName = packageNames.firstOrNull { packageName -> process.name.belongsTo(packageName) },
                user = process.user,
            )
        }
    }

    internal companion object {
        /** How often the fixed title-bar device and process lists refresh themselves. */
        const val REFRESH_INTERVAL_MILLIS: Long = 3_000
    }
}

private fun String.belongsTo(packageName: String): Boolean =
    this == packageName || startsWith("$packageName:")
