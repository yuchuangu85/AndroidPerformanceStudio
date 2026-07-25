package com.androidperformancestudio.perfetto.capture

import com.androidperformancestudio.perfetto.model.CaptureMetadata
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.perfetto.model.PerfettoCaptureConfig
import com.androidperformancestudio.perfetto.model.PerfettoCaptureState
import com.androidperformancestudio.perfetto.model.PerfettoTraceTemplate
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessCancellationSignal
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

private const val DEVICE_CONFIG_PATH = "/data/local/tmp/perfetto-config.pbtxt"
private const val DEVICE_TRACE_PATH = "/data/local/tmp/aps-perfetto-trace.pftrace"

class PerfettoCaptureSession(
    private val processRunner: JvmProcessRunner = JvmProcessRunner(),
    private val sessionDir: Path = Files.createTempDirectory("perfetto-capture"),
) {
    private val _state = MutableStateFlow<PerfettoCaptureState>(PerfettoCaptureState.Idle)
    val state: StateFlow<PerfettoCaptureState> = _state.asStateFlow()

    private val stateMutex = Mutex()
    private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cancellationSignal: ProcessCancellationSignal? = null
    private var recordingJob: Deferred<ProcessRunResult>? = null
    private var activeConfig: PerfettoCaptureConfig? = null
    private var activeAdbPath: String? = null
    private var activeDeviceSerial: String? = null
    private var recordingStartedAt: Instant? = null

    suspend fun startCapture(
        adbPath: String,
        deviceSerial: String,
        config: PerfettoCaptureConfig,
    ): StudioResult<Unit> = withContext(Dispatchers.IO) {
        stateMutex.withLock {
            if (_state.value !is PerfettoCaptureState.Idle) {
                return@withContext StudioResult.Failure(
                    StudioError(
                        category = ErrorCategory.CONFIGURATION,
                        code = "CAPTURE_ALREADY_ACTIVE",
                        message = "A capture is already in progress",
                    )
                )
            }
            activeConfig = config
            _state.value = PerfettoCaptureState.Preparing(config)
        }

        val configText = generateConfigText(config)

        val localConfigPath = sessionDir.resolve("perfetto-config.pbtxt")
        Files.writeString(localConfigPath, configText)

        val pushResult = pushConfig(adbPath, deviceSerial, configText)
        if (pushResult is StudioResult.Failure) {
            setFailed(pushResult.error)
            return@withContext pushResult
        }

        val startResult = startDeviceCapture(adbPath, deviceSerial, config)
        when (startResult) {
            is StudioResult.Success -> {
                val (startTime, pid) = startResult.value
                activeAdbPath = adbPath
                activeDeviceSerial = deviceSerial
                recordingStartedAt = startTime
                _state.value = PerfettoCaptureState.Recording(startTime, pid)
                return@withContext StudioResult.Success(Unit)
            }
            is StudioResult.Failure -> {
                setFailed(startResult.error)
                return@withContext startResult
            }
        }
    }

    suspend fun stopCapture(): StudioResult<PerfettoCaptureState.Completed> =
        withContext(Dispatchers.IO) {
            val currentState = _state.value
            val config = activeConfig ?: return@withContext StudioResult.Failure(
                StudioError(
                    category = ErrorCategory.CONFIGURATION,
                    code = "NO_ACTIVE_CONFIG",
                    message = "No active capture config",
                )
            )

            if (currentState !is PerfettoCaptureState.Recording) {
                return@withContext StudioResult.Failure(
                    StudioError(
                        category = ErrorCategory.CONFIGURATION,
                        code = "NOT_RECORDING",
                        message = "Capture is not recording",
                    )
                )
            }

            cancellationSignal?.cancel()
            val recordingResult = recordingJob?.await()
            if (recordingResult is ProcessRunResult.Failed && recordingResult.error.code != "PROCESS_CANCELLED") {
                setFailed(recordingResult.error)
                return@withContext StudioResult.Failure(recordingResult.error)
            }
            _state.value = PerfettoCaptureState.Pulling(0, null)

            val traceFile = sessionDir.resolve("trace.pftrace")
            val adb = activeAdbPath.orEmpty()
            val serial = activeDeviceSerial.orEmpty()
            val pullResult = pullTrace(adb, serial, traceFile)
            if (pullResult is StudioResult.Failure) {
                setFailed(pullResult.error)
                return@withContext pullResult
            }
            cleanupDeviceTrace(adb, serial)
            val capturedAt = recordingStartedAt ?: Instant.now()
            val metadata = CaptureMetadata(
                deviceSerial = serial,
                deviceModel = "unknown",
                androidSdk = 0,
                capturedAt = capturedAt,
                durationNanos = (Instant.now().toEpochMilli() - capturedAt.toEpochMilli()).coerceAtLeast(0) * 1_000_000,
                traceFileSizeBytes = Files.size(traceFile),
                config = config,
                command = "perfetto -c - --txt",
            )

            val completed = PerfettoCaptureState.Completed(traceFile, metadata)
            _state.value = completed
            recordingJob = null
            cancellationSignal = null
            activeConfig = null
            StudioResult.Success(completed)
        }

    fun cancelCapture() {
        cancellationSignal?.cancel()
    }

    private suspend fun pushConfig(
        adbPath: String,
        deviceSerial: String,
        configText: String,
    ): StudioResult<Unit> {
        val tempFile = Files.createTempFile("perfetto-config", ".pbtxt")
        Files.writeString(tempFile, configText)
        try {
            val request = ProcessRequest(
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
        config: PerfettoCaptureConfig,
    ): StudioResult<Pair<Instant, Long>> {
        val shellCommand = buildString {
            append("cat $DEVICE_CONFIG_PATH | perfetto -c - --txt -o $DEVICE_TRACE_PATH")
            append(" -t ${config.durationSeconds}s")
            append(" -b ${config.bufferSizeKb}kb")
        }

        val request = ProcessRequest(
            executable = Path.of(adbPath),
            arguments = listOf("-s", deviceSerial, "shell", shellCommand),
            timeout = (config.durationSeconds + 30).seconds,
        )
        val startedAt = Instant.now()
        val signal = ProcessCancellationSignal()
        cancellationSignal = signal
        val job = captureScope.async { processRunner.run(request, signal) }
        recordingJob = job
        captureScope.async {
            val result = job.await()
            if (result is ProcessRunResult.Failed && _state.value is PerfettoCaptureState.Recording) {
                setFailed(result.error)
            }
        }
        return StudioResult.Success(Pair(startedAt, 0L))
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
        val request = ProcessRequest(
            executable = Path.of(adbPath),
            arguments = listOf("-s", deviceSerial, "pull", DEVICE_TRACE_PATH, traceFile.toString()),
        )
        return when (val result = processRunner.run(request)) {
            is ProcessRunResult.Completed -> StudioResult.Success(Unit)
            is ProcessRunResult.Failed -> StudioResult.Failure(result.error)
        }
    }

    private suspend fun cleanupDeviceTrace(adbPath: String, deviceSerial: String) {
        if (adbPath.isBlank() || deviceSerial.isBlank()) return
        processRunner.run(
            ProcessRequest(
                executable = Path.of(adbPath),
                arguments = listOf("-s", deviceSerial, "shell", "rm", "-f", DEVICE_TRACE_PATH),
            ),
        )
    }

    private fun generateConfigText(config: PerfettoCaptureConfig): String = buildString {
        appendLine("# Perfetto trace config generated by AndroidPerformanceStudio")
        appendLine("# Template: ${config.template.displayName}")
        appendLine("# Target: ${config.targetPackage ?: "system-wide"}")
        appendLine()
        appendLine("buffers: {")
        appendLine("  size_kb: ${config.bufferSizeKb}")
        appendLine("  fill_policy: DISCARD")
        appendLine("}")
        appendLine()
        appendLine("duration_ms: ${config.durationSeconds * 1000}")
        appendLine()
        appendLine("data_sources: {")
        appendLine("  config {")
        appendLine("    name: \"linux.ftrace\"")
        val categories = templateCategories(config.template)
        appendLine("    ftrace_config {")
        categories.forEach { cat ->
            appendLine("      ftrace_events: \"$cat\"")
        }
        appendLine("    }")
        appendLine("  }")
        appendLine("}")
        if (config.targetPackage != null) {
            appendLine()
            appendLine("data_sources: {")
            appendLine("  config {")
            appendLine("    name: \"android.atrace\"")
            appendLine("    android_atrace_config {")
            appendLine("      atrace_categories: \"sched\"")
            appendLine("      atrace_categories: \"gfx\"")
            appendLine("      atrace_categories: \"view\"")
            appendLine("      atrace_apps: \"${config.targetPackage}\"")
            appendLine("    }")
            appendLine("  }")
            appendLine("}")
        }
    }

    private fun templateCategories(template: PerfettoTraceTemplate): List<String> =
        when (template) {
            PerfettoTraceTemplate.SYSTEM_OVERVIEW ->
                listOf(
                    "sched/sched_switch", "sched/sched_waking",
                    "sched/sched_wakeup", "sched/sched_blocked_reason",
                    "power/cpu_frequency", "power/cpu_idle",
                    "binder/binder_transaction", "binder/binder_transaction_received",
                )
            PerfettoTraceTemplate.APP_PERFORMANCE ->
                listOf("sched/sched_switch", "sched/sched_waking",
                    "binder/binder_transaction", "binder/binder_transaction_received")
            PerfettoTraceTemplate.GFX_PIPELINE ->
                listOf("sched/sched_switch", "power/cpu_frequency")
            PerfettoTraceTemplate.INPUT_LATENCY ->
                listOf("sched/sched_switch", "sched/sched_waking",
                    "binder/binder_transaction")
            PerfettoTraceTemplate.MEMORY_PROFILE ->
                listOf("sched/sched_switch")
            PerfettoTraceTemplate.CUSTOM -> emptyList()
        }

    private fun setFailed(error: StudioError) {
        _state.value = PerfettoCaptureState.Failed(error)
        activeConfig = null
    }
}
