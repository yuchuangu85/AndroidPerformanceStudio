@file:Suppress("MagicNumber")
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.androidperformancestudio.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.StackChartBlock
import com.androidperformancestudio.profileanalysis.StackChartBlockId
import com.androidperformancestudio.profileanalysis.StackChartSnapshot
import com.androidperformancestudio.profileanalysis.matchesStackSearch
import com.androidperformancestudio.profileanalysis.parseFlameSearchTerms
import com.androidperformancestudio.storage.MarkerProjectionRow
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.visualization.FlameGraphPalette
import com.androidperformancestudio.visualization.FlameNodeVisualState
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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
    onViewportChange: (StackChartViewport) -> Unit = {},
    selectedCallNodeId: FlameCallNodeId? = null,
    contextBlockId: StackChartBlockId? = null,
    onHover: (StackChartBlock?, Offset?) -> Unit = { _, _ -> },
    onContextMenu: (StackChartBlock, Offset) -> Unit = { _, _ -> },
    onOpenDetails: (StackChartBlock) -> Unit = {},
    onKeyAction: (KeyEvent) -> Boolean = { false },
    searchText: String = "",
    sameWidths: Boolean = false,
    previewViewport: StackChartViewport? = null,
    userTimings: List<StackChartUserTimingRow> = emptyList(),
    onHoverUserTiming: (MarkerProjectionRow?, Offset?) -> Unit = { _, _ -> },
    sessionKey: Any? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val language = currentSimpleperfLanguage()
    val description = localizedStringResource(SimpleperfViewerRes.sp_stack_call_stacks_description, language)
    val selectLabel = localizedStringResource(SimpleperfViewerRes.sp_accessibility_select, language)
    val detailsLabel = localizedStringResource(SimpleperfViewerRes.sp_accessibility_open_details, language)
    val contextLabel = localizedStringResource(SimpleperfViewerRes.sp_accessibility_open_context_menu, language)
    var widthPixels by remember { mutableIntStateOf(0) }
    var heightPixels by remember { mutableIntStateOf(0) }
    var visibleViewport by remember(sessionKey, viewport) {
        mutableStateOf(previewViewport?.let { navigateStackChartViewport(it, viewport) } ?: viewport)
    }
    var scrollPixels by remember(sessionKey) { mutableFloatStateOf(0f) }
    var hoveredBlockId by remember(snapshot, viewport) { mutableStateOf<StackChartBlockId?>(null) }
    var dragging by remember(sessionKey, viewport) { mutableStateOf(false) }
    LaunchedEffect(sessionKey, viewport, previewViewport) {
        if (!dragging) visibleViewport = previewViewport?.let { navigateStackChartViewport(it, viewport) } ?: viewport
    }
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    val style = rememberFirefoxFlameGraphStyle()
    val textMeasurer = rememberTextMeasurer(cacheSize = 1024)
    val rowHeight = style.rowHeightPx
    val plotLeft = with(density) { FIREFOX_TIMELINE_LABEL_WIDTH.toPx() }
    val plotWidth = (widthPixels - plotLeft - with(density) { FIREFOX_LOCAL_TRACK_MARGIN.toPx() }).coerceAtLeast(0f)
    val maximumDepth = snapshot.maxDepth + userTimings.size
    val clampedPixels = stackChartScrollPixels(maximumDepth, heightPixels, rowHeight, scrollPixels)
    val clampedScroll = floor(clampedPixels / rowHeight).toInt()
    val partialRowOffset = clampedPixels - clampedScroll * rowHeight
    val index = remember(snapshot) { StackChartIndex(snapshot.blocks, snapshot.parentCallNodeIds) }
    val timeScale = remember(snapshot, sameWidths) { if (sameWidths) StackChartSameWidths(snapshot.blocks) else null }
    val layout =
        remember(
            index,
            visibleViewport,
            plotWidth,
            heightPixels,
            rowHeight,
            clampedScroll,
            timeScale,
            userTimings.size,
            partialRowOffset,
        ) {
            index
                .layout(
                    visibleViewport,
                    plotWidth,
                    heightPixels + partialRowOffset,
                    rowHeight,
                    clampedScroll - userTimings.size,
                    timeScale,
                ).map { it.copy(rect = it.rect.translate(Offset(plotLeft, -partialRowOffset))) }
        }
    val selectedFallback =
        snapshot.blocksById[selectedBlockId]
            ?.takeUnless { block -> layout.any { it.block.id == block.id } }
            ?.let { block ->
                val raw = StackChartPresenter.blockRect(block, visibleViewport, plotWidth, rowHeight)
                val left = plotLeft + (timeScale?.xAtTime(block.startNanos, visibleViewport, plotWidth) ?: raw.left)
                val right =
                    plotLeft + (timeScale?.xAtTime(block.endNanosExclusive, visibleViewport, plotWidth) ?: raw.right)
                val top = (block.depth + userTimings.size) * rowHeight - clampedPixels
                val rect = Rect(left, top, max(left + 1f, right).coerceAtMost(plotLeft + plotWidth), top + rowHeight)
                VisibleStackChartBlock(block, rect).takeIf {
                    block.endNanosExclusive > visibleViewport.startNanos &&
                        block.startNanos < visibleViewport.endNanosExclusive &&
                        rect.bottom > 0f &&
                        rect.top < heightPixels
                }
            }
    val displayed = if (selectedFallback == null) layout else layout + selectedFallback
    val timingLayout =
        remember(userTimings, visibleViewport, plotWidth, heightPixels, rowHeight, clampedPixels) {
            visibleStackChartUserTimings(
                userTimings,
                visibleViewport,
                plotWidth,
                heightPixels.toFloat(),
                rowHeight,
                clampedPixels,
            ).map { it.copy(rect = it.rect.translate(Offset(plotLeft, 0f))) }
        }
    val searchTerms = remember(searchText) { parseFlameSearchTerms(searchText) }
    val textStyle =
        remember(style, density) {
            TextStyle(fontSize = with(density) { style.labelFontSizePx.toSp() }, fontFamily = style.labelFontFamily)
        }
    val currentSelect by rememberUpdatedState(onSelect)
    val currentViewportChange by rememberUpdatedState(onViewportChange)
    val currentHover by rememberUpdatedState(onHover)
    val currentTimingHover by rememberUpdatedState(onHoverUserTiming)
    val currentContext by rememberUpdatedState(onContextMenu)
    val currentDetails by rememberUpdatedState(onOpenDetails)
    val currentHitTest by rememberUpdatedState<(Offset) -> StackChartBlock?> { point ->
        if (point.x < plotLeft || point.x >= plotLeft + plotWidth || point.y < 0f) {
            null
        } else {
            val time =
                timeScale?.timeAtX(point.x - plotLeft, visibleViewport, plotWidth)
                    ?: StackChartPresenter.timeAtX(point.x - plotLeft, visibleViewport, plotWidth)
            index.hitTest(time, ((point.y + clampedPixels) / rowHeight).toInt() - userTimings.size)
        }
    }
    val currentTimingHitTest by rememberUpdatedState<(Offset) -> MarkerProjectionRow?> { point ->
        timingLayout.lastOrNull { it.rect.contains(point) }?.marker?.takeUnless { dragging }
    }
    val currentScroll by rememberUpdatedState<(Float) -> Unit> { delta ->
        if (delta.isFinite()) {
            scrollPixels = stackChartScrollPixels(maximumDepth, heightPixels, rowHeight, scrollPixels + delta)
        }
    }

    fun clearHover() {
        hoveredBlockId = null
        currentHover(null, null)
        currentTimingHover(null, null)
    }

    fun blockAt(point: Offset): StackChartBlock? = currentHitTest(point)

    fun updateHover(point: Offset) {
        val marker = currentTimingHitTest(point)
        currentTimingHover(marker, point.takeIf { marker != null })
        val block = blockAt(point).takeUnless { dragging }
        if (hoveredBlockId != block?.id) {
            hoveredBlockId = block?.id
            currentHover(block, point.takeIf { block != null })
        }
    }

    val keyboard =
        rememberFirefoxChartKeyboard(horizontalEnabled = true, resetKey = sessionKey) { motion ->
            clearHover()
            val previous = visibleViewport
            visibleViewport =
                navigateStackChartViewport(
                    visibleViewport,
                    viewport,
                    zoomFactor = motion.zoomFactor,
                    panFraction =
                        if (plotWidth > 0f) -motion.offsetXCss * density.density / plotWidth.toDouble() else 0.0,
                )
            scrollPixels =
                stackChartScrollPixels(
                    maximumDepth,
                    heightPixels,
                    rowHeight,
                    scrollPixels - motion.offsetYCss * density.density,
                )
            if (visibleViewport != previous) currentViewportChange(visibleViewport)
        }

    val currentWheel by
        rememberUpdatedState<(Offset, Offset, Boolean, Boolean) -> Unit> { delta, position, control, shift ->
            clearHover()
            val previous = visibleViewport
            if (control || shift) {
                val amount = (if (delta.y != 0f) delta.y else delta.x) / density.density * if (control) 3f else 1f
                visibleViewport =
                    navigateStackChartViewport(
                        visibleViewport,
                        viewport,
                        zoomFactor = 1.003.pow(amount.toDouble()),
                        anchorFraction = if (plotWidth > 0) (position.x - plotLeft).toDouble() / plotWidth else 0.5,
                    )
            } else {
                currentScroll(delta.y)
                if (delta.x != 0f && plotWidth > 0) {
                    visibleViewport =
                        navigateStackChartViewport(
                            visibleViewport,
                            viewport,
                            panFraction = delta.x / plotWidth.toDouble(),
                        )
                }
            }
            if (visibleViewport != previous) currentViewportChange(visibleViewport)
        }
    val currentDrag by rememberUpdatedState<(Offset) -> Unit> { amount ->
        if (plotWidth > 0f) {
            visibleViewport =
                navigateStackChartViewport(
                    visibleViewport,
                    viewport,
                    panFraction = -amount.x / plotWidth.toDouble(),
                )
        }
        scrollPixels = stackChartScrollPixels(maximumDepth, heightPixels, rowHeight, scrollPixels - amount.y)
    }

    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
    LaunchedEffect(sessionKey, selectedBlockId) {
        clearHover()
        snapshot.blocksById[selectedBlockId]?.let { block ->
            val rows = (heightPixels / rowHeight).toInt().coerceAtLeast(1)
            scrollPixels =
                when {
                    block.depth + userTimings.size < clampedScroll -> (block.depth + userTimings.size) * rowHeight
                    block.depth + userTimings.size >= clampedScroll + rows ->
                        (block.depth + userTimings.size - rows + 1) * rowHeight
                    else -> clampedPixels
                }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .testTag("stack-chart-canvas")
            .clipToBounds()
            .background(style.canvasBackground.toComposeColor())
            .focusRequester(focusRequester)
            .onFocusChanged { if (!it.isFocused) keyboard.clear() }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && keyboard.handle(event)) return@onPreviewKeyEvent true
                if (onKeyAction(event)) return@onPreviewKeyEvent true
                if (keyboard.handle(event)) return@onPreviewKeyEvent true
                val selected = snapshot.blocksById[selectedBlockId]
                when {
                    event.type == KeyEventType.KeyDown && event.key == Key.Enter && selected != null -> {
                        currentDetails(selected)
                        true
                    }
                    else -> false
                }
            }.focusable()
            .onPointerEvent(PointerEventType.Enter) { event ->
                event.changes
                    .lastOrNull()
                    ?.position
                    ?.let(::updateHover)
            }.onPointerEvent(PointerEventType.Move) { event ->
                event.changes
                    .lastOrNull()
                    ?.position
                    ?.let(::updateHover)
            }.onPointerEvent(PointerEventType.Exit) { clearHover() }
            .onPointerEvent(PointerEventType.Press) {
                focusRequester.requestFocus()
            }.onPointerEvent(PointerEventType.PanMove) { event ->
                clearHover()
                event.changes.firstOrNull()?.let { change ->
                    val offset = change.panOffset
                    if (offset.x.isFinite() && offset.y.isFinite()) {
                        currentDrag(offset)
                        currentViewportChange(visibleViewport)
                    }
                    change.consume()
                }
            }.onPointerEvent(PointerEventType.Scroll) { event ->
                clearHover()
                event.changes.firstOrNull()?.let { change ->
                    currentWheel(
                        firefoxChartWheelPixels(event, heightPixels.toFloat(), density.density),
                        change.position,
                        event.keyboardModifiers.isCtrlPressed,
                        event.keyboardModifiers.isShiftPressed,
                    )
                    change.consume()
                }
            }.onSizeChanged {
                widthPixels = it.width
                heightPixels = it.height
            }.pointerInput(sessionKey, viewport, plotWidth) {
                detectTapGestures(
                    onTap = { point ->
                        clearHover()
                        if (currentTimingHitTest(point) == null) currentSelect(blockAt(point)?.id)
                    },
                    onDoubleTap = { point ->
                        clearHover()
                        blockAt(point)?.let(currentDetails)
                    },
                )
            }.pointerInput(sessionKey, viewport, plotWidth) {
                var initialViewport = visibleViewport
                detectDragGestures(
                    onDragStart = {
                        clearHover()
                        initialViewport = visibleViewport
                        dragging = true
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        val previous = visibleViewport
                        currentDrag(amount)
                        if (visibleViewport != previous) currentViewportChange(visibleViewport)
                    },
                    onDragCancel = { dragging = false },
                    onDragEnd = {
                        if (visibleViewport != initialViewport) currentViewportChange(visibleViewport)
                        dragging = false
                    },
                )
            }.onPointerEvent(PointerEventType.Press) { event ->
                if (event.buttons.isSecondaryPressed) {
                    clearHover()
                    event.changes.lastOrNull()?.position?.let { point ->
                        blockAt(point)?.let { currentContext(it, point) }
                    }
                    event.changes.forEach { it.consume() }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = description }) {
            timingLayout.forEach { timing ->
                val rect = timing.rect
                val colors = style.nodeColors("Other")
                drawRect(
                    colors.fill.toComposeColor(),
                    rect.topLeft,
                    Size(rect.width, (rowHeight - density.density).coerceAtLeast(0f)),
                )
                val labelWidth = (rect.width - style.labelStartOffsetPx).roundToInt()
                if (labelWidth > 0) {
                    val label =
                        textMeasurer.measure(
                            AnnotatedString(timing.marker.name),
                            textStyle.copy(color = colors.foreground.toComposeColor()),
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            constraints = Constraints(maxWidth = labelWidth),
                        )
                    drawText(
                        label,
                        topLeft =
                            Offset(
                                rect.left + style.labelStartOffsetPx,
                                rect.top + style.labelBaselineOffsetPx - label.firstBaseline,
                            ),
                    )
                }
            }
            displayed.forEach { visible ->
                val block = visible.block
                val rect = visible.rect
                val frame = snapshot.framesById[block.frameId]
                val selected =
                    block.id == selectedBlockId ||
                        (selectedCallNodeId != null && block.callNodeId == selectedCallNodeId)
                val colors =
                    style.nodeColors(
                        FlameGraphPalette.categoryRole(block.category, frame),
                        FlameNodeVisualState(
                            selected = selected,
                            hovered = block.id == hoveredBlockId,
                            context = block.id == contextBlockId,
                        ),
                    )
                val dimmed =
                    searchTerms.isNotEmpty() &&
                        !selected &&
                        block.id != hoveredBlockId &&
                        block.id != contextBlockId &&
                        searchTerms.none { term ->
                            frame?.matchesStackSearch(term) == true
                        }
                val drawableWidth = (rect.width - 1f).coerceAtLeast(1f)
                val drawableHeight = min(rowHeight - density.density, size.height - rect.top).coerceAtLeast(0f)
                val paintRect = firefoxStackPaintRect(rect, rowHeight, density.density)
                drawRect(
                    (if (dimmed) style.dimmedFill else colors.fill).toComposeColor(),
                    paintRect.topLeft,
                    paintRect.size,
                )
                val labelWidth = (drawableWidth - style.labelStartOffsetPx).roundToInt()
                if (frame != null && labelWidth > 0 && drawableHeight >= style.labelFontSizePx) {
                    val label =
                        textMeasurer.measure(
                            AnnotatedString(frame.symbolName),
                            textStyle.copy(
                                color = (if (dimmed) style.dimmedForeground else colors.foreground).toComposeColor(),
                            ),
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            constraints = Constraints(maxWidth = labelWidth),
                        )
                    drawText(
                        label,
                        topLeft =
                            Offset(
                                rect.left + style.labelStartOffsetPx,
                                rect.top + style.labelBaselineOffsetPx - label.firstBaseline,
                            ),
                    )
                }
            }
            drawRect(style.viewportBorder.toComposeColor(), Offset(plotLeft, 0f), Size(density.density, size.height))
            drawRect(
                style.viewportBorder.toComposeColor(),
                Offset(plotLeft + plotWidth, 0f),
                Size(density.density, size.height),
            )
        }
        // Semantics-only nodes leave drag and double-click gestures on the shared canvas.
        timingLayout.forEach { timing ->
            Box(
                Modifier
                    .absoluteOffset { IntOffset(timing.rect.left.roundToInt(), timing.rect.top.roundToInt()) }
                    .size(with(density) { timing.rect.width.toDp() }, with(density) { timing.rect.height.toDp() })
                    .testTag("stack-user-timing-${timing.marker.id.value}")
                    .semantics { contentDescription = timing.marker.name },
            )
        }
        displayed.distinctBy { it.block.id }.forEach { visible ->
            val block = visible.block
            val rect = visible.rect
            Box(
                Modifier
                    .absoluteOffset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                    .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                    .testTag("stack-block-${block.id.value}")
                    .semantics {
                        contentDescription =
                            snapshot.framesById[block.frameId]?.let { "${it.symbolName}, depth ${block.depth}" }
                                ?: block.id.value
                        selected =
                            block.id == selectedBlockId ||
                            (selectedCallNodeId != null && block.callNodeId == selectedCallNodeId)
                        onClick(selectLabel) {
                            currentSelect(block.id)
                            true
                        }
                        customActions =
                            listOf(
                                CustomAccessibilityAction(detailsLabel) {
                                    currentDetails(block)
                                    true
                                },
                                CustomAccessibilityAction(contextLabel) {
                                    currentContext(block, rect.topLeft)
                                    true
                                },
                            )
                    },
            )
        }
        overlay()
    }
}

internal fun stackChartScrollDepth(
    maxDepth: Int,
    heightPixels: Int,
    rowHeightPixels: Float,
    requestedDepth: Int,
): Int {
    val visibleRows = (heightPixels / rowHeightPixels).toInt().coerceAtLeast(1)
    return requestedDepth.coerceIn(0, (maxDepth + 1 - visibleRows).coerceAtLeast(0))
}

internal fun stackChartScrollDepthDelta(scrollDeltaY: Float): Int? = flameGraphScrollRowDelta(-scrollDeltaY)

internal fun stackChartScrollPixels(
    maxDepth: Int,
    heightPixels: Int,
    rowHeightPixels: Float,
    requestedPixels: Float,
): Float = requestedPixels.coerceIn(0f, ((maxDepth + 1) * rowHeightPixels - heightPixels).coerceAtLeast(0f))
