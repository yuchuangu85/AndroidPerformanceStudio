package com.androidperformancestudio.screencast.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ScrcpyDeviceMessageReaderTest {
    @Test
    fun `reads clipboard ack and uhid output messages`() {
        val stream = ByteArrayOutputStream()
        val output = DataOutputStream(stream)

        output.writeByte(ScrcpyDeviceMessageReader.TYPE_CLIPBOARD)
        val text = "device clipboard"
        val raw = text.toByteArray(Charsets.UTF_8)
        output.writeInt(raw.size)
        output.write(raw)

        output.writeByte(ScrcpyDeviceMessageReader.TYPE_ACK_CLIPBOARD)
        output.writeLong(42L)

        output.writeByte(ScrcpyDeviceMessageReader.TYPE_UHID_OUTPUT)
        output.writeShort(3)
        output.writeShort(2)
        output.write(byteArrayOf(5.toByte(), 6.toByte()))

        val reader = ScrcpyDeviceMessageReader(ByteArrayInputStream(stream.toByteArray()))

        val clipboard = reader.readMessage()
        assertEquals(ScrcpyDeviceMessageReader.DeviceMessage.Clipboard(text), clipboard)

        val ack = reader.readMessage()
        assertEquals(ScrcpyDeviceMessageReader.DeviceMessage.AckClipboard(42L), ack)

        val uhid = reader.readMessage() as ScrcpyDeviceMessageReader.DeviceMessage.UhidOutput
        assertEquals(3, uhid.id)
        assertTrue(uhid.data.contentEquals(byteArrayOf(5.toByte(), 6.toByte())))

        assertNull(reader.readMessage())
    }

    @Test
    fun `fails on unknown device message type`() {
        val stream = ByteArrayOutputStream()
        val output = DataOutputStream(stream)
        output.writeByte(99)

        val reader = ScrcpyDeviceMessageReader(ByteArrayInputStream(stream.toByteArray()))
        assertFailsWith<IOException> { reader.readMessage() }
    }

    private fun assertTrue(condition: Boolean) {
        check(condition)
    }
}
