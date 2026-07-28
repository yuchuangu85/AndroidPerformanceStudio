@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod", "MagicNumber")

package com.androidperformancestudio.network.app

import org.jetbrains.compose.resources.stringResource

import com.androidperformancestudio.network.network_app.generated.resources.Res
import com.androidperformancestudio.network.network_app.generated.resources.*

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
import java.util.Locale
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
    val agentSessionStarted = stringResource(Res.string.agent_session_started)
    val capturedRawEventsTemplate = stringResource(Res.string.captured_raw_events)
    val captureCompletedTemplate = stringResource(Res.string.capture_completed)
    val importedRedactedTemplate = stringResource(Res.string.imported_redacted)
    val importHarTitle = stringResource(Res.string.import_har)
    val httpArchiveLabel = stringResource(Res.string.http_archive)

    fun complete(result: com.androidperformancestudio.network.model.NetworkCaptureResult, message: String) {
        val summary = analyzer.summarize(result.calls)
        runCatching { SqliteNetworkStore.open(db).use { it.save(result) } }
        state = state.copy(capturing = false, result = result, summary = summary, selectedCallId = result.calls.firstOrNull()?.callId, message = message, error = null)
    }

    fun start() {
        pollJob = scope.launch(Dispatchers.IO) {
            runCatching { capture.start(state.deviceSerial, state.packageName) }.onSuccess { session ->
                active = session
                withContext(Dispatchers.Main) { state = state.copy(capturing = true, message = agentSessionStarted, error = null) }
                while (isActive) {
                    delay(750)
                    runCatching { capture.poll(session) }.onSuccess { events ->
                        withContext(Dispatchers.Main) {
                            val eventCount = state.liveEventCount + events.size
                            state =
                                state.copy(
                                    liveEventCount = eventCount,
                                    message = String.format(Locale.ROOT, capturedRawEventsTemplate, eventCount),
                                )
                        }
                    }.onFailure { cancel("poll failed", it) }
                }
            }.onFailure { withContext(Dispatchers.Main) { state = state.copy(capturing = false, error = it.message) } }
        }
    }

    fun stop() {
        val session = active ?: return
        pollJob?.cancel()
        scope.launch(Dispatchers.IO) {
            runCatching { capture.stop(session) }.onSuccess {
                withContext(Dispatchers.Main) {
                    complete(it, String.format(Locale.ROOT, captureCompletedTemplate, it.calls.size))
                }
            }.onFailure { withContext(Dispatchers.Main) { state = state.copy(capturing = false, error = it.message) } }
            active = null
        }
    }
    Column(Modifier.fillMaxSize()) {
        ProfilerMacOsToolbar {
            ProfilerHomeButton(
                contentDescription = stringResource(Res.string.back_to_home),
                onClick = {
                    if (state.capturing)stop()
                    onBack()
                },
            )
            ProfilerCompactButton(
                text = stringResource(Res.string.import_har),
                enabled = !state.capturing,
                onClick = {
                    chooseHar(window, importHarTitle, httpArchiveLabel)?.let { file ->
                        runCatching { HarParser().parse(file.toPath()) }
                            .onSuccess {
                                complete(
                                    it,
                                    String.format(Locale.ROOT, importedRedactedTemplate, file.name),
                                )
                            }.onFailure { state = state.copy(error = it.message) }
                    }
                },
            )
            ProfilerCompactTextField(
                label = stringResource(Res.string.device_serial),
                value = state.deviceSerial,
                onValueChange = { state = state.copy(deviceSerial = it) },
                enabled = !state.capturing,
                modifier = Modifier.width(180.dp),
            )
            ProfilerCompactTextField(
                label = stringResource(Res.string.`package`),
                value = state.packageName,
                onValueChange = { state = state.copy(packageName = it) },
                enabled = !state.capturing,
                modifier = Modifier.width(240.dp),
            )
            ProfilerCompactButton(
                text =
                    if (state.capturing) {
                        stringResource(Res.string.stop_capture)
                    } else {
                        stringResource(Res.string.live_capture)
                    },
                enabled = state.deviceSerial.isNotBlank() && state.packageName.isNotBlank(),
                onClick = { if (state.capturing) stop() else start() },
            )
            ProfilerCompactButton(
                text = stringResource(Res.string.json),
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
                text = stringResource(Res.string.har),
                enabled = state.result != null,
                onClick = {
                    chooseSave(window, "network-session.har")?.let {
                        exporter.writePartialHar(requireNotNull(state.result), it.toPath())
                    }
                },
            )
            ProfilerCompactButton(
                text = stringResource(Res.string.csv),
                enabled = state.result != null,
                onClick = {
                    chooseSave(window, "network-session.csv")?.let {
                        exporter.writeCsv(requireNotNull(state.result), it.toPath())
                    }
                },
            )
            ProfilerCompactButton(
                text = stringResource(Res.string.raw_bundle),
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

private fun chooseHar(
    parent: java.awt.Component,
    dialogTitle: String,
    fileFilterLabel: String,
): File? = JFileChooser().run {
    this.dialogTitle = dialogTitle
    fileFilter = FileNameExtensionFilter(fileFilterLabel, "har", "json")
    if (showOpenDialog(parent) == JFileChooser.APPROVE_OPTION)selectedFile else null
}

private fun chooseSave(parent: java.awt.Component, name: String): File? = JFileChooser().run {
    selectedFile = File(name)
    if (showSaveDialog(parent) == JFileChooser.APPROVE_OPTION)selectedFile else null
}
