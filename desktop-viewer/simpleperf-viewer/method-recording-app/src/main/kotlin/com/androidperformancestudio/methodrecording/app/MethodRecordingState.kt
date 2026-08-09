package com.androidperformancestudio.methodrecording.app

import com.androidperformancestudio.arttrace.ArtTraceAnalysis
import com.androidperformancestudio.arttrace.MethodTopMethod
import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.methodcapture.MethodTraceDeviceOption
import com.androidperformancestudio.methodcapture.MethodTraceProcessOption
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import java.nio.file.Path

/** Capture lifecycle for the method-recording workspace. */
sealed interface MethodTraceCapturePhase {
    data object Idle : MethodTraceCapturePhase

    data object Recording : MethodTraceCapturePhase

    data class Completed(
        val traceFile: Path,
    ) : MethodTraceCapturePhase

    data class Failed(
        val message: String,
    ) : MethodTraceCapturePhase
}

data class MethodRecordingState(
    val devices: List<MethodTraceDeviceOption> = emptyList(),
    val processes: List<MethodTraceProcessOption> = emptyList(),
    val selectedSerial: String? = null,
    val selectedPid: Int? = null,
    val capturePhase: MethodTraceCapturePhase = MethodTraceCapturePhase.Idle,
    val analysis: ArtTraceAnalysis? = null,
    val flameGraph: FlameGraphSnapshot? = null,
    val topMethods: List<MethodTopMethod> = emptyList(),
    val traceLabel: String? = null,
    /** Provenance and completeness for the loaded method timeline evidence. */
    val artifact: CaptureArtifact? = null,
    val error: String? = null,
    val isLoading: Boolean = false,
)
