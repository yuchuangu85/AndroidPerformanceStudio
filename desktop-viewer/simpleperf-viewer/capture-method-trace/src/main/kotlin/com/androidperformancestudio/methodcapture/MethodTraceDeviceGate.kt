package com.androidperformancestudio.methodcapture

import com.androidperformancestudio.adb.AdbDeviceCapabilityDetector
import com.androidperformancestudio.adb.AdbDevicePropertiesReader
import com.androidperformancestudio.adb.AdbTargetCatalog
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.toolchain.HostCancellationSignal
import java.nio.file.Path

/** Whether `am profile` method tracing is available for a device + app. */
data class MethodTraceDeviceSupport(
    val supported: Boolean,
    val reason: String?,
    val sdkApiLevel: Int?,
)

/**
 * Pre-flight gate for method tracing: the device must run API 21+ and the target app must be
 * debuggable or `profileableByShell` — unless the device is rooted, in which case any process can
 * be traced.
 */
object MethodTraceDeviceGate {
    @Suppress("ReturnCount")
    suspend fun evaluate(
        serial: String,
        packageName: String,
        adbExecutable: Path,
        processRunner: MethodTraceCaptureProcessRunner,
    ): StudioResult<MethodTraceDeviceSupport> {
        val properties =
            when (
                val result =
                    AdbDevicePropertiesReader(adbExecutable, processInvocation = processRunner)
                        .read(serial, HostCancellationSignal())
            ) {
                is StudioResult.Failure -> return result
                is StudioResult.Success -> result.value
            }
        val sdkLevel = properties.sdkInt
        if (sdkLevel < MethodTraceCaptureSession.MINIMUM_API) {
            return StudioResult.Success(
                MethodTraceDeviceSupport(
                    supported = false,
                    reason =
                        "Method tracing requires Android API ${MethodTraceCaptureSession.MINIMUM_API}+; " +
                            "device is API $sdkLevel.",
                    sdkApiLevel = sdkLevel,
                ),
            )
        }

        val capabilities =
            when (
                val result =
                    AdbDeviceCapabilityDetector(adbExecutable, processRunner)
                        .detect(properties, HostCancellationSignal())
            ) {
                is StudioResult.Failure -> return result
                is StudioResult.Success -> result.value
            }
        if (capabilities.isRoot) {
            return StudioResult.Success(
                MethodTraceDeviceSupport(supported = true, reason = null, sdkApiLevel = sdkLevel),
            )
        }

        val target =
            when (
                val result =
                    AdbTargetCatalog(adbExecutable, processRunner)
                        .refresh(serial, HostCancellationSignal())
            ) {
                is StudioResult.Failure -> return result
                is StudioResult.Success -> result.value
            }
        val app = target.packages.firstOrNull { candidate -> candidate.packageName == packageName }
        if (app != null && (app.debuggable || app.profileableByShell)) {
            return StudioResult.Success(
                MethodTraceDeviceSupport(supported = true, reason = null, sdkApiLevel = sdkLevel),
            )
        }
        return StudioResult.Success(
            MethodTraceDeviceSupport(
                supported = false,
                reason = "$packageName is not debuggable or profileable; use a debuggable build or a rooted device.",
                sdkApiLevel = sdkLevel,
            ),
        )
    }
}
