package com.androidperformancestudio.winscope.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class ScreenRecordingMetadata internal constructor(
    private val frameTimestampsNanos: LongArray,
) {
    val frameCount: Int get() = frameTimestampsNanos.size

    fun frameIndexAt(timestampNanos: Long): Int? {
        var low = 0
        var high = frameTimestampsNanos.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (frameTimestampsNanos[middle] <= timestampNanos) low = middle + 1 else high = middle
        }
        return (low - 1).coerceAtLeast(0)
    }
}

object ScreenRecordingMetadataReader {
    fun read(path: Path): ScreenRecordingMetadata? =
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val match = findMagic(channel) ?: return null
            channel.position(match.dataOffset)
            if (match.modern) {
                val header = channel.readLittleEndian(Int.SIZE_BYTES + Long.SIZE_BYTES + Int.SIZE_BYTES)
                require(header.int == 2) { "Unsupported Winscope screen-recording metadata version" }
                header.long // realtime-to-elapsed offset; elapsed timestamps below already share Perfetto's clock domain.
                readFrames(channel, header.int, multiplier = 1L)
            } else {
                val count = channel.readLittleEndian(Int.SIZE_BYTES).int
                readFrames(channel, count, multiplier = 1_000L)
            }
        }

    private fun readFrames(
        channel: FileChannel,
        encodedCount: Int,
        multiplier: Long,
    ): ScreenRecordingMetadata {
        val count = Integer.toUnsignedLong(encodedCount)
        require(count in 1..MAX_FRAME_COUNT.toLong()) { "Invalid Winscope screen-recording frame count: $count" }
        require(count * Long.SIZE_BYTES <= channel.size() - channel.position()) { "Truncated Winscope screen-recording metadata" }
        val bytes = channel.readLittleEndian(Math.toIntExact(count * Long.SIZE_BYTES))
        val timestamps = LongArray(count.toInt()) { Math.multiplyExact(bytes.long, multiplier) }
        require(timestamps.indices.drop(1).all { timestamps[it - 1] <= timestamps[it] }) {
            "Winscope screen-recording timestamps are not ordered"
        }
        return ScreenRecordingMetadata(timestamps)
    }

    private fun findMagic(channel: FileChannel): MagicMatch? {
        var end = channel.size()
        while (end > 0) {
            val start = maxOf(0, end - SEARCH_CHUNK_BYTES)
            val bytes = ByteBuffer.allocate((end - start).toInt())
            var position = start
            while (bytes.hasRemaining()) {
                val read = channel.read(bytes, position)
                if (read < 0) break
                position += read
            }
            val data = bytes.array()
            for (index in data.size - MODERN_MAGIC.size downTo 0) {
                when {
                    data.matchesAt(index, MODERN_MAGIC) -> return MagicMatch(start + index + MODERN_MAGIC.size, modern = true)
                    data.matchesAt(index, LEGACY_MAGIC) -> return MagicMatch(start + index + LEGACY_MAGIC.size, modern = false)
                }
            }
            if (start == 0L) break
            end = start + MODERN_MAGIC.size - 1
        }
        return null
    }

    private fun FileChannel.readLittleEndian(size: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.hasRemaining()) require(read(buffer) >= 0) { "Truncated Winscope screen-recording metadata" }
        return buffer.flip()
    }

    private fun ByteArray.matchesAt(
        offset: Int,
        expected: ByteArray,
    ): Boolean = expected.indices.all { this[offset + it] == expected[it] }

    private data class MagicMatch(
        val dataOffset: Long,
        val modern: Boolean,
    )

    private const val SEARCH_CHUNK_BYTES = 64 * 1024L
    private const val MAX_FRAME_COUNT = 1_000_000
    private val MODERN_MAGIC = "#VV1NSC0PET1ME2#".encodeToByteArray()
    private val LEGACY_MAGIC = "#VV1NSC0PET1ME!#".encodeToByteArray()
}
