package com.androidperformancestudio.perfetto.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.perfetto.capture.PerfettoCaptureSession
import com.androidperformancestudio.perfetto.export.TraceExporter
import com.androidperformancestudio.perfetto.model.PerfettoCaptureConfig
import com.androidperformancestudio.perfetto.model.PerfettoCaptureState
import com.androidperformancestudio.perfetto.model.PerfettoTraceTemplate
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.perfetto.model.TraceSession
import com.androidperformancestudio.perfetto.presentation.PerfettoCapturePage
import com.androidperformancestudio.perfetto.storage.TraceSessionStore
import com.androidperformancestudio.perfetto.uiserver.PerfettoUiServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.io.path.fileSize

@Composable
fun FrameWindowScope.PerfettoWorkspace(
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

    LaunchedEffect(Unit) { captureSession.state.collect { captureState = it } }
    LaunchedEffect(Unit) {
        when (val result = sessionStore.listRecent()) {
            is StudioResult.Success -> sessions = result.value
            is StudioResult.Failure -> {}
        }
    }
    LaunchedEffect(initialTraceFile) {
        initialTraceFile?.let { traceFile ->
            activeTraceFile = traceFile
            recentFiles = (listOf(traceFile) + recentFiles).distinct().take(10)
            launchTraceInUi(traceFile, uiServer)
        }
    }

    PerfettoFileMenuBar(
        canExport = activeTraceFile != null,
        recentFiles = recentFiles,
        onOpen = {
            coroutineScope.launch(Dispatchers.IO) {
                val file = chooseTraceFile() ?: return@launch
                activeTraceFile = file.toPath()
                recentFiles = (listOf(file.toPath()) + recentFiles).distinct().take(10)
                launchTraceInUi(file.toPath(), uiServer)
            }
        },
        onExportSession = {
            coroutineScope.launch(Dispatchers.IO) {
                val traceFile = activeTraceFile ?: return@launch
                val saveFile = chooseSaveFile("perfetto-session.zip") ?: return@launch
                val session = TraceSession(
                    id = UUID.randomUUID().toString(),
                    traceFile = traceFile, captureConfig = PerfettoCaptureConfig(PerfettoTraceTemplate.SYSTEM_OVERVIEW),
                    deviceSerial = "", deviceModel = "", androidSdk = 0,
                    capturedAt = Instant.now(), durationNanos = 0, fileSizeBytes = traceFile.fileSize(),
                )
                exporter.exportSessionPackage(session, saveFile.toPath())
            }
        },
        onExportRawTrace = {
            coroutineScope.launch(Dispatchers.IO) {
                val traceFile = activeTraceFile ?: return@launch
                val saveFile = chooseSaveFile("trace.pftrace") ?: return@launch
                java.nio.file.Files.copy(traceFile, saveFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        },
        onOpenRecent = { path -> activeTraceFile = path; launchTraceInUi(path, uiServer) },
        onClearRecent = { recentFiles = emptyList() },
    )

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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
                captureState = captureState, adbPath = adbPath,
                onAdbPathChange = { adbPath = it },
                onStartCapture = { config, deviceSerial ->
                    coroutineScope.launch { captureSession.startCapture(adbPath, deviceSerial, config) }
                },
                onStopCapture = { coroutineScope.launch { captureSession.stopCapture() } },
                onOpenTrace = { traceFile ->
                    activeTraceFile = traceFile
                    launchTraceInUi(traceFile, uiServer)
                },
                modifier = Modifier.weight(1f),
            )
            if (sessions.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("Recent Sessions", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                sessions.take(10).forEach { session ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(session.captureConfig.template.displayName)
                                Text("${session.deviceModel} ${session.capturedAt} ${session.fileSizeBytes/1024/1024}MB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { launchTraceInUi(session.traceFile, uiServer) }
                                ) { Text("Open") }
                                OutlinedButton(onClick = {
                                    coroutineScope.launch {
                                        sessionStore.delete(session.id)
                                        sessions = sessions.filter { it.id != session.id }
                                    }
                                }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { uiServer.stop() } }
}

private fun launchTraceInUi(traceFile: Path, uiServer: PerfettoUiServer) {
    val assetsDir = PerfettoUiServer.tryFindUiAssetsDir()
    uiServer.start(assetsDir)
    uiServer.openTrace(traceFile)
}

private fun chooseTraceFile(): File? = JFileChooser().run {
    dialogTitle = "Open Perfetto Trace"
    fileFilter = FileNameExtensionFilter("Perfetto Traces", "pftrace", "perfetto-trace")
    if (showOpenDialog(null) == JFileChooser.APPROVE_OPTION) selectedFile else null
}

private fun chooseSaveFile(defaultName: String): File? = JFileChooser().run {
    dialogTitle = "Export Trace"
    selectedFile = File(defaultName)
    if (showSaveDialog(null) == JFileChooser.APPROVE_OPTION) selectedFile else null
}
