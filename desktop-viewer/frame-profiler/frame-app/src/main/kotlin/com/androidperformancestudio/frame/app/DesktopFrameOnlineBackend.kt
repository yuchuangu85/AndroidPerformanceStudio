package com.androidperformancestudio.frame.app

import com.androidperformancestudio.adb.AdbDeviceRefresher
import com.androidperformancestudio.adb.AdbDeviceState
import com.androidperformancestudio.adb.AdbTargetCatalog
import com.androidperformancestudio.adb.SystemAdbLocator
import com.androidperformancestudio.frame.capture.GfxInfoCaptureTarget
import com.androidperformancestudio.frame.capture.GfxInfoPollBatch
import com.androidperformancestudio.frame.capture.GfxInfoPollingCaptureSession
import com.androidperformancestudio.frame.model.FrameCaptureSession
import com.androidperformancestudio.frame.model.FrameSource
import com.androidperformancestudio.frame.presentation.FrameDeviceOption
import com.androidperformancestudio.frame.presentation.FrameProcessOption
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.SystemHostPlatformDetector
import java.nio.file.Path
import java.time.Instant

internal sealed interface FrameBackendResult<out T> {
    data class Success<T>(
        val value: T,
    ) : FrameBackendResult<T>

    data class Failure(
        val message: String,
    ) : FrameBackendResult<Nothing>
}

internal interface OnlineFrameCapture {
    val metadata: FrameCaptureSession

    suspend fun start(): List<String>

    suspend fun poll(): GfxInfoPollBatch
}

internal interface FrameOnlineBackend {
    suspend fun listDevices(): FrameBackendResult<List<FrameDeviceOption>>

    suspend fun listProcesses(serial: String): FrameBackendResult<List<FrameProcessOption>>

    fun openCapture(
        serial: String,
        process: FrameProcessOption,
        sessionId: String,
    ): FrameBackendResult<OnlineFrameCapture>
}

internal class DesktopFrameOnlineBackend(
    private val adbLocator: () -> Path? = ::locateSystemAdb,
) : FrameOnlineBackend {
    override suspend fun listDevices(): FrameBackendResult<List<FrameDeviceOption>> {
        val adb = adbLocator() ?: return missingAdb()
        return when (val result = AdbDeviceRefresher(adb).refresh()) {
            is StudioResult.Failure -> FrameBackendResult.Failure(result.error.message)
            is StudioResult.Success ->
                FrameBackendResult.Success(
                    result.value.map { device ->
                        FrameDeviceOption(
                            serial = device.serial,
                            name = device.model?.replace('_', ' ') ?: device.serial,
                            online = device.state == AdbDeviceState.ONLINE,
                        )
                    },
                )
        }
    }

    override suspend fun listProcesses(serial: String): FrameBackendResult<List<FrameProcessOption>> {
        val adb = adbLocator() ?: return missingAdb()
        return when (val result = AdbTargetCatalog(adb).refresh(serial)) {
            is StudioResult.Failure -> FrameBackendResult.Failure(result.error.message)
            is StudioResult.Success -> {
                val debuggablePackages =
                    result.value.packages
                        .filter { it.debuggable }
                        .mapTo(hashSetOf()) { it.packageName }
                val processes =
                    result.value.processes
                        .mapNotNull { process ->
                            val packageName =
                                debuggablePackages.firstOrNull { candidate ->
                                    process.name == candidate || process.name.startsWith("$candidate:")
                                } ?: return@mapNotNull null
                            FrameProcessOption(process.pid, process.name, packageName)
                        }.sortedWith(compareBy<FrameProcessOption> { it.name }.thenBy { it.pid })
                FrameBackendResult.Success(processes)
            }
        }
    }

    override fun openCapture(
        serial: String,
        process: FrameProcessOption,
        sessionId: String,
    ): FrameBackendResult<OnlineFrameCapture> {
        val adb = adbLocator() ?: return missingAdb()
        val metadata =
            FrameCaptureSession(
                id = sessionId,
                source = FrameSource.GFXINFO,
                startedAt = Instant.now(),
                packageName = process.packageName,
                deviceSerial = serial,
            )
        val delegate =
            GfxInfoPollingCaptureSession(
                adbExecutable = adb,
                target = GfxInfoCaptureTarget(serial, process.packageName, process.pid),
                sessionId = sessionId,
            )
        return FrameBackendResult.Success(
            object : OnlineFrameCapture {
                override val metadata: FrameCaptureSession = metadata

                override suspend fun start(): List<String> = delegate.start()

                override suspend fun poll(): GfxInfoPollBatch = delegate.poll()
            },
        )
    }

    private fun missingAdb(): FrameBackendResult.Failure =
        FrameBackendResult.Failure(
            "Android SDK Platform Tools were not found. Configure ANDROID_HOME or ANDROID_SDK_ROOT.",
        )

    private companion object {
        fun locateSystemAdb(): Path? {
            val platform = (SystemHostPlatformDetector().detect() as? StudioResult.Success)?.value ?: return null
            return (SystemAdbLocator(platform).locate() as? StudioResult.Success)?.value?.executable
        }
    }
}
