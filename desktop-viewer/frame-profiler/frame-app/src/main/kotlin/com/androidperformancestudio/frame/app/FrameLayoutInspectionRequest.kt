package com.androidperformancestudio.frame.app

public data class FrameLayoutInspectionRequest(
    val deviceSerial: String?,
    val packageName: String,
    val activityName: String?,
    val windowId: String?,
    val frameId: Long,
)
