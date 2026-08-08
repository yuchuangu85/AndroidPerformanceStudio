package com.androidperformancestudio.methodrecording.app

import com.androidperformancestudio.arttrace.ArtTraceCallStackProjector
import com.androidperformancestudio.arttrace.ArtTraceFlameGraphBuilder
import com.androidperformancestudio.arttrace.ArtTraceParseResult
import com.androidperformancestudio.arttrace.ArtTraceParser
import com.androidperformancestudio.arttrace.MethodTopMethodsReducer
import com.androidperformancestudio.methodcapture.MethodRecordingDeviceGateway
import com.androidperformancestudio.methodcapture.MethodTraceCaptureRequest
import com.androidperformancestudio.methodcapture.MethodTraceCaptureSession
import com.androidperformancestudio.methodrecording.app.generated.resources.Res
import com.androidperformancestudio.methodrecording.app.generated.resources.adb_not_found
import com.androidperformancestudio.methodrecording.app.generated.resources.capture_failed
import com.androidperformancestudio.methodrecording.app.generated.resources.trace_label
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.profileanalysis.CallStackTable
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the method-recording workspace: device/process discovery, live `am profile` capture, and
 * `.trace` import. The parsed trace is projected once into a [CallStackTable] from which the
 * top-methods table and flame graph are derived and exposed through [state].
 */
class MethodRecordingController(
    adbExecutable: Path?,
    private val sessionRoot: Path = defaultSessionRoot(),
    private val language: UiLanguage = UiLanguage.ENGLISH,
) {
    private val captureSession = adbExecutable?.let { MethodTraceCaptureSession(it) }
    private val gateway = adbExecutable?.let { MethodRecordingDeviceGateway(it) }
    private val mutableState = MutableStateFlow(MethodRecordingState())
    val state: StateFlow<MethodRecordingState> = mutableState.asStateFlow()

    private var callStackTable: CallStackTable? = null

    suspend fun refreshDevices() {
        val gw = gateway
        if (gw == null) {
            mutableState.value = mutableState.value.copy(error = localizedStringResource(Res.string.adb_not_found, language))
            return
        }
        when (val result = gw.refreshDevices()) {
            is StudioResult.Failure -> mutableState.value = mutableState.value.copy(error = result.error.message)
            is StudioResult.Success -> {
                val current =
                    mutableState.value.selectedSerial
                        ?.takeIf { serial -> result.value.any { it.serial == serial && it.online } }
                mutableState.value =
                    mutableState.value.copy(
                        devices = result.value,
                        selectedSerial = current,
                        processes = emptyList(),
                        selectedPid = null,
                        error = null,
                    )
                val automatic = current ?: result.value.filter { it.online }.singleOrNull()?.serial
                if (automatic != null && automatic != current) {
                    selectDevice(automatic)
                }
            }
        }
    }

    suspend fun selectDevice(serial: String) {
        val gw = gateway ?: return
        mutableState.value =
            mutableState.value.copy(
                selectedSerial = serial,
                selectedPid = null,
                processes = emptyList(),
                error = null,
            )
        when (val result = gw.loadProcesses(serial)) {
            is StudioResult.Failure -> mutableState.value = mutableState.value.copy(error = result.error.message)
            is StudioResult.Success -> mutableState.value = mutableState.value.copy(processes = result.value)
        }
    }

    fun selectProcess(pid: Int) {
        if (mutableState.value.processes.any { it.pid == pid }) {
            mutableState.value = mutableState.value.copy(selectedPid = pid)
        }
    }

    suspend fun importTrace(path: Path) {
        mutableState.value = mutableState.value.copy(isLoading = true, error = null)
        when (val result = ArtTraceParser.parse(path)) {
            is ArtTraceParseResult.Failure ->
                mutableState.value = mutableState.value.copy(isLoading = false, error = result.message)
            is ArtTraceParseResult.Success -> {
                val analysis = result.analysis
                val table = ArtTraceCallStackProjector.toCallStackTable(analysis)
                callStackTable = table
                mutableState.value =
                    mutableState.value.copy(
                        isLoading = false,
                        analysis = analysis,
                        flameGraph = ArtTraceFlameGraphBuilder.build(table),
                        topMethods = MethodTopMethodsReducer.topMethods(table, analysis),
                        traceLabel =
                            localizedStringResource(
                                Res.string.trace_label,
                                language,
                                path.fileName.toString(),
                                formatBytes(Files.size(path)),
                            ),
                        capturePhase = MethodTraceCapturePhase.Completed(path),
                        error = null,
                    )
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun startCapture() {
        val session = captureSession ?: return
        val snapshot = mutableState.value
        val serial = snapshot.selectedSerial ?: return
        val process = snapshot.processes.firstOrNull { it.pid == snapshot.selectedPid } ?: return
        mutableState.value = snapshot.copy(capturePhase = MethodTraceCapturePhase.Recording, error = null)
        val result =
            try {
                session.capture(
                    MethodTraceCaptureRequest(
                        sessionId = SESSION_ID_FORMAT.format(Instant.now()),
                        sessionRoot = sessionRoot,
                        serial = serial,
                        pid = process.pid,
                        packageName = process.packageName,
                    ),
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                StudioResult.Failure(
                    StudioError(
                        category = ErrorCategory.UNKNOWN,
                        code = "METHOD_TRACE_EXCEPTION",
                        message = exception.message ?: exception::class.simpleName.orEmpty(),
                    ),
                )
            }
        when (result) {
            is StudioResult.Failure -> {
                val message =
                    "${localizedStringResource(Res.string.capture_failed, language)}: ${result.error.message}"
                mutableState.value =
                    mutableState.value.copy(capturePhase = MethodTraceCapturePhase.Failed(message), error = message)
            }
            is StudioResult.Success -> {
                mutableState.value = mutableState.value.copy(isLoading = true)
                importTrace(result.value.traceFile)
            }
        }
    }

    fun requestStop() {
        captureSession?.requestStop()
    }

    private fun formatBytes(bytes: Long): String =
        when {
            bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
            bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
            bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
            else -> "$bytes B"
        }

    private companion object {
        private fun defaultSessionRoot(): Path =
            Path.of(
                System.getProperty("user.home"),
                ".android-performance-studio",
                "method-recording",
                "sessions",
            )

        private val SESSION_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)
    }
}
