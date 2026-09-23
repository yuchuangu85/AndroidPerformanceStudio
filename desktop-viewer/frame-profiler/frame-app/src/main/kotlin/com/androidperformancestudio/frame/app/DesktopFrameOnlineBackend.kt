@file:Suppress("TooGenericExceptionCaught")

package com.androidperformancestudio.frame.app

import com.androidperformancestudio.adb.AdbTargetSnapshot
import com.androidperformancestudio.adb.AndroidDevicePropertyClient
import com.androidperformancestudio.adb.AndroidTargetListener
import com.androidperformancestudio.adb.AndroidTargetMonitor
import com.androidperformancestudio.adb.AndroidTargetMonitors
import com.androidperformancestudio.adb.AndroidTargetSubscription
import com.androidperformancestudio.adb.defaultAdbExecutable
import com.androidperformancestudio.adb.profileableOrDebuggableProcesses
import com.androidperformancestudio.frame.capture.FrameMetricsAgentCaptureSession
import com.androidperformancestudio.frame.capture.GfxInfoCaptureTarget
import com.androidperformancestudio.frame.capture.GfxInfoPollBatch
import com.androidperformancestudio.frame.capture.GfxInfoPollingCaptureSession
import com.androidperformancestudio.frame.model.FrameCaptureSession
import com.androidperformancestudio.frame.model.FrameSource
import com.androidperformancestudio.frame.model.FrameSourceCapabilities
import com.androidperformancestudio.frame.presentation.FrameDeviceOption
import com.androidperformancestudio.frame.presentation.FrameProcessOption
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import kotlinx.coroutines.CancellationException
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

    suspend fun stop(): List<String> = emptyList()
}

internal interface FrameOnlineBackend {
    fun registerTargetListener(listener: AndroidTargetListener): AndroidTargetSubscription

    suspend fun listDevices(): FrameBackendResult<List<FrameDeviceOption>>

    suspend fun listProcesses(serial: String): FrameBackendResult<List<FrameProcessOption>>

    suspend fun openCapture(
        serial: String,
        process: FrameProcessOption,
        sessionId: String,
    ): FrameBackendResult<OnlineFrameCapture>
}

