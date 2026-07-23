package com.androidperformancestudio.perfetto.model
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult


import java.nio.file.Path
import java.time.Instant

/**
 * Preset trace configuration templates, mirroring Simpleperf's [SamplingTemplate].
 */
enum class PerfettoTraceTemplate(
    val displayName: String,
    val description: String,
) {
    SYSTEM_OVERVIEW("System Overview", "sched, freq, binder, memory, gfx — full system trace"),
    APP_PERFORMANCE("App Performance", "atrace for target app + sched + binder"),
    GFX_PIPELINE("Graphics Pipeline", "SurfaceFlinger, HWUI, frame timeline, vsync"),
    INPUT_LATENCY("Input Latency", "input dispatcher + app response + binder"),
    MEMORY_PROFILE("Memory Profile", "meminfo, heapprofd, Java heap sampling"),
    CUSTOM("Custom", "User-defined trace config"),
}

data class PerfettoCaptureConfig(
    val template: PerfettoTraceTemplate,
    val targetPackage: String? = null,
    val durationSeconds: Int = 10,
    val bufferSizeKb: Int = 32768,
    val additionalCategories: List<String> = emptyList(),
    val customConfigText: String? = null,
) {
    init {
        require(durationSeconds in 1..600) { "durationSeconds must be in [1, 600], was $durationSeconds" }
        require(bufferSizeKb in 1024..1048576) { "bufferSizeKb must be in [1024, 1048576], was $bufferSizeKb" }
    }
}

sealed interface PerfettoCaptureState {
    data object Idle : PerfettoCaptureState
    data class Preparing(val config: PerfettoCaptureConfig) : PerfettoCaptureState
    data class Recording(val startTime: Instant, val pid: Long) : PerfettoCaptureState
    data class Pulling(val bytesTransferred: Long, val totalBytes: Long?) : PerfettoCaptureState
    data class Completed(val traceFile: Path, val metadata: CaptureMetadata) : PerfettoCaptureState
    data class Failed(val error: StudioError) : PerfettoCaptureState
}

data class CaptureMetadata(
    val deviceSerial: String,
    val deviceModel: String,
    val androidSdk: Int,
    val capturedAt: Instant,
    val durationNanos: Long,
    val traceFileSizeBytes: Long,
    val config: PerfettoCaptureConfig,
    val command: String,
)
