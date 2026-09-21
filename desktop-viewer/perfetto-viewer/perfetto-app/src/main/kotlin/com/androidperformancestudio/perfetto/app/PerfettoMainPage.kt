package com.androidperformancestudio.perfetto.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.adb.AndroidTargetMonitors
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.perfetto.analysis.DiagnosticQuery
import com.androidperformancestudio.perfetto.analysis.DiagnosticResult
import com.androidperformancestudio.perfetto.analysis.PerfettoDiagnostics
import com.androidperformancestudio.perfetto.capture.PerfettoCapabilityDetector
import com.androidperformancestudio.perfetto.capture.PerfettoCaptureSession
import com.androidperformancestudio.perfetto.export.TraceExporter
import com.androidperformancestudio.perfetto.model.PerfettoArtifactFactory
import com.androidperformancestudio.perfetto.model.PerfettoCaptureConfig
import com.androidperformancestudio.perfetto.model.PerfettoCaptureState
import com.androidperformancestudio.perfetto.model.PerfettoDevice
import com.androidperformancestudio.perfetto.model.PerfettoDeviceCapabilities
import com.androidperformancestudio.perfetto.model.PerfettoTraceTemplate
import com.androidperformancestudio.perfetto.model.TraceSession
import com.androidperformancestudio.perfetto.presentation.PerfettoCapturePage
import com.androidperformancestudio.perfetto.presentation.PerfettoCompactButton
import com.androidperformancestudio.perfetto.presentation.PerfettoCompactTextField
import com.androidperformancestudio.perfetto.presentation.PerfettoStatusDot
import com.androidperformancestudio.perfetto.presentation.PerfettoWorkspacePanel
import com.androidperformancestudio.perfetto.storage.TraceSessionStore
import com.androidperformancestudio.perfetto.uiserver.PerfettoUiServer
import com.androidperformancestudio.perfetto_app.generated.resources.Res
import com.androidperformancestudio.perfetto_app.generated.resources.adb
import com.androidperformancestudio.perfetto_app.generated.resources.adb_path
import com.androidperformancestudio.perfetto_app.generated.resources.captured_traces_will_appear_here
import com.androidperformancestudio.perfetto_app.generated.resources.delete
import com.androidperformancestudio.perfetto_app.generated.resources.device_connected
import com.androidperformancestudio.perfetto_app.generated.resources.device_refresh_failed
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_art_allocation_churn_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_art_allocation_churn_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_art_class_loading_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_art_class_loading_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_art_class_verification_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_art_class_verification_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_art_gc_events_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_art_gc_events_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_art_jit_dex2oat_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_art_jit_dex2oat_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_binder_frame_alignment_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_binder_frame_alignment_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_binder_ipc_long_tail_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_binder_ipc_long_tail_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_binder_latency_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_binder_latency_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_binder_system_server_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_binder_system_server_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_block_io_pressure_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_block_io_pressure_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_cpu_frequency_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_cpu_frequency_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_cpu_hotspots_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_cpu_hotspots_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_dvfs_residency_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_dvfs_residency_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_file_io_syscalls_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_file_io_syscalls_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_frame_jank_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_frame_jank_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_fsync_calls_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_fsync_calls_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_gpu_frequency_residency_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_gpu_frequency_residency_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_input_latency_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_input_latency_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_main_thread_blocked_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_main_thread_blocked_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_memory_timeline_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_memory_timeline_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_page_faults_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_page_faults_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_power_frame_alignment_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_power_frame_alignment_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_power_rails_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_power_rails_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_run_queue_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_run_queue_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_sched_switch_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_sched_switch_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_sched_waking_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_sched_waking_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_sqlite_activity_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_sqlite_activity_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_sqlite_lock_wait_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_sqlite_lock_wait_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_thermal_throttling_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_thermal_throttling_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_thread_states_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_thread_states_title
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_wakeup_latency_description
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_wakeup_latency_title
import com.androidperformancestudio.perfetto_app.generated.resources.export
import com.androidperformancestudio.perfetto_app.generated.resources.export_trace
import com.androidperformancestudio.perfetto_app.generated.resources.failed_to_export_trace
import com.androidperformancestudio.perfetto_app.generated.resources.mb_n
import com.androidperformancestudio.perfetto_app.generated.resources.no_online_device
import com.androidperformancestudio.perfetto_app.generated.resources.open
import com.androidperformancestudio.perfetto_app.generated.resources.open_perfetto_trace
import com.androidperformancestudio.perfetto_app.generated.resources.perfetto_traces
import com.androidperformancestudio.perfetto_app.generated.resources.recent_sessions
import com.androidperformancestudio.perfetto_app.generated.resources.refresh
import com.androidperformancestudio.perfetto_app.generated.resources.running_diagnostic
import com.androidperformancestudio.perfetto_app.generated.resources.select_a_diagnostic_on_the_left_to_view_its_result
import com.androidperformancestudio.perfetto_app.generated.resources.select_device
import com.androidperformancestudio.perfetto_app.generated.resources.text
import com.androidperformancestudio.perfetto_app.generated.resources.trace_diagnostics
import com.androidperformancestudio.platform.adb.AdbCommandFailedException
import com.androidperformancestudio.platform.adb.AdbCommandTimeoutException
import com.androidperformancestudio.platform.adb.AdbDevice
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.platform.adb.AdbException
import com.androidperformancestudio.platform.adb.AdbProcessStartException
import com.androidperformancestudio.platform.perfetto.TraceAnalysisContext
import com.androidperformancestudio.platform.perfetto.TraceAnalysisContexts
import com.androidperformancestudio.platform.perfetto.TraceProcessorToolResolver
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.HeaderSpacer
import com.androidperformancestudio.ui.HeaderToolbar
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.chooseOpenFile
import com.androidperformancestudio.ui.chooseSaveFile
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.fileSize

