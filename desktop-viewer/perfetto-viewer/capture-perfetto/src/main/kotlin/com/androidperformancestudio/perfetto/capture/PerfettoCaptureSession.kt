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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private const val DEVICE_CONFIG_PATH = "/data/local/tmp/perfetto-config.pbtxt"
private const val DEVICE_TRACE_PATH = "/data/misc/perfetto-traces/trace.pftrace"

class PerfettoCaptureSession(
    private val processRunner: JvmProcessRunner = JvmProcessRunner(),
    private val sessionDir: Path = Files.createTempDirectory("perfetto-capture"),
) {
    private val _state = MutableStateFlow<PerfettoCaptureState>(PerfettoCaptureState.Idle)
    val state: StateFlow<PerfettoCaptureState> = _state.asStateFlow()

    private val stateMutex = Mutex()
    private val cancellationSignal = ProcessCancellationSignal()
    private var activeConfig: PerfettoCaptureConfig? = null

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

            cancellationSignal.cancel()
            delay(2000)

            _state.value = PerfettoCaptureState.Pulling(0, null)

            val traceFile = sessionDir.resolve("trace.pftrace")
            val metadata = CaptureMetadata(
                deviceSerial = "",
                deviceModel = "unknown",
                androidSdk = 0,
                capturedAt = Instant.now(),
                durationNanos = 0,
                traceFileSizeBytes = Files.size(traceFile),
                config = config,
                command = "perfetto -c - --txt",
            )

            val completed = PerfettoCaptureState.Completed(traceFile, metadata)
            _state.value = completed
            StudioResult.Success(completed)
        }

    fun cancelCapture() {
        cancellationSignal.cancel()
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
        )

        return when (val result = processRunner.run(request, cancellationSignal)) {
            is ProcessRunResult.Completed -> {
                StudioResult.Success(Pair(Instant.now(), result.output.pid))
            }
            is ProcessRunResult.Failed -> StudioResult.Failure(result.error)
        }
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
    }
}
