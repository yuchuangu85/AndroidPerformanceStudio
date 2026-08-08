package com.androidperformancestudio.methodcapture

import com.androidperformancestudio.adb.AdbDevicePropertiesReader
import com.androidperformancestudio.adb.AdbDeviceRefresher
import com.androidperformancestudio.adb.AdbTargetCatalog
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessCancellationSignal
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
        JvmProcessRunner().run(request, signal)
    },
) {
    suspend fun refreshDevices(): StudioResult<List<MethodTraceDeviceOption>> {
        val refresher = AdbDeviceRefresher(adbExecutable, processInvocation = processRunner)
        return when (val result = refresher.refresh(ProcessCancellationSignal())) {
            is StudioResult.Failure -> result
            is StudioResult.Success ->
                StudioResult.Success(
                    result.value.map { device ->
                        val apiLevel =
                            if (device.state == AdbDeviceState.ONLINE) {
                                val properties =
                                    AdbDevicePropertiesReader(adbExecutable, processInvocation = processRunner)
                                        .read(device.serial, ProcessCancellationSignal())
                                (properties as? StudioResult.Success)?.value?.sdkInt
                            } else {
                                null
                            }
                        MethodTraceDeviceOption(
                            serial = device.serial,
                            name = device.model?.replace('_', ' ') ?: device.serial,
                            online = device.state == AdbDeviceState.ONLINE,
                            sdkApiLevel = apiLevel,
                        )
                    },
                )
        }
    }

    suspend fun loadProcesses(serial: String): StudioResult<List<MethodTraceProcessOption>> {
        val catalog = AdbTargetCatalog(adbExecutable, processRunner)
        return when (val result = catalog.refresh(serial, ProcessCancellationSignal())) {
            is StudioResult.Failure -> result
            is StudioResult.Success -> {
                val profileable =
                    result.value.packages
                        .filter { it.debuggable || it.profileableByShell }
                        .mapTo(hashSetOf()) { it.packageName }
                val processes =
                    result.value.processes
                        .mapNotNull { process ->
                            val packageName =
                                profileable.firstOrNull { candidate ->
                                    process.name == candidate || process.name.startsWith("$candidate:")
                                } ?: return@mapNotNull null
                            MethodTraceProcessOption(pid = process.pid, name = process.name, packageName = packageName)
                        }.sortedWith(compareBy<MethodTraceProcessOption> { it.name }.thenBy { it.pid })
                StudioResult.Success(processes)
            }
        }
    }
}
