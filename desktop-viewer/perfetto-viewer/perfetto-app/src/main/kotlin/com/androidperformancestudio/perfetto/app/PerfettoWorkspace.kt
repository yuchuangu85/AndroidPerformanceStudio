package com.androidperformancestudio.perfetto.app

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
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

@Composable
@Suppress("ktlint:standard:function-naming")
fun FrameWindowScope.PerfettoWorkspace(
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

    LaunchedEffect(Unit) { captureSession.state.collect { captureState = it } }
    LaunchedEffect(adbPath) {
        devices = discoverPerfettoDevices(adbPath)
        selectedDeviceSerial = selectedDeviceSerial?.takeIf { serial -> devices.any { it.serial == serial } }
            ?: devices.firstOrNull { it.online }?.serial
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
        canExport = activeTraceFile != null,
        recentFiles = recentFiles,
        onOpen = {
            coroutineScope.launch(Dispatchers.IO) {
                val file = chooseTraceFile() ?: return@launch
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
                val saveFile = chooseSaveFile("perfetto-session.zip") ?: return@launch
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
        onExportRawTrace = {
            coroutineScope.launch(Dispatchers.IO) {
                val traceFile = activeTraceFile ?: return@launch
                val saveFile = chooseSaveFile("trace.pftrace") ?: return@launch
                runCatching {
                    java.nio.file.Files.copy(
                        traceFile,
                        saveFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }.onSuccess {
                    diagnosticError = null
                }.onFailure { exception ->
                    diagnosticError = exception.message ?: "Failed to export trace"
                }
            }
        },
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
                        selectedDeviceSerial = refreshed.firstOrNull { it.online }?.serial
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
                        modifier = Modifier.width(320.dp).fillMaxHeight(),
                    )
                }
                activeTraceFile?.let { traceFile ->
                    TraceDiagnosticsWorkspacePanel(
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
            PerfettoHomeButton(onClick = onNavigateHome)
            Box(
                modifier =
                    Modifier
                        .width(1.dp)
                        .height(16.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
        Text(
            text = "ADB Path",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
        )
        PerfettoCompactTextField(
            value = adbPath,
            onValueChange = onAdbPathChange,
            modifier = Modifier.width(250.dp),
            placeholder = "adb",
        )
        DeviceSelector(
            devices = devices,
            selectedDeviceSerial = selectedDeviceSerial,
            onSelectDevice = onSelectDevice,
        )
        PerfettoCompactButton(text = "Refresh", onClick = onRefreshDevices)
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
            text = selectedDevice?.let { "${it.model} connected" } ?: "No online device",
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
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedDevice = devices.firstOrNull { it.serial == selectedDeviceSerial }
    Box {
        PerfettoCompactButton(
            text = selectedDevice?.model ?: "Select device",
            onClick = { expanded = true },
            enabled = devices.isNotEmpty(),
            modifier = Modifier.width(170.dp),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            devices.forEach { device ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "${device.model} · ${device.serial}",
                            fontSize = 11.sp,
                        )
                    },
                    onClick = {
                        onSelectDevice(device.serial)
                        expanded = false
                    },
                    enabled = device.online,
                )
            }
        }
    }
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
    sessions: List<TraceSession>,
    onOpen: (TraceSession) -> Unit,
    onDelete: (TraceSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    PerfettoWorkspacePanel(
        title = "RECENT SESSIONS",
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
                    text = "Captured traces will appear here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            sessions.take(10).forEach { session ->
                RecentSessionRow(
                    session = session,
                    onOpen = { onOpen(session) },
                    onDelete = { onDelete(session) },
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
                "${session.deviceModel} · ${session.fileSizeBytes / 1024 / 1024} MB\n" +
                    session.capturedAt,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            lineHeight = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PerfettoCompactButton(text = "Open", onClick = onOpen)
            PerfettoCompactButton(text = "Delete", onClick = onDelete)
        }
    }
}

@Composable
@Suppress("FunctionName", "MagicNumber", "ktlint:standard:function-naming")
private fun PerfettoHomeButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    val iconColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier =
            Modifier
                .width(28.dp)
                .height(28.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .semantics { contentDescription = "Back to home" }
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(15.dp)) {
            val strokeWidth = 1.2.dp.toPx()
            val roofLeft = Offset(1.5.dp.toPx(), 7.dp.toPx())
            val roofPeak = Offset(size.width / 2f, 1.8.dp.toPx())
            val roofRight = Offset(size.width - 1.5.dp.toPx(), 7.dp.toPx())
            val wallLeft = 3.2.dp.toPx()
            val wallRight = size.width - 3.2.dp.toPx()
            val wallTop = 6.2.dp.toPx()
            val wallBottom = size.height - 1.8.dp.toPx()
            val doorWidth = 3.6.dp.toPx()

            drawLine(iconColor, roofLeft, roofPeak, strokeWidth)
            drawLine(iconColor, roofPeak, roofRight, strokeWidth)
            drawLine(iconColor, Offset(wallLeft, wallTop), Offset(wallLeft, wallBottom), strokeWidth)
            drawLine(iconColor, Offset(wallRight, wallTop), Offset(wallRight, wallBottom), strokeWidth)
            drawLine(iconColor, Offset(wallLeft, wallBottom), Offset(wallRight, wallBottom), strokeWidth)
            drawRect(
                color = iconColor,
                topLeft = Offset((size.width - doorWidth) / 2f, 9.dp.toPx()),
                size = Size(doorWidth, wallBottom - 9.dp.toPx()),
                style = Stroke(width = strokeWidth),
            )
        }
    }
}

private suspend fun discoverPerfettoDevices(adbPath: String): List<PerfettoDevice> {
    if (adbPath.isBlank()) return emptyList()
    val result =
        JvmProcessRunner().run(
            ProcessRequest(
                executable = Path.of(adbPath),
                arguments = listOf("devices", "-l"),
                timeout = 10.seconds,
            ),
        )
    val output = result as? ProcessRunResult.Completed ?: return emptyList()
    return output.output.stdout.text
        .lineSequence()
        .drop(1)
        .mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2 || parts[1] != "device") return@mapNotNull null
            val model =
                parts
                    .firstOrNull { it.startsWith("model:") }
                    ?.substringAfter("model:")
                    ?.replace('_', ' ')
                    ?: parts[0]
            PerfettoDevice(parts[0], model)
        }.toList()
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun TraceDiagnosticsWorkspacePanel(
    traceFile: Path,
    selectedQuery: DiagnosticQuery?,
    result: String?,
    error: String?,
    onRun: (DiagnosticQuery) -> Unit,
    modifier: Modifier = Modifier,
) {
    PerfettoWorkspacePanel(
        title = "TRACE DIAGNOSTICS · ${traceFile.fileName}",
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
        ) {
            TraceDiagnosticNavigation(
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
                text = query.title,
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
                text = "Select a diagnostic on the left to view its result.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            return@Column
        }
        Text(
            text = selectedQuery.title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
        )
        Text(
            text = selectedQuery.description,
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
                    text = "Running diagnostic…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
        }
    }
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

private fun chooseTraceFile(): File? =
    JFileChooser().run {
        dialogTitle = "Open Perfetto Trace"
        fileFilter = FileNameExtensionFilter("Perfetto Traces", "pftrace", "perfetto-trace")
        if (showOpenDialog(null) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }

private fun chooseSaveFile(defaultName: String): File? =
    JFileChooser().run {
        dialogTitle = "Export Trace"
        selectedFile = File(defaultName)
        if (showSaveDialog(null) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }
