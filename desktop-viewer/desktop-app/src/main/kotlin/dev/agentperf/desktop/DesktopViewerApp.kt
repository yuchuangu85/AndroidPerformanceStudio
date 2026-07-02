package dev.agentperf.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
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
import dev.agentperf.fixtures.SampleSnapshots
import dev.agentperf.protocol.ProtocolCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import kotlin.math.roundToInt

private val Panel = Color(0xFF141820)
private val CanvasBackground = Color(0xFF0D1016)
private val Border = Color(0xFF2B3240)
private val Accent = Color(0xFF70A5FF)

@Composable
fun DesktopViewerApp() {
    val store = remember {
        InspectorStore().apply { load(SampleSnapshots.dashboard) }
    }
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
                    deviceClient.connect(SAMPLE_PACKAGE)
                }
                while (currentCoroutineContext().isActive) {
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

    MaterialTheme {
        Surface(color = CanvasBackground, modifier = Modifier.fillMaxSize()) {
            Column {
                Header(state)
                HorizontalDivider(color = Border)
                Row(modifier = Modifier.weight(1f)) {
                    HierarchyPane(
                        state = state,
                        onSelect = { id ->
                            if (store.selectNode(id)) state = store.state
                        },
                        modifier = Modifier.width(300.dp).fillMaxHeight(),
                    )
                    Separator()
                    PreviewPane(state, Modifier.weight(1f).fillMaxHeight())
                    Separator()
                    DetailsPane(state, Modifier.widthIn(min = 300.dp, max = 360.dp).fillMaxHeight())
                }
                HorizontalDivider(color = Border)
                FindingsPane(state, Modifier.fillMaxWidth().height(178.dp))
            }
        }
    }
}

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
                        .padding(start = (12 + row.depth * 16).dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (row.depth == 0) "◆" else "›", color = Accent, fontSize = 11.sp)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        row.label,
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
    val screenshot = rememberScreenshot(state.screenshotPng)
    Column(modifier.background(CanvasBackground)) {
        PanelTitle(
            "CANVAS",
            display?.let { "${it.widthPx} × ${it.heightPx}" } ?: "No live frame",
        )
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.fillMaxHeight().widthIn(max = 390.dp),
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFFEEF2F6),
                shadowElevation = 8.dp,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color(0xFFF8FAFC))
                    if (screenshot != null && display != null) {
                        val destination = CanvasGeometry.contain(
                            sourceWidth = display.widthPx,
                            sourceHeight = display.heightPx,
                            canvasWidth = size.width,
                            canvasHeight = size.height,
                        )
                        drawImage(
                            image = screenshot,
                            dstOffset = IntOffset(
                                destination.left.roundToInt(),
                                destination.top.roundToInt(),
                            ),
                            dstSize = IntSize(
                                destination.width.roundToInt(),
                                destination.height.roundToInt(),
                            ),
                        )
                        selectedBounds?.let { bounds ->
                            val overlay = CanvasGeometry.mapBounds(
                                bounds = bounds,
                                sourceWidth = display.widthPx,
                                sourceHeight = display.heightPx,
                                destination = destination,
                            )
                            drawRect(
                                color = Color(0xFFEF4444),
                                topLeft = Offset(overlay.left, overlay.top),
                                size = Size(overlay.width, overlay.height),
                                style = Stroke(width = 2.dp.toPx()),
                            )
                        }
                    } else {
                        drawRect(Color(0xFF1D4ED8), size = Size(size.width, size.height * 0.085f))
                        drawRoundRect(
                            color = Color(0xFFE2E8F0),
                            topLeft = Offset(size.width * 0.06f, size.height * 0.12f),
                            size = Size(size.width * 0.88f, size.height * 0.46f),
                        )
                        selectedBounds?.let { bounds ->
                            val scaleX = size.width / 1080f
                            val scaleY = size.height / 2400f
                            drawRect(
                                color = Color(0xFFEF4444),
                                topLeft = Offset(bounds.left * scaleX, bounds.top * scaleY),
                                size = Size(bounds.width * scaleX, bounds.height * scaleY),
                                style = Stroke(width = 2.dp.toPx()),
                            )
                        }
                    }
                }
            }
        }
    }
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
            Text("TIMELINE  Sample frame", color = Color(0xFF657086), fontSize = 11.sp)
        }
        HorizontalDivider(color = Border)
        if (state.analysis.findings.isEmpty()) {
            Text("No findings", color = Color(0xFF8490A3), modifier = Modifier.padding(16.dp))
        } else {
            state.analysis.findings.forEach { finding ->
                Text(
                    "${finding.ruleId}  ·  ${finding.nodeId}  ·  ${finding.message}",
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
    Row(
        Modifier.fillMaxWidth().height(43.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Color(0xFF95A2B6), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text(trailing, color = Color(0xFF687386), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
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
private fun Separator() {
    Box(Modifier.fillMaxHeight().width(1.dp).background(Border))
}

private const val SAMPLE_PACKAGE = "dev.agentperf.sample"
private const val CAPTURE_INTERVAL_MILLIS = 1_000L
private const val RECONNECT_INTERVAL_MILLIS = 1_000L
