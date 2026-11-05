package com.androidperformancestudio.screencast.session

import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.screencast.protocol.ScrcpyControlWriter
import com.androidperformancestudio.screencast.protocol.ScrcpyDeviceMessageReader
import com.androidperformancestudio.screencast.protocol.ScrcpyVideoStreamReader
import com.androidperformancestudio.screencast.server.ScrcpyServerLauncher
import com.androidperformancestudio.screencast.server.ScreencastQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One device screencast session: owns the reverse tunnel, the scrcpy server
 * process, the video/control sockets, decoding, and control injection.
 *
 * State transitions:
 * ```
 * Idle -> Connecting -> Running -> Disconnected
 *                  \-> Failed
 * ```
 * Starting a second session while one is connecting/running is a no-op
 * (idempotent). [stop] and error paths both funnel into [finish], which
 * always cleans up the sockets and the device-side tunnel/jar.
 */
@Suppress("TooManyFunctions")
class ScrcpySession(
    private val adb: AdbClient,
    private val serial: String,
    private val quality: ScreencastQuality = ScreencastQuality.DEFAULT,
    private val launcherFactory: (AdbClient, String, ScreencastQuality) -> ScrcpyServerLauncher =
        { client, deviceSerial, sessionQuality -> ScrcpyServerLauncher(client, deviceSerial, sessionQuality) },
    private val frameDecoder: ScreencastFrameDecoder =
        H264FrameDecoder(DECODER_MAX_WIDTH, DECODER_MAX_HEIGHT),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<ScrcpySessionState>(ScrcpySessionState.Idle)
    val state: StateFlow<ScrcpySessionState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<DecodedByteFrame>(extraBufferCapacity = 4)
    val frames: SharedFlow<DecodedByteFrame> = _frames.asSharedFlow()

    private val startGuard = AtomicBoolean(false)
    private var controlWriter: ScrcpyControlWriter? = null
    private var videoSocket: Socket? = null
    private var controlSocket: Socket? = null
    private var listener: ServerSocket? = null
    private var launchedServer: ScrcpyServerLauncher.LaunchedServer? = null
    private var launcher: ScrcpyServerLauncher? = null
    private val decoder: ScreencastFrameDecoder = frameDecoder

    val isActive: Boolean
        get() = _state.value is ScrcpySessionState.Connecting || _state.value is ScrcpySessionState.Running

    /** Starts mirroring. Idempotent: does nothing when already connecting/running. */
    fun start() {
        if (isActive) {
            return
        }
        if (!startGuard.compareAndSet(false, true)) {
            return
        }
        scope.launch {
            try {
                runSession()
            } finally {
                startGuard.set(false)
            }
        }
    }

    /** Stops the session and cleans up device and host resources. */
    fun stop() {
        finish(ScrcpySessionState.Idle)
    }

    // Control APIs ----------------------------------------------------------------

    @Suppress("LongParameterList")
    fun sendTouch(
        action: Int,
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        pressure: Float = 1f,
        actionButton: Int = 0,
        buttons: Int = 0,
    ) {
        writeControl {
            it.injectTouch(action, POINTER_ID_MOUSE, x, y, screenWidth, screenHeight, pressure, actionButton, buttons)
        }
    }

    @Suppress("LongParameterList")
    fun sendScroll(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        scrollX: Float,
        scrollY: Float,
    ) {
        writeControl { it.injectScroll(x, y, screenWidth, screenHeight, scrollX, scrollY, buttons = 0) }
    }

    fun sendKeycode(action: Int, keycode: Int) {
        writeControl { it.injectKeycode(action, keycode) }
    }

    fun tapKeycode(keycode: Int) {
        writeControl { it.tapKeycode(keycode) }
    }

    fun back() = tapKeycode(ScrcpyControlWriter.KEYCODE_BACK)

    fun home() = tapKeycode(ScrcpyControlWriter.KEYCODE_HOME)

    fun recents() = tapKeycode(ScrcpyControlWriter.KEYCODE_APP_SWITCH)

    fun pasteText(text: String) {
        if (text.isEmpty()) return
        writeControl { it.setClipboard(text, paste = true) }
    }

    fun rotateDevice() {
        writeControl { it.rotateDevice() }
    }

    /** Turns the device display on/off without sleeping the device; the video stream keeps flowing. */
    fun setDisplayPower(on: Boolean) {
        writeControl { it.setDisplayPower(on) }
    }

    // Internals -------------------------------------------------------------------

    @Suppress("ThrowsCount")
    private suspend fun runSession() {
        _state.value = ScrcpySessionState.Connecting
        val sessionLauncher = launcherFactory(adb, serial, quality)
        launcher = sessionLauncher
        try {
            val server = withContext(Dispatchers.IO) {
                val serverSocket = ServerSocket(0)
                serverSocket.soTimeout = ACCEPT_TIMEOUT_MS
                listener = serverSocket
                try {
                    sessionLauncher.launch(serverSocket.localPort)
                } catch (error: Throwable) {
                    runCatching { serverSocket.close() }
                    listener = null
                    throw error
                }
            }
            launchedServer = server

            val video = acceptOrThrow()
            val control = acceptOrThrow()
            videoSocket = video
            controlSocket = control
            // Room for the decoder to trail the encoder briefly without the
            // kernel dropping the connection.
            runCatching { video.tcpNoDelay = true }
            runCatching { video.receiveBufferSize = SOCKET_BUFFER_SIZE }

            val reader = ScrcpyVideoStreamReader(video.getInputStream())
            val deviceName = reader.readDeviceName()
            val metadata = reader.readVideoMetadata()
            if (metadata.codecId != ScrcpyVideoStreamReader.CODEC_H264) {
                throw IOException("Unsupported video codec from server: ${metadata.codecName}")
            }

            controlWriter = ScrcpyControlWriter(control.getOutputStream())

            _state.value = ScrcpySessionState.Running(deviceName, metadata.width, metadata.height)

            val controlReadJob = scope.launch { drainDeviceMessages(control) }
            try {
                decodeLoop(reader)
            } finally {
                controlReadJob.cancel()
            }
            // The server closed the video stream: normal end of session.
            finish(ScrcpySessionState.Disconnected)
        } catch (error: CancellationException) {
            finish(ScrcpySessionState.Idle)
            throw error
        } catch (error: Exception) {
            if (_state.value is ScrcpySessionState.Running) {
                // A mid-session stream failure reads as a disconnect; log the
                // reason so field issues are diagnosable from the app log.
                println("[screencast] session ended: ${error.javaClass.simpleName}: ${error.message}")
                finish(ScrcpySessionState.Disconnected)
            } else {
                _state.value = ScrcpySessionState.Failed(error.message ?: "Screencast failed")
                finish(ScrcpySessionState.Failed(error.message ?: "Screencast failed"), preserveState = true)
            }
        }
    }

    private fun acceptOrThrow(): Socket {
        val activeListener = listener ?: throw IOException("Screencast listener is closed")
        try {
            return activeListener.accept()
        } catch (error: SocketTimeoutException) {
            throw IOException("Timed out waiting for the scrcpy server to connect back", error)
        }
    }

    private suspend fun decodeLoop(reader: ScrcpyVideoStreamReader) {
        while (true) {
            val packet = reader.readPacket() ?: return
            if (packet.isConfig) {
                decoder.configure(packet.payload)
                continue
            }
            if (_state.value !is ScrcpySessionState.Running) {
                return
            }
            val frame = decoder.decode(packet.payload)
            if (frame != null) {
                _frames.emit(frame)
            }
        }
    }

    @Suppress("SwallowedException")
    private suspend fun drainDeviceMessages(control: Socket) {
        val reader = ScrcpyDeviceMessageReader(control.getInputStream())
        while (true) {
            try {
                reader.readMessage() ?: return
            } catch (error: IOException) {
                // Control socket failures surface through the video loop.
                return
            }
        }
    }

    @Suppress("SwallowedException")
    private fun writeControl(block: (ScrcpyControlWriter) -> Unit) {
        val writer = controlWriter ?: return
        try {
            block(writer)
        } catch (error: IOException) {
            finish(ScrcpySessionState.Disconnected)
        }
    }

    /**
     * Closes host resources and rolls back device resources, then moves to
     * [endState]. When [preserveState] is set the current state (e.g. Failed)
     * is kept so the failure reason remains visible.
     */
    private fun finish(
        endState: ScrcpySessionState,
        preserveState: Boolean = false,
    ) {
        runCatching { controlWriter?.close() }
        runCatching { videoSocket?.close() }
        runCatching { controlSocket?.close() }
        runCatching { listener?.close() }
        controlWriter = null
        videoSocket = null
        controlSocket = null
        listener = null
        decoder.reset()

        val sessionLauncher = launcher
        val launched = launchedServer
        launcher = null
        launchedServer = null

        if (sessionLauncher != null && launched != null) {
            // Run cleanup outside the session scope: cancelling the scope would
            // also cancel the pending cleanup coroutine.
            val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            cleanupScope.launch {
                // NonCancellable: the reverse tunnel and device-side jar must
                // be cleaned up even if the cleanup itself gets raced/cancelled.
                runCatching { withContext(NonCancellable + Dispatchers.IO) { sessionLauncher.cleanup(launched) } }
                if (!preserveState) {
                    _state.value = endState
                }
                scope.cancel()
                cleanupScope.cancel()
            }
        } else {
            if (!preserveState) {
                _state.value = endState
            }
            scope.cancel()
        }
    }

    companion object {
        const val POINTER_ID_MOUSE = -1L
        const val ACCEPT_TIMEOUT_MS = 15_000

        /** Receive-buffer headroom for the video socket (2 MiB). */
        const val SOCKET_BUFFER_SIZE = 2 shl 20

        /** Upper bound for the jcodec plane buffers; sized for 4K screens. */
        const val DECODER_MAX_WIDTH = 3840
        const val DECODER_MAX_HEIGHT = 2160
    }
}
