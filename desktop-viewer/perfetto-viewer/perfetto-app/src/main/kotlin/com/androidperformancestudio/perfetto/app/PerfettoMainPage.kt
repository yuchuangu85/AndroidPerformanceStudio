package com.androidperformancestudio.perfetto.app

import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.perfetto_app.generated.resources.Res
import com.androidperformancestudio.perfetto_app.generated.resources.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.perfetto.analysis.DiagnosticQuery
import com.androidperformancestudio.perfetto.analysis.PerfettoDiagnostics
import com.androidperformancestudio.perfetto.capture.PerfettoCaptureSession
import com.androidperformancestudio.perfetto.export.TraceExporter
import com.androidperformancestudio.perfetto.model.PerfettoCaptureConfig
import com.androidperformancestudio.perfetto.model.PerfettoCaptureState
import com.androidperformancestudio.perfetto.model.PerfettoDevice
import com.androidperformancestudio.perfetto.model.PerfettoTraceTemplate
import com.androidperformancestudio.perfetto.model.TraceSession
import com.androidperformancestudio.perfetto.presentation.PerfettoCapturePage
import com.androidperformancestudio.perfetto.presentation.PerfettoCompactButton
import com.androidperformancestudio.perfetto.presentation.PerfettoCompactTextField
import com.androidperformancestudio.perfetto.presentation.PerfettoStatusDot
import com.androidperformancestudio.perfetto.presentation.PerfettoWorkspacePanel
import com.androidperformancestudio.perfetto.storage.TraceSessionStore
import com.androidperformancestudio.perfetto.traceprocessor.TraceProcessorLocator
import com.androidperformancestudio.perfetto.traceprocessor.TraceProcessorSession
import com.androidperformancestudio.perfetto.uiserver.PerfettoUiServer
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.button.HomeButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.io.path.fileSize
import kotlin.time.Duration.Companion.seconds

internal fun formatRecentSessionTimestamp(capturedAt: Instant): String =
    capturedAt.toString().replace("T", "-T")

