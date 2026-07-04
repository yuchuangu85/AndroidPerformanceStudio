package dev.agentperf.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import dev.agentperf.protocol.ProtocolCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.awt.Cursor
import kotlin.math.roundToInt

private val Panel = Color(0xFF141820)
private val CanvasBackground = Color(0xFF0D1016)
private val Border = Color(0xFF2B3240)
private val Accent = Color(0xFF70A5FF)
internal const val AUTO_SCAN_DEFAULT_ENABLED = false

@Composable
fun DesktopViewerApp() {
    val store = remember { createInitialInspectorStore() }
    var state by remember { mutableStateOf(store.state) }
    var autoScanEnabled by remember { mutableStateOf(AUTO_SCAN_DEFAULT_ENABLED) }
    val deviceClient = remember { LiveDeviceClient() }
    val protocolCodec = remember { ProtocolCodec(supportedMajor = 1) }

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

    var paneWidths by remember { mutableStateOf(PaneWidths()) }
    var findingsHeightDp by remember { mutableStateOf(FindingsLayout.DEFAULT_HEIGHT_DP) }
    var panelVisibility by remember { mutableStateOf(PanelVisibility()) }

    MaterialTheme {
        Surface(color = CanvasBackground, modifier = Modifier.fillMaxSize()) {
            Column {
                Header(
                    state = state,
                    autoScanEnabled = autoScanEnabled,
                    onToggleAutoScan = {
                        autoScanEnabled = !autoScanEnabled
                    },
                    panelVisibility = panelVisibility,
                    onToggleHierarchy = {
                        panelVisibility = panelVisibility.toggleHierarchy()
                    },
                    onToggleFindings = {
                        panelVisibility = panelVisibility.toggleFindings()
                    },
                    onToggleDetails = {
                        panelVisibility = panelVisibility.toggleDetails()
                    },
                )
                HorizontalDivider(color = Border)
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
                                        onSelect = { id ->
                                            if (store.selectNode(id)) state = store.state
                                        },
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
    }
}

internal fun createInitialInspectorStore(): InspectorStore = InspectorStore()

@Composable
private fun Header(
    state: InspectorState,
    autoScanEnabled: Boolean,
    onToggleAutoScan: () -> Unit,
    panelVisibility: PanelVisibility,
    onToggleHierarchy: () -> Unit,
    onToggleFindings: () -> Unit,
    onToggleDetails: () -> Unit,
) {
    val model = InspectorPresenter.present(state)
    val (packageName, separator, connectionLabel) = headerTextSegments(model)
    Row(
        modifier = Modifier.fillMaxWidth().height(29.dp).background(Panel).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(packageName, color = Color(0xFFDCE4F2), fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(10.dp))
        Text(separator, color = Color(0xFF687386))
        Spacer(Modifier.width(10.dp))
        val connectionColor = when (model.connectionTone) {
            ConnectionTone.NEUTRAL -> Color(0xFFF5A524)
            ConnectionTone.SUCCESS -> Color(0xFF55D187)
            ConnectionTone.ERROR -> Color(0xFFEF5350)
        }
        StatusDot(connectionColor)
        Spacer(Modifier.width(6.dp))
        Text(connectionLabel, color = connectionColor, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        AutoScanSwitch(autoScanEnabled, onToggleAutoScan)
        Spacer(Modifier.width(12.dp))
        HeaderSeparator()
        Spacer(Modifier.width(12.dp))
        Text(model.metricsText, color = Color(0xFF8E9AAF), fontSize = 12.sp)
        Spacer(Modifier.width(12.dp))
        HeaderSeparator()
        Spacer(Modifier.width(10.dp))
        PanelToggleButton(PanelPosition.LEFT, panelVisibility.showHierarchy, onToggleHierarchy)
        Spacer(Modifier.width(4.dp))
        PanelToggleButton(PanelPosition.BOTTOM, panelVisibility.showFindings, onToggleFindings)
        Spacer(Modifier.width(4.dp))
        PanelToggleButton(PanelPosition.RIGHT, panelVisibility.showDetails, onToggleDetails)
    }
}

internal fun headerTextSegments(model: InspectorScreenModel): List<String> =
    listOf(model.packageName ?: "No app", "|", model.connectionLabel)

@Composable
private fun AutoScanSwitch(
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "自动扫描",
            color = if (enabled) Color(0xFFDCE4F2) else Color(0xFF687386),
            fontSize = 11.sp,
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier =
                Modifier
                    .width(30.dp)
                    .height(16.dp)
                    .background(
                        color = if (enabled) Accent.copy(alpha = 0.55f) else Color(0xFF353D4B),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(2.dp),
            contentAlignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .background(
                        color = if (enabled) Color.White else Color(0xFF8E9AAF),
                        shape = RoundedCornerShape(50),
                    ),
            )
        }
    }
}

@Composable
private fun HeaderSeparator() {
    Box(Modifier.width(1.dp).height(14.dp).background(Border))
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
    val iconColor = if (visible) Accent else Color(0xFF687386)
    Box(
        modifier =
            Modifier
                .width(26.dp)
                .height(21.dp)
                .background(
                    color = if (visible) Accent.copy(alpha = 0.18f) else Color.Transparent,
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
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    val model = InspectorPresenter.present(state)
    var treeState by remember { mutableStateOf(HierarchyTreeState()) }
    val horizontalScrollState = rememberScrollState()
    val visibleRows = treeState.visibleRows(model.rows)
    Column(modifier.background(Panel)) {
        PanelTitle("HIERARCHY", "${model.rows.size}")
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewportWidth = maxWidth
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScrollState),
            ) {
                LazyColumn(
                    Modifier
                        .fillMaxHeight()
                        .widthIn(min = viewportWidth)
                        .padding(vertical = 6.dp),
                ) {
                    items(visibleRows, key = { it.number }) { row ->
                        val expanded = treeState.isExpanded(row.id)
                        val rowColor = if (row.selected) Color(0xFF253B5F) else Color.Transparent
                        Row(
                            modifier = Modifier
                                .widthIn(min = viewportWidth)
                                .height(HierarchyRowLayout.HEIGHT_DP.dp)
                                .background(rowColor)
                                .clickable { onSelect(row.id) }
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
                                    treeState = treeState.toggle(row.id)
                                },
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "${row.number}  ${row.label}",
                                color = if (row.visible) Color(0xFFD8E0ED) else Color(0xFF687386),
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
                        color = Accent,
                        start = Offset(0.5.dp.toPx(), 2.dp.toPx()),
                        end = Offset(4.dp.toPx(), 5.5.dp.toPx()),
                        strokeWidth = strokeWidth,
                    )
                    drawLine(
                        color = Accent,
                        start = Offset(4.dp.toPx(), 5.5.dp.toPx()),
                        end = Offset(7.5.dp.toPx(), 2.dp.toPx()),
                        strokeWidth = strokeWidth,
                    )
                } else {
                    drawLine(
                        color = Accent,
                        start = Offset(2.dp.toPx(), 0.5.dp.toPx()),
                        end = Offset(5.5.dp.toPx(), 4.dp.toPx()),
                        strokeWidth = strokeWidth,
                    )
                    drawLine(
                        color = Accent,
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
    Column(modifier.background(CanvasBackground)) {
        PanelTitle("CANVAS") {
            Text(
                source?.let { "${it.width} × ${it.height}" } ?: "No live frame",
                color = Color(0xFF687386),
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
                color = Color(0xFFEEF2F6),
                shadowElevation = 8.dp,
            ) {
                if (screenshot != null && source != null) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(Color(0xFFF8FAFC))
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
                                    color = Color(0xFFEF4444),
                                    topLeft = Offset(it.left, it.top),
                                    size = Size(it.width, it.height),
                                    style = Stroke(width = 2.dp.toPx()),
                                )
                            }
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Waiting for live device frame", color = Color(0xFF64748B), fontSize = 13.sp)
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
    val color = if (appOnly) Accent else Color(0xFF687386)
    Text(
        text = if (appOnly) "仅应用 ON" else "仅应用 OFF",
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
    val details = InspectorPresenter.present(state).details
    var expansionState by remember { mutableStateOf(DetailSectionExpansionState()) }
    Column(modifier.background(Panel)) {
        PanelTitle("PROPERTIES", details.id)
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
    val riskSection = section.title == "RENDER RISKS"
    val headerColor = if (riskSection) Color(0xFFFFB454) else Color(0xFF95A2B6)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DetailSectionHeaderLayout.HEIGHT_DP.dp)
            .background(if (riskSection) Color(0xFF241E17) else Color(0xFF181D26))
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
        section.rows.forEach { row ->
            DetailRow(row)
        }
    }
    HorizontalDivider(color = Border)
}

@Composable
private fun DetailRow(row: DetailRowModel) {
    val color = when (row.tone) {
        DetailTone.NORMAL -> Color(0xFFD8E0ED)
        DetailTone.INFO -> Color(0xFF70A5FF)
        DetailTone.WARNING -> Color(0xFFFFB454)
        DetailTone.ERROR -> Color(0xFFFF6B6B)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
            color = Color(0xFF758197),
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
private fun FindingsPane(state: InspectorState, modifier: Modifier) {
    val model = InspectorPresenter.present(state)
    Column(modifier.background(Panel)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(PanelHeaderLayout.HEIGHT_DP.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("FINDINGS", color = Color(0xFF95A2B6), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(18.dp))
            Badge("INFO ${model.severitySummary.info}", Color(0xFF4BA3FF))
            Spacer(Modifier.width(8.dp))
            Badge("WARN ${model.severitySummary.warning}", Color(0xFFF5A524))
            Spacer(Modifier.width(8.dp))
            Badge("ERROR ${model.severitySummary.error}", Color(0xFFEF5350))
            Spacer(Modifier.weight(1f))
            Text("TIMELINE  Live capture", color = Color(0xFF657086), fontSize = 11.sp)
        }
        HorizontalDivider(color = Border)
        if (model.findings.isEmpty()) {
            Text("No findings", color = Color(0xFF8490A3), modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(model.findings) { finding ->
                    val findingColor = when (finding.tone) {
                        FindingTone.INFO -> Color(0xFF4BA3FF)
                        FindingTone.WARNING -> Color(0xFFF5A524)
                        FindingTone.ERROR -> Color(0xFFEF5350)
                    }
                    Text(
                        "[${finding.nodeNumber}]  ${finding.title}  ·  ${finding.message}",
                        color = findingColor,
                        fontSize = FindingsTypography.TEXT_SIZE_SP.sp,
                        lineHeight = FindingsTypography.LINE_HEIGHT_SP.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(
                            horizontal = 16.dp,
                            vertical = FindingsTypography.VERTICAL_PADDING_DP.dp,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelTitle(title: String, trailing: String) {
    PanelTitle(title) {
        Text(trailing, color = Color(0xFF687386), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun PanelTitle(
    title: String,
    trailingContent: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(PanelHeaderLayout.HEIGHT_DP.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Color(0xFF95A2B6), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        trailingContent()
    }
    HorizontalDivider(color = Border)
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
        Box(Modifier.fillMaxHeight().width(1.dp).background(Border))
    }
}

@Composable
private fun FindingsResizeSeparator(onDrag: (Float) -> Unit) {
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
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
    }
}

private const val CAPTURE_INTERVAL_MILLIS = 1_000L
private const val RECONNECT_INTERVAL_MILLIS = 1_000L
