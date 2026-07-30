package com.androidperformancestudio.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CaptureFrameCodecTest {
    private val codec = CaptureFrameCodec()

    @Test
    fun `capture frame survives a binary round trip`() {
        val expected = CaptureFrame(
            snapshotJson = """{"packageName":"com.androidperformancestudio.sample"}""",
            screenshotPng = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d),
        )
        val output = ByteArrayOutputStream()

        codec.write(expected, output)
        val actual = codec.read(ByteArrayInputStream(output.toByteArray()))

        assertEquals(expected.snapshotJson, actual.snapshotJson)
        assertArrayEquals(expected.screenshotPng, actual.screenshotPng)
    }

    @Test
    fun `negative payload lengths are rejected before allocation`() {
        val input = ByteArrayInputStream("CAPTURE -1 4\n".toByteArray())

        assertThrows(CaptureFrameFormatException::class.java) {
            codec.read(input)
        }
    }

    @Test
    fun `oversized payload lengths are rejected before allocation`() {
        val input = ByteArrayInputStream(
            "CAPTURE ${CaptureFrameCodec.MAX_SNAPSHOT_BYTES + 1} 4\n".toByteArray(),
        )

        assertThrows(CaptureFrameFormatException::class.java) {
            codec.read(input)
        }
    }

    @Test
    fun `truncated payloads are rejected`() {
        val input = ByteArrayInputStream("CAPTURE 4 4\n{}{}".toByteArray())

        assertThrows(CaptureFrameFormatException::class.java) {
            codec.read(input)
        }
    }

    @Test
    fun `remote capture errors retain their stable code`() {
        val input = ByteArrayInputStream("ERROR NO_ACTIVITY No resumed activity\n".toByteArray())

        val error = assertThrows(CaptureRemoteException::class.java) {
            codec.read(input)
        }

        assertEquals("NO_ACTIVITY", error.code)
        assertEquals("No resumed activity", error.remoteMessage)
    }
}
