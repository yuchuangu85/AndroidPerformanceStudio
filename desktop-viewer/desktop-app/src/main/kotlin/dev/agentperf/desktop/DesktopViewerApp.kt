package dev.agentperf.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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

@Composable
fun DesktopViewerApp() {
    val store = remember { createInitialInspectorStore() }
    var state by remember { mutableStateOf(store.state) }
    val deviceClient = remember { LiveDeviceClient() }
    val protocolCodec = remember { ProtocolCodec(supportedMajor = 1) }

    LaunchedEffect(Unit) {
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

    MaterialTheme {
        Surface(color = CanvasBackground, modifier = Modifier.fillMaxSize()) {
            Column {
                Header(state)
                HorizontalDivider(color = Border)
                BoxWithConstraints(modifier = Modifier.weight(1f)) {
                    val availableWidthDp = maxWidth.value
                    val normalizedPaneWidths = PaneLayout.fit(paneWidths, availableWidthDp)
                    SideEffect {
                        if (paneWidths != normalizedPaneWidths) {
                            paneWidths = normalizedPaneWidths
                        }
                    }
                    Row(modifier = Modifier.fillMaxSize()) {
                        HierarchyPane(
                            state = state,
                            onSelect = { id ->
                                if (store.selectNode(id)) state = store.state
                            },
                            modifier = Modifier.width(normalizedPaneWidths.hierarchy.dp).fillMaxHeight(),
                        )
                        ResizableSeparator { deltaDp ->
                            paneWidths = PaneLayout.dragHierarchy(
                                widths = PaneLayout.fit(paneWidths, availableWidthDp),
                                deltaDp = deltaDp,
                                availableWidthDp = availableWidthDp,
                            )
                        }
                        PreviewPane(state, Modifier.weight(1f).fillMaxHeight())
                        ResizableSeparator { deltaDp ->
                            paneWidths = PaneLayout.dragProperties(
                                widths = PaneLayout.fit(paneWidths, availableWidthDp),
                                deltaDp = deltaDp,
                                availableWidthDp = availableWidthDp,
                            )
                        }
                        DetailsPane(
                            state,
                            Modifier.width(normalizedPaneWidths.properties.dp).fillMaxHeight(),
                        )
                    }
                }
                HorizontalDivider(color = Border)
                FindingsPane(state, Modifier.fillMaxWidth().height(178.dp))
            }
        }
    }
}

internal fun createInitialInspectorStore(): InspectorStore = InspectorStore()

@Composable
private fun Header(state: InspectorState) {
    val model = InspectorPresenter.present(state)
    Row(
        modifier = Modifier.fillMaxWidth().height(58.dp).background(Panel).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("AgentPerf", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("  Desktop Viewer", color = Color(0xFFAAB4C5))
        Spacer(Modifier.weight(1f))
        val connectionColor = when (model.connectionTone) {
            ConnectionTone.NEUTRAL -> Color(0xFFF5A524)
            ConnectionTone.SUCCESS -> Color(0xFF55D187)
            ConnectionTone.ERROR -> Color(0xFFEF5350)
        }
        StatusDot(connectionColor)
        Spacer(Modifier.width(8.dp))
        Text(model.connectionLabel, color = connectionColor, fontSize = 12.sp)
        Spacer(Modifier.width(12.dp))
        Text(model.packageName ?: "No app", color = Color(0xFFDCE4F2), fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(18.dp))
        Text(model.metricsText, color = Color(0xFF8E9AAF), fontSize = 12.sp)
    }
}

@Composable
private fun HierarchyPane(
    state: InspectorState,
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    val model = InspectorPresenter.present(state)
    Column(modifier.background(Panel)) {
        PanelTitle("HIERARCHY", "${model.rows.size}")
        LazyColumn(Modifier.fillMaxSize().padding(vertical = 6.dp)) {
            items(model.rows, key = { it.id }) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (row.selected) Color(0xFF253B5F) else Color.Transparent)
                        .clickable { onSelect(row.id) }
                        .padding(start = (12 + row.depth * 16).dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (row.depth == 0) "◆" else "›", color = Accent, fontSize = 11.sp)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "${row.number}  ${row.label}",
                        color = if (row.visible) Color(0xFFD8E0ED) else Color(0xFF687386),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
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
    Column(modifier.background(Panel)) {
        PanelTitle("PROPERTIES", details.id)
        Property("Class", details.className)
        Property("Text", details.text ?: "—")
        Property("Bounds", details.bounds?.let { "${it.left}, ${it.top}, ${it.right}, ${it.bottom}" } ?: "—")
        Property("Size", details.bounds?.let { "${it.width} × ${it.height}" } ?: "—")
        Property("Children", details.childCount.toString())
    }
}

@Composable
private fun FindingsPane(state: InspectorState, modifier: Modifier) {
    val model = InspectorPresenter.present(state)
    Column(modifier.background(Panel)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
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
            model.findings.forEach { finding ->
                Text(
                    "[${finding.nodeNumber}]  ${finding.title}  ·  ${finding.message}",
                    color = Color(0xFFC7D0DE),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                )
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
        Modifier.fillMaxWidth().height(43.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Color(0xFF95A2B6), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        trailingContent()
    }
    HorizontalDivider(color = Border)
}

@Composable
private fun Property(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(label.uppercase(), color = Color(0xFF687386), fontSize = 10.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, color = Color(0xFFD8E0ED), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
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

private const val CAPTURE_INTERVAL_MILLIS = 1_000L
private const val RECONNECT_INTERVAL_MILLIS = 1_000L
