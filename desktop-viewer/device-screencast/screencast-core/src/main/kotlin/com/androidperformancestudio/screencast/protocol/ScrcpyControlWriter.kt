package com.androidperformancestudio.screencast.protocol

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Writes scrcpy v3.x control messages to the control socket.
 *
 * Wire format follows the scrcpy protocol documentation and
 * `ControlMessageReader` in the scrcpy server (v3.1):
 * messages start with a one-byte type, followed by type-specific
 * fixed-width fields in big-endian order.
 */
@Suppress("TooManyFunctions")
class ScrcpyControlWriter(
    rawOutputStream: OutputStream,
) : AutoCloseable {
    private val output = DataOutputStream(BufferedOutputStream(rawOutputStream))

    /** Injects a touch/mouse event. Coordinates are in device space, expressed against [screenWidth]x[screenHeight]. */
    @Suppress("LongParameterList")
    @Throws(IOException::class)
    fun injectTouch(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        pressure: Float,
        actionButton: Int,
        buttons: Int,
    ) {
        output.writeByte(TYPE_INJECT_TOUCH_EVENT)
        output.writeByte(action)
        output.writeLong(pointerId)
        writePosition(x, y, screenWidth, screenHeight)
        output.writeShort(floatToU16FixedPoint(pressure))
        output.writeInt(actionButton)
        output.writeInt(buttons)
        output.flush()
    }

    /** Injects a scroll event. Scroll values are floats in [-1, 1] encoded as signed 16-bit fixed point. */
    @Suppress("LongParameterList")
    @Throws(IOException::class)
    fun injectScroll(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        scrollX: Float,
        scrollY: Float,
        buttons: Int,
    ) {
        output.writeByte(TYPE_INJECT_SCROLL_EVENT)
        writePosition(x, y, screenWidth, screenHeight)
        output.writeShort(floatToI16FixedPoint(scrollX))
        output.writeShort(floatToI16FixedPoint(scrollY))
        output.writeInt(buttons)
        output.flush()
    }

    /** Injects a keycode event (action: 0 down, 1 up). */
    @Throws(IOException::class)
    fun injectKeycode(
        action: Int,
        keycode: Int,
        repeat: Int = 0,
        metaState: Int = 0,
    ) {
        output.writeByte(TYPE_INJECT_KEYCODE)
        output.writeByte(action)
        output.writeInt(keycode)
        output.writeInt(repeat)
        output.writeInt(metaState)
        output.flush()
    }

    /** Presses back (falling back to turning the screen on) — action: 0 down, 1 up. */
    @Throws(IOException::class)
    fun backOrScreenOn(action: Int) {
        output.writeByte(TYPE_BACK_OR_SCREEN_ON)
        output.writeByte(action)
        output.flush()
    }

    /** Convenience: tap a keycode as a down/up pair. */
    @Throws(IOException::class)
    fun tapKeycode(keycode: Int) {
        injectKeycode(action = ACTION_DOWN, keycode = keycode)
        injectKeycode(action = ACTION_UP, keycode = keycode)
    }

    /** Injects text into the focused input field. */
    @Throws(IOException::class)
    fun injectText(text: String) {
        val raw = text.toByteArray(StandardCharsets.UTF_8)
        require(raw.size <= INJECT_TEXT_MAX_LENGTH) {
            "Inject text exceeds the scrcpy limit: ${raw.size} > $INJECT_TEXT_MAX_LENGTH bytes"
        }
        output.writeByte(TYPE_INJECT_TEXT)
        output.writeInt(raw.size)
        output.write(raw)
        output.flush()
    }

    /** Writes the device clipboard, optionally triggering a paste into the focused field. */
    @Throws(IOException::class)
    fun setClipboard(
        text: String,
        paste: Boolean,
        sequence: Long = 0L,
    ) {
        val raw = text.toByteArray(StandardCharsets.UTF_8)
        require(raw.size <= CLIPBOARD_TEXT_MAX_LENGTH) {
            "Clipboard text exceeds the scrcpy limit: ${raw.size} > $CLIPBOARD_TEXT_MAX_LENGTH bytes"
        }
        output.writeByte(TYPE_SET_CLIPBOARD)
        output.writeLong(sequence)
        output.writeByte(if (paste) 1 else 0)
        output.writeInt(raw.size)
        output.write(raw)
        output.flush()
    }

    /** Requests the current device clipboard (copyKey: 0 none, 1 copy, 2 cut). */
    @Throws(IOException::class)
    fun getClipboard(copyKey: Int = COPY_KEY_NONE) {
        output.writeByte(TYPE_GET_CLIPBOARD)
        output.writeByte(copyKey)
        output.flush()
    }

    @Throws(IOException::class)
    fun expandNotificationPanel() = writeEmpty(TYPE_EXPAND_NOTIFICATION_PANEL)

    @Throws(IOException::class)
    fun collapsePanels() = writeEmpty(TYPE_COLLAPSE_PANELS)

    @Throws(IOException::class)
    fun rotateDevice() = writeEmpty(TYPE_ROTATE_DEVICE)

    /**
     * Turns the device's physical display on ([on] = true) or off ([on] = false) without putting the
     * device to sleep — the scrcpy video stream keeps flowing while the screen is dark. Maps to the
     * scrcpy SET_DISPLAY_POWER control message (1 = on, 0 = off).
     */
    @Throws(IOException::class)
    fun setDisplayPower(on: Boolean) {
        output.writeByte(TYPE_SET_DISPLAY_POWER)
        output.writeByte(if (on) 1 else 0)
        output.flush()
    }

    /** Reopens the video stream (the server sends a new configuration packet). */
    @Throws(IOException::class)
    fun resetVideo() = writeEmpty(TYPE_RESET_VIDEO)

    override fun close() {
        output.close()
    }

    @Throws(IOException::class)
    private fun writeEmpty(type: Int) {
        output.writeByte(type)
        output.flush()
    }

    private fun writePosition(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        output.writeInt(x)
        output.writeInt(y)
        output.writeShort(screenWidth)
        output.writeShort(screenHeight)
    }

    companion object {
        // Control message types (scrcpy v3.1 ControlMessage).
        const val TYPE_INJECT_KEYCODE = 0
        const val TYPE_INJECT_TEXT = 1
        const val TYPE_INJECT_TOUCH_EVENT = 2
        const val TYPE_INJECT_SCROLL_EVENT = 3
        const val TYPE_BACK_OR_SCREEN_ON = 4
        const val TYPE_EXPAND_NOTIFICATION_PANEL = 5
        const val TYPE_EXPAND_SETTINGS_PANEL = 6
        const val TYPE_COLLAPSE_PANELS = 7
        const val TYPE_GET_CLIPBOARD = 8
        const val TYPE_SET_CLIPBOARD = 9
        const val TYPE_SET_DISPLAY_POWER = 10
        const val TYPE_ROTATE_DEVICE = 11
        const val TYPE_RESET_VIDEO = 17

        const val COPY_KEY_NONE = 0
        const val COPY_KEY_COPY = 1
        const val COPY_KEY_CUT = 2

        // scrcpy server limits (MESSAGE_MAX_SIZE = 1 << 18).
        const val CLIPBOARD_TEXT_MAX_LENGTH = (1 shl 18) - 14
        const val INJECT_TEXT_MAX_LENGTH = 300

        // Android motion/key event actions used by clients.
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_HOVER_MOVE = 7

        // Android motion event buttons (MouseButtons/PointerEvent mapping).
        const val BUTTON_PRIMARY = 1
        const val BUTTON_SECONDARY = 2
        const val BUTTON_TERTIARY = 4

        // Common Android keycodes for toolbar controls.
        const val KEYCODE_BACK = 4
        const val KEYCODE_HOME = 3
        const val KEYCODE_APP_SWITCH = 187
        const val KEYCODE_POWER = 26
        const val KEYCODE_VOLUME_UP = 24
        const val KEYCODE_VOLUME_DOWN = 25

        // Fixed-point conversions mirroring scrcpy util/Binary.
        private const val U16_FIXED_POINT_SCALE = 65536f
        private const val U16_FIXED_POINT_MAX = 0xffff
        private const val I16_FIXED_POINT_SCALE = 32768f

        fun floatToU16FixedPoint(value: Float): Int {
            val fixed = (value * U16_FIXED_POINT_SCALE).toInt()
            if (fixed >= U16_FIXED_POINT_MAX) return U16_FIXED_POINT_MAX
            return maxOf(fixed, 0)
        }

        fun floatToI16FixedPoint(value: Float): Int {
            val fixed = (value * I16_FIXED_POINT_SCALE).toInt()
            return fixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        }
    }
}
