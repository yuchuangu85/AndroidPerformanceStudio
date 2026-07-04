package dev.agentperf.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentperf.application.InspectorState
import dev.agentperf.application.InspectorStore
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

@Composable
fun DesktopViewerApp() {
    val store = remember { createInitialInspectorStore() }
    var state by remember { mutableStateOf(store.state) }
    var autoScanEnabled by remember { mutableStateOf(AUTO_SCAN_DEFAULT_ENABLED) }
    var manualRefreshRequest by remember { mutableStateOf(0) }
    var manualRefreshInProgress by remember { mutableStateOf(false) }
    val deviceClient = remember { LiveDeviceClient() }
    val protocolCodec = remember { ProtocolCodec(supportedMajor = 1) }
    val exportDirectoryChooser = remember { SwingExportDirectoryChooser() }
    val visibleWindowViewsExporter = remember(deviceClient) {
        VisibleWindowViewsExporter(
            captureDump = deviceClient::dumpVisibleWindowViews,
            renderText = VisibleWindowViewsTextRenderer::render,
        )
    }
    var visibleWindowViewsExportState by remember {
        mutableStateOf<VisibleWindowViewsExportUiState>(VisibleWindowViewsExportUiState.Idle)
    }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(autoScanEnabled) {
        if (!autoScanEnabled) {
            store.disconnected()
            state = store.state
            return@LaunchedEffect
        }
        while (currentCoroutineContext().isActive) {
            var session: ConnectedDeviceSession? = null
            try {
                store.connecting()
                state = store.state
                session = withContext(Dispatchers.IO) {
                    deviceClient.connectForegroundApp()
                }
                while (currentCoroutineContext().isActive) {
                    val isCurrent = withContext(Dispatchers.IO) {
                        session.isForegroundAppCurrent()
                    }
                    if (!isCurrent) break
                    val frame = withContext(Dispatchers.IO) { session.capture() }
                    store.loadCapture(
                        snapshot = protocolCodec.decodeSnapshot(frame.snapshotJson),
                        screenshotPng = frame.screenshotPng,
                    )
                    state = store.state
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
        var session: ConnectedDeviceSession? = null
        manualRefreshInProgress = true
        try {
            store.connecting()
            state = store.state
            session = withContext(Dispatchers.IO) {
                deviceClient.connectForegroundApp()
            }
            val frame = withContext(Dispatchers.IO) {
                session.capture()
            }
            store.loadCapture(
                snapshot = protocolCodec.decodeSnapshot(frame.snapshotJson),
                screenshotPng = frame.screenshotPng,
            )
            state = store.state
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            store.connectionFailed(error.message ?: error.javaClass.simpleName)
            state = store.state
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                session?.close()
            }
            manualRefreshInProgress = false
        }
    }

    var paneWidths by remember { mutableStateOf(PaneWidths()) }
    var findingsHeightDp by remember { mutableStateOf(FindingsLayout.DEFAULT_HEIGHT_DP) }
    var panelVisibility by remember { mutableStateOf(PanelVisibility()) }
    var hierarchyTreeState by remember { mutableStateOf(HierarchyTreeState()) }
    val themeStore = remember { ThemePreferenceStore.desktop() }
    var themePreference by remember { mutableStateOf(themeStore.load()) }
    val languageStore = remember { LanguagePreferenceStore.desktop() }
    var languagePreference by remember { mutableStateOf(languageStore.load()) }
    val viewerLanguage = languagePreference.resolve(Locale.getDefault().toLanguageTag())
    val strings = remember(viewerLanguage) { ViewerStrings.forLanguage(viewerLanguage) }
    var settingsVisible by remember { mutableStateOf(false) }
    val darkTheme = themePreference.resolveDark(isSystemInDarkTheme())
    val appFocusRequester = remember { FocusRequester() }
    val exportVisibleWindowViews: () -> Unit = {
        if (visibleWindowViewsExportState !is VisibleWindowViewsExportUiState.Exporting) {
            exportDirectoryChooser.chooseDirectory(strings.chooseExportDirectory)?.let { directory ->
                visibleWindowViewsExportState = VisibleWindowViewsExportUiState.Exporting
                coroutineScope.launch {
                    visibleWindowViewsExportState = try {
                        withContext(Dispatchers.IO) {
                            visibleWindowViewsExporter.export(directory)
                        }
                        VisibleWindowViewsExportUiState.Success(directory)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        VisibleWindowViewsExportUiState.Failure(
                            strings.connectionError(
                                error.message ?: error.javaClass.simpleName,
                            ),
                        )
                    }
                }
            }
        }
    }
    val selectNode: (String) -> Unit = { id ->
        if (store.selectNode(id)) state = store.state
    }
    val performAction: (ViewerAction) -> Unit = { action ->
        when (action) {
            ViewerAction.TOGGLE_AUTO_SCAN -> autoScanEnabled = !autoScanEnabled
            ViewerAction.PREVIOUS_NODE,
            ViewerAction.NEXT_NODE,
            -> {
                val direction = if (action == ViewerAction.PREVIOUS_NODE) {
                    HierarchyNavigationDirection.UP
                } else {
                    HierarchyNavigationDirection.DOWN
                }
                hierarchyTreeState.adjacentNodeId(
                    rows = InspectorPresenter.present(state, strings).rows,
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
            ViewerAction.OPEN_SETTINGS -> settingsVisible = true
        }
    }

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
                        viewerCommandAction(
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
                        if (!autoScanEnabled && !manualRefreshInProgress) {
                            manualRefreshRequest += 1
                        }
                    },
                    panelVisibility = panelVisibility,
                    onAction = performAction,
                    exportInProgress =
                        visibleWindowViewsExportState is VisibleWindowViewsExportUiState.Exporting,
                    onExportVisibleWindowViews = exportVisibleWindowViews,
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
                            Row(modifier = Modifier.fillMaxSize()) {
                                if (panelVisibility.showHierarchy) {
                                    HierarchyPane(
                                        state = state,
                                        treeState = hierarchyTreeState,
                                        onTreeStateChange = { hierarchyTreeState = it },
                                        onSelect = selectNode,
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
                                PreviewPane(state, Modifier.weight(1f).fillMaxHeight())
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
                                onSelectNode = selectNode,
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
                    selectedThemePreference = themePreference,
                    onSelectThemePreference = { preference ->
                        themePreference = preference
                        themeStore.save(preference)
                    },
                    selectedLanguagePreference = languagePreference,
                    onSelectLanguagePreference = { preference ->
                        languagePreference = preference
                        languageStore.save(preference)
                    },
                    onDismiss = {
                        settingsVisible = false
                    },
                )
            }
            when (val exportState = visibleWindowViewsExportState) {
                VisibleWindowViewsExportUiState.Idle,
                VisibleWindowViewsExportUiState.Exporting,
                -> Unit
                is VisibleWindowViewsExportUiState.Success -> {
                    ExportResultDialog(
                        title = strings.visibleWindowViewsExportSucceededTitle,
                        message = strings.visibleWindowViewsExportSucceeded(
                            exportState.directory.toAbsolutePath().toString(),
                        ),
                        dismissLabel = strings.dismiss,
                        onDismiss = {
                            visibleWindowViewsExportState = VisibleWindowViewsExportUiState.Idle
                        },
                    )
                }
                is VisibleWindowViewsExportUiState.Failure -> {
                    ExportResultDialog(
                        title = strings.visibleWindowViewsExportFailedTitle,
                        message = strings.visibleWindowViewsExportFailed(exportState.message),
                        dismissLabel = strings.dismiss,
                        onDismiss = {
                            visibleWindowViewsExportState = VisibleWindowViewsExportUiState.Idle
                        },
                    )
                }
            }
        }
    }
}

internal fun createInitialInspectorStore(): InspectorStore = InspectorStore()

@Composable
private fun Header(
    state: InspectorState,
    autoScanEnabled: Boolean,
    manualRefreshInProgress: Boolean,
    onManualRefresh: () -> Unit,
    panelVisibility: PanelVisibility,
    onAction: (ViewerAction) -> Unit,
    exportInProgress: Boolean,
    onExportVisibleWindowViews: () -> Unit,
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
        ViewerActionDropdown(
            state = state,
            autoScanEnabled = autoScanEnabled,
            panelVisibility = panelVisibility,
            onAction = onAction,
        )
        Spacer(Modifier.width(4.dp))
        AdvancedMenu(
            model = AdvancedMenuModel(
                strings = strings,
                exportInProgress = exportInProgress,
            ),
            onExport = onExportVisibleWindowViews,
        )
        Spacer(Modifier.width(10.dp))
        HeaderSeparator()
        Spacer(Modifier.width(10.dp))
        val scanControlState = ScanControlState(
            autoScanEnabled = autoScanEnabled,
            manualRefreshInProgress = manualRefreshInProgress,
        )
        AutoScanSwitch(autoScanEnabled) {
            onAction(ViewerAction.TOGGLE_AUTO_SCAN)
        }
        if (scanControlState.showManualRefresh) {
            Spacer(Modifier.width(6.dp))
            ManualRefreshButton(
                enabled = scanControlState.manualRefreshEnabled,
                onClick = onManualRefresh,
            )
        }
        Spacer(Modifier.width(12.dp))
        HeaderSeparator()
        Spacer(Modifier.width(12.dp))
        Text(model.metricsText, color = colors.subtleText, fontSize = 12.sp)
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
private fun ViewerActionDropdown(
    state: InspectorState,
    autoScanEnabled: Boolean,
    panelVisibility: PanelVisibility,
    onAction: (ViewerAction) -> Unit,
) {
    val colors = LocalViewerColors.current
    val strings = LocalViewerStrings.current
    var expanded by remember { mutableStateOf(false) }
    val menuItems = ViewerActionMenu.items(strings)
    Box {
        Row(
            modifier = Modifier
                .height(23.dp)
                .clickable { expanded = true }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(strings.actions, color = colors.secondaryText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(5.dp))
            Text("▾", color = colors.mutedText, fontSize = 10.sp)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(colors.panel).width(280.dp),
        ) {
            menuItems.forEachIndexed { index, item ->
                if (index > 0 && menuItems[index - 1].group != item.group) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 3.dp),
                        color = colors.border,
                    )
                }
                val isTreeAction = item.action == ViewerAction.PREVIOUS_NODE ||
                    item.action == ViewerAction.NEXT_NODE ||
                    item.action == ViewerAction.TOGGLE_SELECTED_NODE
                val active = when (item.action) {
                    ViewerAction.TOGGLE_AUTO_SCAN -> autoScanEnabled
                    ViewerAction.TOGGLE_HIERARCHY -> panelVisibility.showHierarchy
                    ViewerAction.TOGGLE_FINDINGS -> panelVisibility.showFindings
                    ViewerAction.TOGGLE_DETAILS -> panelVisibility.showDetails
                    else -> false
                }
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (active) "✓  ${item.label}" else "    ${item.label}",
                                color = colors.primaryText,
                                fontSize = 11.sp,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = item.shortcutLabel,
                                color = colors.mutedText,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    },
                    enabled = !isTreeAction || state.selectedNodeId != null,
                    onClick = {
                        expanded = false
                        onAction(item.action)
                    },
                )
            }
        }
    }
}

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
    val colors = LocalViewerColors.current
    val strings = LocalViewerStrings.current
    val iconColor = if (enabled) colors.secondaryText else colors.mutedText.copy(alpha = 0.55f)
    Box(
        modifier = Modifier
            .size(22.dp)
            .semantics { contentDescription = strings.refreshOnce }
            .let { base ->
                if (enabled) base.clickable(onClick = onClick) else base
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(14.dp)) {
            val strokeWidth = 1.4.dp.toPx()
            drawArc(
                color = iconColor,
                startAngle = -55f,
                sweepAngle = 285f,
                useCenter = false,
                style = Stroke(width = strokeWidth),
            )
            drawLine(
                color = iconColor,
                start = Offset(size.width * 0.78f, size.height * 0.1f),
                end = Offset(size.width * 0.95f, size.height * 0.32f),
                strokeWidth = strokeWidth,
            )
            drawLine(
                color = iconColor,
                start = Offset(size.width * 0.78f, size.height * 0.1f),
                end = Offset(size.width * 0.59f, size.height * 0.29f),
                strokeWidth = strokeWidth,
            )
        }
    }
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
    onTreeStateChange: (HierarchyTreeState) -> Unit,
    onSelect: (String) -> Unit,
    onAction: (ViewerAction) -> Unit,
    modifier: Modifier,
) {
    val colors = LocalViewerColors.current
    val strings = LocalViewerStrings.current
    val model = InspectorPresenter.present(state, strings)
    val horizontalScrollState = rememberScrollState()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val visibleRows = treeState.visibleRows(model.rows)
    LaunchedEffect(state.selectedNodeId, visibleRows) {
        val selectedIndex = visibleRows.indexOfFirst { it.id == state.selectedNodeId }
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        HierarchySelectionScrollPolicy.targetIndex(
            selectedIndex = selectedIndex,
            firstVisibleIndex = visibleItems.firstOrNull()?.index,
            lastVisibleIndex = visibleItems.lastOrNull()?.index,
        )?.let { listState.scrollToItem(it) }
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
                    else -> false
                }
            }
            .focusable(),
    ) {
        PanelTitle(strings.hierarchy, "${model.rows.size}")
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
                        val rowColor = if (row.selected) colors.selectedRow else Color.Transparent
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
                            Text(
                                "${row.number}  ${row.label}",
                                color = if (row.visible) colors.rowText else colors.hiddenRowText,
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

@Composable
private fun PreviewPane(state: InspectorState, modifier: Modifier) {
    val colors = LocalViewerColors.current
    val strings = LocalViewerStrings.current
    val selectedBounds = state.selectedNode?.bounds
    val display = state.snapshot?.display
    val appBounds = state.snapshot?.root?.bounds
    val screenshot = rememberScreenshot(state.screenshotPng)
    var appOnly by remember { mutableStateOf(true) }
    val source = display?.let {
        CanvasGeometry.sourceRect(
            appBounds = appBounds,
            displayWidth = it.widthPx,
            displayHeight = it.heightPx,
            appOnly = appOnly,
        )
    }
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
                if (screenshot != null && source != null) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(colors.previewCanvas)
                        val destination = FloatRect(
                            left = 0f,
                            top = 0f,
                            width = size.width,
                            height = size.height,
                        )
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
                        selectedBounds?.let { bounds ->
                            val overlay = CanvasGeometry.mapBounds(
                                bounds = bounds,
                                source = source,
                                destination = destination,
                            )
                            overlay?.let {
                                drawRect(
                                    color = colors.error,
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

internal fun canvasCornerRadiusDp(appOnly: Boolean): Int = if (appOnly) 24 else 4

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
        bytes?.let { encoded ->
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

@Composable
private fun FindingsPane(
    state: InspectorState,
    onSelectNode: (String) -> Unit,
    modifier: Modifier,
) {
    val colors = LocalViewerColors.current
    val strings = LocalViewerStrings.current
    val model = InspectorPresenter.present(state, strings)
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
            Badge(strings.infoBadge(model.severitySummary.info), colors.info)
            Spacer(Modifier.width(8.dp))
            Badge(strings.warningBadge(model.severitySummary.warning), colors.warning)
            Spacer(Modifier.width(8.dp))
            Badge(strings.errorBadge(model.severitySummary.error), colors.error)
            Spacer(Modifier.weight(1f))
            Text(strings.timelineLiveCapture, color = colors.mutedText, fontSize = 11.sp)
        }
        HorizontalDivider(color = colors.border)
        if (model.findings.isEmpty()) {
            Text(strings.noFindings, color = colors.subtleText, modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(model.findings, key = { it.key }) { finding ->
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
