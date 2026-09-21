package com.androidperformancestudio.methodcapture

import com.androidperformancestudio.adb.AdbDevicePropertiesReader
import com.androidperformancestudio.adb.AndroidTargetListener
import com.androidperformancestudio.adb.AndroidTargetMonitor
import com.androidperformancestudio.adb.AndroidTargetMonitors
import com.androidperformancestudio.adb.AndroidTargetSubscription
import com.androidperformancestudio.adb.profileableOrDebuggableProcesses
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.platform.toolchain.HostCancellationSignal
import com.androidperformancestudio.platform.toolchain.StudioHostProcessExecutor
import java.nio.file.Path

data class MethodTraceDeviceOption(
    val serial: String,
    val name: String,
    val online: Boolean,
    val sdkApiLevel: Int?,
)

data class MethodTraceProcessOption(
    val pid: Int,
    val name: String,
    val packageName: String,
)

/**
 * Discovers devices and (debuggable / profileable) processes for the method-recording UI, reusing
 * the shared `device-adb` classes directly.
 */
class MethodRecordingDeviceGateway(
    private val adbExecutable: Path,
    private val processRunner: MethodTraceCaptureProcessRunner = { request, signal ->
        StudioHostProcessExecutor().run(request, signal)
    },
    private val targetMonitor: AndroidTargetMonitor =
        AndroidTargetMonitors.create(adbExecutable, processRunner),
) {
    fun register(listener: AndroidTargetListener): AndroidTargetSubscription = targetMonitor.register(listener)

    suspend fun refreshDevices(): StudioResult<List<MethodTraceDeviceOption>> =
        when (val result = targetMonitor.refreshDevices(HostCancellationSignal())) {
            is StudioResult.Failure -> result
            is StudioResult.Success ->
                StudioResult.Success(
                    result.value.map { device ->
                        val apiLevel =
                            if (device.online) {
                                val properties =
                                    AdbDevicePropertiesReader(adbExecutable, processInvocation = processRunner)
                                        .read(device.serial, HostCancellationSignal())
                                (properties as? StudioResult.Success)?.value?.sdkInt
                            } else {
                                null
                            }
                        MethodTraceDeviceOption(
                            serial = device.serial,
                            name = device.displayName,
                            online = device.online,
                            sdkApiLevel = apiLevel,
                        )
                    },
                )
        }

    suspend fun loadProcesses(serial: String): StudioResult<List<MethodTraceProcessOption>> =
        when (val result = targetMonitor.refreshTargets(serial, HostCancellationSignal())) {
            is StudioResult.Failure -> result
            is StudioResult.Success ->
                StudioResult.Success(
                    result.value.profileableOrDebuggableProcesses().map { process ->
                        MethodTraceProcessOption(process.pid, process.name, process.packageName)
                    },
                )
        }
}
