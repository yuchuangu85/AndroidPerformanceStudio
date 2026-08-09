package com.androidperformancestudio.perfetto.model
import com.androidperformancestudio.contracts.CaptureArtifact
import java.nio.file.Path
import java.time.Instant

data class TraceSession(
    val id: String,
    val traceFile: Path,
    val captureConfig: PerfettoCaptureConfig,
    val deviceSerial: String,
    val deviceModel: String,
    val androidSdk: Int,
    val capturedAt: Instant,
    val durationNanos: Long,
    val fileSizeBytes: Long,
    val notes: String? = null,
    val isProtected: Boolean = false,
    val artifact: CaptureArtifact? = null,
)

data class TraceSummary(
    val processes: List<TraceProcess> = emptyList(),
    val totalSlices: Long = 0,
    val durationNanos: Long = 0,
)

data class TraceProcess(
    val upid: Int,
    val pid: Int,
    val name: String,
)
