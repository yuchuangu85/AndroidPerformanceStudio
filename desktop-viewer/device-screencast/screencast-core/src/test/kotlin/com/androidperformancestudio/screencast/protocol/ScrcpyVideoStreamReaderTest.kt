package com.androidperformancestudio.screencast.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScrcpyVideoStreamReaderTest {
    @Test
    fun `reads device name and codec metadata before framed packets`() {
        val stream = ByteArrayOutputStream()
        val output = DataOutputStream(stream)

        // Device name: fixed 64-byte NUL-padded field.
        val nameBytes = "Pixel 8".toByteArray(Charsets.UTF_8)
        val nameField = ByteArray(64)
        nameBytes.copyInto(nameField)
        output.write(nameField)

        // Codec metadata: h264 codec id + 1080x2400 (all u32, 12 bytes total).
        output.writeInt(ScrcpyVideoStreamReader.CODEC_H264)
        output.writeInt(1080)
        output.writeInt(2400)

        // Config packet: flag bit 63 set, size 4.
        output.writeLong(ScrcpyVideoStreamReader.PACKET_FLAG_CONFIG)
        output.writeInt(4)
        output.write(byteArrayOf(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte()))

        // Key frame packet: pts 12345 with key-frame flag, size 2.
        output.writeLong(12345L or ScrcpyVideoStreamReader.PACKET_FLAG_KEY_FRAME)
        output.writeInt(2)
        output.write(byteArrayOf(9.toByte(), 8.toByte()))

        val reader = ScrcpyVideoStreamReader(ByteArrayInputStream(stream.toByteArray()))
        assertEquals("Pixel 8", reader.readDeviceName())

        val metadata = reader.readVideoMetadata()
        assertEquals(ScrcpyVideoStreamReader.CODEC_H264, metadata.codecId)
        assertEquals(1080, metadata.width)
        assertEquals(2400, metadata.height)
        assertEquals("h264", metadata.codecName)

        val config = reader.readPacket()
        assertTrue(config!!.isConfig)
        assertTrue(!config.isKeyFrame)
        assertTrue(config.payload.contentEquals(byteArrayOf(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte())))

        val frame = reader.readPacket()
        assertTrue(frame!!.isKeyFrame)
        assertEquals(12345L, frame.pts)
        assertTrue(frame.payload.contentEquals(byteArrayOf(9.toByte(), 8.toByte())))

        assertNull(reader.readPacket())
    }

    @Test
    fun `masks pts away from flag bits`() {
        val stream = ByteArrayOutputStream()
        val output = DataOutputStream(stream)
        val ptsWithBothFlags = ScrcpyVideoStreamReader.PACKET_FLAG_CONFIG or
            ScrcpyVideoStreamReader.PACKET_FLAG_KEY_FRAME or
            987654321L
        output.writeLong(ptsWithBothFlags)
        output.writeInt(1)
        output.write(7)

        val reader = ScrcpyVideoStreamReader(ByteArrayInputStream(stream.toByteArray()))
        val packet = reader.readPacket()!!

        assertTrue(packet.isConfig)
        assertTrue(packet.isKeyFrame)
        assertEquals(987654321L, packet.pts)
    }
}
