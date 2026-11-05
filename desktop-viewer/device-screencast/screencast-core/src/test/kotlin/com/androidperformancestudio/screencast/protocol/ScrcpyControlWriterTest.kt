package com.androidperformancestudio.screencast.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Builds expected wire bytes from plain int literals (masked to bytes). */
private fun bytes(vararg ints: Int): ByteArray = ByteArray(ints.size) { ints[it].toByte() }

class ScrcpyControlWriterTest {
    @Test
    fun `encodes inject touch against the scrcpy wire format`() {
        val output = ByteBufferBackedOutputStream()

        ScrcpyControlWriter(output).injectTouch(
            action = ScrcpyControlWriter.ACTION_DOWN,
            pointerId = 0x1122334455667788L,
            x = 108,
            y = 215,
            screenWidth = 1080,
            screenHeight = 2400,
            pressure = 1f,
            actionButton = ScrcpyControlWriter.BUTTON_PRIMARY,
            buttons = ScrcpyControlWriter.BUTTON_PRIMARY,
        )

        val expected =
            bytes(
                0x02, // TYPE_INJECT_TOUCH_EVENT
                0x00, // ACTION_DOWN
                0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, // pointerId
                0x00, 0x00, 0x00, 108, // x
                0x00, 0x00, 0x00, 215, // y
                0x04, 0x38, // screenWidth (1080)
                0x09, 0x60, // screenHeight (2400)
                0xFF, 0xFF, // pressure 1.0 as u16 fixed point
                0x00, 0x00, 0x00, 0x01, // actionButton primary
                0x00, 0x00, 0x00, 0x01, // buttons primary
            )
        val actual = output.toBytes()
        assertTrue(actual.contentEquals(expected), "actual=${actual.toHex()}")
    }

    @Test
    fun `encodes inject scroll with signed fixed point values`() {
        val output = ByteBufferBackedOutputStream()

        ScrcpyControlWriter(output).injectScroll(
            x = 5,
            y = 6,
            screenWidth = 100,
            screenHeight = 200,
            scrollX = 0f,
            scrollY = -1f,
            buttons = 0,
        )

        val expected =
            bytes(
                0x03, // TYPE_INJECT_SCROLL_EVENT
                0x00, 0x00, 0x00, 5, // x
                0x00, 0x00, 0x00, 6, // y
                0x00, 100, // screenWidth
                0x00, 200, // screenHeight
                0x00, 0x00, // scrollX 0.0
                0x80, 0x00, // scrollY -1.0 as i16 fixed point (-32768)
                0x00, 0x00, 0x00, 0x00, // buttons
            )
        val actual = output.toBytes()
        assertTrue(actual.contentEquals(expected), "actual=${actual.toHex()}")
    }

    @Test
    fun `encodes keycode injection and navigation taps`() {
        val output = ByteBufferBackedOutputStream()
        val writer = ScrcpyControlWriter(output)

        writer.injectKeycode(action = 0, keycode = 26)
        writer.tapKeycode(ScrcpyControlWriter.KEYCODE_BACK)

        val expected =
            bytes(
                0x00, 0x00, // TYPE_INJECT_KEYCODE, action down
                0x00, 0x00, 0x00, 26, // keycode
                0x00, 0x00, 0x00, 0x00, // repeat
                0x00, 0x00, 0x00, 0x00, // metaState
                0x00, 0x00, // TYPE_INJECT_KEYCODE, action down (tap)
                0x00, 0x00, 0x00, 4, // KEYCODE_BACK
                0x00, 0x00, 0x00, 0x00, // repeat
                0x00, 0x00, 0x00, 0x00, // metaState
                0x00, 0x01, // TYPE_INJECT_KEYCODE, action up (tap)
                0x00, 0x00, 0x00, 4,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
            )
        val actual = output.toBytes()
        assertTrue(actual.contentEquals(expected), "actual=${actual.toHex()}")
    }

    @Test
    fun `encodes set clipboard with paste flag and length prefix`() {
        val output = ByteBufferBackedOutputStream()

        ScrcpyControlWriter(output).setClipboard(text = "你好", paste = true, sequence = 7L)

        val textBytes = "你好".toByteArray(Charsets.UTF_8)
        val expected =
            bytes(
                0x09, // TYPE_SET_CLIPBOARD
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07, // sequence
                0x01, // paste
                0x00, 0x00, 0x00, textBytes.size, // utf-8 length
            ) + textBytes
        val actual = output.toBytes()
        assertTrue(actual.contentEquals(expected), "actual=${actual.toHex()}")
    }

    @Test
    fun `rejects oversized clipboard and inject text`() {
        val writer = ScrcpyControlWriter(ByteBufferBackedOutputStream())

        assertFailsWith<IllegalArgumentException> {
            writer.setClipboard("a".repeat(ScrcpyControlWriter.CLIPBOARD_TEXT_MAX_LENGTH + 1), paste = false)
        }
        assertFailsWith<IllegalArgumentException> {
            writer.injectText("a".repeat(ScrcpyControlWriter.INJECT_TEXT_MAX_LENGTH + 1))
        }
    }

    @Test
    fun `writes empty messages and clipboard copy request`() {
        val output = ByteBufferBackedOutputStream()
        val writer = ScrcpyControlWriter(output)

        writer.rotateDevice()
        writer.collapsePanels()
        writer.getClipboard(ScrcpyControlWriter.COPY_KEY_COPY)

        val actual = output.toBytes()
        assertEquals(listOf(11, 7, 8, 1), actual.map(Byte::toInt))
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }
}
