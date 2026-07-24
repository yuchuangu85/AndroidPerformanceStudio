@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod", "MagicNumber")

package com.androidperformancestudio.network.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.network.analysis.NetworkAnalyzer
import com.androidperformancestudio.network.capture.ActiveNetworkCapture
import com.androidperformancestudio.network.capture.NetworkAgentCapture
import com.androidperformancestudio.network.export.NetworkExporter
import com.androidperformancestudio.network.har.HarParser
import com.androidperformancestudio.network.presentation.NetworkProfilerActions
import com.androidperformancestudio.network.presentation.NetworkProfilerScreen
import com.androidperformancestudio.network.presentation.NetworkProfilerState
import com.androidperformancestudio.network.storage.SqliteNetworkStore
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
public fun FrameWindowScope.NetworkProfilerWorkspace(chinese: Boolean = false, onBack: () -> Unit = {}) {
    val analyzer = remember { NetworkAnalyzer() }
    val exporter = remember { NetworkExporter() }
    val capture = remember { NetworkAgentCapture() }
    val scope = rememberCoroutineScope()
    val db = remember { Path.of(System.getProperty("user.home"), ".android-performance-studio", "network", "network.db") }
    var active by remember { mutableStateOf<ActiveNetworkCapture?>(null) }
    var pollJob by remember { mutableStateOf<Job?>(null) }
    var state by remember { mutableStateOf(NetworkProfilerState()) }

    fun complete(result: com.androidperformancestudio.network.model.NetworkCaptureResult, message: String) {
        val summary = analyzer.summarize(result.calls)
        runCatching { SqliteNetworkStore.open(db).use { it.save(result) } }
        state = state.copy(capturing = false, result = result, summary = summary, selectedCallId = result.calls.firstOrNull()?.callId, message = message, error = null)
    }

    fun start() {
        pollJob = scope.launch(Dispatchers.IO) {
            runCatching { capture.start(state.deviceSerial, state.packageName) }.onSuccess { session ->
                active = session
                withContext(Dispatchers.Main) { state = state.copy(capturing = true, message = "Authenticated Network Agent session started.", error = null) }
                while (isActive) {
                    delay(750)
                    runCatching { capture.poll(session) }.onSuccess { events -> withContext(Dispatchers.Main) { state = state.copy(liveEventCount = state.liveEventCount + events.size, message = "Captured ${state.liveEventCount + events.size} raw events. Bodies are not collected.") } }.onFailure { cancel("poll failed", it) }
                }
            }.onFailure { withContext(Dispatchers.Main) { state = state.copy(capturing = false, error = it.message) } }
        }
    }

    fun stop() {
        val session = active ?: return
        pollJob?.cancel()
        scope.launch(Dispatchers.IO) {
            runCatching { capture.stop(session) }.onSuccess { withContext(Dispatchers.Main) { complete(it, "Online capture completed with ${it.calls.size} observed OkHttp calls.") } }.onFailure { withContext(Dispatchers.Main) { state = state.copy(capturing = false, error = it.message) } }
            active = null
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                if (state.capturing)stop()
                onBack()
            }) { Text(if (chinese)"返回主页" else "Back to Home") }
            OutlinedButton(enabled = !state.capturing, onClick = { chooseHar(window)?.let { file -> runCatching { HarParser().parse(file.toPath()) }.onSuccess { complete(it, "Imported ${file.name}; sensitive headers and query values were redacted.") }.onFailure { state = state.copy(error = it.message) } } }) { Text(if (chinese)"导入 HAR" else "Import HAR") }
            OutlinedTextField(state.deviceSerial, { state = state.copy(deviceSerial = it) }, label = { Text(if (chinese)"设备序列号" else "Device serial") }, enabled = !state.capturing, singleLine = true, modifier = Modifier.width(180.dp))
            OutlinedTextField(state.packageName, { state = state.copy(packageName = it) }, label = { Text(if (chinese)"包名" else "Package") }, enabled = !state.capturing, singleLine = true, modifier = Modifier.width(240.dp))
            Button(enabled = state.deviceSerial.isNotBlank() && state.packageName.isNotBlank(), onClick = { if (state.capturing)stop() else start() }) { Text(if (state.capturing)(if (chinese)"停止采集" else "Stop Capture") else (if (chinese)"在线采集" else "Live Capture")) }
            OutlinedButton(enabled = state.result != null, onClick = { chooseSave(window, "network-session.json")?.let { exporter.writeJson(requireNotNull(state.result), requireNotNull(state.summary), it.toPath()) } }) { Text("JSON") }
            OutlinedButton(enabled = state.result != null, onClick = { chooseSave(window, "network-session.har")?.let { exporter.writePartialHar(requireNotNull(state.result), it.toPath()) } }) { Text("HAR") }
            OutlinedButton(enabled = state.result != null, onClick = { chooseSave(window, "network-session.csv")?.let { exporter.writeCsv(requireNotNull(state.result), it.toPath()) } }) { Text("CSV") }
            OutlinedButton(enabled = state.result != null, onClick = { chooseSave(window, "network-raw-bundle.zip")?.let { exporter.writeRawBundle(requireNotNull(state.result), requireNotNull(state.summary), it.toPath()) } }) { Text(if (chinese)"原始包" else "Raw Bundle") }
        }
        NetworkProfilerScreen(state, NetworkProfilerActions { state = state.copy(selectedCallId = it) }, chinese, Modifier.weight(1f))
    }
}

private fun chooseHar(parent: java.awt.Component): File? = JFileChooser().run {
    dialogTitle = "Import HAR"
    fileFilter = FileNameExtensionFilter("HTTP Archive", "har", "json")
    if (showOpenDialog(parent) == JFileChooser.APPROVE_OPTION)selectedFile else null
}

private fun chooseSave(parent: java.awt.Component, name: String): File? = JFileChooser().run {
    selectedFile = File(name)
    if (showSaveDialog(parent) == JFileChooser.APPROVE_OPTION)selectedFile else null
}
