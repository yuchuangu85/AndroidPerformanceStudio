package com.androidperformancestudio.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.platform.LocalWindowInfo
import java.awt.event.MouseWheelEvent
import kotlin.math.pow

internal data class ChartViewportMotion(
    val offsetXCss: Float,
    val offsetYCss: Float,
    val zoomFactor: Double,
)

internal fun firefoxChartWheelPixels(
    event: PointerEvent,
    containerHeightPx: Float,
    density: Float,
): Offset =
    firefoxChartWheelPixels(
        event.changes.firstOrNull()?.scrollDelta ?: Offset.Zero,
        containerHeightPx,
        density,
        event.nativeEvent as? MouseWheelEvent,
    )

internal fun firefoxChartWheelPixels(
    scrollDelta: Offset,
    containerHeightPx: Float,
    density: Float,
    nativeEvent: MouseWheelEvent? = null,
): Offset {
    val pixelsPerUnit =
        if (nativeEvent?.scrollType == MouseWheelEvent.WHEEL_BLOCK_SCROLL) {
            containerHeightPx
        } else {
            // Compose exposes wheel rotations, including in synthetic input, not CSS pixels.
            (nativeEvent?.scrollAmount ?: 1) * SCROLL_LINE_SIZE_CSS_PX * density
        }
    return scrollDelta * pixelsPerUnit
}

@Composable
internal fun rememberFirefoxChartKeyboard(
    horizontalEnabled: Boolean,
    resetKey: Any? = Unit,
    onMotion: (ChartViewportMotion) -> Unit,
): FirefoxChartKeyboardState {
    val keyboard = remember(horizontalEnabled, resetKey) { FirefoxChartKeyboardState(horizontalEnabled) }
    val currentOnMotion by rememberUpdatedState(onMotion)
    val windowInfo = LocalWindowInfo.current
    val windowFocused = windowInfo.isWindowFocused
    DisposableEffect(keyboard) { onDispose(keyboard::clear) }
    LaunchedEffect(keyboard, windowFocused, keyboard.hasPressedKeys) {
        if (!windowFocused) keyboard.clear()
        while (windowFocused && keyboard.hasPressedKeys) {
            withFrameNanos { timestamp ->
                if (windowInfo.isWindowFocused) {
                    keyboard.advanceFrame(timestamp).forEach(currentOnMotion)
                } else {
                    keyboard.clear()
                }
            }
        }
    }
    return keyboard
}

internal class FirefoxChartKeyboardState(
    private val horizontalEnabled: Boolean,
) {
    private val pressed = linkedSetOf<ChartNavigationKey>()
    private var previousFrameNanos: Long? = null
    var hasPressedKeys by mutableStateOf(false)
        private set

    fun handle(event: KeyEvent): Boolean =
        handle(
            event.key,
            event.type,
            event.isCtrlPressed,
            event.isMetaPressed,
            event.isAltPressed,
            event.isShiftPressed,
        )

    @Suppress("LongParameterList")
    fun handle(
        key: Key,
        eventType: KeyEventType,
        controlPressed: Boolean = false,
        metaPressed: Boolean = false,
        altPressed: Boolean = false,
        shiftPressed: Boolean = false,
    ): Boolean =
        when (eventType) {
            KeyEventType.KeyUp -> release(key, controlPressed)
            KeyEventType.KeyDown -> {
                val navigation =
                    when {
                        controlPressed -> controlNavigation(key)
                        metaPressed || altPressed || shiftPressed -> null
                        else -> bareNavigation(key)
                    }
                press(navigation)
            }
            else -> false
        }

    private fun press(navigation: ChartNavigationKey?): Boolean {
        val vertical = navigation == ChartNavigationKey.UP || navigation == ChartNavigationKey.DOWN
        if (navigation == null || (!horizontalEnabled && !vertical)) return false
        pressed.add(navigation)
        updatePressedState()
        return true
    }

    private fun release(
        key: Key,
        controlPressed: Boolean,
    ): Boolean {
        val wasPressed = pressed.size
        if (!controlPressed) {
            pressed.remove(ChartNavigationKey.ZOOM_IN)
            pressed.remove(ChartNavigationKey.ZOOM_OUT)
        }
        controlNavigation(key)?.let(pressed::remove)
        bareNavigation(key)?.let(pressed::remove)
        updatePressedState()
        return wasPressed != pressed.size
    }

    fun clear() {
        pressed.clear()
        updatePressedState()
    }

    fun advanceFrame(frameTimeNanos: Long): List<ChartViewportMotion> {
        val previous = previousFrameNanos
        if (!hasPressedKeys || (previous != null && frameTimeNanos <= previous)) return emptyList()
        previousFrameNanos = frameTimeNanos
        return if (previous == null) {
            emptyList()
        } else {
            val delta =
                ((frameTimeNanos.toDouble() - previous.toDouble()) * KEYBOARD_CSS_PIXELS_PER_NANOSECOND)
                    .coerceAtMost(MAXIMUM_KEYBOARD_DELTA_CSS_PX)
                    .toFloat()
            motionsForDelta(delta)
        }
    }

    private fun motionsForDelta(delta: Float): List<ChartViewportMotion> =
        // Preserve upstream action order, including clamping between opposing actions.
        pressed.map { navigation ->
            when (navigation) {
                ChartNavigationKey.UP -> ChartViewportMotion(0f, delta, 1.0)
                ChartNavigationKey.DOWN -> ChartViewportMotion(0f, -delta, 1.0)
                ChartNavigationKey.LEFT -> ChartViewportMotion(delta, 0f, 1.0)
                ChartNavigationKey.RIGHT -> ChartViewportMotion(-delta, 0f, 1.0)
                ChartNavigationKey.ZOOM_IN -> ChartViewportMotion(0f, 0f, KEYBOARD_ZOOM_SPEED.pow(-delta.toDouble()))
                ChartNavigationKey.ZOOM_OUT -> ChartViewportMotion(0f, 0f, KEYBOARD_ZOOM_SPEED.pow(delta.toDouble()))
            }
        }

    private fun updatePressedState() {
        hasPressedKeys = pressed.isNotEmpty()
        if (!hasPressedKeys) previousFrameNanos = null
    }
}

private enum class ChartNavigationKey { UP, DOWN, LEFT, RIGHT, ZOOM_IN, ZOOM_OUT }

private fun bareNavigation(key: Key): ChartNavigationKey? =
    when (key) {
        Key.W, Key.K -> ChartNavigationKey.UP
        Key.S, Key.J -> ChartNavigationKey.DOWN
        Key.A, Key.H -> ChartNavigationKey.LEFT
        Key.D, Key.L -> ChartNavigationKey.RIGHT
        Key.Q, Key.Y -> ChartNavigationKey.ZOOM_IN
        Key.E, Key.U -> ChartNavigationKey.ZOOM_OUT
        else -> null
    }

private fun controlNavigation(key: Key): ChartNavigationKey? =
    when (key) {
        Key.DirectionUp -> ChartNavigationKey.ZOOM_IN
        Key.DirectionDown -> ChartNavigationKey.ZOOM_OUT
        else -> null
    }

private const val KEYBOARD_CSS_PIXELS_PER_NANOSECOND = 0.000002
private const val MAXIMUM_KEYBOARD_DELTA_CSS_PX = 500.0
private const val KEYBOARD_ZOOM_SPEED = 1.003
private const val SCROLL_LINE_SIZE_CSS_PX = 15f
