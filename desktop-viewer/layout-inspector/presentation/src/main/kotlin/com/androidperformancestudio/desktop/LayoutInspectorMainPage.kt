package com.androidperformancestudio.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.presentation.generated.resources.Res
import com.androidperformancestudio.presentation.generated.resources.*
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.button.HomeButton
import com.androidperformancestudio.ui.button.SettingsButton
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.application.ConnectionStatus
import com.androidperformancestudio.application.InspectorState
import com.androidperformancestudio.application.InspectorStore
import com.androidperformancestudio.platform.adb.AdbDevice
import com.androidperformancestudio.platform.toolchain.RecentPathStore
import com.androidperformancestudio.adb.ConnectedDeviceSession
import com.androidperformancestudio.adb.AdbProcessRunner
import com.androidperformancestudio.adb.LiveDeviceClient
import com.androidperformancestudio.adb.VisibleWindowViewsTextRenderer
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.ProtocolCodec
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.compose.inspection.host.ComposeInjectionManager
import com.androidperformancestudio.compose.inspection.host.ComposeInspectionAuthorization
import com.androidperformancestudio.compose.inspection.host.ComposeInspectorArtifactResolver
import com.androidperformancestudio.compose.inspection.host.ComposeLiveSession
import com.androidperformancestudio.compose.inspection.host.PreparedComposeInspection
import com.androidperformancestudio.compose.inspection.ComposeArchivePrivacy
import com.androidperformancestudio.compose.inspection.ComposeParameterReference
import com.androidperformancestudio.compose.inspection.ComposeValue
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.HeaderDivider
import com.androidperformancestudio.ui.HeaderSpacer
import com.androidperformancestudio.ui.HeaderToolbar
import com.androidperformancestudio.ui.ProfilerCompactButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.skia.Image
import java.awt.Cursor
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.nio.file.Path
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

internal const val AUTO_SCAN_DEFAULT_ENABLED = false
// Source-aware payload preflight is complete; keep the user-facing analysis entry available.
internal const val AI_ANALYSIS_ENTRY_VISIBLE = true
internal const val SYSTEM_UI_PACKAGE_NAME = "com.android.systemui"
internal val FULL_COMPOSE_INSPECTION_VISIBLE: Boolean =
    System.getProperty("agentperf.compose.full.enabled", "false").toBoolean()

private data class AuthorizedComposeTarget(
    val prepared: PreparedComposeInspection,
    val authorization: ComposeInspectionAuthorization,
)

private sealed interface ComposeAuthorizationUiState {
    data object Idle : ComposeAuthorizationUiState
    data object Preparing : ComposeAuthorizationUiState
    data class Review(val prepared: PreparedComposeInspection) : ComposeAuthorizationUiState
    data class Failure(val message: String) : ComposeAuthorizationUiState
}

internal enum class CaptureTargetMode {
    FOREGROUND_APP,
    SYSTEM_UI,
}

private fun CaptureTargetMode.stringResource(): StringResource =
    when (this) {
        CaptureTargetMode.FOREGROUND_APP -> Res.string.foreground_app
        CaptureTargetMode.SYSTEM_UI -> Res.string.system_ui
    }

data class InspectorCorrelationHint(
    val deviceSerial: String?,
    val targetPackageName: String,
    val message: String,
    val correlationNotice: String,
    val foregroundMismatchPrefix: String,
)

