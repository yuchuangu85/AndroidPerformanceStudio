package dev.agentperf.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import dev.agentperf.application.ConnectionStatus
import dev.agentperf.application.InspectorState
import dev.agentperf.application.InspectorStore
import dev.agentperf.adb.AdbDevice
import dev.agentperf.adb.ConnectedDeviceSession
import dev.agentperf.adb.LiveDeviceClient
import dev.agentperf.adb.VisibleWindowViewsTextRenderer
import dev.agentperf.protocol.ProtocolCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.awt.Cursor
import java.awt.event.MouseEvent
import java.util.Locale
import kotlin.math.roundToInt

internal const val AUTO_SCAN_DEFAULT_ENABLED = false
// Keep the implementation available while the user-facing flow is deferred.
// Re-enable only after completing docs/ai-analysis-roadmap.md.
internal const val AI_ANALYSIS_ENTRY_VISIBLE = false
internal const val SYSTEM_UI_PACKAGE_NAME = "com.android.systemui"

internal enum class CaptureTargetMode {
    FOREGROUND_APP,
    SYSTEM_UI,
}

@Composable
fun FrameWindowScope.DesktopViewerApp(
    commonThemePreference: String? = null,
    commonLanguagePreference: String? = null,
) {
    val store = remember { createInitialInspectorStore() }
    var state by remember { mutableStateOf(store.state) }
    var autoScanEnabled by remember { mutableStateOf(AUTO_SCAN_DEFAULT_ENABLED) }
    var manualRefreshRequest by remember { mutableStateOf(0) }
    var manualRefreshInProgress by remember { mutableStateOf(false) }
    val deviceClient = remember { LiveDeviceClient() }
    val refreshTimingSink = remember { ConsoleRefreshTimingSink }
    var captureTargetMode by remember { mutableStateOf(CaptureTargetMode.FOREGROUND_APP) }
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
    var selectedDeviceSerial by remember { mutableStateOf<String?>(null) }
    var deviceListRefreshRequest by remember { mutableStateOf(0) }
    val protocolCodec = remember { ProtocolCodec(supportedMajor = 1) }
    val archiveFileChooser = remember { SwingCaptureArchiveFileChooser() }
    val archiveLimitsStore = remember { CaptureArchiveLimitsStore.desktop() }
    var archiveLimits by remember { mutableStateOf(archiveLimitsStore.load()) }
    val captureArchiveService = remember(protocolCodec, archiveLimits) {
        CaptureArchiveService(
            archiveCodec = CaptureArchiveCodec(limits = archiveLimits),
            protocolCodec = protocolCodec,
        )
    }
    val aiAnalysisInputBuilder = remember { AiAnalysisInputBuilder() }
    val aiAnalysisClient = remember { OpenAiResponsesAnalysisClient.fromEnvironment() }
    var aiAnalysisUiState by remember { mutableStateOf<AiAnalysisUiState>(AiAnalysisUiState.Idle) }
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

    LaunchedEffect(autoScanEnabled, captureTargetMode) {
        if (!autoScanEnabled) {
            if (store.state.connectionStatus != ConnectionStatus.ARCHIVE) {
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
            try {
                store.connecting()
                state = store.state
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
                    delay(CAPTURE_INTERVAL_MILLIS)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                store.connectionFailed(error.message ?: error.javaClass.simpleName)
                state = store.state
                delay(RECONNECT_INTERVAL_MILLIS)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    session?.close()
                }
            }
        }
    }

    LaunchedEffect(manualRefreshRequest, autoScanEnabled) {
        if (manualRefreshRequest == 0 || autoScanEnabled) {
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
    val viewerLanguage = languagePreference.resolve(Locale.getDefault().toLanguageTag())
    val strings = remember(viewerLanguage) { ViewerStrings.forLanguage(viewerLanguage) }
    var settingsVisible by remember { mutableStateOf(false) }
    val darkTheme = themePreference.resolveDark(isSystemInDarkTheme())
    val appFocusRequester = remember { FocusRequester() }
    val exportCaptureArchive: () -> Unit = exportCaptureArchive@{
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
            title = strings.chooseArchiveExportFile,
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
    val importCaptureArchive: () -> Unit = importCaptureArchive@{
        if (archiveUiState is CaptureArchiveUiState.Working) {
            return@importCaptureArchive
        }
        val source = archiveFileChooser.chooseImport(
            strings.chooseArchiveToImport,
        ) ?: return@importCaptureArchive
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
                )
                state = store.state
                importedRawArtifacts = imported.rawArtifacts
                aiAnalysisUiState = AiAnalysisUiState.Idle
                hierarchyTreeState = HierarchyTreeState()
                hiddenLayerState = HiddenLayerState()
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
            strings.chooseScreenshotToImport,
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
    val runAiAnalysis: () -> Unit = runAiAnalysis@{
        if (aiAnalysisUiState is AiAnalysisUiState.Working) return@runAiAnalysis
        val snapshot = state.snapshot ?: return@runAiAnalysis
        val activeRoot = state.activeRoot ?: return@runAiAnalysis
        val input = aiAnalysisInputBuilder.build(
            snapshot = snapshot,
            activeRoot = activeRoot,
            analysis = state.analysis,
            screenshotAvailable = state.screenshotPng?.isNotEmpty() == true,
        )
        aiAnalysisUiState = AiAnalysisUiState.Working
        coroutineScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    aiAnalysisClient.analyze(input)
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
            }
        }
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
                    rows = InspectorPresenter.present(state, strings).rows,
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
                        rows = InspectorPresenter.present(state, strings).rows,
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
            ViewerAction.OPEN_SETTINGS -> settingsVisible = true
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

    NativeViewerMenuBar(
        model = NativeViewerMenuModel(
            strings = strings,
            selectedNodeId = state.selectedNodeId,
            autoScanEnabled = autoScanEnabled,
            panelVisibility = panelVisibility,
            viewDisplayOptions = viewDisplayOptions,
            archiveOperationInProgress =
                archiveUiState is CaptureArchiveUiState.Working ||
                    manualRefreshInProgress,
            canExportArchive = state.snapshot != null,
            canImportScreenshot = state.snapshot != null,
            isMacOs = System.getProperty("os.name").startsWith("Mac", ignoreCase = true),
        ),
        onAction = performAction,
        onViewOption = toggleViewDisplayOption,
        onImportArchive = importCaptureArchive,
        onImportScreenshot = importScreenshot,
        onExportArchive = exportCaptureArchive,
    )

    LaunchedEffect(Unit) {
        appFocusRequester.requestFocus()
    }

    CompositionLocalProvider(LocalViewerStrings provides strings) {
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
                Header(
                    state = state,
                    autoScanEnabled = autoScanEnabled,
                    manualRefreshInProgress = manualRefreshInProgress,
                    onManualRefresh = {
                        if (!autoScanEnabled &&
                            !manualRefreshInProgress &&
                            archiveUiState !is CaptureArchiveUiState.Working
                        ) {
                            manualRefreshRequest += 1
                        }
                    },
                    panelVisibility = panelVisibility,
                    onAction = performAction,
                    deviceChoices = deviceChoices(availableDevices),
                    selectedDeviceSerial = selectedDeviceSerial,
                    onSelectDevice = { serial ->
                        manualRefreshSession.invalidate()
                        selectedDeviceSerial = serial
                        deviceListRefreshRequest += 1
                    },
                    captureTargetMode = captureTargetMode,
                    onSelectCaptureTargetMode = { mode ->
                        if (captureTargetMode != mode) {
                            manualRefreshSession.invalidate()
                            captureTargetMode = mode
                        }
                    },
                    onSelectWindow = { windowId ->
                        if (store.selectWindow(windowId)) {
                            hierarchyTreeState = HierarchyTreeState()
                            hiddenLayerState = HiddenLayerState()
                            state = store.state
                            aiAnalysisUiState = AiAnalysisUiState.Idle
                        }
                    },
                )
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
                                val rows = InspectorPresenter.present(state, strings).rows
                                val sanitizedHiddenLayerState = hiddenLayerState.sanitize(rows)
                                if (hiddenLayerState != sanitizedHiddenLayerState) {
                                    hiddenLayerState = sanitizedHiddenLayerState
                                }
                            }
                            Row(modifier = Modifier.fillMaxSize()) {
                                if (panelVisibility.showHierarchy) {
                                    HierarchyPane(
                                        state = state,
                                        treeState = hierarchyTreeState,
                                        viewDisplayOptions = viewDisplayOptions,
                                        hiddenLayerState = hiddenLayerState,
                                        searchState = searchState,
                                        onTreeStateChange = { hierarchyTreeState = it },
                                        onSelect = selectNode,
                                        onToggleHiddenLayer = toggleHiddenLayer,
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
                                            InspectorPresenter.present(state, strings).rows,
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
                                        state,
                                        Modifier
                                            .width(normalizedPaneWidths.properties.dp)
                                            .fillMaxHeight(),
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
                            strings.importArchiveSucceededTitle
                        CaptureArchiveOperation.IMPORT_SCREENSHOT ->
                            strings.importScreenshotSucceededTitle
                        CaptureArchiveOperation.EXPORT ->
                            strings.exportArchiveSucceededTitle
                    }
                    val message = when (operationState.operation) {
                        CaptureArchiveOperation.IMPORT ->
                            strings.archiveImportSucceeded(path)
                        CaptureArchiveOperation.IMPORT_SCREENSHOT ->
                            strings.screenshotImportSucceeded(path)
                        CaptureArchiveOperation.EXPORT ->
                            strings.archiveExportSucceeded(
                                path = path,
                                rawArtifactsIncluded =
                                    operationState.rawArtifactsIncluded,
                            )
                    }
                    ExportResultDialog(
                        title = title,
                        message = message,
                        dismissLabel = strings.dismiss,
                        onDismiss = {
                            archiveUiState = CaptureArchiveUiState.Idle
                        },
                    )
                }
                is CaptureArchiveUiState.Failure -> {
                    val title = when (operationState.operation) {
                        CaptureArchiveOperation.IMPORT -> strings.importArchiveFailedTitle
                        CaptureArchiveOperation.IMPORT_SCREENSHOT -> strings.importScreenshotFailedTitle
                        CaptureArchiveOperation.EXPORT -> strings.exportArchiveFailedTitle
                    }
                    val message = when (operationState.operation) {
                        CaptureArchiveOperation.IMPORT ->
                            strings.archiveImportFailed(operationState.message)
                        CaptureArchiveOperation.IMPORT_SCREENSHOT ->
                            strings.screenshotImportFailed(operationState.message)
                        CaptureArchiveOperation.EXPORT ->
                            strings.archiveExportFailed(operationState.message)
                    }
                    ExportResultDialog(
                        title = title,
                        message = message,
                        dismissLabel = strings.dismiss,
                        onDismiss = {
                            archiveUiState = CaptureArchiveUiState.Idle
                        },
                    )
                }
            }
        }
    }
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
private fun Header(
    state: InspectorState,
    autoScanEnabled: Boolean,
    manualRefreshInProgress: Boolean,
    onManualRefresh: () -> Unit,
    panelVisibility: PanelVisibility,
    onAction: (ViewerAction) -> Unit,
    deviceChoices: List<DeviceChoiceModel>,
    selectedDeviceSerial: String?,
    onSelectDevice: (String?) -> Unit,
    captureTargetMode: CaptureTargetMode,
    onSelectCaptureTargetMode: (CaptureTargetMode) -> Unit,
    onSelectWindow: (String) -> Unit,
) {
    val colors = LocalViewerColors.current
    val strings = LocalViewerStrings.current
    val model = InspectorPresenter.present(state, strings)
    val (packageName, separator, connectionLabel) = headerTextSegments(model, strings)
    Row(
        modifier = Modifier.fillMaxWidth().height(29.dp).background(colors.panel).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(packageName, color = colors.primaryText, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(8.dp))
        DeviceSelector(
            devices = deviceChoices,
            selectedSerial = selectedDeviceSerial,
            onSelectDevice = onSelectDevice,
        )
        Spacer(Modifier.width(8.dp))
        CaptureTargetSelector(
            selectedMode = captureTargetMode,
            onSelectMode = onSelectCaptureTargetMode,
        )
        if (model.windows.size > 1) {
            Spacer(Modifier.width(8.dp))
            WindowSelector(
                windows = model.windows,
                selectedWindowId = model.selectedWindowId,
                onSelectWindow = onSelectWindow,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(separator, color = colors.mutedText)
        Spacer(Modifier.width(10.dp))
        val connectionColor = when (model.connectionTone) {
            ConnectionTone.NEUTRAL -> colors.warning
            ConnectionTone.SUCCESS -> colors.success
            ConnectionTone.ERROR -> colors.error
        }
        StatusDot(connectionColor)
        Spacer(Modifier.width(6.dp))
        Text(connectionLabel, color = connectionColor, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        val scanControlState = ScanControlState(
            autoScanEnabled = autoScanEnabled,
            manualRefreshInProgress = manualRefreshInProgress,
        )
        if (scanControlState.showManualRefresh) {
            ManualRefreshButton(
                enabled = scanControlState.manualRefreshEnabled,
                onClick = onManualRefresh,
            )
            Spacer(Modifier.width(6.dp))
        }
        AutoScanSwitch(autoScanEnabled) {
            onAction(ViewerAction.TOGGLE_AUTO_SCAN)
        }
        Spacer(Modifier.width(12.dp))
        HeaderSeparator()
        Spacer(Modifier.width(12.dp))
        Text(model.metricsText, color = colors.subtleText, fontSize = 12.sp)
        model.timelineText?.let { timelineText ->
            Spacer(Modifier.width(10.dp))
            Text(timelineText, color = colors.subtleText, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        HeaderSeparator()
        Spacer(Modifier.width(10.dp))
        PanelToggleButton(PanelPosition.LEFT, panelVisibility.showHierarchy) {
            onAction(ViewerAction.TOGGLE_HIERARCHY)
        }
        Spacer(Modifier.width(4.dp))
        PanelToggleButton(PanelPosition.BOTTOM, panelVisibility.showFindings) {
            onAction(ViewerAction.TOGGLE_FINDINGS)
        }
        Spacer(Modifier.width(4.dp))
        PanelToggleButton(PanelPosition.RIGHT, panelVisibility.showDetails) {
            onAction(ViewerAction.TOGGLE_DETAILS)
        }
        Spacer(Modifier.width(10.dp))
        HeaderSeparator()
        Spacer(Modifier.width(8.dp))
        SettingsButton {
            onAction(ViewerAction.OPEN_SETTINGS)
        }
    }
}

@Composable
private fun CaptureTargetSelector(
    selectedMode: CaptureTargetMode,
    onSelectMode: (CaptureTargetMode) -> Unit,
) {
    val colors = LocalViewerColors.current
    val strings = LocalViewerStrings.current
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(4.dp)
    Box {
        Text(
            text = "${strings.captureTarget}: ${strings.captureTargetLabel(selectedMode)} ▾",
            color = colors.secondaryText,
            fontSize = 11.sp,
            maxLines = 1,
            modifier = Modifier
                .background(colors.sectionBackground, shape)
                .border(1.dp, colors.border, shape)
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(colors.panel),
        ) {
            CaptureTargetMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(strings.captureTargetLabel(mode), fontSize = 12.sp) },
                    onClick = {
                        expanded = false
                        onSelectMode(mode)
                    },
                    modifier = Modifier.height(32.dp),
                )
            }
        }
    }
}

@Composable
private fun DeviceSelector(
    devices: List<DeviceChoiceModel>,
    selectedSerial: String?,
    onSelectDevice: (String?) -> Unit,
) {
    val colors = LocalViewerColors.current
    val strings = LocalViewerStrings.current
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = devices.firstOrNull { it.serial == selectedSerial }?.label
        ?: strings.autoDevice
    Box {
        Text(
            text = "$selectedLabel ▾",
            color = colors.secondaryText,
            fontSize = 11.sp,
            maxLines = 1,
            modifier = Modifier
                .background(colors.sectionBackground, RoundedCornerShape(4.dp))
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(colors.panel),
        ) {
            DropdownMenuItem(
                text = { Text(strings.autoDevice, fontSize = 12.sp) },
                onClick = {
                    expanded = false
                    onSelectDevice(null)
                },
                modifier = Modifier.height(32.dp),
            )
            devices.forEach { device ->
                DropdownMenuItem(
                    text = { Text(device.label, fontSize = 12.sp) },
                    onClick = {
                        expanded = false
                        onSelectDevice(device.serial)
                    },
                    modifier = Modifier.height(32.dp),
                )
            }
        }
    }
}

@Composable
private fun WindowSelector(
    windows: List<WindowChoiceModel>,
    selectedWindowId: String?,
    onSelectWindow: (String) -> Unit,
) {
    if (windows.size <= 1) return

    val colors = LocalViewerColors.current
    val strings = LocalViewerStrings.current
    var expanded by remember { mutableStateOf(false) }
    val title = windows.firstOrNull { it.id == selectedWindowId }?.title
        ?: windows.first().title
    val shape = RoundedCornerShape(4.dp)
    Box {
        Text(
            text = "${strings.window}: $title  ▾",
            color = colors.secondaryText,
            fontSize = 11.sp,
            maxLines = 1,
            modifier = Modifier
                .widthIn(min = 140.dp, max = 240.dp)
                .background(colors.sectionBackground, shape)
                .border(1.dp, colors.border, shape)
                .clickable { expanded = true }
                .semantics {
                    contentDescription = strings.selectWindow
                }
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(colors.panel),
        ) {
            windows.forEach { window ->
                DropdownMenuItem(
                    text = { Text(window.title, fontSize = 12.sp) },
                    onClick = {
                        expanded = false
                        onSelectWindow(window.id)
                    },
                    modifier = Modifier.height(32.dp),
                )
            }
        }
    }
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
    strings: ViewerStrings = ViewerStrings.English,
): List<String> =
    listOf(model.packageName ?: strings.noApp, "|", model.connectionLabel)

@Composable
private fun AutoScanSwitch(
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalViewerColors.current
    val strings = LocalViewerStrings.current
    Row(
        modifier = Modifier.clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = strings.autoScan,
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
                        color = if (enabled) {
                            colors.accent.copy(alpha = 0.55f)
                        } else {
                            colors.switchTrackOff
                        },
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(2.dp),
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
    val strings = LocalViewerStrings.current
    HeaderTextButton(
        label = strings.refresh,
        contentDescription = strings.refreshOnce,
        enabled = enabled,
        onClick = onClick,
        widthDp = ManualRefreshButtonStyle.WIDTH_DP,
    )
}

@Composable
private fun HeaderTextButton(
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    widthDp: Int,
) {
    val colors = LocalViewerColors.current
    val shape = RoundedCornerShape(ManualRefreshButtonStyle.CORNER_RADIUS_DP.dp)
    val backgroundAlpha = if (enabled) {
        ManualRefreshButtonStyle.BACKGROUND_ALPHA
    } else {
        ManualRefreshButtonStyle.DISABLED_BACKGROUND_ALPHA
    }
    val borderColor = colors.border.copy(
        alpha = if (enabled) {
            ManualRefreshButtonStyle.BORDER_ALPHA
        } else {
            ManualRefreshButtonStyle.DISABLED_BORDER_ALPHA
        },
    )
    Row(
        modifier = Modifier
            .width(widthDp.dp)
            .height(ManualRefreshButtonStyle.HEIGHT_DP.dp)
            .background(Color.White.copy(alpha = backgroundAlpha), shape)
            .border(1.dp, borderColor, shape)
            .semantics { this.contentDescription = contentDescription }
            .let { base -> if (enabled) base.clickable(onClick = onClick) else base }
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (enabled) colors.primaryText else colors.mutedText.copy(alpha = 0.55f),
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

internal object ManualRefreshButtonStyle {
    const val WIDTH_DP = 56
    const val HEIGHT_DP = 22
    const val CORNER_RADIUS_DP = 7
    const val BACKGROUND_ALPHA = 0.62f
    const val BORDER_ALPHA = 0.45f
    const val DISABLED_BACKGROUND_ALPHA = 0.24f
    const val DISABLED_BORDER_ALPHA = 0.18f
}

@Composable
private fun HeaderSeparator() {
    Box(Modifier.width(1.dp).height(14.dp).background(LocalViewerColors.current.border))
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
private fun SettingsButton(onClick: () -> Unit) {
    val colors = LocalViewerColors.current
    Box(
        modifier = Modifier
            .width(26.dp)
            .height(21.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(15.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val strokeWidth = 1.2.dp.toPx()
            val innerRadius = 2.2.dp.toPx()
            val outerRadius = 5.2.dp.toPx()
            drawCircle(
                color = colors.mutedText,
                radius = innerRadius,
                center = center,
                style = Stroke(width = strokeWidth),
            )
            drawCircle(
                color = colors.mutedText,
                radius = outerRadius,
                center = center,
                style = Stroke(width = strokeWidth),
            )
            repeat(8) { index ->
                val angle = Math.toRadians((index * 45.0) - 90.0)
                val start = Offset(
                    x = center.x + kotlin.math.cos(angle).toFloat() * outerRadius,
                    y = center.y + kotlin.math.sin(angle).toFloat() * outerRadius,
                )
                val endRadius = outerRadius + 2.dp.toPx()
                val end = Offset(
                    x = center.x + kotlin.math.cos(angle).toFloat() * endRadius,
                    y = center.y + kotlin.math.sin(angle).toFloat() * endRadius,
                )
                drawLine(
                    color = colors.mutedText,
                    start = start,
                    end = end,
                    strokeWidth = strokeWidth,
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
    searchState: HierarchySearchState,
    onTreeStateChange: (HierarchyTreeState) -> Unit,
    onSelect: (String) -> Unit,
    onToggleHiddenLayer: (String) -> Unit,
    onSearchStateChange: (HierarchySearchState) -> Unit,
    onAction: (ViewerAction) -> Unit,
    modifier: Modifier,
) {
    val colors = LocalViewerColors.current
    val strings = LocalViewerStrings.current
    val model = InspectorPresenter.present(state, strings)
    val horizontalScrollState = rememberScrollState()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val visibleRows = treeState.displayRows(
        rows = model.rows,
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
        PanelTitle(strings.hierarchy, "${model.rows.size}")
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
    val strings = LocalViewerStrings.current
    val selectedBounds = state.activeRoot?.findNodeBoundsSkippingHidden(
        targetId = state.selectedNodeId,
        hiddenNodeIds = hiddenLayerState.hiddenNodeIds,
    )
    val hoveredBounds = state.activeRoot?.findNodeBoundsSkippingHidden(
        targetId = state.hoveredNodeId,
        hiddenNodeIds = hiddenLayerState.hiddenNodeIds,
    )
    val screenshot = rememberScreenshot(state.screenshotPng)
    val pointerSelection = remember { CanvasPointerSelection() }
    var canvasPixelSize by remember { mutableStateOf(IntSize.Zero) }
    var appOnly by remember { mutableStateOf(true) }
    val source = CanvasWindowSource.sourceRect(state, appOnly)
    val canvasMode = previewCanvasMode(
        hasSource = source != null,
        hasScreenshot = screenshot != null,
    )
    Column(modifier.background(colors.canvasBackground)) {
        PanelTitle(strings.canvas) {
            Text(
                source?.let { "${it.width} × ${it.height}" } ?: strings.noLiveFrame,
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
            Surface(
                modifier = if (previewSize != null) {
                    Modifier.size(previewSize.width.dp, previewSize.height.dp)
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
                                    state.activeRoot?.let { root ->
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
                                    state.activeRoot?.let { root ->
                                        CanvasHitTester.hitCandidates(
                                            root = root,
                                            point = it,
                                            hiddenNodeIds = hiddenLayerState.hiddenNodeIds,
                                            order = hitTestOrder,
                                        )
                                    }
                                }.orEmpty()
                                pointerSelection.click(point, candidates)?.let(onSelectNode)
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
                            state.activeRoot?.let { root ->
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
                        Text(strings.waitingForFrame, color = colors.previewText, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

internal enum class PreviewCanvasMode {
    WAITING,
    LAYOUT_ONLY,
    SCREENSHOT,
}

internal fun previewCanvasMode(
    hasSource: Boolean,
    hasScreenshot: Boolean,
): PreviewCanvasMode = when {
    !hasSource -> PreviewCanvasMode.WAITING
    hasScreenshot -> PreviewCanvasMode.SCREENSHOT
    else -> PreviewCanvasMode.LAYOUT_ONLY
}

private fun dev.agentperf.protocol.UiNode.findNodeBounds(targetId: String?): dev.agentperf.protocol.Bounds? {
    if (targetId == null) return null
    if (id == targetId) return bounds
    return children.firstNotNullOfOrNull { it.findNodeBounds(targetId) }
}

private fun dev.agentperf.protocol.UiNode.findNodeBoundsSkippingHidden(
    targetId: String?,
    hiddenNodeIds: Set<String>,
): dev.agentperf.protocol.Bounds? {
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
    val strings = LocalViewerStrings.current
    val label = if (hidden) strings.showLayer else strings.hideLayer
    Box(
        modifier = Modifier
            .width(LayerVisibilityButtonStyle.WIDTH_DP.dp)
            .height(LayerVisibilityButtonStyle.HEIGHT_DP.dp)
            .background(
                color = if (hidden) colors.accent.copy(alpha = 0.14f) else Color.Transparent,
                shape = RoundedCornerShape(3.dp),
            )
            .semantics { contentDescription = strings.toggleLayerVisibility }
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
    val strings = LocalViewerStrings.current
    val label = when (order) {
        CanvasHitTestOrder.SMALL_AREA_FIRST -> strings.smallAreaHitTesting
        CanvasHitTestOrder.Z_ORDER -> strings.zOrderHitTesting
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
    val strings = LocalViewerStrings.current
    Text(
        text = strings.hiddenLayerSummary(count),
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
    val strings = LocalViewerStrings.current
    val color = if (appOnly) colors.accent else colors.mutedText
    Text(
        text = if (appOnly) strings.appOnlyOn else strings.appOnlyOff,
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
private fun DetailsPane(state: InspectorState, modifier: Modifier) {
    val colors = LocalViewerColors.current
    val strings = LocalViewerStrings.current
    val details = InspectorPresenter.present(state, strings).details
    var expansionState by remember { mutableStateOf(DetailSectionExpansionState()) }
    Column(modifier.background(colors.panel)) {
        PanelTitle(strings.properties, details.id)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(details.sections, key = { it.title }) { section ->
                DetailSection(
                    section = section,
                    expanded = expansionState.isExpanded(section.title),
                    onToggle = {
                        expansionState = expansionState.toggle(section.title)
                    },
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
            DetailRow(row = row, index = index)
        }
    }
    HorizontalDivider(color = colors.border)
}

@Composable
private fun DetailRow(
    row: DetailRowModel,
    index: Int,
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(stripeColor)
            .background(
                if (row.tone == DetailTone.NORMAL) {
                    Color.Transparent
                } else {
                    color.copy(alpha = 0.06f)
                },
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
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

@Composable
private fun FindingsPane(
    state: InspectorState,
    viewDisplayOptions: ViewDisplayOptions,
    aiAnalysisUiState: AiAnalysisUiState,
    onRunAiAnalysis: () -> Unit,
    onSelectNode: (String) -> Unit,
    onSelectTimelineFrame: (Int) -> Unit,
    modifier: Modifier,
) {
    val colors = LocalViewerColors.current
    val strings = LocalViewerStrings.current
    val model = InspectorPresenter.present(state, strings)
    val findings = ViewDisplayProjection.findings(
        findings = model.findings,
        rows = model.rows,
        hideInvisible = viewDisplayOptions.hideInvisibleFindings,
    )
    val severitySummary = ViewDisplayProjection.severitySummary(findings)
    var selectionState by remember { mutableStateOf(FindingSelectionState()) }
    Column(modifier.background(colors.panel)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(PanelHeaderLayout.HEIGHT_DP.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(strings.findings, color = colors.secondaryText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(18.dp))
            Badge(strings.infoBadge(severitySummary.info), colors.info)
            Spacer(Modifier.width(8.dp))
            Badge(strings.warningBadge(severitySummary.warning), colors.warning)
            Spacer(Modifier.width(8.dp))
            Badge(strings.errorBadge(severitySummary.error), colors.error)
            Spacer(Modifier.weight(1f))
            if (AI_ANALYSIS_ENTRY_VISIBLE) {
                val aiStatus = when (aiAnalysisUiState) {
                    AiAnalysisUiState.Idle -> state.aiAnalysis?.summary?.let(strings::aiAnalysisSummary)
                    AiAnalysisUiState.Working -> strings.aiAnalysisRunning
                    is AiAnalysisUiState.Failure -> strings.aiAnalysisFailed(aiAnalysisUiState.message)
                }
                if (aiStatus != null) {
                    Text(aiStatus, color = colors.mutedText, fontSize = 11.sp, maxLines = 1)
                    Spacer(Modifier.width(12.dp))
                }
                TextButton(
                    onClick = onRunAiAnalysis,
                    enabled = state.snapshot != null && aiAnalysisUiState !is AiAnalysisUiState.Working,
                ) {
                    Text(strings.runAiAnalysis, fontSize = 11.sp)
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(strings.timelineLiveCapture, color = colors.mutedText, fontSize = 11.sp)
        }
        if (model.timelineFrames.isNotEmpty()) {
            HorizontalDivider(color = colors.border)
            TimelineStrip(
                frames = model.timelineFrames,
                onSelectTimelineFrame = onSelectTimelineFrame,
            )
        }
        HorizontalDivider(color = colors.border)
        if (findings.isEmpty()) {
            Text(strings.noFindings, color = colors.subtleText, modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(findings, key = { it.key }) { finding ->
                    FindingRow(
                        finding = finding,
                        selected = selectionState.isSelected(finding.key),
                        onDoubleClick = {
                            selectionState = selectionState.select(finding.key)
                            onSelectNode(finding.nodeId)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineStrip(
    frames: List<TimelineFrameModel>,
    onSelectTimelineFrame: (Int) -> Unit,
) {
    val colors = LocalViewerColors.current
    LazyRow(
        modifier = Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(frames, key = { it.index }) { frame ->
            val background = if (frame.selected) colors.selectedRow else colors.sectionBackground
            val textColor = if (frame.selected) colors.primaryText else colors.secondaryText
            Text(
                text = "${frame.label} ${frame.summary}",
                color = textColor,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier
                    .background(background, RoundedCornerShape(4.dp))
                    .clickable { onSelectTimelineFrame(frame.index) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
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
    val strings = LocalViewerStrings.current
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
                    strings.searchHierarchy,
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
                summary ?: strings.searchNoMatch,
                color = if (matchedNodeIds.isEmpty()) colors.error else colors.mutedText,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 32.dp),
            )
            Spacer(Modifier.width(2.dp))
            SearchNavButton(
                label = "◀",
                contentDescription = strings.searchPrevious,
                enabled = matchedNodeIds.isNotEmpty(),
                onClick = onNavigatePrevious,
            )
            Spacer(Modifier.width(2.dp))
            SearchNavButton(
                label = "▶",
                contentDescription = strings.searchNext,
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
