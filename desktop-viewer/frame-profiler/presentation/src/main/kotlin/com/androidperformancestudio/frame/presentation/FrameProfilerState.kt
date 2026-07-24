package com.androidperformancestudio.frame.presentation

import com.androidperformancestudio.frame.analysis.FrameAnalysisResult

public data class FrameProfilerState(
    val devices: List<FrameDeviceOption> = emptyList(),
    val selectedDeviceSerial: String? = null,
    val processes: List<FrameProcessOption> = emptyList(),
    val selectedProcessId: Int? = null,
    val importedFileName: String? = null,
    val analysis: FrameAnalysisResult? = null,
    val selectedFrameId: Long? = null,
    val isLoading: Boolean = false,
    val isRefreshingDevices: Boolean = false,
    val isCapturing: Boolean = false,
    val operationMessage: String? = null,
    val warnings: List<String> = emptyList(),
    val errorMessage: String? = null,
)

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
)
