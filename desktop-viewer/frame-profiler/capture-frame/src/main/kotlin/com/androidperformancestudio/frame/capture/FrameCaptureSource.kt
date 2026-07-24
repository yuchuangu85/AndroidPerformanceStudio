package com.androidperformancestudio.frame.capture

import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSourceCapabilities

public data class FrameCaptureCursor(
    val value: Long = 0L,
)

public data class FrameCaptureBatch(
    val frames: List<FrameSample>,
    val nextCursor: FrameCaptureCursor,
    val droppedFrameCount: Long = 0L,
    val completed: Boolean = false,
)

public interface FrameCaptureSource : AutoCloseable {
    public val capabilities: FrameSourceCapabilities

    public fun poll(
        cursor: FrameCaptureCursor,
        limit: Int,
    ): FrameCaptureBatch
}
