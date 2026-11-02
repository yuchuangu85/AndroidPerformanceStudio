package com.androidperformancestudio.presentation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import java.awt.Canvas
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirefoxChartViewportInputTest {
    @Test
    fun `synthetic wheel deltas are fractional line units with 15 CSS pixels per line`() {
        val event =
            PointerEvent(
                listOf(
                    PointerInputChange(
                        id = PointerId(1),
                        uptimeMillis = 0,
                        position = Offset.Zero,
                        pressed = false,
                        previousUptimeMillis = 0,
                        previousPosition = Offset.Zero,
                        previousPressed = false,
                        isInitiallyConsumed = false,
                        scrollDelta = Offset(0.125f, -0.25f),
                    ),
                ),
            )
        assertEquals(Offset(3.75f, -7.5f), firefoxChartWheelPixels(event, containerHeightPx = 400f, density = 2f))
    }

    @Test
    fun `AWT unit wheel uses scrollAmount once and keeps both fractional axes`() {
        assertEquals(
            Offset(11.25f, -22.5f),
            firefoxChartWheelPixels(
                Offset(0.125f, -0.25f),
                containerHeightPx = 400f,
                density = 2f,
                nativeEvent = nativeWheel(MouseWheelEvent.WHEEL_UNIT_SCROLL, amount = 3),
            ),
        )
    }

    @Test
    fun `AWT block wheel uses actual viewport height without another density multiplier`() {
        assertEquals(
            Offset(50f, -100f),
            firefoxChartWheelPixels(
                Offset(0.125f, -0.25f),
                containerHeightPx = 400f,
                density = 2f,
                nativeEvent = nativeWheel(MouseWheelEvent.WHEEL_BLOCK_SCROLL, amount = 3),
            ),
        )
    }

    @Test
    fun `held key moves at 2000 CSS pixels per second without OS repeat`() {
        val keyboard = FirefoxChartKeyboardState(horizontalEnabled = false)
        assertTrue(keyboard.handle(Key.W, KeyEventType.KeyDown))
        assertTrue(keyboard.advanceFrame(0).isEmpty())
        assertEquals(listOf(ChartViewportMotion(0f, 32f, 1.0)), keyboard.advanceFrame(16_000_000))
        assertEquals(listOf(ChartViewportMotion(0f, 32f, 1.0)), keyboard.advanceFrame(32_000_000))
        keyboard.handle(Key.W, KeyEventType.KeyDown)
        assertEquals(listOf(ChartViewportMotion(0f, 32f, 1.0)), keyboard.advanceFrame(48_000_000))
        assertTrue(keyboard.handle(Key.W, KeyEventType.KeyUp))
        assertTrue(keyboard.advanceFrame(64_000_000).isEmpty())
    }

    @Test
    fun `long animation frames are capped at 500 pixels and retain subpixels`() {
        val keyboard = FirefoxChartKeyboardState(horizontalEnabled = false)
        keyboard.handle(Key.J, KeyEventType.KeyDown)
        keyboard.advanceFrame(0)
        assertEquals(-0.25f, keyboard.advanceFrame(125_000).single().offsetYCss)
        assertEquals(-500f, keyboard.advanceFrame(1_000_125_000).single().offsetYCss)
        assertEquals(-32f, keyboard.advanceFrame(1_016_125_000).single().offsetYCss)
    }

    @Test
    fun `all aliases share upstream actions and releasing an alias stops the action`() {
        listOf(Key.W to Key.K, Key.S to Key.J, Key.A to Key.H, Key.D to Key.L, Key.Q to Key.Y, Key.E to Key.U)
            .forEach { (key, alias) ->
                val keyboard = FirefoxChartKeyboardState(horizontalEnabled = true)
                keyboard.handle(key, KeyEventType.KeyDown)
                keyboard.handle(alias, KeyEventType.KeyDown)
                keyboard.advanceFrame(0)
                assertEquals(1, keyboard.advanceFrame(16_000_000).size)
                keyboard.handle(alias, KeyEventType.KeyUp)
                assertFalse(keyboard.hasPressedKeys)
            }
    }

    @Test
    fun `horizontal keys and ctrl arrows are Stack only`() {
        val flame = FirefoxChartKeyboardState(horizontalEnabled = false)
        listOf(Key.A, Key.H, Key.D, Key.L, Key.Q, Key.Y, Key.E, Key.U).forEach {
            assertFalse(flame.handle(it, KeyEventType.KeyDown))
        }
        assertFalse(flame.handle(Key.DirectionUp, KeyEventType.KeyDown, controlPressed = true))
        assertFalse(flame.hasPressedKeys)

        val stack = FirefoxChartKeyboardState(horizontalEnabled = true)
        stack.handle(Key.DirectionUp, KeyEventType.KeyDown, controlPressed = true)
        stack.handle(Key.A, KeyEventType.KeyDown)
        stack.advanceFrame(0)
        val motions = stack.advanceFrame(16_000_000)
        assertEquals(1.003.pow(-32.0), motions[0].zoomFactor)
        assertEquals(32f, motions[1].offsetXCss)
        stack.handle(Key.CtrlLeft, KeyEventType.KeyUp)
        assertEquals(listOf(ChartViewportMotion(32f, 0f, 1.0)), stack.advanceFrame(32_000_000))
    }

    @Test
    fun `modified vertical shortcuts never become held navigation`() {
        listOf(Key.W, Key.K, Key.S, Key.J).forEach { key ->
            val keyboard = FirefoxChartKeyboardState(horizontalEnabled = true)
            assertFalse(keyboard.handle(key, KeyEventType.KeyDown, controlPressed = true))
            assertFalse(keyboard.handle(key, KeyEventType.KeyDown, metaPressed = true))
            assertFalse(keyboard.handle(key, KeyEventType.KeyDown, altPressed = true))
            assertFalse(keyboard.handle(key, KeyEventType.KeyDown, shiftPressed = true))
            assertFalse(keyboard.hasPressedKeys)
        }
    }

    @Test
    fun `clear discards pressed keys and the old frame timestamp`() {
        val keyboard = FirefoxChartKeyboardState(horizontalEnabled = true)
        keyboard.handle(Key.W, KeyEventType.KeyDown)
        keyboard.advanceFrame(0)
        keyboard.clear()
        assertFalse(keyboard.hasPressedKeys)
        assertTrue(keyboard.advanceFrame(100_000_000).isEmpty())
        keyboard.handle(Key.S, KeyEventType.KeyDown)
        assertTrue(keyboard.advanceFrame(1_000_000_000).isEmpty())
        assertEquals(-32f, keyboard.advanceFrame(1_016_000_000).single().offsetYCss)
    }

    private fun nativeWheel(
        type: Int,
        amount: Int,
    ) = MouseWheelEvent(Canvas(), MouseEvent.MOUSE_WHEEL, 0L, 0, 0, 0, 0, 0, 0, false, type, amount, 0, 0.125)
}
