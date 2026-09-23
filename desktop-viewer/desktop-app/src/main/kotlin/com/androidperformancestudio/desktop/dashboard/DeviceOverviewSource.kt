package com.androidperformancestudio.desktop.dashboard

import com.androidperformancestudio.adb.AdbConfiguration
import com.androidperformancestudio.adb.AndroidTargetMonitors
import com.androidperformancestudio.adb.defaultAdbExecutable
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.platform.adb.AndroidDeviceInfo
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class StudioDeviceSummary(
    val serial: String,
    val displayName: String,
    val state: AdbDeviceState,
) {
    val online: Boolean get() = state == AdbDeviceState.ONLINE
}

internal sealed interface StudioDeviceOverview {
    data class Available(val devices: List<StudioDeviceSummary>) : StudioDeviceOverview {
        val connectedCount: Int get() = devices.count(StudioDeviceSummary::online)
    }

    data object Unavailable : StudioDeviceOverview
}

/** One-shot dashboard projection; it does not select devices or own a feature controller. */
internal class DeviceOverviewSource(
    private val configuredSdkPath: String?,
    private val locateAdb: (String?) -> Path? = { configured ->
        defaultAdbExecutable(
            AdbConfiguration(androidSdkPath = configured?.let { Path.of(it) }),
        )
    },
    private val discover: suspend (Path) -> StudioResult<List<AndroidDeviceInfo>> = { adb ->
        AndroidTargetMonitors.shared(adb).refreshDevices()
    },
) {
    suspend fun load(): StudioDeviceOverview = withContext(Dispatchers.IO) {
        try {
            val adb = locateAdb(configuredSdkPath) ?: return@withContext StudioDeviceOverview.Unavailable
            when (val result = discover(adb)) {
                is StudioResult.Failure -> StudioDeviceOverview.Unavailable
                is StudioResult.Success ->
                    StudioDeviceOverview.Available(
                        result.value.map { device ->
                            StudioDeviceSummary(
                                serial = device.serial,
                                displayName = device.displayName,
                                state = device.state,
                            )
                        },
                    )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            StudioDeviceOverview.Unavailable
        }
    }
}
