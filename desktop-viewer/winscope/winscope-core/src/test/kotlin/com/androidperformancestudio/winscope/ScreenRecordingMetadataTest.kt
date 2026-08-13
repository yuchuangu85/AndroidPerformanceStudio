package com.androidperformancestudio.winscope

import com.androidperformancestudio.winscope.media.ScreenRecordingMetadataReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScreenRecordingMetadataTest {
    @Test
    fun `version 2 metadata maps the cursor to the last recorded frame`() {
        val timestamps = longArrayOf(1_000_000_000L, 1_016_000_000L, 1_033_000_000L)
        val file = writeVideo(MODERN_MAGIC, 2, 123L, timestamps, prefixSize = 64 * 1024 - 8)

        val metadata = ScreenRecordingMetadataReader.read(file)!!

        assertEquals(3, metadata.frameCount)
        assertEquals(0, metadata.frameIndexAt(999_999_999L))
        assertEquals(0, metadata.frameIndexAt(1_000_000_000L))
        assertEquals(1, metadata.frameIndexAt(1_032_999_999L))
        assertEquals(2, metadata.frameIndexAt(2_000_000_000L))
    }

    @Test
    fun `legacy metadata converts elapsed microseconds to nanoseconds`() {
        val file = writeVideo(LEGACY_MAGIC, timestamps = longArrayOf(2_000_000L, 2_020_000L))

        val metadata = ScreenRecordingMetadataReader.read(file)!!

        assertEquals(0, metadata.frameIndexAt(2_019_999_999L))
        assertEquals(1, metadata.frameIndexAt(2_020_000_000L))
    }

    @Test
    fun `ordinary mp4 remains explicitly unsynchronized`() {
        val file = Files.createTempFile("screen-recording", ".mp4")
        Files.write(file, byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()))

        assertNull(ScreenRecordingMetadataReader.read(file))
    }

    private fun writeVideo(
        magic: ByteArray,
        version: Int? = null,
        offset: Long? = null,
        timestamps: LongArray,
        prefixSize: Int = 71,
    ) = Files.createTempFile("screen-recording", ".mp4").also { file ->
        val metadataSize = magic.size + (if (version == null) 4 else 16) + timestamps.size * Long.SIZE_BYTES
        val bytes = ByteBuffer.allocate(prefixSize + metadataSize).order(ByteOrder.LITTLE_ENDIAN)
        repeat(prefixSize) { bytes.put(0x5a) }
        bytes.put(magic)
        version?.let(bytes::putInt)
        offset?.let(bytes::putLong)
        bytes.putInt(timestamps.size)
        timestamps.forEach(bytes::putLong)
        Files.write(file, bytes.array())
    }

    private companion object {
        val MODERN_MAGIC = "#VV1NSC0PET1ME2#".encodeToByteArray()
        val LEGACY_MAGIC = "#VV1NSC0PET1ME!#".encodeToByteArray()
    }
}
