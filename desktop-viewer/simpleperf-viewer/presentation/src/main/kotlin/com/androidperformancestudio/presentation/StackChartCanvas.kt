@file:Suppress("MagicNumber", "MaxLineLength")
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.androidperformancestudio.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.profileanalysis.StackChartBlockId
import com.androidperformancestudio.profileanalysis.StackChartSnapshot
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.visualization.FlameGraphPalette
import com.androidperformancestudio.visualization.FlameNodeVisualState
import com.androidperformancestudio.visualization.NavigationAction
import com.androidperformancestudio.visualization.TimeViewport
import com.androidperformancestudio.visualization.navigate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
@Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
    "LongMethod",
    "LongParameterList",
    "ktlint:standard:function-naming",
)
internal fun StackChartCanvas(
    snapshot: StackChartSnapshot,
    viewport: StackChartViewport,
    selectedBlockId: StackChartBlockId?,
    onSelect: (StackChartBlockId?) -> Unit,
    onCommitRange: (Long, Long) -> Unit,
) {
    val callStacksDescription =
        localizedStringResource(
            SimpleperfViewerRes.sp_stack_call_stacks_description,
            currentSimpleperfLanguage(),
        )
    var widthPixels by remember { mutableIntStateOf(0) }
    var heightPixels by remember { mutableIntStateOf(0) }
    var visibleViewport by remember(snapshot, viewport) { mutableStateOf(viewport) }
    var scrollDepth by remember(snapshot, viewport) { mutableIntStateOf(0) }
    var dragStartX by remember { mutableFloatStateOf(Float.NaN) }
    var dragEndX by remember { mutableFloatStateOf(Float.NaN) }
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    val firefoxStyle = rememberFirefoxFlameGraphStyle()
    val textMeasurer = rememberTextMeasurer()
    val rowHeightPx = firefoxStyle.rowHeightPx
    val clampedScrollDepth = stackChartScrollDepth(snapshot.maxDepth, heightPixels, rowHeightPx, scrollDepth)
    val visible = remember(snapshot, visibleViewport) { StackChartPresenter.visibleBlocks(snapshot.blocks, visibleViewport) }
    val plotLeftPx = with(density) { FIREFOX_TIMELINE_LABEL_WIDTH.toPx() }
    val plotRightMarginPx = with(density) { FIREFOX_LOCAL_TRACK_MARGIN.toPx() }
    val textStyle = TextStyle(fontSize = with(density) { firefoxStyle.labelFontSizePx.toSp() })

    fun navigate(action: NavigationAction) {
        val next =
            TimeViewport(visibleViewport.startNanos, visibleViewport.endNanosExclusive)
                .navigate(action, TimeViewport(viewport.startNanos, viewport.endNanosExclusive))
        visibleViewport = StackChartViewport(next.startNanos, next.endNanosExclusive)
    }

    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .testTag("stack-chart-canvas")
            .background(firefoxStyle.canvasBackground.toComposeColor())
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event -> handleKey(event, ::navigate) }
            .focusable()
            .onPointerEvent(PointerEventType.Press) { focusRequester.requestFocus() }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val delta =
                    event.changes
                        .firstOrNull()
                        ?.scrollDelta
                        ?.y ?: 0f
                flameGraphScrollRowDelta(delta)?.let { rows ->
                    scrollDepth =
                        stackChartScrollDepth(
                            snapshot.maxDepth,
                            heightPixels,
                            rowHeightPx,
                            clampedScrollDepth + rows,
                        )
                }
            }.onSizeChanged {
                widthPixels = it.width
                heightPixels = it.height
            }.pointerInput(snapshot, visibleViewport, widthPixels, rowHeightPx, clampedScrollDepth) {
                detectTapGestures { point ->
                    val plotWidth = (widthPixels - plotLeftPx - plotRightMarginPx).coerceAtLeast(0f)
                    if (point.x < plotLeftPx || point.x >= plotLeftPx + plotWidth) {
                        onSelect(null)
                        return@detectTapGestures
                    }
                    val timestamp = StackChartPresenter.timeAtX(point.x - plotLeftPx, visibleViewport, plotWidth)
                    val depth = (point.y / rowHeightPx).toInt() + clampedScrollDepth
                    onSelect(StackChartPresenter.hitTest(visible, timestamp, depth))
                }
            }.pointerInput(visibleViewport, widthPixels, plotLeftPx, plotRightMarginPx) {
                val plotEnd = (widthPixels - plotRightMarginPx).coerceAtLeast(plotLeftPx)
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragStartX = it.x.coerceIn(plotLeftPx, plotEnd)
                        dragEndX = dragStartX
                    },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragEndX = (dragEndX + amount).coerceIn(plotLeftPx, plotEnd)
                    },
                    onDragCancel = {
                        dragStartX = Float.NaN
                        dragEndX = Float.NaN
                    },
                    onDragEnd = {
                        if (dragStartX.isFinite() && dragEndX.isFinite() && dragStartX != dragEndX) {
                            val plotWidth = (plotEnd - plotLeftPx).coerceAtLeast(0f)
                            val start =
                                StackChartPresenter.timeAtX(
                                    min(dragStartX, dragEndX) - plotLeftPx,
                                    visibleViewport,
                                    plotWidth,
                                )
                            val end =
                                StackChartPresenter.timeAtX(
                                    max(dragStartX, dragEndX) - plotLeftPx,
                                    visibleViewport,
                                    plotWidth,
                                )
                            if (start < end) onCommitRange(start, end)
                        }
                        dragStartX = Float.NaN
                        dragEndX = Float.NaN
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = callStacksDescription }) {
            drawRect(firefoxStyle.canvasBackground.toComposeColor())
            val plotWidth = (size.width - plotLeftPx - plotRightMarginPx).coerceAtLeast(0f)
            visible.forEach { block ->
                val rawRect = StackChartPresenter.blockRect(block, visibleViewport, plotWidth, rowHeightPx)
                val rect = rawRect.translate(Offset(plotLeftPx, -clampedScrollDepth * rowHeightPx))
                val right = (rect.right - STACK_CHART_GAP_PX).coerceAtLeast(rect.left)
                if (right > rect.left && rect.bottom > 0f && rect.top < size.height) {
                    val frame = snapshot.framesById[block.frameId]
                    val colors =
                        firefoxStyle.nodeColors(
                            FlameGraphPalette.categoryRole(null, frame),
                            FlameNodeVisualState(selected = block.id == selectedBlockId),
                        )
                    val drawableHeight = min(rect.height - STACK_CHART_GAP_PX, size.height - rect.top)
                    drawRect(
                        color = colors.fill.toComposeColor(),
                        topLeft = Offset(rect.left, rect.top),
                        size = Size(right - rect.left, drawableHeight),
                    )
                    val labelWidth = (right - rect.left - firefoxStyle.labelStartOffsetPx).roundToInt()
                    if (frame != null && labelWidth > 0 && drawableHeight >= firefoxStyle.labelFontSizePx) {
                        val label =
                            textMeasurer.measure(
                                text = AnnotatedString(frame.symbolName),
                                style = textStyle.copy(color = colors.foreground.toComposeColor()),
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 1,
                                constraints = Constraints(maxWidth = labelWidth),
                            )
                        drawText(
                            label,
                            topLeft =
                                Offset(
                                    rect.left + firefoxStyle.labelStartOffsetPx,
                                    rect.top + firefoxStyle.labelBaselineOffsetPx - label.firstBaseline,
                                ),
                        )
                    }
                }
            }
            drawRect(
                firefoxStyle.viewportBorder.toComposeColor(),
                topLeft = Offset(plotLeftPx, 0f),
                size = Size(STACK_CHART_GAP_PX, size.height),
            )
            drawRect(
                firefoxStyle.viewportBorder.toComposeColor(),
                topLeft = Offset(plotLeftPx + plotWidth, 0f),
                size = Size(STACK_CHART_GAP_PX, size.height),
            )
            if (dragStartX.isFinite() && dragEndX.isFinite()) {
                val left = min(dragStartX, dragEndX)
                drawRect(
                    color = firefoxStyle.focusOutline.toComposeColor().copy(alpha = 0.2f),
                    topLeft = Offset(left, 0f),
                    size = Size(max(1f, kotlin.math.abs(dragEndX - dragStartX)), size.height),
                )
            }
        }
        visible.forEach { block ->
            val plotWidth = (widthPixels - plotLeftPx - plotRightMarginPx).coerceAtLeast(0f)
            val rect =
                StackChartPresenter
                    .blockRect(block, visibleViewport, plotWidth, rowHeightPx)
                    .translate(Offset(plotLeftPx, -clampedScrollDepth * rowHeightPx))
            if (rect.width > 0f && rect.bottom > 0f && rect.top < heightPixels) {
                val frame = snapshot.framesById[block.frameId]
                Box(
                    Modifier
                        .offset(x = with(density) { rect.left.toDp() }, y = with(density) { rect.top.toDp() })
                        .width(with(density) { rect.width.toDp() })
                        .height(with(density) { rowHeightPx.toDp() })
                        .testTag("stack-block-${block.id.value}")
                        .clickable { onSelect(block.id) }
                        .semantics {
                            contentDescription = frame?.let { "${it.symbolName}, depth ${block.depth}" } ?: block.id.value
                            selected = block.id == selectedBlockId
                        },
                )
            }
        }
    }
}

internal fun stackChartScrollDepth(
    maxDepth: Int,
    heightPixels: Int,
    rowHeightPixels: Float,
    requestedDepth: Int,
): Int {
    val visibleRows = (heightPixels / rowHeightPixels).toInt().coerceAtLeast(1)
    val maximum = (maxDepth + 1 - visibleRows).coerceAtLeast(0)
    return requestedDepth.coerceIn(0, maximum)
}

private const val STACK_CHART_GAP_PX = 1f
