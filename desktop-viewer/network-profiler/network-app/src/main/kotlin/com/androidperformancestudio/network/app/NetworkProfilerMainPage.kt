@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod", "MagicNumber")

package com.androidperformancestudio.network.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.androidperformancestudio.contracts.ArtifactFileEvidence
import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.contracts.CaptureArtifactJson
import com.androidperformancestudio.network.analysis.NetworkAnalyzer
import com.androidperformancestudio.network.capture.NetworkAgentCapture
import com.androidperformancestudio.network.export.NetworkExporter
import com.androidperformancestudio.network.network_app.generated.resources.*
import com.androidperformancestudio.network.network_app.generated.resources.Res
import com.androidperformancestudio.network.presentation.NetworkProfilerActions
import com.androidperformancestudio.network.presentation.NetworkProfilerScreen
import com.androidperformancestudio.network.presentation.NetworkProfilerState
import com.androidperformancestudio.network.storage.SqliteNetworkStore
import com.androidperformancestudio.platform.toolchain.NativeSummaryShadow
import com.androidperformancestudio.ui.HeaderSpacer
import com.androidperformancestudio.ui.HeaderToolbar
import com.androidperformancestudio.ui.LocalTitleBarTargetHost
import com.androidperformancestudio.ui.LocalWindowMenuBarActive
import com.androidperformancestudio.ui.ProfilerAiAnalysisButton
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerCompactTextField
import com.androidperformancestudio.ui.ProfilerToolbarStatus
import com.androidperformancestudio.ui.PublishAiContext
import com.androidperformancestudio.ui.TitleBarTargetHost
import com.androidperformancestudio.ui.TitleBarTargetOrInline
import com.androidperformancestudio.ui.TitleBarTargetSlot
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.boundedAiContext
import com.androidperformancestudio.ui.chooseOpenFile
import com.androidperformancestudio.ui.chooseSaveFile
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

