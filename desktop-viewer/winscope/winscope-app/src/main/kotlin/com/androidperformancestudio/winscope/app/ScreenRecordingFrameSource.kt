package com.androidperformancestudio.winscope.app

import com.androidperformancestudio.winscope.media.ScreenRecordingMetadataReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import org.jcodec.api.FrameGrab
import org.jcodec.common.io.NIOUtils
import org.jcodec.scale.AWTUtil
import java.awt.image.BufferedImage
import java.nio.file.Path

internal suspend fun Flow<Long>.collectScreenRecordingRequests(action: suspend (Long) -> Unit) {
    conflate().collect(action)
}

internal class ScreenRecordingFrameSource(
    path: Path,
) : AutoCloseable {
    private val metadata = ScreenRecordingMetadataReader.read(path)
    private val channel = NIOUtils.readableChannel(path.toFile())
    private val grab = FrameGrab.createFrameGrab(channel)
    private var decodedIndex = -1
    private var decodedFrame: BufferedImage? = null

    @Synchronized
    fun frameAt(
        timestampNanos: Long,
        timelineStartNanos: Long = 0L,
    ): BufferedImage? {
        val frameIndex = metadata?.frameIndexAt(timestampNanos)
        if (metadata == null) {
            val second =
                ((timestampNanos - timelineStartNanos).coerceAtLeast(0L) / NANOS_PER_SECOND)
                    .coerceAtMost(grab.videoTrack.meta.totalDuration)
            return grab.seekToSecondPrecise(second).nativeFrame?.let(AWTUtil::toBufferedImage)
        }
        frameIndex ?: return null
        if (frameIndex == decodedIndex) return decodedFrame
        val picture =
            if (frameIndex == decodedIndex + 1) {
                grab.nativeFrame
            } else {
                grab.seekToFramePrecise(frameIndex).nativeFrame
            }
        decodedIndex = frameIndex
        decodedFrame = picture?.let(AWTUtil::toBufferedImage)
        return decodedFrame
    }

    override fun close() {
        NIOUtils.closeQuietly(channel)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
