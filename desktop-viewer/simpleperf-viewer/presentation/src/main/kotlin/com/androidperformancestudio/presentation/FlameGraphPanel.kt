@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.androidperformancestudio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.application.FlameGraphDetailsState
import com.androidperformancestudio.application.FlameGraphPanelState
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.visualization.FirefoxFlameGraphStyle
import com.androidperformancestudio.visualization.FlameGraphCanvas
import com.androidperformancestudio.visualization.FlameGraphLayout
import com.androidperformancestudio.visualization.FlameHorizontalViewport
import com.androidperformancestudio.visualization.FlameViewport
import java.nio.file.Path
import kotlin.math.roundToInt

@Composable
@Suppress("CyclomaticComplexMethod", "FunctionName", "LongMethod", "ktlint:standard:function-naming")
internal fun FlameGraphPanel(
    sessionIdentity: Path,
    state: FlameGraphPanelState,
    snapshot: FlameGraphSnapshot,
    actions: ReportActions,
    tooltipMode: FlameTooltipMode = FlameTooltipMode.FOLLOW_MOUSE,
) {
    val style = rememberFirefoxFlameGraphStyle()
    var widthPixels by remember { mutableIntStateOf(0) }
    var heightPixels by remember { mutableIntStateOf(0) }
    var scrollRow by remember(snapshot) { mutableIntStateOf(0) }
    var horizontalViewport by remember(snapshot) { mutableStateOf(FlameHorizontalViewport()) }
    var contextAnchor by remember(snapshot) { mutableStateOf<Offset?>(null) }
    var hoverAnchor by remember(snapshot) { mutableStateOf<Offset?>(null) }
    var tooltipSize by remember(snapshot) { mutableStateOf(IntSize.Zero) }
    val focusRequester = remember { FocusRequester() }
    val callStacksDescription = localizedSimpleperfText("Flame graph call stacks")
    val requestedViewport =
        FlameViewport(
            widthPx = widthPixels,
            heightPx = heightPixels,
            scrollRow = scrollRow,
            rowHeightPx = style.rowHeightPx,
            horizontal = horizontalViewport,
        )
    val clampedScrollRow = FlameGraphLayout.clampScrollRow(snapshot, requestedViewport)
    val viewport = requestedViewport.copy(scrollRow = clampedScrollRow)
    val layout = remember(snapshot, viewport) { FlameGraphLayout.layout(snapshot, viewport) }

    LaunchedEffect(snapshot, state.selectedNodeId, heightPixels) {
        val next =
            state.selectedNodeId?.let { selected ->
                FlameGraphPresenter.scrollRowToReveal(snapshot, selected, viewport)
            } ?: clampedScrollRow
        if (scrollRow != next) scrollRow = next
    }
    LaunchedEffect(state.contextNodeId) {
        if (state.contextNodeId == null) contextAnchor = null
    }
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }

    Column(modifier = Modifier.fillMaxSize().background(style.panelSurface.toComposeColor())) {
        FirefoxFlameGraphToolbar(
            sessionIdentity = sessionIdentity,
            authoritativeSearch = state.query.searchText,
            implementation = state.query.implementation,
            direction = state.query.direction,
            style = style,
            hasTransforms = state.query.transforms.isNotEmpty(),
            onSearch = actions.onFlameSearch,
            onImplementation = actions.onFlameImplementation,
            onDirection = actions.onCallTreeDirection,
            onUndo = actions.onUndoFlameTransform,
            onClear = actions.onClearFlameTransforms,
        )
        FirefoxTransformNavigator(
            transforms = state.query.transforms,
            style = style,
            onUndo = actions.onUndoFlameTransform,
            onClear = actions.onClearFlameTransforms,
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
                    selectedNodeId = state.selectedNodeId,
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
                            onScrollRow = { scrollRow = it },
                            onContextAnchor = { contextAnchor = it },
                        )
                    },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = callStacksDescription }
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { event ->
                                val modifiersPressed =
                                    event.isCtrlPressed ||
                                        event.isMetaPressed ||
                                        event.isAltPressed ||
                                        event.isShiftPressed
                                val horizontalAction =
                                    FlameGraphKeyboardNavigation.horizontalActionFor(
                                        key = event.key,
                                        eventType = event.type,
                                        modifiersPressed = modifiersPressed,
                                    )
                                if (horizontalAction != null) {
                                    horizontalViewport = horizontalViewport.navigate(horizontalAction)
                                    return@onPreviewKeyEvent true
                                }
                                val action =
                                    FlameGraphPresenter.keyAction(
                                        key = event.key,
                                        eventType = event.type,
                                        snapshot = snapshot,
                                        selectedNodeId = state.contextNodeId ?: state.selectedNodeId,
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
                                        onScrollRow = { scrollRow = it },
                                        onContextAnchor = { contextAnchor = it },
                                    )
                                    true
                                }
                            }.focusable()
                            .onPointerEvent(PointerEventType.Press) { focusRequester.requestFocus() }
                            .onPointerEvent(PointerEventType.Scroll) { event ->
                                val delta =
                                    event.changes
                                        .firstOrNull()
                                        ?.scrollDelta
                                        ?.y ?: 0f
                                if (delta.isFinite() && delta != 0f) {
                                    val rows = delta.roundToInt().takeUnless { it == 0 } ?: if (delta > 0) 1 else -1
                                    scrollRow =
                                        FlameGraphLayout.clampScrollRow(
                                            snapshot,
                                            viewport.copy(scrollRow = clampedScrollRow + rows),
                                        )
                                }
                            }.onSizeChanged { size ->
                                widthPixels = size.width
                                heightPixels = size.height
                            },
                )
                FlameGraphSemanticsOverlay(
                    snapshot = snapshot,
                    layout = layout,
                    selectedNodeId = state.selectedNodeId,
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
            state.hoveredNodeId
                ?.takeIf { state.contextNodeId == null && snapshot.emptyReason == null }
                ?.let(snapshot::tooltipFacts)
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
                            hasTransforms = state.query.transforms.isNotEmpty(),
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
    onScrollRow: (Int) -> Unit,
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
                onScrollRow(FlameGraphPresenter.scrollRowToReveal(snapshot, target, viewport))
            }
        }
    }
}

private fun dispatchContextCommand(
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
