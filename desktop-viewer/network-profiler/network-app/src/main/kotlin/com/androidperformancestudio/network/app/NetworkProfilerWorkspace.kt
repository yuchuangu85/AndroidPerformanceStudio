@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod", "MagicNumber")

package com.androidperformancestudio.network.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerCompactTextField
import com.androidperformancestudio.ui.ProfilerHomeButton
import com.androidperformancestudio.ui.ProfilerMacOsToolbar
import com.androidperformancestudio.ui.ProfilerToolbarStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        ProfilerMacOsToolbar {
            ProfilerHomeButton(
                contentDescription = if (chinese) "返回主页" else "Back to home",
                onClick = {
                    if (state.capturing)stop()
                    onBack()
                },
            )
            ProfilerCompactButton(
                text = if (chinese) "导入 HAR" else "Import HAR",
                enabled = !state.capturing,
                onClick = {
                    chooseHar(window)?.let { file ->
                        runCatching { HarParser().parse(file.toPath()) }
                            .onSuccess {
                                complete(
                                    it,
                                    "Imported ${file.name}; sensitive headers and query values were redacted.",
                                )
                            }.onFailure { state = state.copy(error = it.message) }
                    }
                },
            )
            ProfilerCompactTextField(
                label = if (chinese) "设备序列号" else "Device serial",
                value = state.deviceSerial,
                onValueChange = { state = state.copy(deviceSerial = it) },
                enabled = !state.capturing,
                modifier = Modifier.width(180.dp),
            )
            ProfilerCompactTextField(
                label = if (chinese) "包名" else "Package",
                value = state.packageName,
                onValueChange = { state = state.copy(packageName = it) },
                enabled = !state.capturing,
                modifier = Modifier.width(240.dp),
            )
            ProfilerCompactButton(
                text =
                    if (state.capturing) {
                        if (chinese) "停止采集" else "Stop Capture"
                    } else {
                        if (chinese) "在线采集" else "Live Capture"
                    },
                enabled = state.deviceSerial.isNotBlank() && state.packageName.isNotBlank(),
                onClick = { if (state.capturing) stop() else start() },
            )
            ProfilerCompactButton(
                text = "JSON",
                enabled = state.result != null,
                onClick = {
                    chooseSave(window, "network-session.json")?.let {
                        exporter.writeJson(
                            requireNotNull(state.result),
                            requireNotNull(state.summary),
                            it.toPath(),
                        )
                    }
                },
            )
            ProfilerCompactButton(
                text = "HAR",
                enabled = state.result != null,
                onClick = {
                    chooseSave(window, "network-session.har")?.let {
                        exporter.writePartialHar(requireNotNull(state.result), it.toPath())
                    }
                },
            )
            ProfilerCompactButton(
                text = "CSV",
                enabled = state.result != null,
                onClick = {
                    chooseSave(window, "network-session.csv")?.let {
                        exporter.writeCsv(requireNotNull(state.result), it.toPath())
                    }
                },
            )
            ProfilerCompactButton(
                text = if (chinese) "原始包" else "Raw Bundle",
                enabled = state.result != null,
                onClick = {
                    chooseSave(window, "network-raw-bundle.zip")?.let {
                        exporter.writeRawBundle(
                            requireNotNull(state.result),
                            requireNotNull(state.summary),
                            it.toPath(),
                        )
                    }
                },
            )
            Spacer(Modifier.weight(1f))
            ProfilerToolbarStatus(state.message, state.error)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
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
