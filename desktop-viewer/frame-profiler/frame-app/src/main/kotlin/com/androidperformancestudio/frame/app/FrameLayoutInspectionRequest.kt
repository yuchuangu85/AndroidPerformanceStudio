package com.androidperformancestudio.frame.app

import com.androidperformancestudio.frame.model.FrameSample
import java.nio.file.Path

public data class FrameLayoutInspectionRequest(
    val deviceSerial: String?,
    val packageName: String,
    val activityName: String?,
    val windowId: String?,
    val frameId: Long,
)

public data class FramePerfettoInspectionRequest(
    val traceFile: Path,
    val frameId: Long?,
    val frameTimelineVsyncId: Long?,
    val intendedVsyncNs: Long?,
)

internal fun framePerfettoInspectionRequest(
    traceFile: Path,
    sample: FrameSample?,
): FramePerfettoInspectionRequest =
    FramePerfettoInspectionRequest(
        traceFile = traceFile,
        frameId = sample?.frameId,
        frameTimelineVsyncId = sample?.frameTimelineVsyncId,
        intendedVsyncNs = sample?.intendedVsyncNs,
    )
