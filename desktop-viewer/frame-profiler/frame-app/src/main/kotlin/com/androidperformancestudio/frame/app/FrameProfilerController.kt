@file:Suppress("MaxLineLength", "ReturnCount", "TooGenericExceptionCaught", "TooManyFunctions")

package com.androidperformancestudio.frame.app

import com.androidperformancestudio.frame.analysis.FrameAnalysisResult
import com.androidperformancestudio.frame.analysis.FrameJankAnalyzer
import com.androidperformancestudio.frame.export.FrameCsvExporter
import com.androidperformancestudio.frame.model.FrameCaptureSession
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import com.androidperformancestudio.frame.parser.GfxInfoFrameStatsParser
import com.androidperformancestudio.frame.presentation.FrameProfilerState
import com.androidperformancestudio.frame.storage.SqliteFrameSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

internal class FrameProfilerController(
    private val onlineBackend: FrameOnlineBackend = DesktopFrameOnlineBackend(),
    private val parser: GfxInfoFrameStatsParser = GfxInfoFrameStatsParser(),
    private val analyzer: FrameJankAnalyzer = FrameJankAnalyzer(),
    private val exporter: FrameCsvExporter = FrameCsvExporter(),
    private val databaseFile: Path = defaultDatabaseFile(),
) {
    private val mutableState = MutableStateFlow(FrameProfilerState())
    private val onlineFrames = mutableListOf<FrameSample>()
    private var activeCapture: OnlineFrameCapture? = null

    val state: StateFlow<FrameProfilerState> = mutableState.asStateFlow()

    suspend fun refreshDevices() {
        if (mutableState.value.isCapturing) return
        mutableState.value = mutableState.value.copy(isRefreshingDevices = true, errorMessage = null)
        when (val result = onlineBackend.listDevices()) {
            is FrameBackendResult.Failure ->
                mutableState.value =
                    mutableState.value.copy(
                        isRefreshingDevices = false,
                        errorMessage = result.message,
                    )
            is FrameBackendResult.Success -> {
                val selected =
                    mutableState.value.selectedDeviceSerial?.takeIf { serial ->
                        result.value.any { it.serial == serial && it.online }
                    }
                mutableState.value =
                    mutableState.value.copy(
                        devices = result.value,
                        selectedDeviceSerial = selected,
                        processes = if (selected == null) emptyList() else mutableState.value.processes,
                        selectedProcessId = if (selected == null) null else mutableState.value.selectedProcessId,
                        isRefreshingDevices = false,
                        errorMessage = null,
                    )
            }
        }
    }

    suspend fun selectDevice(serial: String) {
        if (mutableState.value.isCapturing) return
        mutableState.value =
            mutableState.value.copy(
                selectedDeviceSerial = serial,
                selectedProcessId = null,
                processes = emptyList(),
                isRefreshingDevices = true,
                errorMessage = null,
            )
        when (val result = onlineBackend.listProcesses(serial)) {
            is FrameBackendResult.Failure ->
                mutableState.value =
                    mutableState.value.copy(
                        isRefreshingDevices = false,
                        errorMessage = result.message,
                    )
            is FrameBackendResult.Success ->
                mutableState.value =
                    mutableState.value.copy(
                        processes = result.value,
                        selectedProcessId = result.value.singleOrNull()?.pid,
                        isRefreshingDevices = false,
                        errorMessage = null,
                    )
        }
    }

    fun selectProcess(pid: Int) {
        if (!mutableState.value.isCapturing && mutableState.value.processes.any { it.pid == pid }) {
            mutableState.value = mutableState.value.copy(selectedProcessId = pid, errorMessage = null)
        }
    }

    suspend fun startOnlineCapture() {
        val snapshot = mutableState.value
        if (snapshot.isCapturing) return
        val serial = snapshot.selectedDeviceSerial ?: return
        val process = snapshot.processes.firstOrNull { it.pid == snapshot.selectedProcessId } ?: return
        when (val opened = onlineBackend.openCapture(serial, process, UUID.randomUUID().toString())) {
            is FrameBackendResult.Failure -> {
                mutableState.value = snapshot.copy(errorMessage = opened.message)
            }
            is FrameBackendResult.Success -> {
                val capture = opened.value
                val startWarnings =
                    try {
                        capture.start()
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        mutableState.value = snapshot.copy(errorMessage = exception.message ?: "Unable to start capture.")
                        return
                    }
                onlineFrames.clear()
                activeCapture = capture
                mutableState.value =
                    snapshot.copy(
                        importedFileName = null,
                        analysis = null,
                        selectedFrameId = null,
                        isCapturing = true,
                        operationMessage = "Capturing ${process.packageName} via ${capture.metadata.source.captureLabel()}…",
                        warnings = startWarnings,
                        errorMessage = null,
                    )
            }
        }
    }

    suspend fun pollOnlineCapture() {
        val capture = activeCapture ?: return
        if (!mutableState.value.isCapturing) return
        try {
            val batch = capture.poll()
            if (batch.frames.isNotEmpty()) onlineFrames += batch.frames
            val analysis = onlineFrames.takeIf(List<FrameSample>::isNotEmpty)?.let(analyzer::analyze)
            mutableState.value =
                mutableState.value.copy(
                    analysis = analysis,
                    selectedFrameId =
                        mutableState.value.selectedFrameId ?: analysis
                            ?.frames
                            ?.lastOrNull()
                            ?.sample
                            ?.frameId,
                    operationMessage =
                        "Capturing ${capture.metadata.packageName} via ${capture.metadata.source.captureLabel()}: " +
                            "${onlineFrames.size} frames",
                    warnings = (mutableState.value.warnings + batch.warnings).distinct(),
                )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            stopOnlineCapture(exception.message ?: "Online frame capture failed.")
        }
    }

    suspend fun stopOnlineCapture(errorMessage: String? = null) {
        val capture = activeCapture
        activeCapture = null
        val stopWarnings =
            if (capture == null) {
                emptyList()
            } else {
                try {
                    capture.stop()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    listOf("Unable to close online frame capture cleanly: ${exception.message}")
                }
            }
        mutableState.value =
            mutableState.value.copy(
                isCapturing = false,
                operationMessage =
                    if (onlineFrames.isEmpty()) {
                        "Capture stopped without receiving frames."
                    } else {
                        "Capture stopped: ${onlineFrames.size} frames."
                    },
                warnings = (mutableState.value.warnings + stopWarnings).distinct(),
                errorMessage = errorMessage,
            )
        if (capture != null && onlineFrames.isNotEmpty()) {
            persistenceWarning(capture.metadata, onlineFrames)?.let { warning ->
                mutableState.value = mutableState.value.copy(warnings = (mutableState.value.warnings + warning).distinct())
            }
        }
    }

    suspend fun importFrameStats(file: Path) {
        if (mutableState.value.isCapturing) return
        mutableState.value = mutableState.value.copy(isLoading = true, errorMessage = null, operationMessage = null)
        runCatching {
            withContext(Dispatchers.IO) {
                val sessionId = UUID.randomUUID().toString()
                val parsed = parser.parse(Files.readString(file), sessionId)
                require(parsed.frames.isNotEmpty()) { "No usable frame rows were found in ${file.fileName}." }
                val analysis = analyzer.analyze(parsed.frames)
                val session =
                    FrameCaptureSession(
                        id = sessionId,
                        source = FrameSource.GFXINFO,
                        startedAt = Instant.now(),
                        importedFile = file.toAbsolutePath().toString(),
                    )
                LoadedFrameStats(
                    analysis = analysis,
                    warnings = parsed.warnings + listOfNotNull(persistenceWarning(session, parsed.frames)),
                )
            }
        }.onSuccess { loaded ->
            mutableState.value =
                mutableState.value.copy(
                    importedFileName = file.fileName.toString(),
                    analysis = loaded.analysis,
                    selectedFrameId =
                        loaded.analysis.frames
                            .firstOrNull()
                            ?.sample
                            ?.frameId,
                    isLoading = false,
                    operationMessage = "Imported ${loaded.analysis.summary.totalFrames} frames.",
                    warnings = loaded.warnings,
                    errorMessage = null,
                )
        }.onFailure { error ->
            mutableState.value =
                mutableState.value.copy(
                    isLoading = false,
                    errorMessage = error.message ?: "FrameStats import failed.",
                )
        }
    }

    fun selectFrame(frameId: Long) {
        mutableState.value = mutableState.value.copy(selectedFrameId = frameId)
    }

    suspend fun exportCsv(output: Path) {
        val analysis = mutableState.value.analysis ?: return
        runCatching { withContext(Dispatchers.IO) { exporter.export(analysis, output) } }
            .onSuccess {
                mutableState.value = mutableState.value.copy(operationMessage = "Exported ${output.fileName}.", errorMessage = null)
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(errorMessage = error.message ?: "CSV export failed.")
            }
    }

    private suspend fun persistenceWarning(
        session: FrameCaptureSession,
        frames: List<FrameSample>,
    ): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                SqliteFrameSessionStore.open(databaseFile).use { it.save(session, frames) }
            }.exceptionOrNull()?.let { "Analysis succeeded, but the session database could not be updated: ${it.message}" }
        }

    private data class LoadedFrameStats(
        val analysis: FrameAnalysisResult,
        val warnings: List<String>,
    )

    private companion object {
        fun defaultDatabaseFile(): Path =
            Path.of(System.getProperty("user.home"), ".android-performance-studio", "frame-profiler", "frames.db")
    }
}

private fun FrameSource.captureLabel(): String =
    when (this) {
        FrameSource.FRAME_METRICS -> "FrameMetrics Agent"
        FrameSource.GFXINFO -> "gfxinfo"
        FrameSource.JANK_STATS -> "JankStats Agent"
        FrameSource.PERFETTO -> "Perfetto"
    }