@Composable
fun FrameWindowScope.LayoutInspectorMainPage(
    commonThemePreference: String? = null,
    commonLanguagePreference: String? = null,
    settingsRevision: Long = 0L,
    onNavigateHome: (() -> Unit)? = null,
    onOpenUnifiedSettings: (() -> Unit)? = null,
    onOpenMemoryProfiler: ((String) -> Unit)? = null,
    aiAnalysisClient: AiAnalysisClient? = null,
    onOpenSourceCandidate: ((String, com.androidperformancestudio.analysis.AiSourceCandidateReference?) -> Unit)? = null,
    onCanOpenSourceCandidate: ((String) -> Boolean)? = null,
    onCanOpenSourceCandidateDirectly: ((String) -> Boolean)? = null,
    onOpenComposeSource: ((String, Int, Int) -> Unit)? = null,
    correlationHint: InspectorCorrelationHint? = null,
) {
    val store = remember { createInitialInspectorStore() }
    var state by remember { mutableStateOf(store.state) }
    var autoScanEnabled by remember { mutableStateOf(AUTO_SCAN_DEFAULT_ENABLED) }
    var manualRefreshRequest by remember { mutableStateOf(0) }
    var manualRefreshInProgress by remember { mutableStateOf(false) }
    val deviceClient = remember { LiveDeviceClient() }
    val refreshTimingSink = remember { ConsoleRefreshTimingSink }
    var captureTargetMode by remember { mutableStateOf(CaptureTargetMode.FOREGROUND_APP) }
    val composeProcessRunner = remember { AdbProcessRunner() }
    val composeArtifactResolver = remember {
        ComposeInspectorArtifactResolver(
            cacheDir = Path.of(System.getProperty("user.home"), ".android-performance-studio", "compose-inspectors"),
        )
    }
    val composeInjectionManager = remember {
        ComposeInjectionManager(composeProcessRunner, composeArtifactResolver)
    }
    var fullComposeEnabled by remember { mutableStateOf(false) }
    var hideSystemComposables by remember { mutableStateOf(true) }
    var composeAuthorization by remember { mutableStateOf<AuthorizedComposeTarget?>(null) }
    var composeAuthorizationUiState by remember {
        mutableStateOf<ComposeAuthorizationUiState>(ComposeAuthorizationUiState.Idle)
    }
    var composeSession by remember { mutableStateOf<ComposeLiveSession?>(null) }
    var recompositionActive by remember { mutableStateOf(false) }
    val recompositionHeatTracker = remember { RecompositionHeatTracker() }
    var recompositionHeat by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    LaunchedEffect(state.composeInspection?.frame?.frameId) {
        recompositionHeat = recompositionHeatTracker.sample(state.composeInspection)
    }
    val manualRefreshSession = remember(deviceClient, captureTargetMode) {
        ReusableForegroundSession(
            connect = { serial -> deviceClient.connectTarget(captureTargetMode, serial) },
            isCurrent = { session -> captureTargetMode.isSessionCurrent(session) },
            capture = ConnectedDeviceSession::capture,
        )
    }
    DisposableEffect(manualRefreshSession) {
        onDispose { manualRefreshSession.close() }
    }
    var availableDevices by remember { mutableStateOf<List<AdbDevice>>(emptyList()) }
    var selectedDeviceSerial by remember(correlationHint) { mutableStateOf(correlationHint?.deviceSerial) }
    var deviceListRefreshRequest by remember { mutableStateOf(0) }
    val protocolCodec = remember { ProtocolCodec(supportedMajor = 1) }
    val archiveFileChooser = remember { SwingCaptureArchiveFileChooser() }
    val recentArchiveStore =
        remember {
            RecentPathStore.desktop(
                fileName = "recent-layout-inspector-archives.txt",
                temporaryFilePrefix = "recent-layout-archives-",
            )
        }
    var recentArchives by remember { mutableStateOf(recentArchiveStore.load()) }
    val archiveLimitsStore = remember { CaptureArchiveLimitsStore.desktop() }
    var archiveLimits by remember { mutableStateOf(archiveLimitsStore.load()) }
    val captureArchiveService = remember(protocolCodec, archiveLimits) {
        CaptureArchiveService(
            archiveCodec = CaptureArchiveCodec(limits = archiveLimits),
            protocolCodec = protocolCodec,
        )
    }
    val aiAnalysisInputBuilder = remember { AiAnalysisInputBuilder() }
    val effectiveAiAnalysisClient = remember(aiAnalysisClient) { aiAnalysisClient ?: OpenAiResponsesAnalysisClient.fromEnvironment() }
    var aiAnalysisUiState by remember { mutableStateOf<AiAnalysisUiState>(AiAnalysisUiState.Idle) }
    var pendingAiAnalysis by remember { mutableStateOf<PreparedAiAnalysis?>(null) }
    var aiAnalysisJob by remember { mutableStateOf<Job?>(null) }
    var archiveUiState by remember {
        mutableStateOf<CaptureArchiveUiState>(CaptureArchiveUiState.Idle)
    }
    var importedRawArtifacts by remember {
        mutableStateOf<CaptureRawArtifacts?>(null)
    }
    val coroutineScope = rememberCoroutineScope()
    var hiddenLayerState by remember { mutableStateOf(HiddenLayerState()) }
    var searchState by remember { mutableStateOf(HierarchySearchState()) }

    LaunchedEffect(deviceListRefreshRequest) {
        val devices = withContext(Dispatchers.IO) {
            runCatching { deviceClient.listAuthorizedDevices() }.getOrDefault(emptyList())
        }
        availableDevices = devices
        selectedDeviceSerial = sanitizeSelectedDeviceSerial(selectedDeviceSerial, devices)
    }

    LaunchedEffect(autoScanEnabled, captureTargetMode, fullComposeEnabled, composeAuthorization) {
        if (!autoScanEnabled) {
            if (store.state.connectionStatus != ConnectionStatus.ARCHIVE &&
                store.state.connectionStatus != ConnectionStatus.ERROR
            ) {
                store.disconnected()
                state = store.state
                aiAnalysisUiState = AiAnalysisUiState.Idle
            }
            return@LaunchedEffect
        }
        importedRawArtifacts = null
        manualRefreshSession.invalidate()
        while (currentCoroutineContext().isActive) {
            val timer = RefreshTimer("auto", refreshTimingSink)
            var session: ConnectedDeviceSession? = null
            var fullSession: ComposeLiveSession? = null
            try {
                store.connecting()
                state = store.state
                if (fullComposeEnabled) {
                    val authorized = checkNotNull(composeAuthorization) { "Full Compose inspection is not authorized" }
                    fullSession = withContext(Dispatchers.IO) {
                        val injected = composeInjectionManager.attach(authorized.prepared, authorized.authorization)
                        ComposeLiveSession.open(
                            injection = injected,
                            artifactResolver = composeArtifactResolver,
                            processRunner = composeProcessRunner,
                            expectedComposeVersion = checkNotNull(authorized.prepared.preflight.composeVersion),
                            explicitLocalArtifact = System.getProperty("agentperf.compose.inspector.path")
                                ?.takeIf(String::isNotBlank)?.let(Path::of),
                        )
                    }
                    composeSession = fullSession
                    if (recompositionActive) withContext(Dispatchers.IO) {
                        fullSession.startRecompositionObservation()
                    }
                    while (currentCoroutineContext().isActive) {
                        val capture = withContext(Dispatchers.IO) {
                            fullSession.capture(hideSystemComposables)
                        }
                        store.loadCapture(
                            snapshot = capture.snapshot,
                            screenshotPng = checkNotNull(capture.screenshotPng) { "ADB screenshot failed" },
                            composeInspection = capture.composeInspection,
                        )
                        importedRawArtifacts = null
                        hiddenLayerState = HiddenLayerState()
                        state = store.state
                        aiAnalysisUiState = AiAnalysisUiState.Idle
                        delay(
                            (if (recompositionActive) ACTIVE_COMPOSE_CAPTURE_INTERVAL_MILLIS
                            else CAPTURE_INTERVAL_MILLIS).milliseconds,
                        )
                    }
                    continue
                }
                session = withContext(Dispatchers.IO) {
                    timer.measure("connectTarget") {
                        deviceClient.connectTarget(captureTargetMode, selectedDeviceSerial)
                    }
                }
                while (currentCoroutineContext().isActive) {
                    val isCurrent = withContext(Dispatchers.IO) {
                        timer.measure("isTargetCurrent") {
                            captureTargetMode.isSessionCurrent(session)
                        }
                    }
                    if (!isCurrent) break
                    val frame = withContext(Dispatchers.IO) {
                        timer.measure("capture") { session.capture() }
                    }
                    val snapshot = timer.measure("decodeSnapshot") {
                        protocolCodec.decodeSnapshot(frame.snapshotJson)
                    }
                    timer.measure("publishCapture") {
                        store.loadCapture(
                            snapshot = snapshot,
                            screenshotPng = frame.screenshotPng,
                        )
                        importedRawArtifacts = null
                        hiddenLayerState = HiddenLayerState()
                        state = store.state
                        aiAnalysisUiState = AiAnalysisUiState.Idle
                    }
                    delay(CAPTURE_INTERVAL_MILLIS.milliseconds)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                store.connectionFailed(error.message ?: error.javaClass.simpleName)
                state = store.state
                if (fullComposeEnabled) {
                    composeAuthorization = null
                    fullComposeEnabled = false
                    recompositionActive = false
                    autoScanEnabled = false
                } else {
                    delay(RECONNECT_INTERVAL_MILLIS.milliseconds)
                }
            } finally {
                composeSession = null
                withContext(NonCancellable + Dispatchers.IO) {
                    fullSession?.close()
                    session?.close()
                }
            }
        }
    }

    LaunchedEffect(state.selectedNodeId, state.composeInspection?.frame?.frameId, composeSession) {
        val document = state.composeInspection ?: return@LaunchedEffect
        val nodeId = state.selectedNodeId?.removePrefix("compose-inspection:")?.toLongOrNull()
            ?: return@LaunchedEffect
        if (document.frame.details.containsKey(nodeId)) return@LaunchedEffect
        val node = document.frame.roots.asSequence().flatMap { it.nodes.asSequence() }
            .firstNotNullOfOrNull { it.findComposeNode(nodeId) } ?: return@LaunchedEffect
        val detail = withContext(Dispatchers.IO) {
            composeSession?.loadDetail(nodeId, node.anchorHash)
        } ?: return@LaunchedEffect
        if (store.loadComposeDetail(document.frame.frameId, detail)) state = store.state
    }

    LaunchedEffect(manualRefreshRequest, autoScanEnabled, fullComposeEnabled) {
        if (manualRefreshRequest == 0 || autoScanEnabled || fullComposeEnabled) {
            manualRefreshInProgress = false
            return@LaunchedEffect
        }
        val timer = RefreshTimer("manual", refreshTimingSink)
        manualRefreshInProgress = true
        try {
            store.connecting()
            state = store.state
            val frame = withContext(Dispatchers.IO) {
                timer.measure("captureForegroundApp") {
                    manualRefreshSession.capture(selectedDeviceSerial)
                }
            }
            val snapshot = timer.measure("decodeSnapshot") {
                protocolCodec.decodeSnapshot(frame.snapshotJson)
            }
            timer.measure("publishCapture") {
                store.loadCapture(
                    snapshot = snapshot,
                    screenshotPng = frame.screenshotPng,
                )
                importedRawArtifacts = null
                hiddenLayerState = HiddenLayerState()
                state = store.state
                aiAnalysisUiState = AiAnalysisUiState.Idle
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            manualRefreshSession.invalidate()
            store.connectionFailed(error.message ?: error.javaClass.simpleName)
            state = store.state
        } finally {
            manualRefreshInProgress = false
        }
    }

    var paneWidths by remember { mutableStateOf(PaneWidths()) }
    var findingsHeightDp by remember { mutableStateOf(FindingsLayout.DEFAULT_HEIGHT_DP) }
    var panelVisibility by remember { mutableStateOf(PanelVisibility()) }
    var hierarchyTreeState by remember { mutableStateOf(HierarchyTreeState()) }
    var hierarchyIsolationState by remember { mutableStateOf(HierarchyIsolationState()) }
    val viewDisplayOptionsStore = remember { ViewDisplayOptionsStore.desktop() }
    var viewDisplayOptions by remember {
        mutableStateOf(viewDisplayOptionsStore.load())
    }
    val themePreference =
        commonThemePreference?.let(ThemePreference::fromStorage) ?: ThemePreference.SYSTEM
    val languagePreference =
        commonLanguagePreference?.let(LanguagePreference::fromStorage) ?: LanguagePreference.SYSTEM
    val canvasBorderColorStore = remember { CanvasBorderColorStore.desktop() }
    var canvasBorderColors by remember { mutableStateOf(canvasBorderColorStore.load()) }
    val uiLanguage = languagePreference.resolve(Locale.getDefault())
    var settingsVisible by remember { mutableStateOf(false) }
    val darkTheme = themePreference.resolveDark(isSystemInDarkTheme())
    LaunchedEffect(settingsRevision) {
        if (settingsRevision > 0L) {
            archiveLimits = archiveLimitsStore.load()
            viewDisplayOptions = viewDisplayOptionsStore.load()
            canvasBorderColors = canvasBorderColorStore.load()
        }
    }
    val appFocusRequester = remember { FocusRequester() }
    var pendingComposeExportConsent by remember { mutableStateOf(false) }
    val performExportCaptureArchive: (ComposeArchivePrivacy) -> Unit = exportCaptureArchive@{ composePrivacy ->
        if (archiveUiState is CaptureArchiveUiState.Working) {
            return@exportCaptureArchive
        }
        val snapshot = state.snapshot ?: return@exportCaptureArchive
        val screenshot = state.screenshotPng?.copyOf()?.takeIf { it.isNotEmpty() }
        val analysis = state.analysis
        val aiAnalysis = state.aiAnalysis
        val timelineFrames = state.timelineFrames
        val captureStatus = state.connectionStatus
        val preservedRawArtifacts = importedRawArtifacts
        val target = archiveFileChooser.chooseExport(
            title = localizedStringResource(Res.string.choose_archive_export_file, uiLanguage),
            initialFileName = captureArchiveDefaultFileName(
                packageName = snapshot.packageName,
                capturedAtEpochMillis = snapshot.capturedAtEpochMillis,
            ),
        ) ?: return@exportCaptureArchive
        archiveUiState = CaptureArchiveUiState.Working(CaptureArchiveOperation.EXPORT)
        coroutineScope.launch {
            archiveUiState = try {
                val rawArtifacts = withContext(Dispatchers.IO) {
                    if (captureStatus == ConnectionStatus.ARCHIVE) {
                        preservedRawArtifacts
                    } else {
                        runCatching {
                            val zip = deviceClient.dumpVisibleWindowViews(selectedDeviceSerial)
                            CaptureRawArtifacts(
                                zip = zip,
                                text = VisibleWindowViewsTextRenderer.render(zip),
                            )
                        }.getOrNull()
                    }
                }
                val result = withContext(Dispatchers.IO) {
                    captureArchiveService.export(
                        target = target,
                        producerVersion =
                            System.getProperty("agentperf.version", "development"),
                        snapshot = snapshot,
                        screenshotPng = screenshot,
                        rawArtifacts = rawArtifacts,
                        analysis = analysis,
                        aiAnalysis = aiAnalysis,
                        timelineFrames = timelineFrames,
                        composeInspection = state.composeInspection,
                        composePrivacy = composePrivacy,
                    )
                }
                CaptureArchiveUiState.Success(
                    operation = CaptureArchiveOperation.EXPORT,
                    path = result.path,
                    rawArtifactsIncluded = result.rawArtifactsIncluded,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                CaptureArchiveUiState.Failure(
                    operation = CaptureArchiveOperation.EXPORT,
                    message = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }
    val exportCaptureArchive: () -> Unit = {
        if (state.composeInspection == null) {
            performExportCaptureArchive(ComposeArchivePrivacy.SAFE_REDACTED)
        } else {
            pendingComposeExportConsent = true
        }
    }
    val openCaptureArchive: (Path) -> Unit = openCaptureArchive@{ source ->
        if (archiveUiState is CaptureArchiveUiState.Working) {
            return@openCaptureArchive
        }
        autoScanEnabled = false
        archiveUiState = CaptureArchiveUiState.Working(CaptureArchiveOperation.IMPORT)
        coroutineScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    captureArchiveService.import(source)
                }
                store.loadArchive(
                    snapshot = imported.snapshot,
                    screenshotPng = imported.screenshotPng,
                    analysis = imported.analysis,
                    aiAnalysis = imported.aiAnalysis,
                    timelineFrames = imported.timelineFrames,
                    composeInspection = imported.composeInspection,
                    composeInspectionWarning = imported.composeInspectionWarning,
                )
                state = store.state
                importedRawArtifacts = imported.rawArtifacts
                aiAnalysisUiState = AiAnalysisUiState.Idle
                hierarchyTreeState = HierarchyTreeState()
                hiddenLayerState = HiddenLayerState()
                recentArchives = withContext(Dispatchers.IO) {
                    recentArchiveStore.record(source)
                }
                archiveUiState = CaptureArchiveUiState.Success(
                    operation = CaptureArchiveOperation.IMPORT,
                    path = source,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                archiveUiState = CaptureArchiveUiState.Failure(
                    operation = CaptureArchiveOperation.IMPORT,
                    message = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }
    val importCaptureArchive: () -> Unit = importCaptureArchive@{
        if (archiveUiState is CaptureArchiveUiState.Working) {
            return@importCaptureArchive
        }
        archiveFileChooser.chooseImport(
            localizedStringResource(Res.string.choose_archive_to_import, uiLanguage),
        )?.let(openCaptureArchive)
    }
    val clearRecentArchives: () -> Unit = {
        recentArchiveStore.clear()
        recentArchives = emptyList()
    }
    val importScreenshot: () -> Unit = importScreenshot@{
        if (archiveUiState is CaptureArchiveUiState.Working) {
            return@importScreenshot
        }
        if (state.snapshot == null) {
            return@importScreenshot
        }
        val target = store.manualScreenshotTarget() ?: return@importScreenshot
        val expectedDisplay = state.snapshot?.display ?: return@importScreenshot
        val source = archiveFileChooser.chooseScreenshotImport(
            localizedStringResource(Res.string.choose_screenshot_to_import, uiLanguage),
        ) ?: return@importScreenshot
        autoScanEnabled = false
        archiveUiState = CaptureArchiveUiState.Working(CaptureArchiveOperation.IMPORT_SCREENSHOT)
        coroutineScope.launch {
            try {
                val screenshot = withContext(Dispatchers.IO) {
                    captureArchiveService.importScreenshot(
                        source = source,
                        expectedWidthPx = expectedDisplay.widthPx,
                        expectedHeightPx = expectedDisplay.heightPx,
                    )
                }
                val loaded = store.loadManualScreenshot(
                    target = target,
                    screenshotPng = screenshot.png,
                )
                if (!loaded) {
                    throw IllegalStateException("The selected layout changed while importing the screenshot")
                }
                state = store.state
                aiAnalysisUiState = AiAnalysisUiState.Idle
                archiveUiState = CaptureArchiveUiState.Success(
                    operation = CaptureArchiveOperation.IMPORT_SCREENSHOT,
                    path = source,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                archiveUiState = CaptureArchiveUiState.Failure(
                    operation = CaptureArchiveOperation.IMPORT_SCREENSHOT,
                    message = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }
    val prepareAiAnalysis: (AiAnalysisInput) -> Unit = { input ->
        aiAnalysisUiState = AiAnalysisUiState.Working
        coroutineScope.launch {
            try {
                pendingAiAnalysis = withContext(Dispatchers.IO) {
                    effectiveAiAnalysisClient.prepare(input)
                }
                aiAnalysisUiState = AiAnalysisUiState.Idle
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                aiAnalysisUiState = AiAnalysisUiState.Failure(error.message ?: error.javaClass.simpleName)
            }
        }
    }
    val performAiAnalysis: (PreparedAiAnalysis) -> Unit = performAiAnalysis@{ prepared ->
        if (aiAnalysisUiState is AiAnalysisUiState.Working) return@performAiAnalysis
        aiAnalysisUiState = AiAnalysisUiState.Working
        aiAnalysisJob = coroutineScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    effectiveAiAnalysisClient.analyze(prepared)
                }
                store.loadAiAnalysis(report)
                state = store.state
                aiAnalysisUiState = AiAnalysisUiState.Idle
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                aiAnalysisUiState = AiAnalysisUiState.Failure(
                    error.message ?: error.javaClass.simpleName,
                )
            } finally {
                if (aiAnalysisJob == currentCoroutineContext()[Job]) aiAnalysisJob = null
            }
        }
    }
    val cancelAiAnalysis: () -> Unit = {
        aiAnalysisJob?.cancel()
        aiAnalysisJob = null
        aiAnalysisUiState = AiAnalysisUiState.Idle
    }
    val buildAiAnalysisInput: (UiNode?) -> AiAnalysisInput? = { selectedNode ->
        val snapshot = state.snapshot
        val activeRoot = state.activeRoot
        if (snapshot == null || activeRoot == null) {
            null
        } else {
            aiAnalysisInputBuilder.build(
                snapshot = snapshot,
                activeRoot = activeRoot,
                analysis = state.analysis,
                screenshotAvailable = state.screenshotPng?.isNotEmpty() == true,
                selectedNode = selectedNode,
            )
        }
    }
    val runAiAnalysis: () -> Unit = runAiAnalysis@{
        if (aiAnalysisUiState is AiAnalysisUiState.Working) return@runAiAnalysis
        prepareAiAnalysis(buildAiAnalysisInput(state.selectedNode) ?: return@runAiAnalysis)
    }

    val selectNode: (String) -> Unit = { id ->
        if (store.selectNode(id)) state = store.state
    }
    val performAction: (ViewerAction) -> Unit = { action ->
        when (action) {
            ViewerAction.TOGGLE_AUTO_SCAN -> {
                if (archiveUiState !is CaptureArchiveUiState.Working) {
                    autoScanEnabled = !autoScanEnabled
                }
            }
            ViewerAction.PREVIOUS_NODE,
            ViewerAction.NEXT_NODE,
            -> {
                val direction = if (action == ViewerAction.PREVIOUS_NODE) {
                    HierarchyNavigationDirection.UP
                } else {
                    HierarchyNavigationDirection.DOWN
                }
                val rows = ViewDisplayProjection.hierarchyRows(
                    rows = hierarchyIsolationState.rows(InspectorPresenter.present(state, uiLanguage).rows),
                    hideInvisible = viewDisplayOptions.hideInvisibleHierarchyViews,
                )
                hierarchyTreeState.adjacentNodeId(
                    rows = rows,
                    selectedNodeId = state.selectedNodeId,
                    direction = direction,
                )?.let(selectNode)
            }
            ViewerAction.TOGGLE_SELECTED_NODE -> {
                state.selectedNodeId?.let { selectedNodeId ->
                    hierarchyTreeState = hierarchyTreeState.toggleExpandable(
                        nodeId = selectedNodeId,
                        rows = InspectorPresenter.present(state, uiLanguage).rows,
                    )
                }
            }
            ViewerAction.TOGGLE_HIERARCHY -> {
                panelVisibility = panelVisibility.toggleHierarchy()
            }
            ViewerAction.TOGGLE_FINDINGS -> {
                panelVisibility = panelVisibility.toggleFindings()
            }
            ViewerAction.TOGGLE_DETAILS -> {
                panelVisibility = panelVisibility.toggleDetails()
            }
            ViewerAction.TOGGLE_HIERARCHY_IDS -> {
                val updatedOptions = viewDisplayOptions.toggleHierarchyIds()
                viewDisplayOptions = updatedOptions
                viewDisplayOptionsStore.save(updatedOptions)
            }
            ViewerAction.OPEN_SETTINGS -> {
                if (onOpenUnifiedSettings == null) settingsVisible = true else onOpenUnifiedSettings()
            }
        }
    }
    val toggleCanvasHitTestOrder: () -> Unit = {
        val updatedOptions = viewDisplayOptions.toggleCanvasHitTestOrder()
        viewDisplayOptions = updatedOptions
        viewDisplayOptionsStore.save(updatedOptions)
    }
    val toggleHiddenLayer: (String) -> Unit = { nodeId ->
        hiddenLayerState = hiddenLayerState.toggle(nodeId)
    }
    val clearHiddenLayers: () -> Unit = {
        hiddenLayerState = hiddenLayerState.clear()
    }
    val toggleViewDisplayOption: (ViewDisplayOption) -> Unit = { option ->
        val updatedOptions = viewDisplayOptions.toggle(option)
        viewDisplayOptions = updatedOptions
        viewDisplayOptionsStore.save(updatedOptions)
    }
    val toggleFullComposeInspection: () -> Unit = {
        if (fullComposeEnabled) {
            fullComposeEnabled = false
            composeAuthorization = null
            recompositionActive = false
        } else if (composeAuthorizationUiState !is ComposeAuthorizationUiState.Preparing) {
            composeAuthorizationUiState = ComposeAuthorizationUiState.Preparing
            coroutineScope.launch {
                val result = try {
                    val serial = selectedDeviceSerial
                        ?: availableDevices.singleOrNull()?.serial
                        ?: error("Select exactly one authorized device")
                    val prepared = withContext(Dispatchers.IO) {
                        val packageName = deviceClient.foregroundPackageName(serial)
                        val bundleRoot = Path.of(
                            System.getProperty("agentperf.compose.agent.bundle", "compose-agent-bundle"),
                        ).toAbsolutePath()
                        composeInjectionManager.preflight(
                            serial,
                            packageName,
                            bundleRoot,
                            explicitLocalArtifact = System.getProperty("agentperf.compose.inspector.path")
                                ?.takeIf(String::isNotBlank)?.let(Path::of),
                        )
                    }
                    ComposeAuthorizationUiState.Review(prepared)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    ComposeAuthorizationUiState.Failure(error.message ?: error.javaClass.simpleName)
                }
                if (composeAuthorizationUiState is ComposeAuthorizationUiState.Preparing) {
                    composeAuthorizationUiState = result
                }
            }
        }
    }

    NativeViewerMenuBar(
        model = NativeViewerMenuModel(
            language = uiLanguage,
            selectedNodeId = state.selectedNodeId,
            autoScanEnabled = autoScanEnabled,
            panelVisibility = panelVisibility,
            viewDisplayOptions = viewDisplayOptions,
            archiveOperationInProgress =
                archiveUiState is CaptureArchiveUiState.Working ||
                    manualRefreshInProgress,
            canExportArchive = state.snapshot != null,
            canImportScreenshot = state.snapshot != null,
            recentArchives = recentArchives,
            isMacOs = System.getProperty("os.name").startsWith("Mac", ignoreCase = true),
        ),
        onAction = performAction,
        onViewOption = toggleViewDisplayOption,
        onImportArchive = importCaptureArchive,
        onOpenRecentArchive = openCaptureArchive,
        onClearRecentArchives = clearRecentArchives,
        onImportScreenshot = importScreenshot,
        onExportArchive = exportCaptureArchive,
    )

    LaunchedEffect(Unit) {
        appFocusRequester.requestFocus()
    }

    CompositionLocalProvider(LocalLayoutInspectorLanguage provides uiLanguage) {
        ViewerTheme(darkTheme = darkTheme) {
            val colors = LocalViewerColors.current
            Surface(
            color = colors.canvasBackground,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(appFocusRequester)
                .onPreviewKeyEvent { event ->
                    val action = if (event.type == KeyEventType.KeyDown) {
                        ViewerActionMenu.commandAction(
                            key = event.key,
                            commandPressed = event.isMetaPressed || event.isCtrlPressed,
                        )
                    } else {
                        null
                    }
                    action?.let {
                        performAction(it)
                        true
                    } ?: false
                }
                .focusable(),
        ) {
            Column {
                HeaderToolbar(
                    language = uiLanguage,
                    onNavigateHome = onNavigateHome,
                    onNavigateSettings = { performAction(ViewerAction.OPEN_SETTINGS ) }
                ) {
                    val colors = LocalViewerColors.current
                    val language = LocalLayoutInspectorLanguage.current
                    val model = InspectorPresenter.present(state, language)
                    val (packageName, separator, connectionLabel) = headerTextSegments(model, language)


                    Text(packageName, color = colors.primaryText, fontFamily = FontFamily.Monospace)
                    HeaderSpacer()
                    DeviceSelector(
                        devices = deviceChoices(availableDevices),
                        selectedSerial = selectedDeviceSerial,
                        onSelectDevice = { serial ->
                            manualRefreshSession.invalidate()
                            selectedDeviceSerial = serial
                            deviceListRefreshRequest += 1
                        },
                    )
                    HeaderSpacer()
                    CaptureTargetSelector(
                        selectedMode = captureTargetMode,
                        onSelectMode = { mode ->
                            if (captureTargetMode != mode) {
                                manualRefreshSession.invalidate()
                                captureTargetMode = mode
                            }
                        },
                    )
                    if (FULL_COMPOSE_INSPECTION_VISIBLE) {
                        HeaderSpacer()
                        TextButton(onClick = toggleFullComposeInspection) {
                            Text(
                                localizedStringResource(
                                    if (fullComposeEnabled) Res.string.full_compose_on else Res.string.full_compose_off,
                                    language,
                                ),
                                fontSize = 11.sp,
                            )
                        }
                    }
                    if (model.windows.size > 1) {
                        HeaderSpacer()
                        WindowSelector(
                            windows = model.windows,
                            selectedWindowId = model.selectedWindowId,
                            onSelectWindow = { windowId ->
                                if (store.selectWindow(windowId)) {
                                    hierarchyTreeState = HierarchyTreeState()
                                    hiddenLayerState = HiddenLayerState()
                                    state = store.state
                                    aiAnalysisUiState = AiAnalysisUiState.Idle
                                }
                            },
                        )
                    }
                    HeaderSpacer()
                    Text(separator, color = colors.mutedText)
                    HeaderSpacer()
                    val connectionColor = when (model.connectionTone) {
                        ConnectionTone.NEUTRAL -> colors.warning
                        ConnectionTone.SUCCESS -> colors.success
                        ConnectionTone.ERROR -> colors.error
                    }
                    StatusDot(connectionColor)
                    HeaderSpacer()
                    Text(connectionLabel, color = connectionColor, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    val scanControlState = ScanControlState(
                        autoScanEnabled = autoScanEnabled,
                        manualRefreshInProgress = manualRefreshInProgress,
                    )
                    if (scanControlState.showManualRefresh && !fullComposeEnabled) {
                        ManualRefreshButton(
                            enabled = scanControlState.manualRefreshEnabled,
                            onClick = {
                                if (!autoScanEnabled &&
                                    !manualRefreshInProgress &&
                                    archiveUiState !is CaptureArchiveUiState.Working
                                ) {
                                    manualRefreshRequest += 1
                                }
                            },
                        )
                        HeaderSpacer()
                    }
                    AutoScanSwitch(autoScanEnabled) {
                        performAction(ViewerAction.TOGGLE_AUTO_SCAN)
                    }
                    if (fullComposeEnabled) {
                        HeaderSpacer()
                        Row(
                            modifier = Modifier.clickable { hideSystemComposables = !hideSystemComposables },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = !hideSystemComposables,
                                onCheckedChange = { hideSystemComposables = !it },
                            )
                            Text(
                                localizedStringResource(Res.string.system_composables, language),
                                fontSize = 10.sp,
                            )
                        }
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    withContext(Dispatchers.IO) {
                                        if (recompositionActive) {
                                            composeSession?.stopRecompositionObservation()
                                        } else {
                                            composeSession?.startRecompositionObservation()
                                        }
                                    }
                                    recompositionActive = !recompositionActive
                                }
                            },
                            enabled = composeSession != null,
                        ) {
                            Text(
                                localizedStringResource(
                                    if (recompositionActive) Res.string.stop_recomposition else Res.string.start_recomposition,
                                    language,
                                ),
                                fontSize = 10.sp,
                            )
                        }
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    withContext(Dispatchers.IO) { composeSession?.resetRecompositionCounts() }
                                    recompositionActive = true
                                }
                            },
                            enabled = composeSession != null,
                        ) {
                            Text(localizedStringResource(Res.string.reset_recomposition, language), fontSize = 10.sp)
                        }
                    }
                    HeaderSpacer()
                    HeaderDivider()
                    HeaderSpacer()
                    Text(model.metricsText, color = colors.subtleText, fontSize = 12.sp)
                    model.timelineText?.let { timelineText ->
                        Spacer(Modifier.width(10.dp))
                        Text(timelineText, color = colors.subtleText, fontSize = 12.sp)
                    }
                    HeaderSpacer()
                    HeaderDivider()
                    HeaderSpacer()
                    PanelToggleButton(PanelPosition.LEFT, panelVisibility.showHierarchy) {
                        performAction(ViewerAction.TOGGLE_HIERARCHY)
                    }
                    HeaderSpacer()
                    PanelToggleButton(PanelPosition.BOTTOM, panelVisibility.showFindings) {
                        performAction(ViewerAction.TOGGLE_FINDINGS)
                    }
                    HeaderSpacer()
                    PanelToggleButton(PanelPosition.RIGHT, panelVisibility.showDetails) {
                        performAction(ViewerAction.TOGGLE_DETAILS)
                    }

                }
                correlationHint?.let { hint ->
                    CorrelationBanner(
                        hint = hint,
                        capturedPackageName = state.snapshot?.packageName,
                    )
                }
                HorizontalDivider(color = colors.border)
                BoxWithConstraints(modifier = Modifier.weight(1f)) {
                    val availableHeightDp = maxHeight.value
                    val normalizedFindingsHeight = FindingsLayout.fit(findingsHeightDp, availableHeightDp)
                    SideEffect {
                        if (findingsHeightDp != normalizedFindingsHeight) {
                            findingsHeightDp = normalizedFindingsHeight
                        }
                    }
                    Column(modifier = Modifier.fillMaxSize()) {
                        BoxWithConstraints(modifier = Modifier.weight(1f)) {
                            val availableWidthDp = maxWidth.value
                            val normalizedPaneWidths = PaneLayout.fit(paneWidths, availableWidthDp)
                            SideEffect {
                                if (paneWidths != normalizedPaneWidths) {
                                    paneWidths = normalizedPaneWidths
                                }
                            }
                            SideEffect {
                                val rows = InspectorPresenter.present(state, uiLanguage).rows
                                val sanitizedHiddenLayerState = hiddenLayerState.sanitize(rows)
                                if (hiddenLayerState != sanitizedHiddenLayerState) {
                                    hiddenLayerState = sanitizedHiddenLayerState
                                }
                                val sanitizedIsolation = hierarchyIsolationState.sanitize(rows)
                                if (hierarchyIsolationState != sanitizedIsolation) {
                                    hierarchyIsolationState = sanitizedIsolation
                                }
                            }
                            Row(modifier = Modifier.fillMaxSize()) {
                                if (panelVisibility.showHierarchy) {
                                    HierarchyPane(
                                        state = state,
                                        treeState = hierarchyTreeState,
                                        viewDisplayOptions = viewDisplayOptions,
                                        hiddenLayerState = hiddenLayerState,
                                        isolationState = hierarchyIsolationState,
                                        searchState = searchState,
                                        onTreeStateChange = { hierarchyTreeState = it },
                                        onSelect = selectNode,
                                        onToggleHiddenLayer = toggleHiddenLayer,
                                        onIsolate = { nodeId ->
                                            hierarchyIsolationState = hierarchyIsolationState.isolate(
                                                nodeId,
                                                InspectorPresenter.present(state, uiLanguage).rows,
                                            )
                                        },
                                        onIsolateParent = {
                                            hierarchyIsolationState = hierarchyIsolationState.parent(
                                                InspectorPresenter.present(state, uiLanguage).rows,
                                            )
                                        },
                                        onClearIsolation = { hierarchyIsolationState = hierarchyIsolationState.clear() },
                                        onSearchStateChange = { searchState = it },
                                        onAction = performAction,
                                        modifier =
                                            Modifier
                                                .width(normalizedPaneWidths.hierarchy.dp)
                                                .fillMaxHeight(),
                                    )
                                    ResizableSeparator { deltaDp ->
                                        paneWidths = PaneLayout.dragHierarchy(
                                            widths = PaneLayout.fit(paneWidths, availableWidthDp),
                                            deltaDp = deltaDp,
                                            availableWidthDp = availableWidthDp,
                                        )
                                    }
                                }
                                PreviewPane(
                                    state = state,
                                    recompositionHeat = recompositionHeat,
                                    isolationRootNodeId = hierarchyIsolationState.rootNodeId,
                                    showVisibleViewBounds = viewDisplayOptions.showVisibleViewBounds,
                                    hitTestOrder = viewDisplayOptions.canvasHitTestOrder,
                                    hiddenLayerState = hiddenLayerState,
                                    borderColors = canvasBorderColors,
                                    onToggleHitTestOrder = toggleCanvasHitTestOrder,
                                    onClearHiddenLayers = clearHiddenLayers,
                                    onHoverNode = { nodeId ->
                                        store.setHoveredNode(nodeId)
                                        state = store.state
                                    },
                                    onSelectNode = { nodeId ->
                                        hierarchyTreeState = hierarchyTreeState.reveal(
                                            nodeId,
                                            InspectorPresenter.present(state, uiLanguage).rows,
                                        )
                                        selectNode(nodeId)
                                    },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                                if (panelVisibility.showDetails) {
                                    ResizableSeparator { deltaDp ->
                                        paneWidths = PaneLayout.dragProperties(
                                            widths = PaneLayout.fit(paneWidths, availableWidthDp),
                                            deltaDp = deltaDp,
                                            availableWidthDp = availableWidthDp,
                                        )
                                    }
                                    DetailsPane(
                                        state = state,
                                        modifier = Modifier
                                            .width(normalizedPaneWidths.properties.dp)
                                            .fillMaxHeight(),
                                        onOpenMemoryProfiler = onOpenMemoryProfiler,
                                        onOpenComposeSource = onOpenComposeSource,
                                        onLoadComposeParameter = { reference ->
                                            val document = state.composeInspection ?: return@DetailsPane
                                            val current = document.frame.details[reference.composableId]
                                                ?.findValue(reference)
                                            val maxElements = ((current?.elements?.size ?: 0) * 2)
                                                .coerceAtLeast(50).coerceAtMost(10_000)
                                            coroutineScope.launch {
                                                val expanded = withContext(Dispatchers.IO) {
                                                    composeSession?.loadParameterDetails(reference, 0, maxElements)
                                                } ?: return@launch
                                                if (store.loadComposeParameterDetails(
                                                        document.frame.frameId,
                                                        reference,
                                                        expanded,
                                                    )
                                                ) state = store.state
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        if (panelVisibility.showFindings) {
                            FindingsResizeSeparator { deltaDp ->
                                findingsHeightDp = FindingsLayout.drag(
                                    heightDp = FindingsLayout.fit(findingsHeightDp, availableHeightDp),
                                    deltaDp = deltaDp,
                                    availableHeightDp = availableHeightDp,
                                )
                            }
                            FindingsPane(
                                state = state,
                                viewDisplayOptions = viewDisplayOptions,
                                onSelectNode = selectNode,
                                aiAnalysisUiState = aiAnalysisUiState,
                                onRunAiAnalysis = runAiAnalysis,
                                onCancelAiAnalysis = cancelAiAnalysis,
                                onOpenSourceCandidate = onOpenSourceCandidate,
                                onCanOpenSourceCandidate = onCanOpenSourceCandidate,
                                onCanOpenSourceCandidateDirectly = onCanOpenSourceCandidateDirectly,
                                onSelectTimelineFrame = { index ->
                                    if (archiveUiState !is CaptureArchiveUiState.Working &&
                                        store.selectTimelineFrame(index)
                                    ) {
                                        hierarchyTreeState = HierarchyTreeState()
                                        hiddenLayerState = HiddenLayerState()
                                        state = store.state
                                        aiAnalysisUiState = AiAnalysisUiState.Idle
                                    }
                                },
                                onCloseTimelineFrame = { index ->
                                    if (archiveUiState !is CaptureArchiveUiState.Working &&
                                        store.removeTimelineFrame(index)
                                    ) {
                                        hierarchyTreeState = HierarchyTreeState()
                                        hiddenLayerState = HiddenLayerState()
                                        state = store.state
                                        aiAnalysisUiState = AiAnalysisUiState.Idle
                                    }
                                },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(normalizedFindingsHeight.dp),
                            )
                        }
                    }
                }
            }
        }
            if (settingsVisible) {
                SettingsDialog(
                    viewDisplayOptions = viewDisplayOptions,
                    onViewDisplayOptionsChanged = { updated ->
                        viewDisplayOptions = updated
                        viewDisplayOptionsStore.save(updated)
                    },
                    archiveLimits = archiveLimits,
                    onArchiveLimitsChanged = { updated ->
                        archiveLimits = updated
                        archiveLimitsStore.save(updated)
                    },
                    canvasBorderColors = canvasBorderColors,
                    onCanvasBorderColorsChanged = { updated ->
                        canvasBorderColors = updated
                        canvasBorderColorStore.save(updated)
                    },
                    onDismiss = {
                        settingsVisible = false
                    },
                )
            }
            when (val operationState = archiveUiState) {
                CaptureArchiveUiState.Idle,
                is CaptureArchiveUiState.Working,
                -> Unit
                is CaptureArchiveUiState.Success -> {
                    val path = operationState.path.toAbsolutePath().toString()
                    val title = when (operationState.operation) {
                        CaptureArchiveOperation.IMPORT ->
                            localizedStringResource(Res.string.import_archive_succeeded_title, uiLanguage)
                        CaptureArchiveOperation.IMPORT_SCREENSHOT ->
                            localizedStringResource(Res.string.import_screenshot_succeeded_title, uiLanguage)
                        CaptureArchiveOperation.EXPORT ->
                            localizedStringResource(Res.string.export_archive_succeeded_title, uiLanguage)
                    }
                    val message = when (operationState.operation) {
                        CaptureArchiveOperation.IMPORT ->
                            localizedStringResource(Res.string.archive_import_succeeded, uiLanguage, path)
                        CaptureArchiveOperation.IMPORT_SCREENSHOT ->
                            localizedStringResource(Res.string.screenshot_import_succeeded, uiLanguage, path)
                        CaptureArchiveOperation.EXPORT ->
                            localizedStringResource(
                                if (operationState.rawArtifactsIncluded) {
                                    Res.string.archive_export_succeeded
                                } else {
                                    Res.string.archive_export_succeeded_no_attachments
                                },
                                uiLanguage,
                                path,
                            )
                    }
                    ExportResultDialog(
                        title = title,
                        message = message,
                        dismissLabel = localizedStringResource(Res.string.dismiss, uiLanguage),
                        onDismiss = {
                            archiveUiState = CaptureArchiveUiState.Idle
                        },
                    )
                }
                is CaptureArchiveUiState.Failure -> {
                    val title = when (operationState.operation) {
                        CaptureArchiveOperation.IMPORT -> localizedStringResource(Res.string.import_archive_failed_title, uiLanguage)
                        CaptureArchiveOperation.IMPORT_SCREENSHOT -> localizedStringResource(Res.string.import_screenshot_failed_title, uiLanguage)
                        CaptureArchiveOperation.EXPORT -> localizedStringResource(Res.string.export_archive_failed_title, uiLanguage)
                    }
                    val message = when (operationState.operation) {
                        CaptureArchiveOperation.IMPORT ->
                            localizedStringResource(Res.string.archive_import_failed, uiLanguage, operationState.message)
                        CaptureArchiveOperation.IMPORT_SCREENSHOT ->
                            localizedStringResource(Res.string.screenshot_import_failed, uiLanguage, operationState.message)
                        CaptureArchiveOperation.EXPORT ->
                            localizedStringResource(Res.string.archive_export_failed, uiLanguage, operationState.message)
                    }
                    ExportResultDialog(
                        title = title,
                        message = message,
                        dismissLabel = localizedStringResource(Res.string.dismiss, uiLanguage),
                        onDismiss = {
                            archiveUiState = CaptureArchiveUiState.Idle
                        },
                    )
                }
            }
            pendingAiAnalysis?.let { prepared ->
                val input = prepared.input
                val manifest = prepared.manifest
                AlertDialog(
                    onDismissRequest = { pendingAiAnalysis = null },
                    title = { Text(localizedStringResource(Res.string.ai_analysis_dialog_title, uiLanguage)) },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            val analysisScope =
                                localizedStringResource(
                                    if (input.selectedNodeId == null) {
                                        Res.string.ai_analysis_scope_report_summary
                                    } else {
                                        Res.string.ai_analysis_scope_selected_node
                                    },
                                    uiLanguage,
                                )
                            Text(
                                localizedStringResource(
                                    Res.string.ai_analysis_dialog_details,
                                    uiLanguage,
                                    analysisScope,
                                    manifest.evidenceCount,
                                    manifest.sources.size,
                                    manifest.payloadBytes,
                                ),
                            )
                            Text(
                                localizedStringResource(
                                    Res.string.ai_analysis_model_and_bindings,
                                    uiLanguage,
                                    manifest.model,
                                    manifest.sourceSnapshotIds.size,
                                    manifest.buildEvidenceBundleIds.size,
                                ),
                            )
                            manifest.sourceSnapshotIds.forEach { snapshotId ->
                                Text(
                                    localizedStringResource(
                                        Res.string.ai_analysis_source_snapshot_item,
                                        uiLanguage,
                                        snapshotId,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            manifest.evidence.forEach { evidence ->
                                Text(
                                    localizedStringResource(
                                        Res.string.ai_analysis_evidence_item,
                                        uiLanguage,
                                        evidence.id,
                                        evidence.kind,
                                        evidence.summary,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            manifest.sources.forEach { source ->
                                Text(
                                    localizedStringResource(
                                        Res.string.ai_analysis_source_item,
                                        uiLanguage,
                                        source.relativePath,
                                        source.startLine ?: 1,
                                        source.endLine ?: source.startLine ?: 1,
                                        source.lineCount,
                                        source.byteCount,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    localizedStringResource(
                                        Res.string.ai_analysis_source_resolution,
                                        uiLanguage,
                                        source.resolutionConfidence ?: "UNKNOWN",
                                        source.reasons.joinToString(" · "),
                                        source.indexComplete?.toString() ?: "unknown",
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            manifest.blockedReason?.let { reason ->
                                Text(
                                    localizedStringResource(Res.string.ai_analysis_blocked, uiLanguage, reason),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (manifest.requiresSourceUploadAuthorization) {
                                Text(
                                    localizedStringResource(Res.string.ai_analysis_source_upload_hint, uiLanguage),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (manifest.requiresNarrowerScope) {
                                Text(
                                    localizedStringResource(Res.string.ai_analysis_narrow_scope_hint, uiLanguage),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            val controlsEnabled = aiAnalysisUiState !is AiAnalysisUiState.Working
                            if (state.selectedNode != null) {
                                val entireReport = input.selectedNodeId == null
                                val changeScope: (Boolean) -> Unit = { useEntireReport ->
                                    val selectedNode = if (useEntireReport) null else state.selectedNode
                                    buildAiAnalysisInput(selectedNode)?.let { scopedInput ->
                                        prepareAiAnalysis(
                                            scopedInput.copy(includeSourceSnippets = input.includeSourceSnippets),
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.clickable(enabled = controlsEnabled) { changeScope(!entireReport) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = entireReport,
                                        enabled = controlsEnabled,
                                        onCheckedChange = changeScope,
                                    )
                                    Text(localizedStringResource(Res.string.ai_analysis_entire_report, uiLanguage))
                                }
                            }
                            val performanceDataOnly = manifest.performanceDataOnly
                            val changePayload: (Boolean) -> Unit = { performanceOnly ->
                                prepareAiAnalysis(input.copy(includeSourceSnippets = !performanceOnly))
                            }
                            Row(
                                modifier = Modifier.clickable(enabled = controlsEnabled) { changePayload(!performanceDataOnly) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = performanceDataOnly,
                                    enabled = controlsEnabled,
                                    onCheckedChange = changePayload,
                                )
                                Text(localizedStringResource(Res.string.ai_analysis_performance_data_only, uiLanguage))
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = manifest.canAnalyze && aiAnalysisUiState !is AiAnalysisUiState.Working,
                            onClick = {
                                pendingAiAnalysis = null
                                performAiAnalysis(prepared)
                            },
                        ) { Text(localizedStringResource(Res.string.analyze, uiLanguage)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingAiAnalysis = null }) {
                            Text(localizedStringResource(Res.string.cancel, uiLanguage))
                        }
                    },
                )
            }
            when (val composeState = composeAuthorizationUiState) {
                ComposeAuthorizationUiState.Idle -> Unit
                ComposeAuthorizationUiState.Preparing -> ExportResultDialog(
                    title = localizedStringResource(Res.string.compose_preflight_title, uiLanguage),
                    message = localizedStringResource(Res.string.compose_preflight_running, uiLanguage),
                    dismissLabel = localizedStringResource(Res.string.cancel, uiLanguage),
                    onDismiss = { composeAuthorizationUiState = ComposeAuthorizationUiState.Idle },
                )
                is ComposeAuthorizationUiState.Failure -> ExportResultDialog(
                    title = localizedStringResource(Res.string.compose_preflight_failed, uiLanguage),
                    message = composeState.message,
                    dismissLabel = localizedStringResource(Res.string.dismiss, uiLanguage),
                    onDismiss = { composeAuthorizationUiState = ComposeAuthorizationUiState.Idle },
                )
                is ComposeAuthorizationUiState.Review -> {
                    val preflight = composeState.prepared.preflight
                    AlertDialog(
                        onDismissRequest = { composeAuthorizationUiState = ComposeAuthorizationUiState.Idle },
                        title = { Text(localizedStringResource(Res.string.compose_authorize_title, uiLanguage)) },
                        text = {
                            Text(
                                localizedStringResource(
                                    Res.string.compose_authorize_message,
                                    uiLanguage,
                                    preflight.packageName,
                                    preflight.pid,
                                    preflight.apiLevel,
                                    preflight.abi,
                                    preflight.composeVersion ?: "—",
                                    preflight.inspectorSource ?: "—",
                                    localizedStringResource(
                                        if (preflight.inspectorDownloadRequired) Res.string.download_required
                                        else Res.string.download_not_required,
                                        uiLanguage,
                                    ),
                                    preflight.bundleFingerprint.take(12),
                                    preflight.performanceNotice,
                                ),
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                composeAuthorization = AuthorizedComposeTarget(
                                    composeState.prepared,
                                    ComposeInspectionAuthorization.authorize(preflight),
                                )
                                composeAuthorizationUiState = ComposeAuthorizationUiState.Idle
                                captureTargetMode = CaptureTargetMode.FOREGROUND_APP
                                fullComposeEnabled = true
                                autoScanEnabled = true
                            }) { Text(localizedStringResource(Res.string.authorize_attach, uiLanguage)) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                composeAuthorizationUiState = ComposeAuthorizationUiState.Idle
                            }) { Text(localizedStringResource(Res.string.cancel, uiLanguage)) }
                        },
                    )
                }
            }
            if (pendingComposeExportConsent) {
                var fullFidelity by remember { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = { pendingComposeExportConsent = false },
                    title = { Text(localizedStringResource(Res.string.compose_export_title, uiLanguage)) },
                    text = {
                        Column {
                            Text(localizedStringResource(Res.string.compose_export_safe_default, uiLanguage))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { fullFidelity = !fullFidelity },
                            ) {
                                Checkbox(checked = fullFidelity, onCheckedChange = { fullFidelity = it })
                                Text(localizedStringResource(Res.string.compose_export_full_fidelity, uiLanguage))
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            pendingComposeExportConsent = false
                            performExportCaptureArchive(
                                if (fullFidelity) ComposeArchivePrivacy.FULL_FIDELITY
                                else ComposeArchivePrivacy.SAFE_REDACTED,
                            )
                        }) { Text(localizedStringResource(Res.string.export_archive, uiLanguage)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingComposeExportConsent = false }) {
                            Text(localizedStringResource(Res.string.cancel, uiLanguage))
                        }
                    },
                )
            }
        }
    }
}

private fun com.androidperformancestudio.compose.inspection.ComposableNode.findComposeNode(
    targetId: Long,
): com.androidperformancestudio.compose.inspection.ComposableNode? =
    if (id == targetId) this else children.firstNotNullOfOrNull { it.findComposeNode(targetId) }

@Composable
private fun CorrelationBanner(
    hint: InspectorCorrelationHint,
    capturedPackageName: String?,
) {
    val colors = LocalViewerColors.current
    val matches = capturedPackageName == null || capturedPackageName == hint.targetPackageName
    val suffix =
        if (matches) {
            " · ${hint.correlationNotice}"
        } else {
            " · ${hint.foregroundMismatchPrefix}: $capturedPackageName"
        }
    Text(
        text = hint.message + suffix,
        modifier = Modifier.fillMaxWidth().background(colors.panel).padding(horizontal = 18.dp, vertical = 5.dp),
        color = if (matches) colors.secondaryText else colors.warning,
        fontSize = 12.sp,
    )
}

internal fun createInitialInspectorStore(): InspectorStore = InspectorStore()

internal fun LiveDeviceClient.connectTarget(
    mode: CaptureTargetMode,
    serial: String?,
): ConnectedDeviceSession = when (mode) {
    CaptureTargetMode.FOREGROUND_APP -> connectForegroundApp(serial)
    CaptureTargetMode.SYSTEM_UI -> connect(SYSTEM_UI_PACKAGE_NAME, serial)
}

internal fun CaptureTargetMode.isSessionCurrent(session: ConnectedDeviceSession): Boolean =
    when (this) {
        CaptureTargetMode.FOREGROUND_APP -> session.isForegroundAppCurrent()
        CaptureTargetMode.SYSTEM_UI -> session.packageName == SYSTEM_UI_PACKAGE_NAME
    }

@Composable
private fun CaptureTargetSelector(
    selectedMode: CaptureTargetMode,
    onSelectMode: (CaptureTargetMode) -> Unit,
) {
    val language = LocalLayoutInspectorLanguage.current
    val targetLabel = localizedStringResource(Res.string.capture_target, language)
    DropdownSelector(
        items = CaptureTargetMode.entries,
        selectedItem = selectedMode,
        onItemSelected = onSelectMode,
        itemLabel = { localizedStringResource(it.stringResource(), language) },
        selectedItemLabel = { "$targetLabel: ${localizedStringResource(it.stringResource(), language)}" },
        placeholder = targetLabel,
    )
}

@Composable
private fun DeviceSelector(
    devices: List<DeviceChoiceModel>,
    selectedSerial: String?,
    onSelectDevice: (String?) -> Unit,
) {
    val language = LocalLayoutInspectorLanguage.current
    val autoDeviceLabel = localizedStringResource(Res.string.auto_device, language)
    val selectedDevice = devices.firstOrNull { it.serial == selectedSerial }

    DropdownSelector(
        items = devices,
        selectedItem = selectedDevice,
        onItemSelected = { device -> onSelectDevice(device.serial) },
        itemLabel = DeviceChoiceModel::label,
        placeholder = autoDeviceLabel,
        onPlaceholderSelected = { onSelectDevice(null) },
    )
}

@Composable
private fun WindowSelector(
    windows: List<WindowChoiceModel>,
    selectedWindowId: String?,
    onSelectWindow: (String) -> Unit,
) {
    if (windows.size <= 1) return

    val language = LocalLayoutInspectorLanguage.current
    val windowLabel = localizedStringResource(Res.string.window, language)
    DropdownSelector(
        items = windows,
        selectedItem = windows.firstOrNull { it.id == selectedWindowId } ?: windows.first(),
        onItemSelected = { onSelectWindow(it.id) },
        itemLabel = WindowChoiceModel::title,
        selectedItemLabel = { "$windowLabel: ${it.title}" },
        placeholder = windowLabel,
        selectorDescription = localizedStringResource(Res.string.select_window, language),
        modifier = Modifier.widthIn(min = 140.dp, max = 240.dp),
        fillWidth = true,
    )
}

@Composable
private fun ExportResultDialog(
    title: String,
    message: String,
    dismissLabel: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel)
            }
        },
    )
}

internal fun headerTextSegments(
    model: InspectorScreenModel,
    language: UiLanguage = UiLanguage.ENGLISH,
): List<String> =
    listOf(model.packageName ?: localizedStringResource(Res.string.no_app, language), "|", model.connectionLabel)

@Composable
private fun AutoScanSwitch(
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalViewerColors.current
    val language = LocalLayoutInspectorLanguage.current
    Row(
        modifier = Modifier.clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = localizedStringResource(Res.string.auto_scan, language),
            color = if (enabled) colors.primaryText else colors.mutedText,
            fontSize = 11.sp,
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier =
                Modifier
                    .width(30.dp)
                    .height(16.dp)
                    .background(
                        color = if (enabled) colors.accent.copy(alpha = 0.55f) else colors.switchTrackOff,
                        shape = RoundedCornerShape(8.dp),
                    ).padding(2.dp),
            contentAlignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .background(
                        color = if (enabled) Color.White else colors.switchThumbOff,
                        shape = RoundedCornerShape(50),
                    ),
            )
        }
    }
}

@Composable
private fun ManualRefreshButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val language = LocalLayoutInspectorLanguage.current
    ProfilerCompactButton(
        text = localizedStringResource(Res.string.refresh, language),
        enabled = enabled,
        onClick = onClick,
        modifier =
            Modifier
                .width(56.dp)
                .semantics {
                    contentDescription = localizedStringResource(Res.string.refresh_once, language)
                },
    )
}

private enum class PanelPosition {
    LEFT,
    BOTTOM,
    RIGHT,
}

@Composable
private fun PanelToggleButton(
    position: PanelPosition,
    visible: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalViewerColors.current
    val iconColor = if (visible) colors.accent else colors.mutedText
    Box(
        modifier =
            Modifier
                .width(26.dp)
                .height(21.dp)
                .background(
                    color = if (visible) colors.accent.copy(alpha = 0.18f) else Color.Transparent,
                    shape = RoundedCornerShape(3.dp),
                )
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(15.dp)) {
            val inset = 1.dp.toPx()
            val strokeWidth = 1.dp.toPx()
            val contentLeft = inset + strokeWidth
            val contentTop = inset + strokeWidth
            val contentWidth = size.width - contentLeft * 2
            val contentHeight = size.height - contentTop * 2
            drawRect(
                color = iconColor,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
                style = Stroke(width = strokeWidth),
            )
            when (position) {
                PanelPosition.LEFT -> drawRect(
                    color = iconColor.copy(alpha = 0.8f),
                    topLeft = Offset(contentLeft, contentTop),
                    size = Size(4.dp.toPx(), contentHeight),
                )
                PanelPosition.BOTTOM -> drawRect(
                    color = iconColor.copy(alpha = 0.8f),
                    topLeft = Offset(contentLeft, size.height - contentTop - 4.dp.toPx()),
                    size = Size(contentWidth, 4.dp.toPx()),
                )
                PanelPosition.RIGHT -> drawRect(
                    color = iconColor.copy(alpha = 0.8f),
                    topLeft = Offset(size.width - contentLeft - 4.dp.toPx(), contentTop),
                    size = Size(4.dp.toPx(), contentHeight),
                )
            }
        }
    }
}

@Composable
private fun HierarchyPane(
    state: InspectorState,
    treeState: HierarchyTreeState,
    viewDisplayOptions: ViewDisplayOptions,
    hiddenLayerState: HiddenLayerState,
    isolationState: HierarchyIsolationState,
    searchState: HierarchySearchState,
    onTreeStateChange: (HierarchyTreeState) -> Unit,
    onSelect: (String) -> Unit,
    onToggleHiddenLayer: (String) -> Unit,
    onIsolate: (String?) -> Unit,
    onIsolateParent: () -> Unit,
    onClearIsolation: () -> Unit,
    onSearchStateChange: (HierarchySearchState) -> Unit,
    onAction: (ViewerAction) -> Unit,
    modifier: Modifier,
) {
    val colors = LocalViewerColors.current
    val language = LocalLayoutInspectorLanguage.current
    val model = InspectorPresenter.present(state, language)
    val scrollbarStyle = LocalScrollbarStyle.current.copy(
        unhoverColor = colors.mutedText.copy(alpha = 0.42f),
        hoverColor = colors.secondaryText.copy(alpha = 0.82f),
    )
    val horizontalScrollState = rememberScrollState()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val visibleRows = treeState.displayRows(
        rows = isolationState.rows(model.rows),
        hideInvisible = viewDisplayOptions.hideInvisibleHierarchyViews,
    )
    val matchedNodeIds = searchState.matchedNodeIds(visibleRows)
    val currentMatchedNodeId = searchState.currentMatchedNodeId(matchedNodeIds)
    LaunchedEffect(state.selectedNodeId, visibleRows) {
        val selectedIndex = visibleRows.indexOfFirst { it.id == state.selectedNodeId }
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        HierarchySelectionScrollPolicy.targetIndex(
            selectedIndex = selectedIndex,
            firstVisibleIndex = visibleItems.firstOrNull()?.index,
            lastVisibleIndex = visibleItems.lastOrNull()?.index,
        )?.let { listState.scrollToItem(it) }
    }
    LaunchedEffect(currentMatchedNodeId, visibleRows) {
        if (currentMatchedNodeId == null) return@LaunchedEffect
        val matchIndex = visibleRows.indexOfFirst { it.id == currentMatchedNodeId }
        if (matchIndex < 0) return@LaunchedEffect
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val firstVisible = visibleItems.firstOrNull()?.index ?: 0
        val lastVisible = visibleItems.lastOrNull()?.index ?: 0
        val viewportSize = lastVisible - firstVisible
        val targetScroll = (matchIndex - viewportSize / 2).coerceAtLeast(0)
        listState.scrollToItem(targetScroll)
    }
    Column(
        modifier
            .background(colors.panel)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.DirectionUp -> {
                        onAction(ViewerAction.PREVIOUS_NODE)
                        true
                    }
                    Key.DirectionDown -> {
                        onAction(ViewerAction.NEXT_NODE)
                        true
                    }
                    Key.Enter -> {
                        onAction(ViewerAction.TOGGLE_SELECTED_NODE)
                        true
                    }
                    Key.H -> {
                        state.selectedNodeId?.let(onToggleHiddenLayer)
                        state.selectedNodeId != null
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        PanelTitle(localizedStringResource(Res.string.hierarchy, language)) {
            Text("${visibleRows.size}", color = colors.mutedText, fontSize = 11.sp)
            TextButton(
                onClick = { onIsolate(state.selectedNodeId) },
                enabled = state.selectedNodeId != null,
            ) {
                Text(localizedStringResource(Res.string.isolate_subtree, language), fontSize = 9.sp)
            }
            if (isolationState.active) {
                TextButton(onClick = onIsolateParent) {
                    Text(localizedStringResource(Res.string.isolate_parent, language), fontSize = 9.sp)
                }
                TextButton(onClick = onClearIsolation) {
                    Text(localizedStringResource(Res.string.clear_isolation, language), fontSize = 9.sp)
                }
            }
        }
        HierarchySearchBar(
            searchState = searchState,
            matchedNodeIds = matchedNodeIds,
            onQueryChange = { query ->
                onSearchStateChange(searchState.withQuery(query))
            },
            onNavigatePrevious = {
                val updated = searchState.navigatePrevious(matchedNodeIds)
                onSearchStateChange(updated)
                updated.currentMatchedNodeId(matchedNodeIds)?.let { nodeId ->
                    onTreeStateChange(treeState.reveal(nodeId, model.rows))
                    onSelect(nodeId)
                }
            },
            onNavigateNext = {
                val updated = searchState.navigateNext(matchedNodeIds)
                onSearchStateChange(updated)
                updated.currentMatchedNodeId(matchedNodeIds)?.let { nodeId ->
                    onTreeStateChange(treeState.reveal(nodeId, model.rows))
                    onSelect(nodeId)
                }
            },
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewportWidth = maxWidth
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScrollState),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(min = viewportWidth)
                        .padding(vertical = 6.dp),
                ) {
                    items(visibleRows, key = { it.number }) { row ->
                        val expanded = treeState.isExpanded(row.id)
                        val isSearchMatch = searchState.matches(row)
                        val isCurrentMatch = row.id == currentMatchedNodeId
                        val rowColor = when {
                            row.selected -> colors.selectedRow
                            isCurrentMatch -> colors.searchCurrentMatchRow
                            isSearchMatch -> colors.searchMatchRow
                            else -> Color.Transparent
                        }
                        Row(
                            modifier = Modifier
                                .widthIn(min = viewportWidth)
                                .height(HierarchyRowLayout.HEIGHT_DP.dp)
                                .background(rowColor)
                                .clickable {
                                    focusRequester.requestFocus()
                                    onSelect(row.id)
                                }
                                .padding(
                                    start = (8 + row.depth * HierarchyRowLayout.INDENT_DP).dp,
                                    end = 8.dp,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            HierarchyDisclosure(
                                hasChildren = row.hasChildren,
                                expanded = expanded,
                                onToggle = {
                                    focusRequester.requestFocus()
                                    onSelect(row.id)
                                    onTreeStateChange(treeState.toggleExpandable(row.id, model.rows))
                                },
                            )
                            Spacer(Modifier.width(4.dp))
                            if (viewDisplayOptions.showHierarchyLayerVisibilityButtons) {
                                LayerVisibilityButton(
                                    hidden = hiddenLayerState.isHidden(row.id),
                                    onToggle = {
                                        focusRequester.requestFocus()
                                        onToggleHiddenLayer(row.id)
                                    },
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            val labelText = ViewDisplayProjection.hierarchyLabel(
                                row = row,
                                hideIndex = viewDisplayOptions.hideHierarchyIndices,
                                showId = viewDisplayOptions.showHierarchyIds,
                            )
                            if (isSearchMatch && searchState.isSearching) {
                                HierarchySearchHighlightText(
                                    text = labelText,
                                    query = searchState.query,
                                    baseColor = when {
                                        hiddenLayerState.isHidden(row.id) -> colors.hiddenRowText
                                        row.visible -> if (isCurrentMatch) colors.searchHighlightText else colors.rowText
                                        else -> colors.hiddenRowText
                                    },
                                    highlightColor = colors.searchHighlightText,
                                )
                            } else {
                                Text(
                                    labelText,
                                    color = when {
                                        hiddenLayerState.isHidden(row.id) -> colors.hiddenRowText
                                        row.visible -> colors.rowText
                                        else -> colors.hiddenRowText
                                    },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = HierarchyRowLayout.FONT_SIZE_SP.sp,
                                    lineHeight = 11.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(horizontalScrollState),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                style = scrollbarStyle,
            )
        }
    }
}

@Composable
private fun HierarchyDisclosure(
    hasChildren: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalViewerColors.current
    Box(
        modifier = Modifier
            .width(12.dp)
            .fillMaxHeight()
            .let { base ->
                if (hasChildren) base.clickable(onClick = onToggle) else base
            },
        contentAlignment = Alignment.Center,
    ) {
        if (hasChildren) {
            Canvas(Modifier.size(8.dp)) {
                val strokeWidth = 1.2.dp.toPx()
                if (expanded) {
                    drawLine(
                        color = colors.accent,
                        start = Offset(0.5.dp.toPx(), 2.dp.toPx()),
                        end = Offset(4.dp.toPx(), 5.5.dp.toPx()),
                        strokeWidth = strokeWidth,
                    )
                    drawLine(
                        color = colors.accent,
                        start = Offset(4.dp.toPx(), 5.5.dp.toPx()),
                        end = Offset(7.5.dp.toPx(), 2.dp.toPx()),
                        strokeWidth = strokeWidth,
                    )
                } else {
                    drawLine(
                        color = colors.accent,
                        start = Offset(2.dp.toPx(), 0.5.dp.toPx()),
                        end = Offset(5.5.dp.toPx(), 4.dp.toPx()),
                        strokeWidth = strokeWidth,
                    )
                    drawLine(
                        color = colors.accent,
                        start = Offset(5.5.dp.toPx(), 4.dp.toPx()),
                        end = Offset(2.dp.toPx(), 7.5.dp.toPx()),
                        strokeWidth = strokeWidth,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PreviewPane(
    state: InspectorState,
    recompositionHeat: Map<String, Float>,
    isolationRootNodeId: String?,
    showVisibleViewBounds: Boolean,
    hitTestOrder: CanvasHitTestOrder,
    hiddenLayerState: HiddenLayerState,
    borderColors: CanvasBorderColors,
    onToggleHitTestOrder: () -> Unit,
    onClearHiddenLayers: () -> Unit,
    onHoverNode: (String?) -> Unit,
    onSelectNode: (String) -> Unit,
    modifier: Modifier,
) {
    val colors = LocalViewerColors.current
    val language = LocalLayoutInspectorLanguage.current
    val scrollbarStyle = LocalScrollbarStyle.current.copy(
        unhoverColor = colors.mutedText.copy(alpha = 0.42f),
        hoverColor = colors.secondaryText.copy(alpha = 0.82f),
    )
    val previewRoot = isolationRootNodeId?.let { state.activeRoot?.findNode(it) } ?: state.activeRoot
    val selectedBounds = previewRoot?.findNodeBoundsSkippingHidden(
        targetId = state.selectedNodeId,
        hiddenNodeIds = hiddenLayerState.hiddenNodeIds,
    )
    val hoveredBounds = previewRoot?.findNodeBoundsSkippingHidden(
        targetId = state.hoveredNodeId,
        hiddenNodeIds = hiddenLayerState.hiddenNodeIds,
    )
    val screenshot = rememberScreenshot(state.screenshotPng)
    val pointerSelection = remember { CanvasPointerSelection() }
    LaunchedEffect(state.snapshot?.capturedAtEpochMillis, state.selectedWindowId) {
        pointerSelection.reset()
    }
    var canvasPixelSize by remember { mutableStateOf(IntSize.Zero) }
    var appOnly by remember { mutableStateOf(true) }
    var previewZoom by remember { mutableStateOf(PreviewZoomState.DEFAULT_SCALE) }
    val previewPan = remember { mutableStateOf(Offset.Zero) }
    val source = if (appOnly && isolationRootNodeId != null) {
        val display = state.snapshot?.display
        display?.let {
            CanvasGeometry.sourceRect(previewRoot?.bounds, it.widthPx, it.heightPx, appOnly = true)
        }
    } else {
        CanvasWindowSource.sourceRect(state, appOnly)
    }
    val canvasMode = previewCanvasMode(
        hasSource = source != null,
        hasScreenshot = screenshot != null,
    )
    val density = LocalDensity.current
    Column(modifier.background(colors.canvasBackground)) {
        PanelTitle(localizedStringResource(Res.string.canvas, language)) {
            Text(
                source?.let { "${it.width} × ${it.height}" } ?: localizedStringResource(Res.string.no_live_frame, language),
                color = colors.mutedText,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(12.dp))
            CanvasModeToggle(
                appOnly = appOnly,
                onToggle = { appOnly = !appOnly },
            )
            Spacer(Modifier.width(8.dp))
            HitTestOrderToggle(
                order = hitTestOrder,
                onToggle = onToggleHitTestOrder,
            )
            if (hiddenLayerState.count > 0) {
                Spacer(Modifier.width(8.dp))
                HiddenLayerSummary(
                    count = hiddenLayerState.count,
                    onClear = onClearHiddenLayers,
                )
            }
        }
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            val previewSize = source?.let {
                CanvasGeometry.previewSize(
                    source = it,
                    maxWidth = maxWidth.value,
                    maxHeight = maxHeight.value,
                    portraitMaxWidth = 390f,
                )
            }
            val scaledPreviewSize = previewSize?.let {
                FloatSize(
                    width = it.width * previewZoom,
                    height = it.height * previewZoom,
                )
            }
            val viewportSizePx = with(density) {
                Size(maxWidth.toPx(), maxHeight.toPx())
            }
            val scaledPreviewSizePx = scaledPreviewSize?.let {
                with(density) {
                    Size(it.width.dp.toPx(), it.height.dp.toPx())
                }
            }
            val previewContentSizePx = scaledPreviewSizePx ?: Size.Zero
            val horizontalScrollbarAdapter = remember(
                previewPan,
                previewContentSizePx,
                viewportSizePx,
            ) {
                PreviewScrollbarAdapter(
                    axis = PreviewScrollbarAxis.HORIZONTAL,
                    pan = previewPan,
                    contentWidthPx = previewContentSizePx.width,
                    contentHeightPx = previewContentSizePx.height,
                    viewportWidthPx = viewportSizePx.width,
                    viewportHeightPx = viewportSizePx.height,
                )
            }
            val verticalScrollbarAdapter = remember(
                previewPan,
                previewContentSizePx,
                viewportSizePx,
            ) {
                PreviewScrollbarAdapter(
                    axis = PreviewScrollbarAxis.VERTICAL,
                    pan = previewPan,
                    contentWidthPx = previewContentSizePx.width,
                    contentHeightPx = previewContentSizePx.height,
                    viewportWidthPx = viewportSizePx.width,
                    viewportHeightPx = viewportSizePx.height,
                )
            }
            LaunchedEffect(scaledPreviewSizePx, viewportSizePx) {
                previewPan.value = scaledPreviewSizePx?.let {
                    PreviewPanState.clamp(
                        pan = previewPan.value,
                        contentWidthPx = it.width,
                        contentHeightPx = it.height,
                        viewportWidthPx = viewportSizePx.width,
                        viewportHeightPx = viewportSizePx.height,
                    )
                } ?: Offset.Zero
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .onPointerEvent(PointerEventType.Scroll) { event ->
                        if (scaledPreviewSizePx == null) return@onPointerEvent
                        val nativeEvent = event.nativeEvent as? MouseWheelEvent
                        if (nativeEvent?.let { it.isMetaDown || it.isControlDown } == true) {
                            val mousePos = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                            event.changes.forEach { it.consume() }
                            val scrollAmount = nativeEvent.preciseWheelRotation.toFloat()
                            val zoomDelta = -scrollAmount * 0.1f
                            val oldZoom = previewZoom
                            val newZoom = (oldZoom + zoomDelta).coerceIn(PreviewZoomState.MIN_SCALE, PreviewZoomState.MAX_SCALE)
                            if (newZoom == oldZoom) return@onPointerEvent
                            val ratio = newZoom / oldZoom
                            val oldCenterX = (viewportSizePx.width - scaledPreviewSizePx.width) / 2f
                            val oldCenterY = (viewportSizePx.height - scaledPreviewSizePx.height) / 2f
                            val newContentWidth = scaledPreviewSizePx.width * ratio
                            val newContentHeight = scaledPreviewSizePx.height * ratio
                            val newCenterX = (viewportSizePx.width - newContentWidth) / 2f
                            val newCenterY = (viewportSizePx.height - newContentHeight) / 2f
                            val currentPan = previewPan.value
                            val newPan = Offset(
                                x = mousePos.x * (1f - ratio) + (oldCenterX + currentPan.x) * ratio - newCenterX,
                                y = mousePos.y * (1f - ratio) + (oldCenterY + currentPan.y) * ratio - newCenterY,
                            )
                            previewZoom = newZoom
                            previewPan.value = PreviewPanState.clamp(
                                pan = newPan,
                                contentWidthPx = newContentWidth,
                                contentHeightPx = newContentHeight,
                                viewportWidthPx = viewportSizePx.width,
                                viewportHeightPx = viewportSizePx.height,
                            )
                            return@onPointerEvent
                        }
                        val scrollDelta = event.changes.firstOrNull()?.scrollDelta ?: Offset.Zero
                        val fallbackWheelDelta = nativeEvent?.let { wheel ->
                            val wheelPixels = wheel.preciseWheelRotation.toFloat() * PreviewPanState.WHEEL_SCROLL_PIXELS
                            if (wheel.isShiftDown) Offset(wheelPixels, 0f) else Offset(0f, wheelPixels)
                        } ?: Offset.Zero
                        // Compose Desktop reports wheel ticks as small normalized deltas; prefer the
                        // native pixel-scaled value so one wheel notch moves the preview perceptibly.
                        val panDelta = fallbackWheelDelta.takeUnless { it == Offset.Zero } ?: scrollDelta
                        val currentPan = previewPan.value
                        val nextPan = PreviewPanState.scroll(
                            pan = currentPan,
                            scrollDelta = panDelta,
                            contentWidthPx = scaledPreviewSizePx.width,
                            contentHeightPx = scaledPreviewSizePx.height,
                            viewportWidthPx = viewportSizePx.width,
                            viewportHeightPx = viewportSizePx.height,
                        )
                        if (nextPan != currentPan) {
                            event.changes.forEach { it.consume() }
                            previewPan.value = nextPan
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .offset {
                            val pan = previewPan.value
                            IntOffset(
                                x = pan.x.roundToInt(),
                                y = pan.y.roundToInt(),
                            )
                        }
                        .pointerInput(scaledPreviewSizePx, viewportSizePx) {
                            if (scaledPreviewSizePx != null) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    previewPan.value = PreviewPanState.clamp(
                                        pan = previewPan.value + dragAmount,
                                        contentWidthPx = scaledPreviewSizePx.width,
                                        contentHeightPx = scaledPreviewSizePx.height,
                                        viewportWidthPx = viewportSizePx.width,
                                        viewportHeightPx = viewportSizePx.height,
                                    )
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = if (scaledPreviewSize != null) {
                            Modifier.requiredSize(
                                width = scaledPreviewSize.width.dp,
                                height = scaledPreviewSize.height.dp,
                            )
                        } else {
                            Modifier.fillMaxHeight().widthIn(max = 390.dp)
                        },
                        shape = RoundedCornerShape(canvasCornerRadiusDp(appOnly).dp),
                        color = colors.previewSurface,
                        shadowElevation = 8.dp,
                    ) {
                        if (canvasMode != PreviewCanvasMode.WAITING && source != null) {
                            Canvas(
                                Modifier
                                    .fillMaxSize()
                                    .onSizeChanged { canvasPixelSize = it }
                                    .onPointerEvent(PointerEventType.Move) { event ->
                                        val point = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                                        val destination = canvasPixelSize.asDestination() ?: return@onPointerEvent
                                        val screenPoint = CanvasGeometry.unmapPoint(point, source, destination)
                                        val candidates = screenPoint?.let {
                                            previewRoot?.let { root ->
                                                CanvasHitTester.hitCandidates(
                                                    root = root,
                                                    point = it,
                                                    hiddenNodeIds = hiddenLayerState.hiddenNodeIds,
                                                    order = hitTestOrder,
                                                )
                                            }
                                        }.orEmpty()
                                        onHoverNode(pointerSelection.move(point, candidates))
                                    }
                                    .onPointerEvent(PointerEventType.Exit) {
                                        pointerSelection.leave()
                                        onHoverNode(null)
                                    }
                                    .onPointerEvent(PointerEventType.Press) { event ->
                                        val point = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                                        val destination = canvasPixelSize.asDestination() ?: return@onPointerEvent
                                        val screenPoint = CanvasGeometry.unmapPoint(point, source, destination)
                                        val candidates = screenPoint?.let {
                                            previewRoot?.let { root ->
                                                CanvasHitTester.hitCandidates(
                                                    root = root,
                                                    point = it,
                                                    hiddenNodeIds = hiddenLayerState.hiddenNodeIds,
                                                    order = hitTestOrder,
                                                )
                                            }
                                        }.orEmpty()
                                        pointerSelection.click(
                                            point = point,
                                            hitPath = candidates,
                                            cycleCandidates = hitTestOrder == CanvasHitTestOrder.Z_ORDER,
                                        )?.let(onSelectNode)
                                    },
                            ) {
                                drawRect(colors.previewCanvas)
                                val destination = FloatRect(
                                    left = 0f,
                                    top = 0f,
                                    width = size.width,
                                    height = size.height,
                                )
                                if (canvasMode == PreviewCanvasMode.SCREENSHOT && screenshot != null) {
                                    drawImage(
                                        image = screenshot,
                                        srcOffset = IntOffset(source.left, source.top),
                                        srcSize = IntSize(source.width, source.height),
                                        dstOffset = IntOffset.Zero,
                                        dstSize = IntSize(
                                            destination.width.roundToInt(),
                                            destination.height.roundToInt(),
                                        ),
                                    )
                                }
                                if (showVisibleViewBounds) {
                                    previewRoot?.let { root ->
                                        ViewBoundsOverlay.mappedVisibleBounds(
                                            root = root,
                                            selectedNodeId = state.selectedNodeId,
                                            source = source,
                                            destination = destination,
                                            hiddenNodeIds = hiddenLayerState.hiddenNodeIds,
                                        ).forEach { overlay ->
                                            drawRect(
                                                color = borderColors.normal.toComposeColor().copy(alpha = 0.62f),
                                                topLeft = Offset(overlay.left, overlay.top),
                                                size = Size(overlay.width, overlay.height),
                                                style = Stroke(width = 1.dp.toPx()),
                                            )
                                        }
                                    }
                                }
                                previewRoot?.let { root ->
                                    mappedRecompositionHeat(
                                        root = root,
                                        heatByNodeId = recompositionHeat,
                                        source = source,
                                        destination = destination,
                                        hiddenNodeIds = hiddenLayerState.hiddenNodeIds,
                                    ).forEach { overlay ->
                                        drawRect(
                                            color = Color(
                                                red = 1f,
                                                green = 0.75f * (1f - overlay.intensity),
                                                blue = 0.08f,
                                                alpha = 0.45f + 0.4f * overlay.intensity,
                                            ),
                                            topLeft = Offset(overlay.bounds.left, overlay.bounds.top),
                                            size = Size(overlay.bounds.width, overlay.bounds.height),
                                            style = Stroke(width = (1f + 3f * overlay.intensity).dp.toPx()),
                                        )
                                    }
                                }
                                selectedBounds?.let { bounds ->
                                    val overlay = CanvasGeometry.mapBounds(
                                        bounds = bounds,
                                        source = source,
                                        destination = destination,
                                    )
                                    overlay?.let {
                                        drawRect(
                                            color = borderColors.selected.toComposeColor(),
                                            topLeft = Offset(it.left, it.top),
                                            size = Size(it.width, it.height),
                                            style = Stroke(width = 3.dp.toPx()),
                                        )
                                    }
                                }
                                hoveredBounds?.let { bounds ->
                                    CanvasGeometry.mapBounds(bounds, source, destination)?.let {
                                        drawRect(
                                            color = borderColors.hovered.toComposeColor(),
                                            topLeft = Offset(it.left, it.top),
                                            size = Size(it.width, it.height),
                                            style = Stroke(width = 2.dp.toPx()),
                                        )
                                    }
                                }
                            }
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(localizedStringResource(Res.string.waiting_for_frame, language), color = colors.previewText, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            HorizontalScrollbar(
                adapter = horizontalScrollbarAdapter,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(end = 8.dp),
                style = scrollbarStyle,
            )
            VerticalScrollbar(
                adapter = verticalScrollbarAdapter,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(bottom = 8.dp),
                style = scrollbarStyle,
            )
            PreviewZoomControls(
                scale = previewZoom,
                onZoomOut = { previewZoom = PreviewZoomState.zoomOut(previewZoom) },
                onZoomIn = { previewZoom = PreviewZoomState.zoomIn(previewZoom) },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

@Composable
private fun PreviewZoomControls(
    scale: Float,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    val language = LocalLayoutInspectorLanguage.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .background(colors.panel.copy(alpha = 0.9f), shape)
            .border(1.dp, colors.border.copy(alpha = 0.65f), shape)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreviewZoomButton(
            label = "−",
            contentDescription = localizedStringResource(Res.string.zoom_out_preview, language),
            enabled = scale > PreviewZoomState.MIN_SCALE,
            onClick = onZoomOut,
        )
        Text(
            text = PreviewZoomState.label(scale),
            color = colors.primaryText,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(42.dp),
        )
        PreviewZoomButton(
            label = "+",
            contentDescription = localizedStringResource(Res.string.zoom_in_preview, language),
            enabled = scale < PreviewZoomState.MAX_SCALE,
            onClick = onZoomIn,
        )
    }
}

@Composable
private fun PreviewZoomButton(
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalViewerColors.current
    Box(
        modifier = Modifier
            .size(24.dp)
            .semantics { this.contentDescription = contentDescription }
            .let { base -> if (enabled) base.clickable(onClick = onClick) else base },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) colors.primaryText else colors.mutedText.copy(alpha = 0.45f),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

internal enum class PreviewCanvasMode {
    WAITING,
    LAYOUT_ONLY,
    SCREENSHOT,
}

internal object PreviewZoomState {
    const val MIN_SCALE = 0.5f
    const val MAX_SCALE = 2.5f
    const val DEFAULT_SCALE = 1f
    private const val STEP = 0.25f

    fun zoomIn(scale: Float): Float = (scale + STEP).coerceAtMost(MAX_SCALE)

    fun zoomOut(scale: Float): Float = (scale - STEP).coerceAtLeast(MIN_SCALE)

    fun label(scale: Float): String = "${(scale * 100).roundToInt()}%"
}

internal object PreviewPanState {
    const val WHEEL_SCROLL_PIXELS = 64f

    fun scroll(
        pan: Offset,
        scrollDelta: Offset,
        contentWidthPx: Float,
        contentHeightPx: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
    ): Offset = clamp(
        pan = pan - scrollDelta,
        contentWidthPx = contentWidthPx,
        contentHeightPx = contentHeightPx,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
    )

    fun clamp(
        pan: Offset,
        contentWidthPx: Float,
        contentHeightPx: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
    ): Offset {
        val horizontalLimit = ((contentWidthPx - viewportWidthPx) / 2f).coerceAtLeast(0f)
        val verticalLimit = ((contentHeightPx - viewportHeightPx) / 2f).coerceAtLeast(0f)
        return Offset(
            x = if (horizontalLimit == 0f) 0f else pan.x.coerceIn(-horizontalLimit, horizontalLimit),
            y = if (verticalLimit == 0f) 0f else pan.y.coerceIn(-verticalLimit, verticalLimit),
        )
    }
}

internal enum class PreviewScrollbarAxis {
    HORIZONTAL,
    VERTICAL,
}

internal class PreviewScrollbarAdapter(
    private val axis: PreviewScrollbarAxis,
    private val pan: MutableState<Offset>,
    private val contentWidthPx: Float,
    private val contentHeightPx: Float,
    private val viewportWidthPx: Float,
    private val viewportHeightPx: Float,
) : ScrollbarAdapter {
    override val scrollOffset: Double
        get() {
            val overflow = (axis.contentSize() - axis.viewportSize()).coerceAtLeast(0f)
            val panOffset = when (axis) {
                PreviewScrollbarAxis.HORIZONTAL -> pan.value.x
                PreviewScrollbarAxis.VERTICAL -> pan.value.y
            }
            return (overflow / 2f - panOffset).coerceIn(0f, overflow).toDouble()
        }

    override val contentSize: Double
        get() = axis.contentSize().toDouble()

    override val viewportSize: Double
        get() = axis.viewportSize().toDouble()

    override suspend fun scrollTo(scrollOffset: Double) {
        val overflow = (axis.contentSize() - axis.viewportSize()).coerceAtLeast(0f)
        val panOffset = overflow / 2f - scrollOffset.toFloat().coerceIn(0f, overflow)
        val current = pan.value
        val requested = when (axis) {
            PreviewScrollbarAxis.HORIZONTAL -> current.copy(x = panOffset)
            PreviewScrollbarAxis.VERTICAL -> current.copy(y = panOffset)
        }
        pan.value = PreviewPanState.clamp(
            pan = requested,
            contentWidthPx = contentWidthPx,
            contentHeightPx = contentHeightPx,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
        )
    }

    private fun PreviewScrollbarAxis.contentSize(): Float =
        when (this) {
            PreviewScrollbarAxis.HORIZONTAL -> contentWidthPx
            PreviewScrollbarAxis.VERTICAL -> contentHeightPx
        }

    private fun PreviewScrollbarAxis.viewportSize(): Float =
        when (this) {
            PreviewScrollbarAxis.HORIZONTAL -> viewportWidthPx
            PreviewScrollbarAxis.VERTICAL -> viewportHeightPx
        }
}

internal fun previewCanvasMode(
    hasSource: Boolean,
    hasScreenshot: Boolean,
): PreviewCanvasMode = when {
    !hasSource -> PreviewCanvasMode.WAITING
    hasScreenshot -> PreviewCanvasMode.SCREENSHOT
    else -> PreviewCanvasMode.LAYOUT_ONLY
}

private fun UiNode.findNodeBounds(targetId: String?): Bounds? {
    if (targetId == null) return null
    if (id == targetId) return bounds
    return children.firstNotNullOfOrNull { it.findNodeBounds(targetId) }
}

private fun UiNode.findNode(targetId: String): UiNode? =
    if (id == targetId) this else children.firstNotNullOfOrNull { it.findNode(targetId) }

private fun UiNode.findNodeBoundsSkippingHidden(
    targetId: String?,
    hiddenNodeIds: Set<String>,
): Bounds? {
    if (targetId == null || id in hiddenNodeIds) return null
    if (id == targetId) return bounds
    return children.firstNotNullOfOrNull { child ->
        child.findNodeBoundsSkippingHidden(targetId, hiddenNodeIds)
    }
}

private fun IntSize.asDestination(): FloatRect? =
    takeIf { width > 0 && height > 0 }?.let {
        FloatRect(0f, 0f, it.width.toFloat(), it.height.toFloat())
    }

internal fun canvasCornerRadiusDp(appOnly: Boolean): Int = if (appOnly) 24 else 4

@Composable
private fun LayerVisibilityButton(
    hidden: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalViewerColors.current
    val language = LocalLayoutInspectorLanguage.current
    val label = if (hidden) localizedStringResource(Res.string.show_layer, language) else localizedStringResource(Res.string.hide_layer, language)
    Box(
        modifier = Modifier
            .width(LayerVisibilityButtonStyle.WIDTH_DP.dp)
            .height(LayerVisibilityButtonStyle.HEIGHT_DP.dp)
            .background(
                color = if (hidden) colors.accent.copy(alpha = 0.14f) else Color.Transparent,
                shape = RoundedCornerShape(3.dp),
            )
            .semantics { contentDescription = localizedStringResource(Res.string.toggle_layer_visibility, language) }
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (hidden) colors.accent else colors.mutedText,
            fontSize = LayerVisibilityButtonStyle.FONT_SIZE_SP.sp,
            lineHeight = LayerVisibilityButtonStyle.LINE_HEIGHT_SP.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun HitTestOrderToggle(
    order: CanvasHitTestOrder,
    onToggle: () -> Unit,
) {
    val colors = LocalViewerColors.current
    val language = LocalLayoutInspectorLanguage.current
    val label = when (order) {
        CanvasHitTestOrder.SMALL_AREA_FIRST -> localizedStringResource(Res.string.small_area_hit_testing, language)
        CanvasHitTestOrder.Z_ORDER -> localizedStringResource(Res.string.z_order_hit_testing, language)
    }
    Text(
        text = label,
        color = colors.secondaryText,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(colors.sectionBackground, RoundedCornerShape(4.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun HiddenLayerSummary(
    count: Int,
    onClear: () -> Unit,
) {
    val colors = LocalViewerColors.current
    val language = LocalLayoutInspectorLanguage.current
    Text(
        text = localizedStringResource(Res.string.hidden_layer_summary, language, count),
        color = colors.warning,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(colors.warning.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .clickable(onClick = onClear)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun CanvasModeToggle(
    appOnly: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalViewerColors.current
    val language = LocalLayoutInspectorLanguage.current
    val color = if (appOnly) colors.accent else colors.mutedText
    Text(
        text = if (appOnly) localizedStringResource(Res.string.app_only_on, language) else localizedStringResource(Res.string.app_only_off, language),
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun rememberScreenshot(bytes: ByteArray?): ImageBitmap? =
    remember(bytes) {
        bytes?.takeIf { it.isNotEmpty() }?.let { encoded ->
            runCatching { Image.makeFromEncoded(encoded).toComposeImageBitmap() }.getOrNull()
        }
    }

@Composable
private fun DetailsPane(
    state: InspectorState,
    modifier: Modifier,
    onOpenMemoryProfiler: ((String) -> Unit)? = null,
    onOpenComposeSource: ((String, Int, Int) -> Unit)? = null,
    onLoadComposeParameter: ((ComposeParameterReference) -> Unit)? = null,
) {
    val colors = LocalViewerColors.current
    val language = LocalLayoutInspectorLanguage.current
    val details = InspectorPresenter.present(state, language).details
    val composeSource = state.selectedNodeId?.removePrefix("compose-inspection:")?.toLongOrNull()?.let { nodeId ->
        state.composeInspection?.frame?.roots?.firstNotNullOfOrNull { root ->
            root.nodes.firstNotNullOfOrNull { it.findComposeNode(nodeId) }
        }?.source
    }
    var expansionState by remember { mutableStateOf(DetailSectionExpansionState()) }
    Column(modifier.background(colors.panel)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                PanelTitle(localizedStringResource(Res.string.properties, language), details.id)
            }
            val className = details.className.takeUnless { it == "—" || it.isBlank() }
            if (onOpenMemoryProfiler != null && className != null) {
                val shape = RoundedCornerShape(4.dp)
                Box(
                    modifier = Modifier
                        .border(1.dp, colors.border, shape)
                        .clickable(onClick = {
                            onOpenMemoryProfiler(className)
                        })
                ) {
                    Text(
                        text = localizedStringResource(Res.string.find_in_memory_profiler, language),
                        color = colors.secondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .background(colors.sectionBackground, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    }
            }
            if (onOpenComposeSource != null && composeSource != null) {
                Spacer(Modifier.width(6.dp))
                TextButton(
                    onClick = {
                        onOpenComposeSource(
                            composeSource.fileName,
                            composeSource.packageHash,
                            composeSource.lineNumber,
                        )
                    },
                ) {
                    Text(localizedStringResource(Res.string.open_compose_source, language), fontSize = 10.sp)
                }
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(details.sections, key = { it.title }) { section ->
                DetailSection(
                    section = section,
                    expanded = expansionState.isExpanded(section.title),
                    onToggle = {
                        expansionState = expansionState.toggle(section.title)
                    },
                    onLoadComposeParameter = onLoadComposeParameter,
                )
            }
        }
    }
}

@Composable
private fun DetailSection(
    section: DetailSectionModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    onLoadComposeParameter: ((ComposeParameterReference) -> Unit)?,
) {
    val colors = LocalViewerColors.current
    val riskSection = section.highlightsRenderingRisk
    val headerColor = if (riskSection) colors.warning else colors.secondaryText
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DetailSectionHeaderLayout.HEIGHT_DP.dp)
            .background(if (riskSection) colors.riskSectionBackground else colors.sectionBackground)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(10.dp)) {
            val strokeWidth = 1.3.dp.toPx()
            if (expanded) {
                drawLine(
                    color = headerColor,
                    start = Offset(1.dp.toPx(), 3.dp.toPx()),
                    end = Offset(5.dp.toPx(), 7.dp.toPx()),
                    strokeWidth = strokeWidth,
                )
                drawLine(
                    color = headerColor,
                    start = Offset(5.dp.toPx(), 7.dp.toPx()),
                    end = Offset(9.dp.toPx(), 3.dp.toPx()),
                    strokeWidth = strokeWidth,
                )
            } else {
                drawLine(
                    color = headerColor,
                    start = Offset(3.dp.toPx(), 1.dp.toPx()),
                    end = Offset(7.dp.toPx(), 5.dp.toPx()),
                    strokeWidth = strokeWidth,
                )
                drawLine(
                    color = headerColor,
                    start = Offset(7.dp.toPx(), 5.dp.toPx()),
                    end = Offset(3.dp.toPx(), 9.dp.toPx()),
                    strokeWidth = strokeWidth,
                )
            }
        }
        Spacer(Modifier.width(7.dp))
        Text(
            text = section.title,
            color = headerColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
    if (expanded) {
        section.rows.forEachIndexed { index, row ->
            DetailRow(row = row, index = index, onLoadComposeParameter = onLoadComposeParameter)
        }
    }
    HorizontalDivider(color = colors.border)
}

@Composable
private fun DetailRow(
    row: DetailRowModel,
    index: Int,
    onLoadComposeParameter: ((ComposeParameterReference) -> Unit)?,
) {
    val colors = LocalViewerColors.current
    val color = when (row.tone) {
        DetailTone.NORMAL -> colors.rowText
        DetailTone.INFO -> colors.info
        DetailTone.WARNING -> colors.warning
        DetailTone.ERROR -> colors.error
    }
    val stripeColor = if (DetailRowStripe.usesDeepBackground(index)) {
        colors.detailRowDeep
    } else {
        colors.detailRowLight
    }
    val baseModifier = Modifier
            .fillMaxWidth()
            .background(stripeColor)
            .background(
                if (row.tone == DetailTone.NORMAL) {
                    Color.Transparent
                } else {
                    color.copy(alpha = 0.06f)
                },
            )
    Row(
        modifier = if (row.composeReference != null && onLoadComposeParameter != null) {
            baseModifier.clickable { onLoadComposeParameter(row.composeReference) }
                .padding(horizontal = 12.dp, vertical = 4.dp)
        } else {
            baseModifier.padding(horizontal = 12.dp, vertical = 4.dp)
        },
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = row.label,
            color = colors.detailLabel,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            modifier = Modifier.width(108.dp),
        )
        SelectionContainer {
            Text(
                text = row.value,
                color = color,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun com.androidperformancestudio.compose.inspection.ComposableDetail.findValue(
    reference: ComposeParameterReference,
): ComposeValue? {
    fun List<ComposeValue>.find(): ComposeValue? = firstNotNullOfOrNull { value ->
        if (value.reference == reference) value else value.elements.find()
    }
    return parameters.find() ?: modifiers.find() ?: mergedSemantics.find() ?: unmergedSemantics.find()
}

@Composable
private fun FindingsPane(
    state: InspectorState,
    viewDisplayOptions: ViewDisplayOptions,
    aiAnalysisUiState: AiAnalysisUiState,
    onRunAiAnalysis: () -> Unit,
    onCancelAiAnalysis: () -> Unit,
    onOpenSourceCandidate: ((String, com.androidperformancestudio.analysis.AiSourceCandidateReference?) -> Unit)?,
    onCanOpenSourceCandidate: ((String) -> Boolean)?,
    onCanOpenSourceCandidateDirectly: ((String) -> Boolean)?,
    onSelectNode: (String) -> Unit,
    onSelectTimelineFrame: (Int) -> Unit,
    onCloseTimelineFrame: (Int) -> Unit,
    modifier: Modifier,
) {
    val colors = LocalViewerColors.current
    val language = LocalLayoutInspectorLanguage.current
    val model = InspectorPresenter.present(state, language)
    val findings = ViewDisplayProjection.findings(
        findings = model.findings,
        rows = model.rows,
        hideInvisible = viewDisplayOptions.hideInvisibleFindings,
    )
    val severitySummary = ViewDisplayProjection.severitySummary(findings)
    var selectionState by remember { mutableStateOf(FindingSelectionState()) }
    var sourceCandidateChoices by remember { mutableStateOf<List<String>>(emptyList()) }
    Column(modifier.background(colors.panel)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(PanelHeaderLayout.HEIGHT_DP.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(localizedStringResource(Res.string.findings, language), color = colors.secondaryText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(18.dp))
            Badge(localizedStringResource(Res.string.info_badge, language, severitySummary.info), colors.info)
            Spacer(Modifier.width(8.dp))
            Badge(localizedStringResource(Res.string.warning_badge, language, severitySummary.warning), colors.warning)
            Spacer(Modifier.width(8.dp))
            Badge(localizedStringResource(Res.string.error_badge, language, severitySummary.error), colors.error)
            Spacer(Modifier.weight(1f))
            if (AI_ANALYSIS_ENTRY_VISIBLE) {
                val aiStatus = when (aiAnalysisUiState) {
                    AiAnalysisUiState.Idle -> state.aiAnalysis?.summary?.let { localizedStringResource(Res.string.ai_analysis_summary, language, it) }
                    AiAnalysisUiState.Working -> localizedStringResource(Res.string.ai_analysis_running, language)
                    is AiAnalysisUiState.Failure -> localizedStringResource(Res.string.ai_analysis_failed, language, aiAnalysisUiState.message)
                }
                if (aiStatus != null) {
                    Text(aiStatus, color = colors.mutedText, fontSize = 11.sp, maxLines = 1)
                    Spacer(Modifier.width(12.dp))
                }
                TextButton(
                    onClick = if (aiAnalysisUiState is AiAnalysisUiState.Working) {
                        onCancelAiAnalysis
                    } else {
                        onRunAiAnalysis
                    },
                    enabled = state.snapshot != null,
                ) {
                    Text(
                        localizedStringResource(
                            if (aiAnalysisUiState is AiAnalysisUiState.Working) Res.string.cancel else Res.string.run_ai_analysis,
                            language,
                        ),
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(localizedStringResource(Res.string.timeline_live_capture, language), color = colors.mutedText, fontSize = 11.sp)
        }
        if (model.timelineFrames.isNotEmpty()) {
            HorizontalDivider(color = colors.border)
            TimelineStrip(
                frames = model.timelineFrames,
                onSelectTimelineFrame = onSelectTimelineFrame,
                onCloseTimelineFrame = onCloseTimelineFrame,
            )
        }
        HorizontalDivider(color = colors.border)
        if (findings.isEmpty()) {
            Text(localizedStringResource(Res.string.no_findings, language), color = colors.subtleText, modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(findings, key = { it.key }) { finding ->
                    FindingRow(
                        finding = finding,
                        selected = selectionState.isSelected(finding.key),
                        onDoubleClick = {
                            selectionState = selectionState.select(finding.key)
                            val archived = state.aiAnalysis?.provenance?.sourceCandidates.orEmpty()
                            val navigableCandidates = finding.sourceCandidateIds.filter { candidateId ->
                                archived.firstOrNull { it.id == candidateId }?.resolutionConfidence?.let { it != "WEAK" }
                                    ?: (onCanOpenSourceCandidate?.invoke(candidateId) == true)
                            }
                            when (navigableCandidates.size) {
                                0 -> onSelectNode(finding.nodeId)
                                1 -> navigableCandidates.single().let { candidateId ->
                                    if (onCanOpenSourceCandidateDirectly?.invoke(candidateId) == true) {
                                        onOpenSourceCandidate?.invoke(
                                            candidateId,
                                            state.aiAnalysis?.provenance?.sourceCandidates?.firstOrNull { it.id == candidateId },
                                        )
                                    } else {
                                        sourceCandidateChoices = listOf(candidateId)
                                    }
                                }
                                else -> sourceCandidateChoices = navigableCandidates
                            }
                        },
                    )
                }
            }
        }
    }
    if (sourceCandidateChoices.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { sourceCandidateChoices = emptyList() },
            title = { Text(localizedStringResource(Res.string.select_source_candidate, language)) },
            text = {
                Column {
                    sourceCandidateChoices.forEachIndexed { index, candidateId ->
                        TextButton(
                            onClick = {
                                sourceCandidateChoices = emptyList()
                                onOpenSourceCandidate?.invoke(
                                    candidateId,
                                    state.aiAnalysis?.provenance?.sourceCandidates?.firstOrNull { it.id == candidateId },
                                )
                            },
                        ) {
                            Text(
                                localizedStringResource(
                                    Res.string.source_candidate,
                                    language,
                                    index + 1,
                                    candidateId.take(12),
                                ),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { sourceCandidateChoices = emptyList() }) {
                    Text(localizedStringResource(Res.string.cancel, language))
                }
            },
        )
    }
}

@Composable
private fun TimelineStrip(
    frames: List<TimelineFrameModel>,
    onSelectTimelineFrame: (Int) -> Unit,
    onCloseTimelineFrame: (Int) -> Unit,
) {
    val colors = LocalViewerColors.current
    val language = LocalLayoutInspectorLanguage.current
    val scrollbarStyle = LocalScrollbarStyle.current.copy(
        unhoverColor = colors.mutedText.copy(alpha = 0.42f),
        hoverColor = colors.secondaryText.copy(alpha = 0.82f),
    )
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val buttons = TimelineScrollNavigation.buttons(
        canScrollBackward = listState.canScrollBackward,
        canScrollForward = listState.canScrollForward,
    )
    val scroll: (TimelineScrollDirection) -> Unit = { direction ->
        coroutineScope.launch {
            listState.animateScrollBy(
                TimelineScrollNavigation.scrollDistance(
                    direction = direction,
                    viewportWidthPx = listState.layoutInfo.viewportSize.width,
                ),
            )
        }
    }

    Box(modifier = Modifier.fillMaxWidth().height(42.dp)) {
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = if (buttons.visible) 32.dp else 12.dp,
                    end = if (buttons.visible) 32.dp else 12.dp,
                    bottom = 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(frames, key = { it.index }) { frame ->
                val background =
                    if (frame.selected) colors.selectedRow
                    else if (colors.isDark) colors.detailRowDeep
                    else colors.sectionBackground
                val textColor = if (frame.selected) colors.primaryText else colors.secondaryText
                Row(
                    modifier = Modifier
                        .background(background, RoundedCornerShape(4.dp))
                        .border(1.dp, if (frame.selected) colors.accent.copy(alpha = 0.7f) else colors.border, RoundedCornerShape(4.dp))
                        .clickable { onSelectTimelineFrame(frame.index) }
                        .padding(start = 8.dp, end = 3.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${frame.label} ${frame.summary}",
                        color = textColor,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                    Box(
                        modifier =
                            Modifier
                                .padding(start = 5.dp)
                                .size(16.dp)
                                .semantics {
                                    contentDescription =
                                        localizedStringResource(Res.string.remove_timeline_frame, language)
                                }
                                .clickable { onCloseTimelineFrame(frame.index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("×", color = colors.mutedText, fontSize = 14.sp, lineHeight = 14.sp)
                    }
                }
            }
        }
        if (buttons.visible) {
            TimelineScrollButton(
                direction = TimelineScrollDirection.LEFT,
                enabled = buttons.leftEnabled,
                modifier = Modifier.align(Alignment.CenterStart),
                onClick = { scroll(TimelineScrollDirection.LEFT) },
            )
            TimelineScrollButton(
                direction = TimelineScrollDirection.RIGHT,
                enabled = buttons.rightEnabled,
                modifier = Modifier.align(Alignment.CenterEnd),
                onClick = { scroll(TimelineScrollDirection.RIGHT) },
            )
        }
        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = if (buttons.visible) 32.dp else 12.dp),
            style = scrollbarStyle,
        )
    }
}

@Composable
private fun TimelineScrollButton(
    direction: TimelineScrollDirection,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalViewerColors.current
    Box(
        modifier = modifier
            .width(28.dp)
            .fillMaxHeight()
            .background(colors.panel)
            .border(1.dp, colors.border)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (direction == TimelineScrollDirection.LEFT) "‹" else "›",
            color = if (enabled) colors.primaryText else colors.subtleText,
            fontSize = 18.sp,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FindingRow(
    finding: FindingRowModel,
    selected: Boolean,
    onDoubleClick: () -> Unit,
) {
    val colors = LocalViewerColors.current
    val findingColor = when (finding.tone) {
        FindingTone.INFO -> colors.info
        FindingTone.WARNING -> colors.warning
        FindingTone.ERROR -> colors.error
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.selectedRow else Color.Transparent)
            .onPointerEvent(
                eventType = PointerEventType.Press,
                pass = PointerEventPass.Initial,
            ) { event ->
                val mouseEvent = event.nativeEvent as? MouseEvent
                if (mouseEvent?.button == MouseEvent.BUTTON1 && mouseEvent.clickCount == 2) {
                    onDoubleClick()
                }
            },
    ) {
        SelectionContainer {
            Text(
                "[${finding.nodeNumber}]  ${finding.title}  ·  ${finding.message}",
                color = findingColor,
                fontSize = FindingsTypography.TEXT_SIZE_SP.sp,
                lineHeight = FindingsTypography.LINE_HEIGHT_SP.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 16.dp,
                        vertical = FindingsTypography.VERTICAL_PADDING_DP.dp,
                    ),
            )
        }
    }
}

@Composable
private fun PanelTitle(title: String, trailing: String) {
    val colors = LocalViewerColors.current
    PanelTitle(title) {
        Text(trailing, color = colors.mutedText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun PanelTitle(
    title: String,
    trailingContent: @Composable () -> Unit,
) {
    val colors = LocalViewerColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(PanelHeaderLayout.HEIGHT_DP.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = colors.secondaryText, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        trailingContent()
    }
    HorizontalDivider(color = colors.border)
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
}

@Composable
private fun ResizableSeparator(onDrag: (Float) -> Unit) {
    val colors = LocalViewerColors.current
    val density = LocalDensity.current
    val currentOnDrag by rememberUpdatedState(onDrag)
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(PaneLayout.SPLITTER_WIDTH_DP.dp)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(density) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    currentOnDrag(dragAmount / density.density)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxHeight().width(1.dp).background(colors.border))
    }
}

@Composable
private fun FindingsResizeSeparator(onDrag: (Float) -> Unit) {
    val colors = LocalViewerColors.current
    val density = LocalDensity.current
    val currentOnDrag by rememberUpdatedState(onDrag)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(FindingsLayout.SPLITTER_HEIGHT_DP.dp)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
            .pointerInput(density) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    currentOnDrag(dragAmount / density.density)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

private const val CAPTURE_INTERVAL_MILLIS = 1_000L
private const val ACTIVE_COMPOSE_CAPTURE_INTERVAL_MILLIS = 200L
private const val RECONNECT_INTERVAL_MILLIS = 1_000L

@Composable
private fun HierarchySearchBar(
    searchState: HierarchySearchState,
    matchedNodeIds: List<String>,
    onQueryChange: (String) -> Unit,
    onNavigatePrevious: () -> Unit,
    onNavigateNext: () -> Unit,
) {
    val colors = LocalViewerColors.current
    val language = LocalLayoutInspectorLanguage.current
    val summary = searchState.matchSummary(matchedNodeIds)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(22.dp)
                .border(1.dp, colors.border, RoundedCornerShape(3.dp))
                .background(colors.sectionBackground.copy(alpha = 0.3f), RoundedCornerShape(3.dp))
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = searchState.query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = if (searchState.query.isNotEmpty()) 14.dp else 0.dp),
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = colors.primaryText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onNavigateNext() }),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
            )
            if (searchState.query.isEmpty()) {
                Text(
                    localizedStringResource(Res.string.search_hierarchy, language),
                    color = colors.mutedText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
            if (searchState.query.isNotEmpty()) {
                Text(
                    text = "✕",
                    color = colors.mutedText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clickable { onQueryChange("") }
                )
            }
        }
        if (searchState.isSearching) {
            Spacer(Modifier.width(4.dp))
            Text(
                summary ?: localizedStringResource(Res.string.search_no_match, language),
                color = if (matchedNodeIds.isEmpty()) colors.error else colors.mutedText,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 32.dp),
            )
            Spacer(Modifier.width(2.dp))
            SearchNavButton(
                label = "◀",
                contentDescription = localizedStringResource(Res.string.search_previous, language),
                enabled = matchedNodeIds.isNotEmpty(),
                onClick = onNavigatePrevious,
            )
            Spacer(Modifier.width(2.dp))
            SearchNavButton(
                label = "▶",
                contentDescription = localizedStringResource(Res.string.search_next, language),
                enabled = matchedNodeIds.isNotEmpty(),
                onClick = onNavigateNext,
            )
        }
    }
}

@Composable
private fun SearchNavButton(
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalViewerColors.current
    Box(
        modifier = Modifier
            .width(20.dp)
            .height(20.dp)
            .background(
                color = if (enabled) colors.sectionBackground else Color.Transparent,
                shape = RoundedCornerShape(3.dp),
            )
            .let { base -> if (enabled) base.clickable(onClick = onClick) else base }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) colors.primaryText else colors.mutedText.copy(alpha = 0.4f),
            fontSize = 9.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun HierarchySearchHighlightText(
    text: String,
    query: String,
    baseColor: Color,
    highlightColor: Color,
) {
    val lowerText = text.lowercase()
    val lowerQuery = query.lowercase()
    val spans = mutableListOf<AnnotatedRange>()
    var searchFrom = 0
    while (searchFrom < lowerText.length) {
        val index = lowerText.indexOf(lowerQuery, searchFrom)
        if (index < 0) break
        spans += AnnotatedRange(index, index + query.length)
        searchFrom = index + query.length
    }
    if (spans.isEmpty()) {
        Text(
            text,
            color = baseColor,
            fontFamily = FontFamily.Monospace,
            fontSize = HierarchyRowLayout.FONT_SIZE_SP.sp,
            lineHeight = 11.sp,
            maxLines = 1,
            softWrap = false,
        )
        return
    }
    val annotatedString = buildAnnotatedString {
        var lastEnd = 0
        for (range in spans) {
            if (range.start > lastEnd) {
                withStyle(androidx.compose.ui.text.SpanStyle(color = baseColor)) {
                    append(text.substring(lastEnd, range.start))
                }
            }
            withStyle(
                androidx.compose.ui.text.SpanStyle(
                    color = highlightColor,
                    fontWeight = FontWeight.Bold,
                )
            ) {
                append(text.substring(range.start, range.end))
            }
            lastEnd = range.end
        }
        if (lastEnd < text.length) {
            withStyle(androidx.compose.ui.text.SpanStyle(color = baseColor)) {
                append(text.substring(lastEnd))
            }
        }
    }
    Text(
        annotatedString,
        fontFamily = FontFamily.Monospace,
        fontSize = HierarchyRowLayout.FONT_SIZE_SP.sp,
        lineHeight = 11.sp,
        maxLines = 1,
        softWrap = false,
    )
}

private data class AnnotatedRange(val start: Int, val end: Int)