internal class DesktopFrameOnlineBackend(
    private val adbLocator: () -> Path? = ::defaultAdbExecutable,
    private val targetMonitorProvider: (Path) -> AndroidTargetMonitor = AndroidTargetMonitors::shared,
) : FrameOnlineBackend {
    override fun registerTargetListener(listener: AndroidTargetListener): AndroidTargetSubscription {
        val monitor = adbLocator()?.let(targetMonitorProvider) ?: return AndroidTargetSubscription {}
        return monitor.register(listener)
    }

    override suspend fun listDevices(): FrameBackendResult<List<FrameDeviceOption>> {
        val adb = adbLocator() ?: return missingAdb()
        return when (val result = targetMonitorProvider(adb).refreshDevices()) {
            is StudioResult.Failure -> FrameBackendResult.Failure(result.error.message)
            is StudioResult.Success ->
                FrameBackendResult.Success(
                    result.value.map { device ->
                        FrameDeviceOption(
                            serial = device.serial,
                            name = device.displayName,
                            online = device.online,
                        )
                    },
                )
        }
    }

    override suspend fun listProcesses(serial: String): FrameBackendResult<List<FrameProcessOption>> {
        val adb = adbLocator() ?: return missingAdb()
        return when (val result = targetMonitorProvider(adb).refreshTargets(serial)) {
            is StudioResult.Failure -> FrameBackendResult.Failure(result.error.message)
            is StudioResult.Success -> FrameBackendResult.Success(result.value.frameCaptureProcesses())
        }
    }

    override suspend fun openCapture(
        serial: String,
        process: FrameProcessOption,
        sessionId: String,
    ): FrameBackendResult<OnlineFrameCapture> {
        val adb = adbLocator() ?: return missingAdb()
        val apiLevel = readApiLevel(adb, serial)
        val metadata =
            FrameCaptureSession(
                id = sessionId,
                source = FrameSource.GFXINFO,
                startedAt = Instant.now(),
                packageName = process.packageName,
                deviceSerial = serial,
                deviceApiLevel = apiLevel,
                sourceCapabilities = GFXINFO_CAPABILITIES,
                provenanceComplete = apiLevel != null,
                provenanceWarnings =
                    if (apiLevel == null) {
                        listOf("Unable to read the device Android API level.")
                    } else {
                        emptyList()
                    },
            )
        val target = GfxInfoCaptureTarget(serial, process.packageName, process.pid)
        val gfxInfoDelegate =
            GfxInfoPollingCaptureSession(
                adbExecutable = adb,
                target = target,
                sessionId = sessionId,
            )
        val agentDelegate =
            FrameMetricsAgentCaptureSession(
                adbExecutable = adb,
                target = target,
                sessionId = sessionId,
            )
        val gfxInfoCapture =
            object : OnlineFrameCapture {
                override val metadata: FrameCaptureSession = metadata

                override suspend fun start(): List<String> = gfxInfoDelegate.start()

                override suspend fun poll(): GfxInfoPollBatch = gfxInfoDelegate.poll()
            }
        val agentCapture =
            object : OnlineFrameCapture {
                override val metadata: FrameCaptureSession =
                    metadata.copy(
                        source = FrameSource.FRAME_METRICS,
                        agentProtocol = "1",
                        sourceCapabilities = FRAME_METRICS_CAPABILITIES,
                    )

                override suspend fun start(): List<String> = agentDelegate.start()

                override suspend fun poll(): GfxInfoPollBatch = agentDelegate.poll()

                override suspend fun stop(): List<String> = agentDelegate.stop()
            }
        return FrameBackendResult.Success(
            AgentPreferredOnlineFrameCapture(
                agent = agentCapture,
                fallback = gfxInfoCapture,
            ),
        )
    }

    private fun missingAdb(): FrameBackendResult.Failure =
        FrameBackendResult.Failure(
            "Android SDK Platform Tools were not found. Configure ANDROID_HOME or ANDROID_SDK_ROOT.",
        )

    private suspend fun readApiLevel(
        adb: Path,
        serial: String,
    ): Int? =
        (AndroidDevicePropertyClient(DefaultAdbClient(adb)).sdkInt(serial) as? StudioResult.Success)
            ?.value


    private companion object {
        val FRAME_METRICS_CAPABILITIES = FrameSourceCapabilities(true, true, true, true, true)
        val GFXINFO_CAPABILITIES = FrameSourceCapabilities(true, true, false, true, false)
    }
}

internal fun AdbTargetSnapshot.frameCaptureProcesses(): List<FrameProcessOption> =
    profileableOrDebuggableProcesses().map { process ->
        FrameProcessOption(process.pid, process.name, process.packageName)
    }

internal class AgentPreferredOnlineFrameCapture(
    private val agent: OnlineFrameCapture,
    private val fallback: OnlineFrameCapture,
) : OnlineFrameCapture {
    private var mode = CaptureMode.AGENT

    override val metadata: FrameCaptureSession
        get() = if (mode == CaptureMode.AGENT) agent.metadata else fallback.metadata

    override suspend fun start(): List<String> =
        try {
            val warnings = agent.start()
            mode = CaptureMode.AGENT
            warnings
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            mode = CaptureMode.GFXINFO
            listOf(
                "FrameMetrics Agent is unavailable; using gfxinfo polling: " +
                    (exception.message ?: exception::class.simpleName.orEmpty()),
            ) + fallback.start()
        }

    override suspend fun poll(): GfxInfoPollBatch =
        when (mode) {
            CaptureMode.AGENT -> agent.poll()
            CaptureMode.GFXINFO -> fallback.poll()
        }

    override suspend fun stop(): List<String> =
        when (mode) {
            CaptureMode.AGENT -> agent.stop()
            CaptureMode.GFXINFO -> fallback.stop()
        }

    private enum class CaptureMode {
        AGENT,
        GFXINFO,
    }
}
