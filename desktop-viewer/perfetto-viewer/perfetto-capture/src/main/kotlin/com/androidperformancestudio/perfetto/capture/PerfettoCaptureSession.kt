package com.androidperformancestudio.perfetto.capture

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.perfetto.model.CaptureMetadata
import com.androidperformancestudio.perfetto.model.PerfettoArtifactFactory
import com.androidperformancestudio.perfetto.model.PerfettoCaptureConfig
import com.androidperformancestudio.perfetto.model.PerfettoCaptureState
import com.androidperformancestudio.perfetto.model.PerfettoTraceTemplate
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbCommandCancelledException
import com.androidperformancestudio.platform.adb.AdbCommandFailedException
import com.androidperformancestudio.platform.adb.AdbCommandTimeoutException
import com.androidperformancestudio.platform.adb.AdbException
import com.androidperformancestudio.platform.adb.AdbProcessStartException
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val DEVICE_CONFIG_PATH = "/data/local/tmp/perfetto-config.pbtxt"
private const val DEVICE_TRACE_PATH = "/data/misc/perfetto-traces/aps-perfetto-trace.pftrace"
private const val MILLISECONDS_PER_SECOND = 1_000L
private const val REMOTE_STOP_POLL_ATTEMPTS = 20
private const val REMOTE_STOP_POLL_DELAY_MILLIS = 250L
private val CUSTOM_DURATION_PATTERN = Regex("(?m)^\\s*duration_ms\\s*:\\s*(\\d+)\\s*$")

internal fun automaticCompletionDelayMillis(config: PerfettoCaptureConfig): Long? =
    if (config.template == PerfettoTraceTemplate.CUSTOM) {
        config.customConfigText
            ?.let(CUSTOM_DURATION_PATTERN::find)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    } else {
        config.durationSeconds * MILLISECONDS_PER_SECOND
    }