internal fun formatRecentSessionTimestamp(capturedAt: Instant): String = capturedAt.toString().replace("T", "-T")

internal fun exportRawTraceFile(
    traceFile: Path,
    destination: Path,
): StudioResult<Path> =
    try {
        java.nio.file.Files.copy(
            traceFile,
            destination,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        StudioResult.Success(destination)
    } catch (exception: IOException) {
        StudioResult.Failure(
            StudioError(
                category = ErrorCategory.IO,
                code = "TRACE_EXPORT_FAILED",
                message = exception.message ?: "Failed to export trace",
                cause = exception,
            ),
        )
    }

@Composable
@Suppress("ktlint:standard:function-naming")
fun FrameWindowScope.PerfettoMainPage(
    language: UiLanguage = UiLanguage.ENGLISH,
    isActive: Boolean = true,
    androidSdkPath: Path? = null,
    onNavigateHome: (() -> Unit)? = null,
    onOpenUserGuide: (() -> Unit)? = null,
    initialTraceFile: Path? = null,
    initialTraceNotice: String? = null,
    initialTraceTimestampNanos: Long? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    val captureSession = remember { PerfettoCaptureSession() }
    val capabilityDetector = remember { PerfettoCapabilityDetector() }
    val sessionStore = remember { TraceSessionStore() }
    val uiServer = remember { PerfettoUiServer() }
    val exporter = remember { TraceExporter() }
    val artifactFactory = remember { PerfettoArtifactFactory() }
    var captureState by remember { mutableStateOf<PerfettoCaptureState>(PerfettoCaptureState.Idle) }
    var sessions by remember { mutableStateOf<List<TraceSession>>(emptyList()) }
    var recentFiles by remember { mutableStateOf<List<Path>>(emptyList()) }
    var activeTraceFile by remember { mutableStateOf<Path?>(null) }
    var activeArtifact by remember { mutableStateOf<com.androidperformancestudio.contracts.CaptureArtifact?>(null) }
    var adbPath by remember(androidSdkPath) { mutableStateOf(defaultPerfettoAdbPath(androidSdkPath)) }
    var devices by remember { mutableStateOf<List<PerfettoDevice>>(emptyList()) }
    var selectedDeviceSerial by remember { mutableStateOf<String?>(null) }
    var deviceCapabilities by remember { mutableStateOf<PerfettoDeviceCapabilities?>(null) }
    var capabilityRefreshKey by remember { mutableStateOf(0) }
    var deviceRefreshInProgress by remember { mutableStateOf(false) }
    var deviceRefreshError by remember { mutableStateOf<StudioError?>(null) }
    var deviceRefreshRequest by remember { mutableStateOf(0L) }
    var analysisContext by remember { mutableStateOf<TraceAnalysisContext?>(null) }
    var analysisContexts by remember { mutableStateOf<TraceAnalysisContexts?>(null) }
    var diagnosticQuery by remember { mutableStateOf<DiagnosticQuery?>(null) }
    var diagnosticResult by remember { mutableStateOf<String?>(null) }
    var diagnosticError by remember { mutableStateOf<String?>(null) }
    var fileDialogOpen by remember { mutableStateOf(false) }
    var traceOpenJob by remember { mutableStateOf<Job?>(null) }
    val traceOpenMutex = remember { Mutex() }
    val openTrace: (Path, com.androidperformancestudio.contracts.CaptureArtifact?, Long?) -> Unit = { traceFile, artifact, timestamp ->
        traceOpenJob?.cancel()
        traceOpenJob =
            coroutineScope.launch {
                traceOpenMutex.withLock {
                    val importedArtifact =
                        artifact ?: withContext(Dispatchers.IO) {
                            artifactFactory.imported(UUID.randomUUID().toString(), traceFile, Instant.now())
                        }
                    activeTraceFile = traceFile
                    activeArtifact = importedArtifact
                    recentFiles = (listOf(traceFile) + recentFiles).distinct().take(10)
                    when (val opened = withContext(Dispatchers.IO) { launchTraceInUi(traceFile, uiServer, timestamp) }) {
                        is StudioResult.Failure -> diagnosticError = opened.error.message
                        is StudioResult.Success -> diagnosticError = null
                    }
                }
            }
    }
    val exportRawTrace: (Path) -> Unit = { traceFile ->
        coroutineScope.launch(Dispatchers.IO) {
            val defaultName =
                traceFile.fileName
                    ?.toString()
                    .orEmpty()
                    .ifBlank { "trace.pftrace" }
            val saveFile =
                chooseSaveFile(
                    null,
                    localizedStringResource(Res.string.export_trace, language),
                    defaultName,
                ) ?: return@launch
            when (val exported = exportRawTraceFile(traceFile, saveFile.toPath())) {
                is StudioResult.Success -> diagnosticError = null
                is StudioResult.Failure -> {
                    diagnosticError =
                        exported.error.message.ifBlank {
                            localizedStringResource(Res.string.failed_to_export_trace, language)
                        }
                }
            }
        }
    }

    val refreshDevices: suspend () -> Unit = {
        val request = ++deviceRefreshRequest
        deviceRefreshInProgress = true
        when (val result = withContext(Dispatchers.IO) { discoverPerfettoDevices(adbPath) }) {
            is StudioResult.Success -> {
                if (request == deviceRefreshRequest) {
                    devices = result.value
                    selectedDeviceSerial = preferredDeviceSerial(selectedDeviceSerial, result.value)
                    deviceRefreshError = null
                    capabilityRefreshKey++
                    deviceRefreshInProgress = false
                }
            }

            is StudioResult.Failure -> {
                if (request == deviceRefreshRequest) {
                    deviceRefreshError = result.error
                    deviceRefreshInProgress = false
                }
            }
        }
    }

    LaunchedEffect(Unit) { captureSession.state.collect { captureState = it } }
    LaunchedEffect(adbPath) { refreshDevices() }
    LaunchedEffect(adbPath, selectedDeviceSerial, devices, capabilityRefreshKey) {
        deviceCapabilities = null
        val device = devices.firstOrNull { it.serial == selectedDeviceSerial } ?: return@LaunchedEffect
        deviceCapabilities = withContext(Dispatchers.IO) { capabilityDetector.detect(adbPath, device) }
    }
    LaunchedEffect(Unit) {
        when (val result = sessionStore.listRecent()) {
            is StudioResult.Success -> sessions = result.value
            is StudioResult.Failure -> diagnosticError = result.error.message
        }
    }
    LaunchedEffect(initialTraceFile, initialTraceTimestampNanos) {
        initialTraceFile?.let { openTrace(it, null, initialTraceTimestampNanos) }
    }
    LaunchedEffect(captureState) {
        val completed = captureState as? PerfettoCaptureState.Completed ?: return@LaunchedEffect
        activeTraceFile = completed.traceFile
        activeArtifact = completed.metadata.artifact
        recentFiles = (listOf(completed.traceFile) + recentFiles).distinct().take(10)
        val session =
            TraceSession(
                id = UUID.randomUUID().toString(),
                traceFile = completed.traceFile,
                captureConfig = completed.metadata.config,
                deviceSerial = completed.metadata.deviceSerial,
                deviceModel = completed.metadata.deviceModel,
                androidSdk = completed.metadata.androidSdk,
                capturedAt = completed.metadata.capturedAt,
                durationNanos = completed.metadata.durationNanos,
                fileSizeBytes = completed.metadata.traceFileSizeBytes,
                artifact = completed.metadata.artifact,
            )
        when (val saved = sessionStore.save(session)) {
            is StudioResult.Success -> sessions = listOf(session) + sessions.filterNot { it.id == session.id }
            is StudioResult.Failure -> diagnosticError = saved.error.message
        }
    }
    LaunchedEffect(activeTraceFile, activeArtifact) {
        val contextToClose = analysisContext
        analysisContext = null
        diagnosticResult = null
        diagnosticError = null
        withContext(Dispatchers.IO) { contextToClose?.close() }
    }

    PerfettoFileMenuBar(
        language = language,
        canExport = activeTraceFile != null,
        recentFiles = recentFiles,
        onOpen = {
            if (!fileDialogOpen) {
                fileDialogOpen = true
                try {
                    // The modal chooser must run on the UI thread, or repeated clicks stack
                    // concurrent JFileChoosers (non-modal from a background thread) and deadlock AWT.
                    val file =
                        chooseOpenFile(
                            null,
                            localizedStringResource(Res.string.open_perfetto_trace, language),
                            localizedStringResource(Res.string.perfetto_traces, language),
                            "pftrace",
                            "perfetto-trace",
                        )
                    if (file != null) openTrace(file.toPath(), null, null)
                } finally {
                    fileDialogOpen = false
                }
            }
        },
        onExportSession = {
            coroutineScope.launch(Dispatchers.IO) {
                val traceFile = activeTraceFile ?: return@launch
                val saveFile =
                    chooseSaveFile(
                        null,
                        localizedStringResource(Res.string.export_trace, language),
                        "perfetto-session.zip",
                    ) ?: return@launch
                val session =
                    sessions.firstOrNull { it.traceFile == traceFile }
                        ?: TraceSession(
                            id = UUID.randomUUID().toString(),
                            traceFile = traceFile,
                            captureConfig = PerfettoCaptureConfig(PerfettoTraceTemplate.SYSTEM_OVERVIEW),
                            deviceSerial = "",
                            deviceModel = "",
                            androidSdk = 0,
                            capturedAt = Instant.now(),
                            durationNanos = 0,
                            fileSizeBytes = traceFile.fileSize(),
                            artifact = activeArtifact,
                        )
                when (val exported = exporter.exportSessionPackage(session, saveFile.toPath())) {
                    is StudioResult.Failure -> diagnosticError = exported.error.message
                    is StudioResult.Success -> diagnosticError = null
                }
            }
        },
        onExportRawTrace = { activeTraceFile?.let(exportRawTrace) },
        onOpenRecent = { path ->
            openTrace(path, sessions.firstOrNull { it.traceFile == path }?.artifact, null)
        },
        onClearRecent = { recentFiles = emptyList() },
    )

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            HeaderToolbar(
                language = language,
                onNavigateHome = onNavigateHome,
                onNavigateSettings = null,
            ) {
                PerfettoToolbarContent(
                    language = language,
                    adbPath = adbPath,
                    onAdbPathChange = { adbPath = it },
                    devices = devices,
                    selectedDeviceSerial = selectedDeviceSerial,
                    onSelectDevice = { selectedDeviceSerial = it },
                    deviceRefreshInProgress = deviceRefreshInProgress,
                    onRefreshDevices = { coroutineScope.launch { refreshDevices() } },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            deviceRefreshError?.let {
                Text(
                    text = localizedStringResource(Res.string.device_refresh_failed, language),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = ViewerTypography.label.fontSize,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
            if (initialTraceFile != null && initialTraceNotice != null) {
                InitialTraceNotice(
                    traceFile = initialTraceFile,
                    notice = initialTraceNotice,
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 8.dp),
                )
            }
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().weight(if (activeTraceFile == null) 1f else 0.62f),
                ) {
                    PerfettoCapturePage(
                        captureState = captureState,
                        language = language,
                        selectedDeviceSerial = selectedDeviceSerial,
                        deviceCapabilities = deviceCapabilities,
                        onStartCapture = { config, deviceSerial ->
                            coroutineScope.launch {
                                captureSession.startCapture(adbPath, deviceSerial, config)
                            }
                        },
                        onStopCapture = { coroutineScope.launch { captureSession.stopCapture() } },
                        onOpenTrace = { traceFile ->
                            openTrace(traceFile, sessions.firstOrNull { it.traceFile == traceFile }?.artifact, null)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        color = MaterialTheme.colorScheme.outline,
                    )
                    RecentSessionsPanel(
                        language = language,
                        sessions = sessions,
                        onOpen = { session ->
                            openTrace(session.traceFile, session.artifact, null)
                        },
                        onDelete = { session ->
                            coroutineScope.launch {
                                when (val deleted = sessionStore.delete(session.id)) {
                                    is StudioResult.Success -> sessions = sessions.filter { it.id != session.id }
                                    is StudioResult.Failure -> diagnosticError = deleted.error.message
                                }
                            }
                        },
                        onExport = { session -> exportRawTrace(session.traceFile) },
                        modifier = Modifier.width(240.dp).fillMaxHeight(),
                    )
                }
                activeTraceFile?.let { traceFile ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    TraceDiagnosticsWorkspacePanel(
                        language = language,
                        traceFile = traceFile,
                        selectedQuery = diagnosticQuery,
                        result = diagnosticResult,
                        error = diagnosticError,
                        onRun = { query ->
                            diagnosticQuery = query
                            diagnosticResult = null
                            diagnosticError = null
                            coroutineScope.launch(Dispatchers.IO) {
                                val artifact =
                                    activeArtifact ?: run {
                                        diagnosticError = "Trace evidence was not registered"
                                        return@launch
                                    }
                                val context =
                                    analysisContext ?: when (val located = TraceProcessorToolResolver().resolve()) {
                                        is StudioResult.Failure -> {
                                            diagnosticError = located.error.message
                                            return@launch
                                        }
                                        is StudioResult.Success -> {
                                            val registry =
                                                analysisContexts ?: TraceAnalysisContexts(located.value).also { analysisContexts = it }
                                            when (val opened = registry.open(artifact, traceFile)) {
                                                is StudioResult.Failure -> {
                                                    diagnosticError = opened.error.message
                                                    return@launch
                                                }
                                                is StudioResult.Success -> opened.value.also { analysisContext = it }
                                            }
                                        }
                                    }
                                when (val queryResult = context.query(query.typedQuery())) {
                                    is StudioResult.Success ->
                                        diagnosticResult = DiagnosticResult(query.columns, queryResult.value).toPlainText()
                                    is StudioResult.Failure -> diagnosticError = queryResult.error.message
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().weight(0.38f),
                    )
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            captureSession.cancelCapture()
            uiServer.stop()
            analysisContext?.close()
        }
    }
    // The shell keeps this page composed (hidden via alpha) when navigating away, so onDispose
    // never fires. Reset the active trace/view state explicitly when the page goes inactive.
    LaunchedEffect(isActive) {
        if (!isActive) {
            traceOpenJob?.cancel()
            traceOpenJob = null
            fileDialogOpen = false
            captureSession.cancelCapture()
            uiServer.stop()
            activeTraceFile = null
            activeArtifact = null
            diagnosticQuery = null
            diagnosticResult = null
            diagnosticError = null
        }
    }
}

@Composable
@Suppress("LongParameterList", "ktlint:standard:function-naming")
private fun RowScope.PerfettoToolbarContent(
    language: UiLanguage,
    adbPath: String,
    onAdbPathChange: (String) -> Unit,
    devices: List<PerfettoDevice>,
    selectedDeviceSerial: String?,
    onSelectDevice: (String) -> Unit,
    deviceRefreshInProgress: Boolean,
    onRefreshDevices: () -> Unit,
) {
    val selectedDevice = devices.firstOrNull { it.serial == selectedDeviceSerial }
    Text(
        text = localizedStringResource(Res.string.adb_path, language),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = ViewerTypography.label.fontSize,
    )
    HeaderSpacer()
    PerfettoCompactTextField(
        value = adbPath,
        onValueChange = onAdbPathChange,
        modifier = Modifier.width(250.dp),
        placeholder = localizedStringResource(Res.string.adb, language),
    )
    HeaderSpacer()
    DeviceSelector(
        devices = devices,
        selectedDeviceSerial = selectedDeviceSerial,
        onSelectDevice = onSelectDevice,
        language = language,
    )
    HeaderSpacer()
    PerfettoCompactButton(
        text = localizedStringResource(Res.string.refresh, language),
        onClick = onRefreshDevices,
        enabled = !deviceRefreshInProgress,
    )
    Spacer(Modifier.weight(1f))
    PerfettoStatusDot(
        color =
            if (selectedDevice?.online == true) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
    )
    HeaderSpacer()
    Text(
        text =
            selectedDevice?.let { localizedStringResource(Res.string.device_connected, language, it.model) }
                ?: localizedStringResource(Res.string.no_online_device, language),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = ViewerTypography.label.fontSize,
        maxLines = 1,
    )
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun DeviceSelector(
    devices: List<PerfettoDevice>,
    selectedDeviceSerial: String?,
    onSelectDevice: (String) -> Unit,
    language: UiLanguage,
) {
    val selectedDevice = devices.firstOrNull { it.serial == selectedDeviceSerial }
    val selectDeviceLabel = localizedStringResource(Res.string.select_device, language)
    DropdownSelector(
        items = devices,
        selectedItem = selectedDevice,
        onItemSelected = { onSelectDevice(it.serial) },
        itemLabel = { localizedStringResource(Res.string.text, language, it.model, it.serial) },
        selectedItemLabel = PerfettoDevice::model,
        placeholder = selectDeviceLabel,
        modifier = Modifier.width(170.dp),
        selectorDescription = selectDeviceLabel,
        itemEnabled = PerfettoDevice::online,
        fillWidth = true,
    )
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun InitialTraceNotice(
    traceFile: Path,
    notice: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier =
            modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = traceFile.fileName.toString(),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = ViewerTypography.secondary.fontSize,
        )
        Text(
            text = notice,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = ViewerTypography.label.fontSize,
            maxLines = 1,
        )
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun RecentSessionsPanel(
    language: UiLanguage,
    sessions: List<TraceSession>,
    onOpen: (TraceSession) -> Unit,
    onDelete: (TraceSession) -> Unit,
    onExport: (TraceSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    PerfettoWorkspacePanel(
        title = localizedStringResource(Res.string.recent_sessions, language),
        modifier = modifier,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (sessions.isEmpty()) {
                Text(
                    text = localizedStringResource(Res.string.captured_traces_will_appear_here, language),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = ViewerTypography.secondary.fontSize,
                )
            }
            sessions.take(10).forEach { session ->
                RecentSessionRow(
                    session = session,
                    onOpen = { onOpen(session) },
                    onDelete = { onDelete(session) },
                    onExport = { onExport(session) },
                    language = language,
                )
            }
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun RecentSessionRow(
    session: TraceSession,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    language: UiLanguage,
) {
    val shape = RoundedCornerShape(4.dp)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = session.captureConfig.template.displayName,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = ViewerTypography.secondary.fontSize,
        )
        Text(
            text =
                localizedStringResource(Res.string.mb_n, language, session.deviceModel, session.fileSizeBytes / 1024 / 1024) +
                    formatRecentSessionTimestamp(session.capturedAt),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = ViewerTypography.label.fontSize,
            lineHeight = ViewerTypography.secondary.lineHeight,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PerfettoCompactButton(text = localizedStringResource(Res.string.open, language), onClick = onOpen)
            PerfettoCompactButton(text = localizedStringResource(Res.string.export, language), onClick = onExport)
            PerfettoCompactButton(text = localizedStringResource(Res.string.delete, language), onClick = onDelete)
        }
    }
}

internal suspend fun discoverPerfettoDevices(
    adbPath: String,
    deviceDiscovery: suspend (Path) -> StudioResult<List<AdbDevice>> = { executable ->
        AndroidTargetMonitors.shared(executable).refreshDevices()
    },
): StudioResult<List<PerfettoDevice>> {
    if (adbPath.isBlank()) {
        return StudioResult.Failure(
            StudioError(
                category = ErrorCategory.CONFIGURATION,
                code = "PERFETTO_ADB_PATH_EMPTY",
                message = "ADB path is empty.",
            ),
        )
    }
    return try {
        val executable = Path.of(adbPath)
        when (val result = deviceDiscovery(executable)) {
            is StudioResult.Failure -> result
            is StudioResult.Success ->
                StudioResult.Success(
                    result.value.map { device ->
                        PerfettoDevice(
                            serial = device.serial,
                            model = device.model?.replace('_', ' ') ?: device.serial,
                            online = device.state == AdbDeviceState.ONLINE,
                        )
                    },
                )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: InvalidPathException) {
        deviceDiscoveryFailure(
            category = ErrorCategory.CONFIGURATION,
            code = "PERFETTO_ADB_PATH_INVALID",
            error = error,
        )
    } catch (error: AdbProcessStartException) {
        deviceDiscoveryFailure(
            category = ErrorCategory.PROCESS_START,
            code = "PERFETTO_ADB_PROCESS_START_FAILED",
            error = error,
        )
    } catch (error: AdbCommandTimeoutException) {
        deviceDiscoveryFailure(
            category = ErrorCategory.PROCESS_TIMEOUT,
            code = "PERFETTO_ADB_COMMAND_TIMEOUT",
            error = error,
        )
    } catch (error: AdbCommandFailedException) {
        deviceDiscoveryFailure(
            category = ErrorCategory.PROCESS_EXIT,
            code = "PERFETTO_ADB_COMMAND_FAILED",
            error = error,
        )
    } catch (error: AdbException) {
        deviceDiscoveryFailure(
            category = ErrorCategory.UNKNOWN,
            code = "PERFETTO_DEVICE_DISCOVERY_FAILED",
            error = error,
        )
    }
}

private fun deviceDiscoveryFailure(
    category: ErrorCategory,
    code: String,
    error: Throwable,
): StudioResult.Failure =
    StudioResult.Failure(
        StudioError(
            category = category,
            code = code,
            message = error.message ?: "Unable to refresh ADB devices.",
            cause = error,
        ),
    )

internal fun defaultPerfettoAdbPath(
    androidSdkPath: Path?,
    isWindows: Boolean = System.getProperty("os.name").contains("windows", ignoreCase = true),
): String {
    val executableName = if (isWindows) "adb.exe" else "adb"
    val configuredExecutable = androidSdkPath?.resolve("platform-tools")?.resolve(executableName)
    return configuredExecutable?.takeIf(Files::isRegularFile)?.toString() ?: "adb"
}

internal fun preferredDeviceSerial(
    selectedSerial: String?,
    devices: List<PerfettoDevice>,
): String? =
    selectedSerial?.takeIf { serial -> devices.any { it.serial == serial && it.online } }
        ?: devices.filter(PerfettoDevice::online).singleOrNull()?.serial

@Composable
@Suppress("ktlint:standard:function-naming")
private fun TraceDiagnosticsWorkspacePanel(
    language: UiLanguage,
    traceFile: Path,
    selectedQuery: DiagnosticQuery?,
    result: String?,
    error: String?,
    onRun: (DiagnosticQuery) -> Unit,
    modifier: Modifier = Modifier,
) {
    PerfettoWorkspacePanel(
        title = localizedStringResource(Res.string.trace_diagnostics, language, traceFile.fileName),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
        ) {
            TraceDiagnosticNavigation(
                language = language,
                queries = PerfettoDiagnostics.all,
                selectedQuery = selectedQuery,
                onSelect = onRun,
                modifier = Modifier.width(240.dp).fillMaxHeight(),
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
            )
            TraceDiagnosticContent(
                language = language,
                selectedQuery = selectedQuery,
                result = result,
                error = error,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun TraceDiagnosticNavigation(
    language: UiLanguage,
    queries: List<DiagnosticQuery>,
    selectedQuery: DiagnosticQuery?,
    onSelect: (DiagnosticQuery) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        queries.forEach { query ->
            PerfettoCompactButton(
                text = query.localizedTitle(language),
                onClick = { onSelect(query) },
                modifier = Modifier.fillMaxWidth(),
                selected = selectedQuery == query,
            )
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun TraceDiagnosticContent(
    language: UiLanguage,
    selectedQuery: DiagnosticQuery?,
    result: String?,
    error: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selectedQuery == null) {
            Text(
                text = localizedStringResource(Res.string.select_a_diagnostic_on_the_left_to_view_its_result, language),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = ViewerTypography.secondary.fontSize,
            )
            return@Column
        }
        Text(
            text = selectedQuery.localizedTitle(language),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = ViewerTypography.bodyCompact.fontSize,
        )
        Text(
            text = selectedQuery.localizedDescription(language),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = ViewerTypography.label.fontSize,
        )
        when {
            error != null ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = ViewerTypography.secondary.fontSize,
                )
            result != null ->
                Text(
                    text = result.take(8_000),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = ViewerTypography.label.fontSize,
                    lineHeight = ViewerTypography.secondary.lineHeight,
                )
            else ->
                Text(
                    text = localizedStringResource(Res.string.running_diagnostic, language),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = ViewerTypography.label.fontSize,
                )
        }
    }
}

private fun DiagnosticQuery.localizedTitle(language: UiLanguage): String =
    when (id) {
        "cpu_hotspots" -> localizedStringResource(Res.string.diagnostic_cpu_hotspots_title, language)
        "sched_switch" -> localizedStringResource(Res.string.diagnostic_sched_switch_title, language)
        "sched_waking" -> localizedStringResource(Res.string.diagnostic_sched_waking_title, language)
        "run_queue" -> localizedStringResource(Res.string.diagnostic_run_queue_title, language)
        "main_thread_blocked" -> localizedStringResource(Res.string.diagnostic_main_thread_blocked_title, language)
        "cpu_freq_dist" -> localizedStringResource(Res.string.diagnostic_cpu_frequency_title, language)
        "binder_latency" -> localizedStringResource(Res.string.diagnostic_binder_latency_title, language)
        "binder_ipc_long_tail" -> localizedStringResource(Res.string.diagnostic_binder_ipc_long_tail_title, language)
        "binder_system_server" -> localizedStringResource(Res.string.diagnostic_binder_system_server_title, language)
        "binder_frame_alignment" -> localizedStringResource(Res.string.diagnostic_binder_frame_alignment_title, language)
        "frame_jank" -> localizedStringResource(Res.string.diagnostic_frame_jank_title, language)
        "file_io_syscalls" -> localizedStringResource(Res.string.diagnostic_file_io_syscalls_title, language)
        "fsync_calls" -> localizedStringResource(Res.string.diagnostic_fsync_calls_title, language)
        "block_io_pressure" -> localizedStringResource(Res.string.diagnostic_block_io_pressure_title, language)
        "page_faults" -> localizedStringResource(Res.string.diagnostic_page_faults_title, language)
        "sqlite_activity" -> localizedStringResource(Res.string.diagnostic_sqlite_activity_title, language)
        "sqlite_lock_wait" -> localizedStringResource(Res.string.diagnostic_sqlite_lock_wait_title, language)
        "art_gc_events" -> localizedStringResource(Res.string.diagnostic_art_gc_events_title, language)
        "art_allocation_churn" -> localizedStringResource(Res.string.diagnostic_art_allocation_churn_title, language)
        "art_jit_dex2oat" -> localizedStringResource(Res.string.diagnostic_art_jit_dex2oat_title, language)
        "art_class_loading" -> localizedStringResource(Res.string.diagnostic_art_class_loading_title, language)
        "art_class_verification" -> localizedStringResource(Res.string.diagnostic_art_class_verification_title, language)
        "thermal_throttling" -> localizedStringResource(Res.string.diagnostic_thermal_throttling_title, language)
        "dvfs_residency" -> localizedStringResource(Res.string.diagnostic_dvfs_residency_title, language)
        "gpu_frequency_residency" -> localizedStringResource(Res.string.diagnostic_gpu_frequency_residency_title, language)
        "power_rails" -> localizedStringResource(Res.string.diagnostic_power_rails_title, language)
        "power_frame_alignment" -> localizedStringResource(Res.string.diagnostic_power_frame_alignment_title, language)
        "mem_counters" -> localizedStringResource(Res.string.diagnostic_memory_timeline_title, language)
        "input_latency" -> localizedStringResource(Res.string.diagnostic_input_latency_title, language)
        "thread_states" -> localizedStringResource(Res.string.diagnostic_thread_states_title, language)
        "wakeup_latency" -> localizedStringResource(Res.string.diagnostic_wakeup_latency_title, language)
        else -> title
    }

private fun DiagnosticQuery.localizedDescription(language: UiLanguage): String =
    when (id) {
        "cpu_hotspots" -> localizedStringResource(Res.string.diagnostic_cpu_hotspots_description, language)
        "sched_switch" -> localizedStringResource(Res.string.diagnostic_sched_switch_description, language)
        "sched_waking" -> localizedStringResource(Res.string.diagnostic_sched_waking_description, language)
        "run_queue" -> localizedStringResource(Res.string.diagnostic_run_queue_description, language)
        "main_thread_blocked" -> localizedStringResource(Res.string.diagnostic_main_thread_blocked_description, language)
        "cpu_freq_dist" -> localizedStringResource(Res.string.diagnostic_cpu_frequency_description, language)
        "binder_latency" -> localizedStringResource(Res.string.diagnostic_binder_latency_description, language)
        "binder_ipc_long_tail" -> localizedStringResource(Res.string.diagnostic_binder_ipc_long_tail_description, language)
        "binder_system_server" -> localizedStringResource(Res.string.diagnostic_binder_system_server_description, language)
        "binder_frame_alignment" -> localizedStringResource(Res.string.diagnostic_binder_frame_alignment_description, language)
        "frame_jank" -> localizedStringResource(Res.string.diagnostic_frame_jank_description, language)
        "file_io_syscalls" -> localizedStringResource(Res.string.diagnostic_file_io_syscalls_description, language)
        "fsync_calls" -> localizedStringResource(Res.string.diagnostic_fsync_calls_description, language)
        "block_io_pressure" -> localizedStringResource(Res.string.diagnostic_block_io_pressure_description, language)
        "page_faults" -> localizedStringResource(Res.string.diagnostic_page_faults_description, language)
        "sqlite_activity" -> localizedStringResource(Res.string.diagnostic_sqlite_activity_description, language)
        "sqlite_lock_wait" -> localizedStringResource(Res.string.diagnostic_sqlite_lock_wait_description, language)
        "art_gc_events" -> localizedStringResource(Res.string.diagnostic_art_gc_events_description, language)
        "art_allocation_churn" -> localizedStringResource(Res.string.diagnostic_art_allocation_churn_description, language)
        "art_jit_dex2oat" -> localizedStringResource(Res.string.diagnostic_art_jit_dex2oat_description, language)
        "art_class_loading" -> localizedStringResource(Res.string.diagnostic_art_class_loading_description, language)
        "art_class_verification" -> localizedStringResource(Res.string.diagnostic_art_class_verification_description, language)
        "thermal_throttling" -> localizedStringResource(Res.string.diagnostic_thermal_throttling_description, language)
        "dvfs_residency" -> localizedStringResource(Res.string.diagnostic_dvfs_residency_description, language)
        "gpu_frequency_residency" -> localizedStringResource(Res.string.diagnostic_gpu_frequency_residency_description, language)
        "power_rails" -> localizedStringResource(Res.string.diagnostic_power_rails_description, language)
        "power_frame_alignment" -> localizedStringResource(Res.string.diagnostic_power_frame_alignment_description, language)
        "mem_counters" -> localizedStringResource(Res.string.diagnostic_memory_timeline_description, language)
        "input_latency" -> localizedStringResource(Res.string.diagnostic_input_latency_description, language)
        "thread_states" -> localizedStringResource(Res.string.diagnostic_thread_states_description, language)
        "wakeup_latency" -> localizedStringResource(Res.string.diagnostic_wakeup_latency_description, language)
        else -> description
    }

private fun launchTraceInUi(
    traceFile: Path,
    uiServer: PerfettoUiServer,
    timestampNanos: Long? = null,
): StudioResult<Unit> {
    val assetsDir = PerfettoUiServer.tryFindUiAssetsDir()
    val started = uiServer.start(assetsDir)
    if (started is StudioResult.Failure) return started
    return uiServer.openTrace(traceFile, timestampNanos)
}
