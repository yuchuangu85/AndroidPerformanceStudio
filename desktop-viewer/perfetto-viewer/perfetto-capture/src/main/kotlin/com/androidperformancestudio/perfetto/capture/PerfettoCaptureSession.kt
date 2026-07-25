package com.androidperformancestudio.perfetto.capture

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.perfetto.model.CaptureMetadata
import com.androidperformancestudio.perfetto.model.PerfettoCaptureConfig
import com.androidperformancestudio.perfetto.model.PerfettoCaptureState
import com.androidperformancestudio.perfetto.model.PerfettoTraceTemplate
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
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
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val DEVICE_CONFIG_PATH = "/data/local/tmp/perfetto-config.pbtxt"
private const val DEVICE_TRACE_PATH = "/data/local/tmp/aps-perfetto-trace.pftrace"
private const val MILLISECONDS_PER_SECOND = 1_000L
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
    private val processRunner: JvmProcessRunner = JvmProcessRunner(),
    private val sessionDir: Path = Files.createTempDirectory("perfetto-capture"),
) {
    private val _state = MutableStateFlow<PerfettoCaptureState>(PerfettoCaptureState.Idle)
    val state: StateFlow<PerfettoCaptureState> = _state.asStateFlow()

    private val stateMutex = Mutex()
    private val completionMutex = Mutex()
    private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var completionJob: Job? = null
    private var activeConfig: PerfettoCaptureConfig? = null
    private var activeAdbPath: String? = null
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

            val pushResult = pushConfig(adbPath, deviceSerial, configText)
            if (pushResult is StudioResult.Failure) {
                cleanupDeviceFiles(adbPath, deviceSerial)
                setFailed(pushResult.error)
                return@withContext pushResult
            }

            val startResult = startDeviceCapture(adbPath, deviceSerial)
            when (startResult) {
                is StudioResult.Success -> {
                    val (startTime, pid) = startResult.value
                    val deviceInfo = readDeviceInfo(adbPath, deviceSerial)
                    activeAdbPath = adbPath
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
                    cleanupDeviceFiles(adbPath, deviceSerial)
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
        adbPath: String,
        deviceSerial: String,
        configText: String,
    ): StudioResult<Unit> {
        val tempFile = Files.createTempFile("perfetto-config", ".pbtxt")
        Files.writeString(tempFile, configText)
        try {
            val request =
                ProcessRequest(
                    executable = Path.of(adbPath),
                    arguments = listOf("-s", deviceSerial, "push", tempFile.toString(), DEVICE_CONFIG_PATH),
                )
            return when (val result = processRunner.run(request)) {
                is ProcessRunResult.Completed -> StudioResult.Success(Unit)
                is ProcessRunResult.Failed -> StudioResult.Failure(result.error)
            }
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    private suspend fun startDeviceCapture(
        adbPath: String,
        deviceSerial: String,
    ): StudioResult<Pair<Instant, Long>> {
        val shellCommand = "cat $DEVICE_CONFIG_PATH | perfetto --txt -c - -o $DEVICE_TRACE_PATH --background-wait"

        val request =
            ProcessRequest(
                executable = Path.of(adbPath),
                arguments = listOf("-s", deviceSerial, "shell", shellCommand),
                timeout = 35.seconds,
            )
        val startedAt = Instant.now()
        return when (val result = processRunner.run(request)) {
            is ProcessRunResult.Failed -> StudioResult.Failure(result.error)
            is ProcessRunResult.Completed -> {
                val output = result.output.stdout.text + "\n" + result.output.stderr.text
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

            val adb = activeAdbPath.orEmpty()
            val serial = activeDeviceSerial.orEmpty()
            val pid = activePerfettoPid
            if (requestRemoteStop && pid != null) stopRemoteCapture(adb, serial, pid)
            if (pid != null && !waitForRemoteStop(adb, serial, pid)) {
                stopRemoteCapture(adb, serial, pid)
                if (!waitForRemoteStop(adb, serial, pid)) {
                    forceStopRemoteCapture(adb, serial, pid)
                    cleanupDeviceFiles(adb, serial)
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
                    pullTrace(adb, serial, traceFile)
                } finally {
                    cleanupDeviceFiles(adb, serial)
                }
            if (pullResult is StudioResult.Failure) {
                setFailed(pullResult.error)
                return@withLock pullResult
            }
            val capturedAt = recordingStartedAt ?: Instant.now()
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
                        ),
                )
            _state.value = completed
            clearActiveCapture()
            StudioResult.Success(completed)
        }

    private suspend fun stopRemoteCapture(
        adbPath: String,
        deviceSerial: String,
        pid: Long,
    ) {
        processRunner.run(
            ProcessRequest(
                executable = Path.of(adbPath),
                arguments = listOf("-s", deviceSerial, "shell", "kill", "-TERM", pid.toString()),
                timeout = 5.seconds,
            ),
        )
    }

    private suspend fun forceStopRemoteCapture(
        adbPath: String,
        deviceSerial: String,
        pid: Long,
    ) {
        processRunner.run(
            ProcessRequest(
                executable = Path.of(adbPath),
                arguments = listOf("-s", deviceSerial, "shell", "kill", "-KILL", pid.toString()),
                timeout = 5.seconds,
            ),
        )
    }

    private suspend fun waitForRemoteStop(
        adbPath: String,
        deviceSerial: String,
        pid: Long,
    ): Boolean {
        repeat(20) {
            val result =
                processRunner.run(
                    ProcessRequest(
                        executable = Path.of(adbPath),
                        arguments = listOf("-s", deviceSerial, "shell", "kill", "-0", pid.toString()),
                        timeout = 2.seconds,
                    ),
                )
            if (result is ProcessRunResult.Failed) return true
            delay(250)
        }
        return false
    }

    private suspend fun readDeviceInfo(
        adbPath: String,
        deviceSerial: String,
    ): Pair<String, Int> {
        val request =
            ProcessRequest(
                executable = Path.of(adbPath),
                arguments =
                    listOf(
                        "-s",
                        deviceSerial,
                        "shell",
                        "getprop ro.product.model; getprop ro.build.version.sdk",
                    ),
                timeout = 5.seconds,
            )
        val result = processRunner.run(request) as? ProcessRunResult.Completed ?: return Pair("unknown", 0)
        val lines =
            result.output.stdout.text
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toList()
        return Pair(lines.getOrNull(0) ?: "unknown", lines.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private suspend fun pullTrace(
        adbPath: String,
        deviceSerial: String,
        traceFile: Path,
    ): StudioResult<Unit> {
        if (adbPath.isBlank() || deviceSerial.isBlank()) {
            return StudioResult.Failure(
                StudioError(
                    category = ErrorCategory.CONFIGURATION,
                    code = "CAPTURE_TARGET_MISSING",
                    message = "Select an online Android device before starting a capture",
                ),
            )
        }
        val request =
            ProcessRequest(
                executable = Path.of(adbPath),
                arguments = listOf("-s", deviceSerial, "pull", DEVICE_TRACE_PATH, traceFile.toString()),
            )
        return when (val result = processRunner.run(request)) {
            is ProcessRunResult.Completed -> StudioResult.Success(Unit)
            is ProcessRunResult.Failed -> StudioResult.Failure(result.error)
        }
    }

    private suspend fun cleanupDeviceFiles(
        adbPath: String,
        deviceSerial: String,
    ) {
        if (adbPath.isBlank() || deviceSerial.isBlank()) return
        processRunner.run(
            ProcessRequest(
                executable = Path.of(adbPath),
                arguments = listOf("-s", deviceSerial, "shell", "rm", "-f", DEVICE_TRACE_PATH, DEVICE_CONFIG_PATH),
            ),
        )
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

    private fun clearActiveCapture() {
        activeConfig = null
        completionJob = null
        activeAdbPath = null
        activeDeviceSerial = null
        activePerfettoPid = null
        activeDeviceModel = "unknown"
        activeAndroidSdk = 0
        recordingStartedAt = null
    }
}
