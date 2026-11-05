package com.androidperformancestudio.perfetto.uiserver

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.perfetto.TraceProcessorTool
import com.androidperformancestudio.platform.toolchain.HostProcessLaunchRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRunner
import com.androidperformancestudio.platform.toolchain.JvmHostProcessRunner
import com.androidperformancestudio.platform.toolchain.RunningHostProcess
import java.awt.Desktop
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Runs `trace_processor_shell server http` as a native process bound to 127.0.0.1:9001 and opens
 * the official Perfetto UI, which auto-detects the RPC endpoint. Unlike [PerfettoUiServer], which
 * only serves trace bytes to the browser-side WASM engine, this keeps the trace in native memory:
 * large traces beyond the browser's limits load through the WebSocket RPC bridge instead.
 *
 * Port 9001 is the only local origin ui.perfetto.dev's CSP allows, so the two servers are mutually
 * exclusive — the caller stops [PerfettoUiServer] before starting this one.
 */
class NativeTraceProcessorServer(
    private val processRunner: HostProcessRunner = JvmHostProcessRunner(),
    private val startupTimeout: Duration = 10.seconds,
    private val isReady: suspend (Int) -> Boolean = ::isNativeTraceProcessorReady,
) {
    private var process: RunningHostProcess? = null
    val isRunning: Boolean get() = process != null

    /**
     * Starts the native RPC server for [traceFile] and opens the official UI. A running server is
     * reused for the trace (the process already holds it), but the browser is re-opened on every
     * call so repeated clicks keep surfacing the UI; start failures stop the process first.
     */
    suspend fun start(
        tool: TraceProcessorTool,
        traceFile: Path,
    ): StudioResult<Unit> {
        if (process != null) return openOfficialUi()
        if (!Files.isRegularFile(traceFile)) {
            return StudioResult.Failure(
                StudioError(
                    category = ErrorCategory.IO,
                    code = "TRACE_FILE_NOT_FOUND",
                    message = "Trace file does not exist: $traceFile",
                ),
            )
        }
        val launched =
            try {
                processRunner.launch(
                    HostProcessLaunchRequest(
                        executable = tool.path,
                        arguments =
                            listOf(
                                "server",
                                "http",
                                "--ip-address",
                                LOOPBACK,
                                "--port",
                                DEFAULT_PORT.toString(),
                                "--idle-timeout",
                                IDLE_TIMEOUT.toString(),
                                traceFile.toString(),
                            ),
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return failure("TRACE_PROCESSOR_START_FAILED", error.message ?: "Trace processor could not start", error)
            }
        process = launched
        val deadline = System.nanoTime() + startupTimeout.inWholeNanoseconds
        while (System.nanoTime() < deadline && launched.isAlive) {
            if (isReady(DEFAULT_PORT)) return openOfficialUi()
            delay(READY_POLL_INTERVAL_MILLIS)
        }
        stop()
        return failure(
            "TRACE_PROCESSOR_NOT_READY",
            "Trace processor did not become ready on port $DEFAULT_PORT within $startupTimeout",
        )
    }

    private fun openOfficialUi(): StudioResult<Unit> =
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                stop()
                StudioResult.Failure(
                    StudioError(
                        category = ErrorCategory.CONFIGURATION,
                        code = "BROWSER_NOT_SUPPORTED",
                        message = "No system browser is available to open the Perfetto UI",
                    ),
                )
            } else {
                // No `?url=`: the UI probes 127.0.0.1:9001 itself and offers the native
                // accelerator over WebSocket, keeping the trace in the native process.
                Desktop.getDesktop().browse(URI.create(OFFICIAL_UI_URL))
                StudioResult.Success(Unit)
            }
        } catch (exception: Exception) {
            stop()
            StudioResult.Failure(
                StudioError(
                    category = ErrorCategory.IO,
                    code = "BROWSER_OPEN_FAILED",
                    message = exception.message ?: "Failed to open the Perfetto UI",
                    cause = exception,
                ),
            )
        }

    /** Stops the native process and releases port [DEFAULT_PORT]. Safe to call when not running. */
    fun stop() {
        process?.terminate()
        process = null
    }

    private fun failure(
        code: String,
        message: String,
        cause: Throwable? = null,
    ): StudioResult<Unit> =
        StudioResult.Failure(StudioError(category = ErrorCategory.PROCESS_START, code = code, message = message, cause = cause))

    companion object {
        /** The only port ui.perfetto.dev's CSP allows fetching/probing from. */
        const val DEFAULT_PORT = 9001
        const val OFFICIAL_UI_URL = "https://ui.perfetto.dev"
        private const val LOOPBACK = "127.0.0.1"
        private val IDLE_TIMEOUT: Duration = 30.minutes
        private const val READY_POLL_INTERVAL_MILLIS = 50L

        /** Mirrors the readiness probe used for the diagnostics trace processor context. */
        private suspend fun isNativeTraceProcessorReady(port: Int): Boolean =
            runCatching {
                val connection = URI.create("http://$LOOPBACK:$port/status").toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 150
                connection.readTimeout = 150
                connection.responseCode == HttpURLConnection.HTTP_OK
            }.getOrDefault(false)
    }
}