@Composable
public fun FrameWindowScope.NetworkProfilerMainPage(
    language: UiLanguage = UiLanguage.ENGLISH,
    onBack: () -> Unit = {},
    nativeHarEngineEnabled: Boolean = true,
) {
    val analyzer = remember { NetworkAnalyzer() }
    val exporter = remember { NetworkExporter() }
    val capture = remember { NetworkAgentCapture() }
    val scope = rememberCoroutineScope()
    val db = remember { Path.of(System.getProperty("user.home"), ".android-performance-studio", "network", "network.db") }
    var pollJob by remember { mutableStateOf<Job?>(null) }
    var stopRequested by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(NetworkProfilerState()) }
    val aiContext = remember(state) { runCatching { boundedAiContext(networkAiContext(state)) }.getOrNull() }
    PublishAiContext("network", aiContext)
    val targetHost = LocalTitleBarTargetHost.current.takeIf { LocalWindowMenuBarActive.current }

    LaunchedEffect(targetHost, targetHost?.selectedDeviceSerial, targetHost?.selectedPackageName, state.capturing) {
        state = state.withTitleBarTarget(targetHost)
    }

    fun complete(result: com.androidperformancestudio.network.model.NetworkCaptureResult, message: String, artifact: CaptureArtifact, durationMillis: Long = 0L, engine: String = "Capture") {
        val summary = analyzer.summarize(result.calls)
        runCatching { SqliteNetworkStore.open(db).use { it.save(result) } }
        runCatching {
            val directory = db.toAbsolutePath().parent.resolve("capture-artifacts")
            Files.createDirectories(directory)
            Files.writeString(directory.resolve("${artifact.id.value}.capture-artifact.json"), CaptureArtifactJson.encode(artifact))
        }
        state = state.copy(capturing = false, result = result, summary = summary, selectedCallId = result.calls.firstOrNull()?.callId, message = message, error = null, artifact = artifact, importDurationMillis = durationMillis, importEngine = engine)
    }

    fun start() {
        if (state.capturing || !state.canCaptureWith(targetHost)) return
        val serial = state.deviceSerial
        val packageName = state.packageName
        stopRequested = false
        state = state.copy(capturing = true, liveEventCount = 0, message = null, error = null)
        pollJob = scope.launch {
            runNetworkCaptureSession(
                start = { capture.start(serial, packageName) },
                poll = { session -> capture.poll(session).size },
                stop = capture::stop,
                publishResult = { stopRequested },
                callbacks = NetworkCaptureCallbacks(
                    onStarted = {
                        state = state.copy(message = localizedStringResource(Res.string.agent_session_started, language), error = null)
                    },
                    onEvents = { count ->
                        val total = state.liveEventCount + count
                        state = state.copy(liveEventCount = total, message = localizedStringResource(Res.string.captured_raw_events, language, total))
                    },
                    onCompleted = { result ->
                        complete(result, localizedStringResource(Res.string.capture_completed, language, result.calls.size), NetworkArtifactFactory().agent(result), engine = "Agent")
                    },
                    onFailure = { error -> state = state.copy(error = error.message ?: error.javaClass.simpleName) },
                    onFinished = {
                        pollJob = null
                        state = state.copy(capturing = false)
                    },
                ),
            )
        }
    }

    fun stop() {
        if (stopRequested) return
        stopRequested = true
        pollJob?.cancel()
    }
    Column(Modifier.fillMaxSize()) {
        HeaderToolbar(
            language = language,
            onNavigateHome = {
                if (state.capturing) stop()
                onBack()
            },
            onNavigateSettings = null,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProfilerCompactButton(
                        text = localizedStringResource(Res.string.import_har, language),
                        enabled = !state.capturing,
                        onClick = {
                            chooseOpenFile(
                                window,
                                localizedStringResource(Res.string.import_har, language),
                                localizedStringResource(Res.string.http_archive, language),
                                "har",
                                "json",
                            )?.let { file ->
                                stopRequested = false
                                state = state.copy(capturing = true, error = null)
                                pollJob = scope.launch {
                                    try {
                                        val importStarted = System.nanoTime()
                                        val imported = withContext(Dispatchers.IO) {
                                            val executable = NativeSummaryShadow.configured("aps.harEngine.path", "APS_HAR_ENGINE")
                                            val mode = if (nativeHarEngineEnabled) HarImportMode.NATIVE_WITH_LEGACY_FALLBACK else HarImportMode.SHADOW
                                            val shadowExecutable = executable?.takeIf { !nativeHarEngineEnabled }
                                            val digest = shadowExecutable?.let { ArtifactFileEvidence.sha256(file.toPath()).value }
                                            val context = currentCoroutineContext()
                                            val result = HarImportParser(mode, executable).parse(file.toPath()) { context.ensureActive() }
                                            val artifact = NetworkArtifactFactory().har(file.toPath(), result)
                                            shadowExecutable?.let {
                                                val shadow = NativeSummaryShadow().compare(
                                                    it, file.toPath(), mapOf("entryCount" to result.calls.size.toLong()),
                                                    expectedSourceDigest = digest,
                                                )
                                                runCatching {
                                                    val directory = db.toAbsolutePath().parent.resolve("native-shadow")
                                                    Files.createDirectories(directory)
                                                    Files.writeString(directory.resolve("${artifact.id.value}.properties"), shadow.properties())
                                                }
                                            }
                                            result to artifact
                                        }
                                        complete(
                                            imported.first,
                                            localizedStringResource(Res.string.imported_redacted, language, file.name),
                                            imported.second,
                                            (System.nanoTime() - importStarted) / 1_000_000L,
                                            if (nativeHarEngineEnabled) "Rust" else "Legacy",
                                        )
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        state = state.copy(error = error.message)
                                    } finally {
                                        pollJob = null
                                        state = state.copy(capturing = false)
                                    }
                                }
                            }
                        },
                    )
                    TitleBarTargetOrInline(TitleBarTargetSlot.DEVICE) {
                        HeaderSpacer()
                        ProfilerCompactTextField(
                            label = localizedStringResource(Res.string.device_serial, language),
                            value = state.deviceSerial,
                            onValueChange = { state = state.copy(deviceSerial = it) },
                            enabled = !state.capturing,
                            modifier = Modifier.width(180.dp),
                        )
                    }
                    TitleBarTargetOrInline(TitleBarTargetSlot.PROCESS) {
                        HeaderSpacer()
                        ProfilerCompactTextField(
                            label = localizedStringResource(Res.string.`package`, language),
                            value = state.packageName,
                            onValueChange = { state = state.copy(packageName = it) },
                            enabled = !state.capturing,
                            modifier = Modifier.width(240.dp),
                        )
                    }
                    HeaderSpacer()
                    ProfilerCompactButton(
                        text =
                            if (state.capturing) {
                                localizedStringResource(Res.string.stop_capture, language)
                            } else {
                                localizedStringResource(Res.string.live_capture, language)
                            },
                        enabled = state.canCaptureWith(targetHost),
                        onClick = { if (state.capturing) stop() else start() },
                    )
                    HeaderSpacer()
                    ProfilerCompactButton(
                        text = localizedStringResource(Res.string.json, language),
                        enabled = state.result != null,
                        onClick = {
                            chooseSaveFile(window, localizedStringResource(Res.string.json, language), "network-session.json")?.let {
                                exporter.writeJson(
                                    requireNotNull(state.result),
                                    requireNotNull(state.summary),
                                    it.toPath(),
                                )
                            }
                        },
                    )
                    HeaderSpacer()
                    ProfilerCompactButton(
                        text = localizedStringResource(Res.string.har, language),
                        enabled = state.result != null,
                        onClick = {
                            chooseSaveFile(window, localizedStringResource(Res.string.har, language), "network-session.har")?.let {
                                exporter.writePartialHar(requireNotNull(state.result), it.toPath())
                            }
                        },
                    )
                    HeaderSpacer()
                    ProfilerCompactButton(
                        text = localizedStringResource(Res.string.csv, language),
                        enabled = state.result != null,
                        onClick = {
                            chooseSaveFile(window, localizedStringResource(Res.string.csv, language), "network-session.csv")?.let {
                                exporter.writeCsv(requireNotNull(state.result), it.toPath())
                            }
                        },
                    )
                    HeaderSpacer()
                    ProfilerCompactButton(
                        text = localizedStringResource(Res.string.raw_bundle, language),
                        enabled = state.result != null,
                        onClick = {
                            chooseSaveFile(window, localizedStringResource(Res.string.raw_bundle, language), "network-raw-bundle.zip")?.let {
                                exporter.writeRawBundle(
                                    requireNotNull(state.result),
                                    requireNotNull(state.summary),
                                    it.toPath(),
                                )
                            }
                        },
                    )
                    HeaderSpacer()
                    ProfilerAiAnalysisButton(aiContext, language)
                }
                Spacer(Modifier.width(8.dp))
                ProfilerToolbarStatus(state.message, state.error, Modifier.widthIn(max = 240.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        NetworkProfilerScreen(state, NetworkProfilerActions { state = state.copy(selectedCallId = it) }, language, Modifier.weight(1f))
        if (state.importDurationMillis != null && state.importEngine != null) {
            Text(
                text = "${state.importEngine} · import ${state.importDurationMillis} ms",
                modifier = Modifier.fillMaxWidth().widthIn(min = 0.dp).padding(horizontal = 12.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

internal fun NetworkProfilerState.withTitleBarTarget(host: TitleBarTargetHost?): NetworkProfilerState {
    if (host == null || capturing) return this
    return copy(deviceSerial = host.selectedDeviceSerial.orEmpty(), packageName = host.selectedPackageName.orEmpty())
}

internal fun NetworkProfilerState.canCaptureWith(host: TitleBarTargetHost?): Boolean {
    if (capturing) return true
    return deviceSerial.isNotBlank() &&
        packageName.isNotBlank() &&
        (host == null || (host.selectedDeviceSerial == deviceSerial && host.selectedPackageName == packageName))
}
