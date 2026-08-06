@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod", "MagicNumber")

package com.androidperformancestudio.network.app

import androidx.compose.foundation.isSystemInDarkTheme
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
import com.androidperformancestudio.network.network_app.generated.resources.*
import com.androidperformancestudio.network.network_app.generated.resources.Res
import com.androidperformancestudio.network.presentation.NetworkProfilerActions
import com.androidperformancestudio.network.presentation.NetworkProfilerScreen
import com.androidperformancestudio.network.presentation.NetworkProfilerState
import com.androidperformancestudio.network.storage.SqliteNetworkStore
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerCompactTextField
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.button.HomeButton
import com.androidperformancestudio.ui.ProfilerMacOsToolbar
import com.androidperformancestudio.ui.ProfilerToolbarStatus
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
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
import kotlin.time.Duration.Companion.milliseconds

@Composable
public fun FrameWindowScope.NetworkProfilerMainPage(language: UiLanguage = UiLanguage.ENGLISH, darkTheme: Boolean = isSystemInDarkTheme(), onBack: () -> Unit = {}) {
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
                withContext(Dispatchers.Main) { state = state.copy(capturing = true, message = localizedStringResource(Res.string.agent_session_started, language), error = null) }
                while (isActive) {
                    delay(750.milliseconds)
                    runCatching { capture.poll(session) }.onSuccess { events -> withContext(Dispatchers.Main) { state = state.copy(liveEventCount = state.liveEventCount + events.size, message = localizedStringResource(Res.string.captured_raw_events, language, state.liveEventCount + events.size)) } }.onFailure { cancel("poll failed", it) }
                }
            }.onFailure { withContext(Dispatchers.Main) { state = state.copy(capturing = false, error = it.message) } }
        }
    }

    fun stop() {
        val session = active ?: return
        pollJob?.cancel()
        scope.launch(Dispatchers.IO) {
            runCatching { capture.stop(session) }.onSuccess { withContext(Dispatchers.Main) { complete(it, localizedStringResource(Res.string.capture_completed, language, it.calls.size)) } }.onFailure { withContext(Dispatchers.Main) { state = state.copy(capturing = false, error = it.message) } }
            active = null
        }
    }
    ViewerTheme(darkTheme = darkTheme) {
    Column(Modifier.fillMaxSize()) {
        ProfilerMacOsToolbar {
            HomeButton(
                contentDescription = localizedStringResource(Res.string.back_to_home, language),
                onClick = {
                    if (state.capturing)stop()
                    onBack()
                },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.import_har, language),
                enabled = !state.capturing,
                onClick = {
                    chooseHar(window, language)?.let { file ->
                        runCatching { HarParser().parse(file.toPath()) }
                            .onSuccess {
                                complete(
                                    it,
                                    localizedStringResource(Res.string.imported_redacted, language, file.name),
                                )
                            }.onFailure { state = state.copy(error = it.message) }
                    }
                },
            )
            ProfilerCompactTextField(
                label = localizedStringResource(Res.string.device_serial, language),
                value = state.deviceSerial,
                onValueChange = { state = state.copy(deviceSerial = it) },
                enabled = !state.capturing,
                modifier = Modifier.width(180.dp),
            )
            ProfilerCompactTextField(
                label = localizedStringResource(Res.string.`package`, language),
                value = state.packageName,
                onValueChange = { state = state.copy(packageName = it) },
                enabled = !state.capturing,
                modifier = Modifier.width(240.dp),
            )
            ProfilerCompactButton(
                text =
                    if (state.capturing) {
                        localizedStringResource(Res.string.stop_capture, language)
                    } else {
                        localizedStringResource(Res.string.live_capture, language)
                    },
                enabled = state.deviceSerial.isNotBlank() && state.packageName.isNotBlank(),
                onClick = { if (state.capturing) stop() else start() },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.json, language),
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
                text = localizedStringResource(Res.string.har, language),
                enabled = state.result != null,
                onClick = {
                    chooseSave(window, "network-session.har")?.let {
                        exporter.writePartialHar(requireNotNull(state.result), it.toPath())
                    }
                },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.csv, language),
                enabled = state.result != null,
                onClick = {
                    chooseSave(window, "network-session.csv")?.let {
                        exporter.writeCsv(requireNotNull(state.result), it.toPath())
                    }
                },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.raw_bundle, language),
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
        NetworkProfilerScreen(state, NetworkProfilerActions { state = state.copy(selectedCallId = it) }, language, Modifier.weight(1f))
    }
    }
}

private fun chooseHar(parent: java.awt.Component, language: UiLanguage): File? = JFileChooser().run {
    dialogTitle = localizedStringResource(Res.string.import_har, language)
    fileFilter = FileNameExtensionFilter(localizedStringResource(Res.string.http_archive, language), "har", "json")
    if (showOpenDialog(parent) == JFileChooser.APPROVE_OPTION)selectedFile else null
}

private fun chooseSave(parent: java.awt.Component, name: String): File? = JFileChooser().run {
    selectedFile = File(name)
    if (showSaveDialog(parent) == JFileChooser.APPROVE_OPTION)selectedFile else null
}
