package com.androidperformancestudio.frame.presentation

import com.androidperformancestudio.frame.analysis.FrameAnalysisResult
import com.androidperformancestudio.frame.model.FrameSample
import java.nio.file.Path

public data class FrameProfilerState(
    val devices: List<FrameDeviceOption> = emptyList(),
    val selectedDeviceSerial: String? = null,
    val processes: List<FrameProcessOption> = emptyList(),
    val selectedProcessId: Int? = null,
    val importedFileName: String? = null,
    val analysis: FrameAnalysisResult? = null,
    val selectedFrameId: Long? = null,
    val perfettoTraceFile: Path? = null,
    val isLoading: Boolean = false,
    val isRefreshingDevices: Boolean = false,
    val isCapturing: Boolean = false,
    val operationStatus: FrameOperationStatus? = null,
    val warnings: List<String> = emptyList(),
    val errorMessage: String? = null,
)

public sealed interface FrameOperationStatus {
    public data class Capturing(
        val packageName: String,
        val source: String,
        val frameCount: Int? = null,
    ) : FrameOperationStatus

    public data class CaptureStopped(
        val frameCount: Int,
    ) : FrameOperationStatus

    public data class ImportedFrames(
        val frameCount: Int,
    ) : FrameOperationStatus

    public data class Exported(
        val fileName: String,
    ) : FrameOperationStatus
}

public data class FrameDeviceOption(
    val serial: String,
    val name: String,
    val online: Boolean = true,
)

public data class FrameProcessOption(
    val pid: Int,
    val name: String,
    val packageName: String,
)

public data class FrameProfilerActions(
    val onSelectFrame: (Long) -> Unit = {},
    val onInspectLayout: (FrameSample) -> Unit = {},
)