internal fun exportRawTraceFile(traceFile: Path, destination: Path): StudioResult<Path> =
    try {
        java.nio.file.Files.copy(
            traceFile,
            destination,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        StudioResult.Success(destination)
    } catch (exception: Exception) {
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
    onNavigateHome: (() -> Unit)? = null,
    onOpenUserGuide: (() -> Unit)? = null,
    initialTraceFile: Path? = null,
    initialTraceNotice: String? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    val captureSession = remember { PerfettoCaptureSession() }
    val sessionStore = remember { TraceSessionStore() }
    val uiServer = remember { PerfettoUiServer() }
    val exporter = remember { TraceExporter() }
    var captureState by remember { mutableStateOf<PerfettoCaptureState>(PerfettoCaptureState.Idle) }
    var sessions by remember { mutableStateOf<List<TraceSession>>(emptyList()) }
    var recentFiles by remember { mutableStateOf<List<Path>>(emptyList()) }
    var activeTraceFile by remember { mutableStateOf<Path?>(null) }
    var adbPath by remember { mutableStateOf("adb") }
    var devices by remember { mutableStateOf<List<PerfettoDevice>>(emptyList()) }
    var selectedDeviceSerial by remember { mutableStateOf<String?>(null) }
    var analysisSession by remember { mutableStateOf<TraceProcessorSession?>(null) }
    var diagnosticQuery by remember { mutableStateOf<DiagnosticQuery?>(null) }
    var diagnosticResult by remember { mutableStateOf<String?>(null) }
    var diagnosticError by remember { mutableStateOf<String?>(null) }
    val exportRawTrace: (Path) -> Unit = { traceFile ->
        coroutineScope.launch(Dispatchers.IO) {
            val defaultName = traceFile.fileName?.toString().orEmpty().ifBlank { "trace.pftrace" }
            val saveFile = chooseSaveFile(defaultName, language) ?: return@launch
            when (val exported = exportRawTraceFile(traceFile, saveFile.toPath())) {
                is StudioResult.Success -> diagnosticError = null
                is StudioResult.Failure -> {
                    diagnosticError = exported.error.message.ifBlank {
                        localizedStringResource(Res.string.failed_to_export_trace, language)
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { captureSession.state.collect { captureState = it } }
    LaunchedEffect(adbPath) {
        devices = discoverPerfettoDevices(adbPath)
        selectedDeviceSerial = preferredDeviceSerial(selectedDeviceSerial, devices)
    }
    LaunchedEffect(Unit) {
        when (val result = sessionStore.listRecent()) {
            is StudioResult.Success -> sessions = result.value
            is StudioResult.Failure -> diagnosticError = result.error.message
        }
    }
    LaunchedEffect(initialTraceFile) {
        initialTraceFile?.let { traceFile ->
            activeTraceFile = traceFile
            recentFiles = (listOf(traceFile) + recentFiles).distinct().take(10)
            when (val opened = launchTraceInUi(traceFile, uiServer)) {
                is StudioResult.Failure -> diagnosticError = opened.error.message
                is StudioResult.Success -> diagnosticError = null
            }
        }
    }
    LaunchedEffect(captureState) {
        val completed = captureState as? PerfettoCaptureState.Completed ?: return@LaunchedEffect
        activeTraceFile = completed.traceFile
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
            )
        when (val saved = sessionStore.save(session)) {
            is StudioResult.Success -> sessions = listOf(session) + sessions.filterNot { it.id == session.id }
            is StudioResult.Failure -> diagnosticError = saved.error.message
        }
    }
    LaunchedEffect(activeTraceFile) {
        analysisSession?.stop()
        analysisSession = null
        diagnosticResult = null
        diagnosticError = null
    }

    PerfettoFileMenuBar(
        language = language,
        canExport = activeTraceFile != null,
        recentFiles = recentFiles,
        onOpen = {
            coroutineScope.launch(Dispatchers.IO) {
                val file = chooseTraceFile(language) ?: return@launch
                activeTraceFile = file.toPath()
                recentFiles = (listOf(file.toPath()) + recentFiles).distinct().take(10)
                when (val opened = launchTraceInUi(file.toPath(), uiServer)) {
                    is StudioResult.Failure -> diagnosticError = opened.error.message
                    is StudioResult.Success -> diagnosticError = null
                }
            }
        },
        onExportSession = {
            coroutineScope.launch(Dispatchers.IO) {
                val traceFile = activeTraceFile ?: return@launch
                val saveFile = chooseSaveFile("perfetto-session.zip", language) ?: return@launch
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
                        )
                when (val exported = exporter.exportSessionPackage(session, saveFile.toPath())) {
                    is StudioResult.Failure -> diagnosticError = exported.error.message
                    is StudioResult.Success -> diagnosticError = null
                }
            }
        },
        onExportRawTrace = { activeTraceFile?.let(exportRawTrace) },
        onOpenRecent = { path ->
            activeTraceFile = path
            when (val opened = launchTraceInUi(path, uiServer)) {
                is StudioResult.Failure -> diagnosticError = opened.error.message
                is StudioResult.Success -> diagnosticError = null
            }
        },
        onClearRecent = { recentFiles = emptyList() },
    )

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            PerfettoToolbar(
                language = language,
                onNavigateHome = onNavigateHome,
                adbPath = adbPath,
                onAdbPathChange = { adbPath = it },
                devices = devices,
                selectedDeviceSerial = selectedDeviceSerial,
                onSelectDevice = { selectedDeviceSerial = it },
                onRefreshDevices = {
                    coroutineScope.launch(Dispatchers.IO) {
                        val refreshed = discoverPerfettoDevices(adbPath)
                        devices = refreshed
                        selectedDeviceSerial = preferredDeviceSerial(selectedDeviceSerial, refreshed)
                    }
                },
            )
            if (initialTraceFile != null && initialTraceNotice != null) {
                InitialTraceNotice(
                    traceFile = initialTraceFile,
                    notice = initialTraceNotice,
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 8.dp),
                )
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().weight(if (activeTraceFile == null) 1f else 0.62f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PerfettoCapturePage(
                        captureState = captureState,
                        language = language,
                        selectedDeviceSerial = selectedDeviceSerial,
                        onStartCapture = { config, deviceSerial ->
                            coroutineScope.launch {
                                captureSession.startCapture(adbPath, deviceSerial, config)
                            }
                        },
                        onStopCapture = { coroutineScope.launch { captureSession.stopCapture() } },
                        onOpenTrace = { traceFile ->
                            activeTraceFile = traceFile
                            when (val opened = launchTraceInUi(traceFile, uiServer)) {
                                is StudioResult.Failure -> diagnosticError = opened.error.message
                                is StudioResult.Success -> diagnosticError = null
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    RecentSessionsPanel(
                        language = language,
                        sessions = sessions,
                        onOpen = { session ->
                            when (val opened = launchTraceInUi(session.traceFile, uiServer)) {
                                is StudioResult.Failure -> diagnosticError = opened.error.message
                                is StudioResult.Success -> diagnosticError = null
                            }
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
                        modifier = Modifier.width(320.dp).fillMaxHeight(),
                    )
                }
                activeTraceFile?.let { traceFile ->
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
                                val session =
                                    analysisSession ?: when (val located = TraceProcessorLocator().locate()) {
                                        is StudioResult.Failure -> {
                                            diagnosticError = located.error.message
                                            return@launch
                                        }
                                        is StudioResult.Success ->
                                            TraceProcessorSession(located.value, traceFile).also {
                                                when (val started = it.start()) {
                                                    is StudioResult.Failure -> {
                                                        diagnosticError = started.error.message
                                                        return@launch
                                                    }
                                                    is StudioResult.Success -> analysisSession = it
                                                }
                                            }
                                    }
                                when (val queryResult = session.query(query.sql)) {
                                    is StudioResult.Success -> diagnosticResult = queryResult.value
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
            analysisSession?.stop()
        }
    }
}

@Composable
@Suppress("LongParameterList", "ktlint:standard:function-naming")
private fun PerfettoToolbar(
    language: UiLanguage,
    onNavigateHome: (() -> Unit)?,
    adbPath: String,
    onAdbPathChange: (String) -> Unit,
    devices: List<PerfettoDevice>,
    selectedDeviceSerial: String?,
    onSelectDevice: (String) -> Unit,
    onRefreshDevices: () -> Unit,
) {
    val selectedDevice = devices.firstOrNull { it.serial == selectedDeviceSerial }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                .padding(horizontal = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onNavigateHome != null) {
            HomeButton(
                localizedStringResource(Res.string.back_to_home, language),
                onClick = onNavigateHome,
            )
            Box(
                modifier =
                    Modifier
                        .width(1.dp)
                        .height(16.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
        Text(
            text = localizedStringResource(Res.string.adb_path, language),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
        )
        PerfettoCompactTextField(
            value = adbPath,
            onValueChange = onAdbPathChange,
            modifier = Modifier.width(250.dp),
            placeholder = localizedStringResource(Res.string.adb, language),
        )
        DeviceSelector(
            devices = devices,
            selectedDeviceSerial = selectedDeviceSerial,
            onSelectDevice = onSelectDevice,
            language = language,
        )
        PerfettoCompactButton(text = localizedStringResource(Res.string.refresh, language), onClick = onRefreshDevices)
        Spacer(Modifier.weight(1f))
        PerfettoStatusDot(
            color =
                if (selectedDevice?.online == true) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
        )
        Text(
            text = selectedDevice?.let { localizedStringResource(Res.string.device_connected, language, it.model) }
                ?: localizedStringResource(Res.string.no_online_device, language),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
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
            fontSize = 11.sp,
        )
        Text(
            text = notice,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
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
                    fontSize = 11.sp,
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
            fontSize = 11.sp,
        )
        Text(
            text =
                localizedStringResource(Res.string.mb_n, language, session.deviceModel, session.fileSizeBytes / 1024 / 1024) +
                    formatRecentSessionTimestamp(session.capturedAt),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            lineHeight = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PerfettoCompactButton(text = localizedStringResource(Res.string.open, language), onClick = onOpen)
            PerfettoCompactButton(text = localizedStringResource(Res.string.export, language), onClick = onExport)
            PerfettoCompactButton(text = localizedStringResource(Res.string.delete, language), onClick = onDelete)
        }
    }
}

private suspend fun discoverPerfettoDevices(adbPath: String): List<PerfettoDevice> {
    if (adbPath.isBlank()) return emptyList()
    return runCatching {
        DefaultAdbClient(Path.of(adbPath))
            .listDevices()
            .filter { it.state == AdbDeviceState.ONLINE }
            .map { device ->
                PerfettoDevice(
                    serial = device.serial,
                    model = device.model?.replace('_', ' ') ?: device.serial,
                )
            }
    }.getOrDefault(emptyList())
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
                fontSize = 11.sp,
            )
            return@Column
        }
        Text(
            text = selectedQuery.localizedTitle(language),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
        )
        Text(
            text = selectedQuery.localizedDescription(language),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
        )
        when {
            error != null ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp,
                )
            result != null ->
                Text(
                    text = result.take(8_000),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                )
            else ->
                Text(
                    text = localizedStringResource(Res.string.running_diagnostic, language),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
        }
    }
}

private fun DiagnosticQuery.localizedTitle(language: UiLanguage): String =
    when (id) {
        "cpu_hotspots" -> localizedStringResource(Res.string.diagnostic_cpu_hotspots_title, language)
        "cpu_freq_dist" -> localizedStringResource(Res.string.diagnostic_cpu_frequency_title, language)
        "binder_latency" -> localizedStringResource(Res.string.diagnostic_binder_latency_title, language)
        "frame_jank" -> localizedStringResource(Res.string.diagnostic_frame_jank_title, language)
        "mem_counters" -> localizedStringResource(Res.string.diagnostic_memory_timeline_title, language)
        "input_latency" -> localizedStringResource(Res.string.diagnostic_input_latency_title, language)
        "thread_states" -> localizedStringResource(Res.string.diagnostic_thread_states_title, language)
        "wakeup_latency" -> localizedStringResource(Res.string.diagnostic_wakeup_latency_title, language)
        else -> title
    }

private fun DiagnosticQuery.localizedDescription(language: UiLanguage): String =
    when (id) {
        "cpu_hotspots" -> localizedStringResource(Res.string.diagnostic_cpu_hotspots_description, language)
        "cpu_freq_dist" -> localizedStringResource(Res.string.diagnostic_cpu_frequency_description, language)
        "binder_latency" -> localizedStringResource(Res.string.diagnostic_binder_latency_description, language)
        "frame_jank" -> localizedStringResource(Res.string.diagnostic_frame_jank_description, language)
        "mem_counters" -> localizedStringResource(Res.string.diagnostic_memory_timeline_description, language)
        "input_latency" -> localizedStringResource(Res.string.diagnostic_input_latency_description, language)
        "thread_states" -> localizedStringResource(Res.string.diagnostic_thread_states_description, language)
        "wakeup_latency" -> localizedStringResource(Res.string.diagnostic_wakeup_latency_description, language)
        else -> description
    }

private fun launchTraceInUi(
    traceFile: Path,
    uiServer: PerfettoUiServer,
): StudioResult<Unit> {
    val assetsDir = PerfettoUiServer.tryFindUiAssetsDir()
    val started = uiServer.start(assetsDir)
    if (started is StudioResult.Failure) return started
    return uiServer.openTrace(traceFile)
}

private fun chooseTraceFile(language: UiLanguage): File? =
    JFileChooser().run {
        dialogTitle = localizedStringResource(Res.string.open_perfetto_trace, language)
        fileFilter = FileNameExtensionFilter(localizedStringResource(Res.string.perfetto_traces, language), "pftrace", "perfetto-trace")
        if (showOpenDialog(null) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }

private fun chooseSaveFile(defaultName: String, language: UiLanguage): File? =
    JFileChooser().run {
        dialogTitle = localizedStringResource(Res.string.export_trace, language)
        selectedFile = File(defaultName)
        if (showSaveDialog(null) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }
