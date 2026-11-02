@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.androidperformancestudio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.application.FlameGraphDetailsState
import com.androidperformancestudio.application.FlameGraphPanelState
import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.visualization.FirefoxFlameGraphStyle
import com.androidperformancestudio.visualization.FlameGraphCanvas
import com.androidperformancestudio.visualization.FlameGraphLayout
import com.androidperformancestudio.visualization.FlameViewport
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
@Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
    "LongMethod",
    "LongParameterList",
    "ktlint:standard:function-naming",
)
fun FlameGraphPanel(
    state: FlameGraphPanelState,
    query: CallStackAnalysisQuery = CallStackAnalysisQuery(),
    selectedNodeId: FlameCallNodeId? = null,
    snapshot: FlameGraphSnapshot,
    actions: ReportActions,
    tooltipMode: FlameTooltipMode = FlameTooltipMode.FOLLOW_MOUSE,
    sessionKey: Any? = null,
) {
    val style = rememberFirefoxFlameGraphStyle()
    var widthPixels by remember { mutableIntStateOf(0) }
    var heightPixels by remember { mutableIntStateOf(0) }
    val startsAtBottom = snapshot.rows.startsAtBottom
    var scrollOffsetPx by remember(sessionKey, startsAtBottom) { mutableStateOf(0f) }
    var contextAnchor by remember(snapshot) { mutableStateOf<Offset?>(null) }
    var hoverAnchor by remember(snapshot) { mutableStateOf<Offset?>(null) }
    var tooltipSize by remember(snapshot) { mutableStateOf(IntSize.Zero) }
    val focusRequester = remember { FocusRequester() }
    val callStacksDescription =
        localizedStringResource(
            SimpleperfViewerRes.sp_flame_flame_graph_call_stacks,
            currentSimpleperfLanguage(),
        )
    val requestedViewport =
        FlameViewport(
            widthPx = widthPixels,
            heightPx = heightPixels,
            scrollRow = 0,
            rowHeightPx = style.rowHeightPx,
            scrollOffsetPx = scrollOffsetPx,
        )
    val clampedScrollOffset = FlameGraphLayout.clampScrollOffset(snapshot, requestedViewport)
    val viewport = requestedViewport.copy(scrollOffsetPx = clampedScrollOffset)
    val currentScroll by rememberUpdatedState<(Float) -> Unit> { offsetY ->
        val offset = if (startsAtBottom) offsetY else -offsetY
        scrollOffsetPx =
            FlameGraphLayout.clampScrollOffset(
                snapshot,
                viewport.copy(scrollOffsetPx = scrollOffsetPx + offset),
            )
    }
    val density = LocalDensity.current.density
    val keyboard =
        rememberFirefoxChartKeyboard(
            horizontalEnabled = false,
            resetKey = Triple(sessionKey, startsAtBottom, snapshot.emptyReason == null),
        ) { motion -> currentScroll(motion.offsetYCss * density) }
    val layout = remember(snapshot, viewport) { FlameGraphLayout.layout(snapshot, viewport) }
    val hoveredTooltipFacts =
        remember(snapshot, state.hoveredNodeId) {
            state.hoveredNodeId?.let(snapshot::tooltipFacts)
        }

    LaunchedEffect(snapshot, heightPixels) {
        if (scrollOffsetPx != clampedScrollOffset) scrollOffsetPx = clampedScrollOffset
    }
    LaunchedEffect(sessionKey, startsAtBottom, selectedNodeId, heightPixels) {
        val next =
            selectedNodeId?.let { selected ->
                FlameGraphLayout.scrollOffsetToReveal(snapshot, selected, viewport)
            } ?: clampedScrollOffset
        if (scrollOffsetPx != next) scrollOffsetPx = next
    }
    LaunchedEffect(state.contextNodeId) {
        if (state.contextNodeId == null) contextAnchor = null
    }
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }

    Column(modifier = Modifier.fillMaxSize().background(style.panelSurface.toComposeColor())) {
        FirefoxTransformNavigator(
            transforms = query.transforms,
            style = style,
            onUndo = actions.onUndoFlameTransform,
            onClear = actions.onClearFlameTransforms,
            onRemoveAfter = actions.onRemoveFlameTransformsAfter,
        )
        FirefoxFlameGraphViewport(
            style = style,
            details = state.details,
            onCloseDetails = actions.onCloseFlameDetails,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp),
        ) {
            if (snapshot.emptyReason == null) {
                FlameGraphCanvas(
                    layout = layout,
                    selectedNodeId = selectedNodeId,
                    hoveredNodeId = state.hoveredNodeId,
                    contextNodeId = state.contextNodeId,
                    labelForNode = { node ->
                        snapshot.callNodes
                            .frameAt(node.nodeIndex)
                            ?.symbolName
                            .orEmpty()
                    },
                    categoryForNode = { node -> snapshot.callNodes.categoryAt(node.nodeIndex) },
                    frameForNode = { node -> snapshot.callNodes.frameAt(node.nodeIndex) },
                    style = style,
                    onHoverPosition = { hoverAnchor = it },
                    onIntent = { intent ->
                        dispatchFlameAction(
                            action = FlameGraphPresenter.actionFor(intent),
                            actions = actions,
                            snapshot = snapshot,
                            viewport = viewport,
                            onScrollOffset = { scrollOffsetPx = it },
                            onContextAnchor = { contextAnchor = it },
                        )
                    },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = callStacksDescription }
                            .focusRequester(focusRequester)
                            .onFocusChanged { if (!it.hasFocus) keyboard.clear() }
                            .onPreviewKeyEvent { event ->
                                if (keyboard.handle(event)) return@onPreviewKeyEvent true
                                val action =
                                    FlameGraphPresenter.keyAction(
                                        key = event.key,
                                        eventType = event.type,
                                        snapshot = snapshot,
                                        selectedNodeId = state.contextNodeId ?: selectedNodeId,
                                        hasContextMenu = state.contextNodeId != null,
                                        hasTooltip = state.hoveredNodeId != null,
                                        hasDetails = state.details != FlameGraphDetailsState.Closed,
                                        controlPressed = event.isCtrlPressed,
                                        metaPressed = event.isMetaPressed,
                                        altPressed = event.isAltPressed,
                                        shiftPressed = event.isShiftPressed,
                                    )
                                if (action == null) {
                                    false
                                } else {
                                    dispatchFlameAction(
                                        action = action,
                                        actions = actions,
                                        snapshot = snapshot,
                                        viewport = viewport,
                                        onScrollOffset = { scrollOffsetPx = it },
                                        onContextAnchor = { contextAnchor = it },
                                    )
                                    true
                                }
                            }.focusable()
                            .onPointerEvent(PointerEventType.Press) { focusRequester.requestFocus() }
                            .pointerInput(sessionKey, startsAtBottom) {
                                awaitEachGesture {
                                    val down =
                                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    if (down.type != PointerType.Mouse || currentEvent.buttons.isPrimaryPressed) {
                                        var previousPosition = down.position
                                        do {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            val delta = change.position - previousPosition
                                            previousPosition = change.position
                                            if (delta != Offset.Zero) {
                                                currentScroll(delta.y)
                                                change.consume()
                                            }
                                        } while (change.pressed)
                                    }
                                }
                            }.onPointerEvent(PointerEventType.PanMove) { event ->
                                event.changes.forEach { change -> change.consume() }
                                val delta =
                                    event.changes
                                        .firstOrNull()
                                        ?.panOffset
                                        ?.y ?: 0f
                                if (!event.keyboardModifiers.isCtrlPressed && !event.keyboardModifiers.isShiftPressed) {
                                    if (delta.isFinite()) currentScroll(delta)
                                }
                            }.onPointerEvent(PointerEventType.Scroll) { event ->
                                event.changes.forEach { change -> change.consume() }
                                val delta =
                                    firefoxChartWheelPixels(event, heightPixels.toFloat(), density).y
                                flameGraphScrollPixelDelta(
                                    delta,
                                    controlPressed = event.keyboardModifiers.isCtrlPressed,
                                    shiftPressed = event.keyboardModifiers.isShiftPressed,
                                )?.let(currentScroll)
                            }.onSizeChanged { size ->
                                widthPixels = size.width
                                heightPixels = size.height
                            },
                )
                FlameGraphSemanticsOverlay(
                    snapshot = snapshot,
                    layout = layout,
                    selectedNodeId = selectedNodeId,
                    hoveredNodeId = state.hoveredNodeId,
                    contextNodeId = state.contextNodeId,
                    onSelect = actions.onSelectCallNode,
                    onOpenDetails = actions.onOpenFlameDetails,
                    onOpenContextMenu = { nodeId, _ -> actions.onOpenFlameContext(nodeId) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                FirefoxFlameGraphEmptyState(snapshot, actions, style, Modifier.fillMaxSize())
            }
            hoveredTooltipFacts
                ?.takeIf { state.contextNodeId == null && snapshot.emptyReason == null }
                ?.let { facts ->
                    val tooltipModifier =
                        when (tooltipMode) {
                            FlameTooltipMode.FIXED -> Modifier.align(Alignment.TopEnd).padding(8.dp)
                            FlameTooltipMode.FOLLOW_MOUSE ->
                                Modifier
                                    .offset {
                                        firefoxTooltipOffset(
                                            mouse = hoverAnchor ?: Offset.Zero,
                                            tooltipSize = tooltipSize,
                                            viewportSize = IntSize(widthPixels, heightPixels),
                                        )
                                    }.onSizeChanged { tooltipSize = it }
                        }
                    FirefoxFlameGraphTooltip(
                        facts = facts,
                        style = style,
                        modifier = tooltipModifier,
                    )
                }
            state.contextNodeId?.takeIf { snapshot.emptyReason == null }?.let { contextNodeId ->
                FirefoxFlameGraphContextMenu(
                    entries =
                        FlameGraphContextCommands.entries(
                            snapshot,
                            contextNodeId,
                            hasTransforms = query.transforms.isNotEmpty(),
                        ),
                    anchor = contextAnchor ?: Offset.Zero,
                    style = style,
                    onCommand = { command -> dispatchContextCommand(command, actions) },
                    onDismiss = { actions.onOpenFlameContext(null) },
                )
            }
        }
    }
}

internal fun flameGraphScrollPixelDelta(
    scrollDeltaY: Float,
    controlPressed: Boolean = false,
    shiftPressed: Boolean = false,
): Float? {
    val validScroll = scrollDeltaY.isFinite() && scrollDeltaY != 0f
    if (controlPressed || shiftPressed || !validScroll) return null
    return -scrollDeltaY
}

// Compatibility for callers still using integer row navigation; charts use pixel input above.
internal fun flameGraphScrollRowDelta(
    scrollDeltaY: Float,
    controlPressed: Boolean = false,
    shiftPressed: Boolean = false,
): Int? =
    flameGraphScrollPixelDelta(scrollDeltaY, controlPressed, shiftPressed)?.let { delta ->
        val magnitude = abs(delta).roundToInt().coerceAtLeast(1)
        if (delta > 0f) magnitude else -magnitude
    }

internal fun firefoxTooltipOffset(
    mouse: Offset,
    tooltipSize: IntSize,
    viewportSize: IntSize,
): IntOffset =
    IntOffset(
        x = firefoxTooltipCoordinate(mouse.x, tooltipSize.width, viewportSize.width),
        y = firefoxTooltipCoordinate(mouse.y, tooltipSize.height, viewportSize.height),
    )

private fun firefoxTooltipCoordinate(
    mousePosition: Float,
    tooltipLength: Int,
    viewportLength: Int,
): Int {
    val after = mousePosition.toInt() + FIREFOX_TOOLTIP_MOUSE_OFFSET_PX
    if (after + tooltipLength < viewportLength) return after
    val before = mousePosition.toInt() - tooltipLength - FIREFOX_TOOLTIP_MOUSE_OFFSET_PX
    return if (before >= 0) before else FIREFOX_TOOLTIP_VISUAL_MARGIN_PX
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun FirefoxFlameGraphViewport(
    style: FirefoxFlameGraphStyle,
    details: FlameGraphDetailsState,
    onCloseDetails: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(modifier) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .heightIn(min = MINIMUM_VIEWPORT_HEIGHT_DP.dp)
                    .background(style.canvasBackground.toComposeColor())
                    .border(1.dp, style.viewportBorder.toComposeColor())
                    .clipToBounds(),
            content = content,
        )
        FirefoxFrameDetailsBottomBox(details, onCloseDetails, style)
    }
}

@Suppress("LongParameterList")
private fun dispatchFlameAction(
    action: FlameGraphPanelAction,
    actions: ReportActions,
    snapshot: FlameGraphSnapshot,
    viewport: FlameViewport,
    onScrollOffset: (Float) -> Unit,
    onContextAnchor: (Offset?) -> Unit = {},
) {
    when (action) {
        is FlameGraphPanelAction.Hover -> actions.onHoverFlameNode(action.nodeId)
        is FlameGraphPanelAction.Select -> actions.onSelectCallNode(action.nodeId)
        is FlameGraphPanelAction.OpenContextMenu -> {
            onContextAnchor(action.anchor)
            actions.onOpenFlameContext(action.nodeId)
        }
        is FlameGraphPanelAction.OpenDetails -> {
            actions.onOpenFlameContext(null)
            actions.onOpenFlameDetails(action.nodeId)
        }
        is FlameGraphPanelAction.Copy -> {
            actions.onOpenFlameContext(null)
            actions.onCopyFlameFunction(action.text)
        }
        is FlameGraphPanelAction.ApplyTransform -> {
            actions.onOpenFlameContext(null)
            actions.onApplyFlameTransform(action.transform)
        }
        FlameGraphPanelAction.DismissContextMenu -> {
            onContextAnchor(null)
            actions.onOpenFlameContext(null)
        }
        FlameGraphPanelAction.CloseDetails -> actions.onCloseFlameDetails()
        FlameGraphPanelAction.DismissTooltip -> actions.onHoverFlameNode(null)
        is FlameGraphPanelAction.Navigate -> {
            actions.onOpenFlameContext(null)
            actions.onNavigateFlameNode(action.command)?.let { target ->
                onScrollOffset(FlameGraphLayout.scrollOffsetToReveal(snapshot, target, viewport))
            }
        }
    }
}

internal fun dispatchContextCommand(
    command: FlameGraphContextCommand,
    actions: ReportActions,
) {
    actions.onOpenFlameContext(null)
    when (command) {
        is FlameGraphContextCommand.ApplyTransform -> actions.onApplyFlameTransform(command.transform)
        is FlameGraphContextCommand.Copy -> actions.onCopyFlameFunction(command.text)
        FlameGraphContextCommand.Undo -> actions.onUndoFlameTransform()
        FlameGraphContextCommand.Clear -> actions.onClearFlameTransforms()
    }
}

private const val FIREFOX_TOOLTIP_MOUSE_OFFSET_PX = 11
private const val FIREFOX_TOOLTIP_VISUAL_MARGIN_PX = 8

private const val MINIMUM_VIEWPORT_HEIGHT_DP = 220
