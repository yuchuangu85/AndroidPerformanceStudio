package com.androidperformancestudio.screencast.presentation

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import com.androidperformancestudio.screencast.protocol.ScrcpyControlWriter
import com.androidperformancestudio.screencast.server.ScreencastQuality
import com.androidperformancestudio.screencast.session.DecodedByteFrame
import com.androidperformancestudio.screencast.session.ScrcpySession
import com.androidperformancestudio.screencast.session.ScrcpySessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/** A selectable adb device shown in the screencast device dropdown. */
data class DeviceOption(
    val serial: String,
    val displayName: String,
)

/**
 * Presentation-side controller for the screencast page: owns the session,
 * converts decoded AWT frames into Compose image bitmaps, and exposes control
 * actions to the UI.
 */
class ScreencastPageController(
    private val sessionFactory: (String, ScreencastQuality) -> ScrcpySession,
    private val deviceLister: suspend () -> List<DeviceOption>,
    initialQuality: ScreencastQuality = ScreencastQuality.DEFAULT,
    decoderFallbackMessage: String? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Off-thread pixel conversion for incoming video frames. */
    private val frameScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _devices = MutableStateFlow<List<DeviceOption>>(emptyList())
    val devices: StateFlow<List<DeviceOption>>
        get() = _devices

    /** Last device-refresh failure message, or null; shown by the page. */
    private val _refreshError = MutableStateFlow<String?>(null)
    val refreshError: StateFlow<String?>
        get() = _refreshError

    private val _selectedSerial = MutableStateFlow<String?>(null)
    val selectedSerial: StateFlow<String?>
        get() = _selectedSerial

    /** User-selected streaming quality; switching restarts the session. */
    private val _quality = MutableStateFlow(initialQuality)
    val quality: StateFlow<ScreencastQuality>
        get() = _quality

    /** Non-null when the FFmpeg decoder fell back to jcodec on this host. */
    val decoderFallbackMessage: String? = decoderFallbackMessage

    private var session: ScrcpySession? = null

    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    val frame: StateFlow<ImageBitmap?>
        get() = _frame

    val state: StateFlow<ScrcpySessionState>
        get() = session?.state ?: initialIdleState

    /** Canvas size is written by the canvas composable for coordinate mapping. */
    var canvasSize: IntSize = IntSize.Zero

    private val frameLogCounter = java.util.concurrent.atomic.AtomicLong(0)

    private companion object {
        val initialIdleState = MutableStateFlow<ScrcpySessionState>(ScrcpySessionState.Idle)

        /** Log one converted-frame line every N frames (sampling to avoid spam). */
        const val FRAME_LOG_INTERVAL_FRAMES = 100L

        /** Number of stack-trace frames to print when frame conversion fails. */
        const val STACKTRACE_SUMMARY_LINES = 5
    }

    fun refreshDevices() {
        scope.launch {
            _refreshError.value = null
            runCatching { deviceLister() }
                .onSuccess { list ->
                    _devices.value = list
                    if (_selectedSerial.value == null || list.none { it.serial == _selectedSerial.value }) {
                        _selectedSerial.value = list.singleOrNull()?.serial
                    }
                }
                .onFailure { error ->
                    _refreshError.value = error.message ?: error.javaClass.simpleName
                }
        }
    }

    fun selectDevice(serial: String) {
        _selectedSerial.value = serial
    }

    /**
     * Switches the streaming quality. While a session is live this restarts
     * it with the new parameters (scrcpy encoder options are startup-only);
     * before a session it just records the selection.
     */
    fun selectQuality(quality: ScreencastQuality) {
        if (quality == _quality.value) return
        _quality.value = quality
        if (_selectedSerial.value != null && session?.isActive == true) {
            start()
        }
    }

    fun start() {
        val serial = _selectedSerial.value ?: return
        stop()
        val newSession = sessionFactory(serial, _quality.value)
        session = newSession
        // Convert frames off the UI thread and conflate: a slow decoder may
        // lag behind the encoder, and only the newest frame matters for a
        // live mirror.
        frameScope.launch {
            newSession.frames.conflate().collect { frame ->
                try {
                    val bitmap = frame.toComposeImageBitmap()
                    _frame.value = bitmap
                    val count = frameLogCounter.incrementAndGet()
                    if (count % FRAME_LOG_INTERVAL_FRAMES == 1L) {
                        println("[screencast-ui] frame converted: ${frame.width}x${frame.height}")
                    }
                } catch (error: Throwable) {
                    println("[screencast-ui] frame conversion FAILED: $error")
                    error.stackTrace.take(STACKTRACE_SUMMARY_LINES).forEach { println("[screencast-ui]   at $it") }
                }
            }
        }
        newSession.start()
    }

    fun stop() {
        session?.stop()
        session = null
        _frame.value = null
    }

    fun sendTouch(
        action: Int,
        x: Int,
        y: Int,
        pressure: Float,
        buttons: Int,
    ) {
        val running = state.value as? ScrcpySessionState.Running ?: return
        session?.sendTouch(
            action, x, y,
            screenWidth = running.width,
            screenHeight = running.height,
            pressure = pressure,
            buttons = buttons,
        )
    }

    fun sendScroll(
        x: Int,
        y: Int,
        scrollX: Float,
        scrollY: Float,
    ) {
        val running = state.value as? ScrcpySessionState.Running ?: return
        session?.sendScroll(
            x, y,
            screenWidth = running.width,
            screenHeight = running.height,
            scrollX = scrollX,
            scrollY = scrollY,
        )
    }

    fun tapNavigationKey(keycode: Int) {
        session?.tapKeycode(keycode)
    }

    fun pasteToDevice(text: String) {
        if (text.isNotBlank()) {
            session?.pasteText(text)
        }
    }

    /**
     * Whether the device's physical display is turned off (via scrcpy set-display-power). The stream
     * keeps flowing while the screen is dark. Toggled by [toggleScreenPower].
     */
    private val _screenOff = MutableStateFlow(false)
    val screenOff: StateFlow<Boolean>
        get() = _screenOff

    /**
     * Whether the device has been sent a POWER key (screen on → off, or off → on). Toggled by
     * [togglePowerKey]; reflects the last injected POWER press.
     */
    private val _powerKeyActive = MutableStateFlow(false)
    val powerKeyActive: StateFlow<Boolean>
        get() = _powerKeyActive

    /** Toggles the device display power on/off without sleeping the device (scrcpy control message). */
    fun toggleScreenPower() {
        // Only meaningful while a session is running; ignore otherwise.
        state.value as? ScrcpySessionState.Running ?: return
        val turnOn = _screenOff.value // currently off → turn back on
        session?.setDisplayPower(turnOn)
        _screenOff.value = !turnOn
    }

    /**
     * Injects an Android POWER key press (KEYCODE_POWER) into the device — equivalent to pressing
     * the device's physical power button. Toggles the screen between on and off (the device may
     * sleep when turned off, which can stall the scrcpy stream until it is woken again). Only acts
     * while a session is running.
     */
    fun togglePowerKey() {
        state.value as? ScrcpySessionState.Running ?: return
        session?.tapKeycode(ScrcpyControlWriter.KEYCODE_POWER)
        _powerKeyActive.value = !_powerKeyActive.value
    }

    fun dispose() {
        stop()
        scope.cancel()
        frameScope.cancel()
    }
}

/**
 * Converts a decoded frame into a Compose image bitmap. The BGRA bytes are
 * already in Skia N32 layout, so this is a single bulk copy into the Skia
 * pixel buffer — no per-pixel loop.
 */
public fun DecodedByteFrame.toComposeImageBitmap(): ImageBitmap {
    val bitmap = org.jetbrains.skia.Bitmap()
    val info = org.jetbrains.skia.ImageInfo.makeN32(width, height, org.jetbrains.skia.ColorAlphaType.PREMUL)
    bitmap.allocPixels(info)
    bitmap.installPixels(info, bgra, info.minRowBytes)
    return bitmap.asComposeImageBitmap()
}
