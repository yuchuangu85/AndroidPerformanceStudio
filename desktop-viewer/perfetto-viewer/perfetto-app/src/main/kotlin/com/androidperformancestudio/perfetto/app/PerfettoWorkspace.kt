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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onNavigateHome != null) {
                    PerfettoHomeButton(onClick = onNavigateHome)
                }
                Text("Perfetto Trace Analyzer", style = MaterialTheme.typography.headlineMedium)
            }
            if (initialTraceFile != null && initialTraceNotice != null) {
                Spacer(Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(initialTraceFile.fileName.toString(), style = MaterialTheme.typography.titleSmall)
                        Text(
                            initialTraceNotice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            PerfettoCapturePage(
                captureState = captureState,
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
                onStartCapture = { config, deviceSerial ->
                    coroutineScope.launch { captureSession.startCapture(adbPath, deviceSerial, config) }
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
            activeTraceFile?.let { traceFile ->
                TraceDiagnosticsPanel(
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
                            when (val result = session.query(query.sql)) {
                                is StudioResult.Success -> diagnosticResult = result.value
                                is StudioResult.Failure -> diagnosticError = result.error.message
                            }
                        }
                    },
                )
            }
            if (sessions.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("Recent Sessions", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                sessions.take(10).forEach { session ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(session.captureConfig.template.displayName)
                                Text(
                                    "${session.deviceModel} ${session.capturedAt} ${session.fileSizeBytes / 1024 / 1024}MB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        when (val opened = launchTraceInUi(session.traceFile, uiServer)) {
                                            is StudioResult.Failure -> diagnosticError = opened.error.message
                                            is StudioResult.Success -> diagnosticError = null
                                        }
                                    },
                                ) { Text("Open") }
                                OutlinedButton(onClick = {
                                    coroutineScope.launch {
                                        when (val deleted = sessionStore.delete(session.id)) {
                                            is StudioResult.Success -> sessions = sessions.filter { it.id != session.id }
                                            is StudioResult.Failure -> diagnosticError = deleted.error.message
                                        }
                                    }
                                }) { Text("Delete") }
                            }
                        }
                    }
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
@Suppress("FunctionName", "MagicNumber", "ktlint:standard:function-naming")
private fun PerfettoHomeButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    val iconColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier =
            Modifier
                .width(30.dp)
                .height(28.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
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
private fun TraceDiagnosticsPanel(
    traceFile: Path,
    selectedQuery: DiagnosticQuery?,
    result: String?,
    error: String?,
    onRun: (DiagnosticQuery) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Trace diagnostics", style = MaterialTheme.typography.titleSmall)
            Text(traceFile.fileName.toString(), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PerfettoDiagnostics.all.take(6).forEach { query ->
                    OutlinedButton(onClick = { onRun(query) }) {
                        Text(query.title, maxLines = 1)
                    }
                }
            }
            selectedQuery?.let { Text("${it.title}: ${it.description}", style = MaterialTheme.typography.bodySmall) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            result?.let { Text(it.take(8_000), style = MaterialTheme.typography.bodySmall) }
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