class PerfettoCaptureSession(
    private val adbClientFactory: (Path) -> AdbClient = ::DefaultAdbClient,
    private val sessionDir: Path = Files.createTempDirectory("perfetto-capture"),
    private val artifactFactory: PerfettoArtifactFactory = PerfettoArtifactFactory(),
) {
    private val _state = MutableStateFlow<PerfettoCaptureState>(PerfettoCaptureState.Idle)
    val state: StateFlow<PerfettoCaptureState> = _state.asStateFlow()

    private val stateMutex = Mutex()
    private val completionMutex = Mutex()
    private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var completionJob: Job? = null
    private var activeConfig: PerfettoCaptureConfig? = null
    private var activeAdbClient: AdbClient? = null
    private var activeDeviceSerial: String? = null
    private var activePerfettoPid: Long? = null
    private var activeDeviceModel: String = "unknown"
    private var activeAndroidSdk: Int = 0
    private var recordingStartedAt: Instant? = null

    suspend fun startCapture(
        adbPath: String,
        deviceSerial: String,
        config: PerfettoCaptureConfig,
    ): StudioResult<Unit> =
        withContext(Dispatchers.IO) {
            if (adbPath.isBlank() || deviceSerial.isBlank()) {
                return@withContext StudioResult.Failure(
                    StudioError(
                        category = ErrorCategory.CONFIGURATION,
                        code = "CAPTURE_TARGET_MISSING",
                        message = "Select an online Android device before starting a capture",
                    ),
                )
            }
            stateMutex.withLock {
                if (_state.value is PerfettoCaptureState.Preparing ||
                    _state.value is PerfettoCaptureState.Recording ||
                    _state.value is PerfettoCaptureState.Pulling
                ) {
                    return@withContext StudioResult.Failure(
                        StudioError(
                            category = ErrorCategory.CONFIGURATION,
                            code = "CAPTURE_ALREADY_ACTIVE",
                            message = "A capture is already in progress",
                        ),
                    )
                }
                activeConfig = config
                _state.value = PerfettoCaptureState.Preparing(config)
            }

            val configText = PerfettoConfigTextBuilder.build(config)
            val adbClient =
                try {
                    adbClientFactory(Path.of(adbPath))
                } catch (error: IllegalArgumentException) {
                    val failure = captureFailure<Unit>("ADB_PATH_INVALID", error.message ?: "ADB path is invalid")
                    setFailed((failure as StudioResult.Failure).error)
                    return@withContext failure
                }

            val pushResult = pushConfig(adbClient, deviceSerial, configText)
            if (pushResult is StudioResult.Failure) {
                cleanupDeviceFiles(adbClient, deviceSerial)
                setFailed(pushResult.error)
                return@withContext pushResult
            }

            val startResult = startDeviceCapture(adbClient, deviceSerial)
            when (startResult) {
                is StudioResult.Success -> {
                    val (startTime, pid) = startResult.value
                    val deviceInfo = readDeviceInfo(adbClient, deviceSerial)
                    activeAdbClient = adbClient
                    activeDeviceSerial = deviceSerial
                    activePerfettoPid = pid
                    activeDeviceModel = deviceInfo.first
                    activeAndroidSdk = deviceInfo.second
                    recordingStartedAt = startTime
                    _state.value = PerfettoCaptureState.Recording(startTime, pid)
                    scheduleAutomaticCompletion(config)
                    return@withContext StudioResult.Success(Unit)
                }
                is StudioResult.Failure -> {
                    cleanupDeviceFiles(adbClient, deviceSerial)
                    setFailed(startResult.error)
                    return@withContext startResult
                }
            }
        }

    suspend fun stopCapture(): StudioResult<PerfettoCaptureState.Completed> =
        withContext(Dispatchers.IO) {
            completionJob?.cancel()
            completeCapture(requestRemoteStop = true)
        }

    fun cancelCapture() {
        captureScope.launch { stopCapture() }
    }

    private suspend fun pushConfig(
        adbClient: AdbClient,
        deviceSerial: String,
        configText: String,
    ): StudioResult<Unit> {
        val tempFile = Files.createTempFile("perfetto-config", ".pbtxt")
        Files.writeString(tempFile, configText)
        try {
            return adbCall { adbClient.push(deviceSerial, tempFile, DEVICE_CONFIG_PATH) }
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    private suspend fun startDeviceCapture(
        adbClient: AdbClient,
        deviceSerial: String,
    ): StudioResult<Pair<Instant, Long>> {
        val shellCommand = "cat $DEVICE_CONFIG_PATH | perfetto --txt -c - -o $DEVICE_TRACE_PATH --background-wait"

        val startedAt = Instant.now()
        return when (val result = adbCall { adbClient.shell(deviceSerial, listOf("sh", "-c", shellCommand), 35.seconds) }) {
            is StudioResult.Failure -> result
            is StudioResult.Success -> {
                val output = result.value.stdout + "\n" + result.value.stderr
                val pid =
                    Regex("(?m)^\\s*(\\d+)\\s*$")
                        .find(output)
                        ?.groupValues
                        ?.get(1)
                        ?.toLongOrNull()
                if (pid == null || pid <= 0) {
                    StudioResult.Failure(
                        StudioError(
                            category = ErrorCategory.PROCESS_EXIT,
                            code = "PERFETTO_PID_MISSING",
                            message = "Perfetto started without returning a background process id",
                        ),
                    )
                } else {
                    StudioResult.Success(Pair(startedAt, pid))
                }
            }
        }
    }

    private fun scheduleAutomaticCompletion(config: PerfettoCaptureConfig) {
        completionJob?.cancel()
        val delayMillis = automaticCompletionDelayMillis(config) ?: return
        completionJob =
            captureScope.launch {
                delay(delayMillis.milliseconds)
                completeCapture(requestRemoteStop = false)
            }
    }

    private suspend fun completeCapture(requestRemoteStop: Boolean): StudioResult<PerfettoCaptureState.Completed> =
        completionMutex.withLock {
            val currentState = _state.value
            val config =
                activeConfig ?: return@withLock captureFailure(
                    code = "NO_ACTIVE_CONFIG",
                    message = "No active capture config",
                )
            if (currentState !is PerfettoCaptureState.Recording) {
                return@withLock captureFailure(code = "NOT_RECORDING", message = "Capture is not recording")
            }

            val adbClient = activeAdbClient ?: return@withLock captureFailure("ADB_CLIENT_MISSING", "Capture ADB client is unavailable")
            val serial = activeDeviceSerial.orEmpty()
            val pid = activePerfettoPid
            if (requestRemoteStop && pid != null) stopRemoteCapture(adbClient, serial, pid)
            if (pid != null && !waitForRemoteStop(adbClient, serial, pid)) {
                stopRemoteCapture(adbClient, serial, pid)
                if (!waitForRemoteStop(adbClient, serial, pid)) {
                    forceStopRemoteCapture(adbClient, serial, pid)
                    cleanupDeviceFiles(adbClient, serial)
                    val error =
                        StudioError(
                            category = ErrorCategory.PROCESS_TIMEOUT,
                            code = "PERFETTO_STOP_TIMEOUT",
                            message = "Timed out waiting for device Perfetto process $pid to stop",
                        )
                    setFailed(error)
                    return@withLock StudioResult.Failure(error)
                }
            }

            _state.value = PerfettoCaptureState.Pulling(0, null)
            val traceFile = sessionDir.resolve("trace-${System.currentTimeMillis()}.pftrace")
            val pullResult =
                try {
                    pullTrace(adbClient, serial, traceFile)
                } finally {
                    cleanupDeviceFiles(adbClient, serial)
                }
            if (pullResult is StudioResult.Failure) {
                setFailed(pullResult.error)
                return@withLock pullResult
            }
            val capturedAt = recordingStartedAt ?: Instant.now()
            val artifact =
                try {
                    artifactFactory.captured(
                        id = UUID.randomUUID().toString(),
                        traceFile = traceFile,
                        deviceSerial = serial,
                        deviceModel = activeDeviceModel,
                        capturedAt = capturedAt,
                    )
                } catch (error: IOException) {
                    return@withLock failArtifactRegistration(error)
                } catch (error: IllegalArgumentException) {
                    return@withLock failArtifactRegistration(error)
                }
            val completed =
                PerfettoCaptureState.Completed(
                    traceFile = traceFile,
                    metadata =
                        CaptureMetadata(
                            deviceSerial = serial,
                            deviceModel = activeDeviceModel,
                            androidSdk = activeAndroidSdk,
                            capturedAt = capturedAt,
                            durationNanos =
                                (Instant.now().toEpochMilli() - capturedAt.toEpochMilli()).coerceAtLeast(0) * 1_000_000,
                            traceFileSizeBytes = Files.size(traceFile),
                            config = config,
                            command = "perfetto --txt -c - -o $DEVICE_TRACE_PATH --background-wait",
                            artifact = artifact,
                        ),
                )
            _state.value = completed
            clearActiveCapture()
            StudioResult.Success(completed)
        }

    private suspend fun stopRemoteCapture(
        adbClient: AdbClient,
        deviceSerial: String,
        pid: Long,
    ) {
        adbCall { adbClient.shell(deviceSerial, listOf("kill", "-TERM", pid.toString()), 5.seconds) }
    }

    private suspend fun forceStopRemoteCapture(
        adbClient: AdbClient,
        deviceSerial: String,
        pid: Long,
    ) {
        adbCall { adbClient.shell(deviceSerial, listOf("kill", "-KILL", pid.toString()), 5.seconds) }
    }

    private suspend fun waitForRemoteStop(
        adbClient: AdbClient,
        deviceSerial: String,
        pid: Long,
    ): Boolean {
        repeat(REMOTE_STOP_POLL_ATTEMPTS) {
            val result = adbCall { adbClient.shell(deviceSerial, listOf("kill", "-0", pid.toString()), 2.seconds) }
            if (result is StudioResult.Failure && result.error.code == "ADB_COMMAND_FAILED") return true
            delay(REMOTE_STOP_POLL_DELAY_MILLIS)
        }
        return false
    }

    private suspend fun readDeviceInfo(
        adbClient: AdbClient,
        deviceSerial: String,
    ): Pair<String, Int> {
        val result =
            adbCall {
                adbClient.shell(
                    deviceSerial,
                    listOf("sh", "-c", "getprop ro.product.model; getprop ro.build.version.sdk"),
                    5.seconds,
                )
            } as? StudioResult.Success ?: return Pair("unknown", 0)
        val lines =
            result.value.stdout
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toList()
        return Pair(lines.getOrNull(0) ?: "unknown", lines.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private suspend fun pullTrace(
        adbClient: AdbClient,
        deviceSerial: String,
        traceFile: Path,
    ): StudioResult<Unit> {
        if (deviceSerial.isBlank()) {
            return StudioResult.Failure(
                StudioError(
                    category = ErrorCategory.CONFIGURATION,
                    code = "CAPTURE_TARGET_MISSING",
                    message = "Select an online Android device before starting a capture",
                ),
            )
        }
        return adbCall { adbClient.pull(deviceSerial, DEVICE_TRACE_PATH, traceFile) }
    }

    private suspend fun cleanupDeviceFiles(
        adbClient: AdbClient,
        deviceSerial: String,
    ) {
        if (deviceSerial.isBlank()) return
        adbCall { adbClient.shell(deviceSerial, listOf("rm", "-f", DEVICE_TRACE_PATH, DEVICE_CONFIG_PATH)) }
    }

    private fun <T> captureFailure(
        code: String,
        message: String,
    ): StudioResult<T> =
        StudioResult.Failure(
            StudioError(
                category = ErrorCategory.CONFIGURATION,
                code = code,
                message = message,
            ),
        )

    private fun setFailed(error: StudioError) {
        _state.value = PerfettoCaptureState.Failed(error)
        clearActiveCapture()
    }

    private fun failArtifactRegistration(error: Throwable): StudioResult<PerfettoCaptureState.Completed> {
        val failure =
            StudioError(
                category = ErrorCategory.DATA_VALIDATION,
                code = "PERFETTO_ARTIFACT_INVALID",
                message = error.message ?: "Captured Perfetto evidence could not be registered",
                cause = error,
            )
        setFailed(failure)
        return StudioResult.Failure(failure)
    }

    private fun clearActiveCapture() {
        activeConfig = null
        completionJob = null
        activeAdbClient = null
        activeDeviceSerial = null
        activePerfettoPid = null
        activeDeviceModel = "unknown"
        activeAndroidSdk = 0
        recordingStartedAt = null
    }
}

private suspend fun <T> adbCall(block: suspend () -> T): StudioResult<T> =
    try {
        StudioResult.Success(block())
    } catch (error: AdbCommandFailedException) {
        adbFailure(ErrorCategory.PROCESS_EXIT, "ADB_COMMAND_FAILED", error)
    } catch (error: AdbCommandTimeoutException) {
        adbFailure(ErrorCategory.PROCESS_TIMEOUT, "ADB_COMMAND_TIMEOUT", error)
    } catch (error: AdbProcessStartException) {
        adbFailure(ErrorCategory.PROCESS_START, "ADB_PROCESS_START_FAILED", error)
    } catch (error: AdbCommandCancelledException) {
        adbFailure(ErrorCategory.PROCESS_CANCELLED, "ADB_COMMAND_CANCELLED", error)
    } catch (error: AdbException) {
        adbFailure(ErrorCategory.UNKNOWN, "ADB_COMMAND_FAILED", error)
    }

private fun <T> adbFailure(
    category: ErrorCategory,
    code: String,
    error: Throwable,
): StudioResult<T> = StudioResult.Failure(StudioError(category, code, error.message.orEmpty(), error))
