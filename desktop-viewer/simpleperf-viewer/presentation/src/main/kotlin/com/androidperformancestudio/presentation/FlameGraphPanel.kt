@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.androidperformancestudio.presentation

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.application.FlameGraphDetailsState
import com.androidperformancestudio.application.FlameGraphPanelState
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.visualization.FlameGraphCanvas
import com.androidperformancestudio.visualization.FlameGraphLayout
import com.androidperformancestudio.visualization.FlameTheme
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
) {
    var widthPixels by remember { mutableIntStateOf(0) }
    var heightPixels by remember { mutableIntStateOf(0) }
    var scrollRow by remember(snapshot) { mutableIntStateOf(0) }
    var contextAnchor by remember(snapshot) { mutableStateOf<Offset?>(null) }
    val focusRequester = remember { FocusRequester() }
    val callStacksDescription = localizedSimpleperfText("Flame graph call stacks")
    val requestedViewport =
        FlameViewport(
            widthPx = widthPixels,
            heightPx = heightPixels,
            scrollRow = scrollRow,
            rowHeightPx = FLAME_ROW_HEIGHT,
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlameGraphToolbar(
            sessionIdentity = sessionIdentity,
            authoritativeSearch = state.query.searchText,
            implementation = state.query.implementation,
            direction = state.query.direction,
            hasTransforms = state.query.transforms.isNotEmpty(),
            onSearch = actions.onFlameSearch,
            onImplementation = actions.onFlameImplementation,
            onDirection = actions.onCallTreeDirection,
            onUndo = actions.onUndoFlameTransform,
            onClear = actions.onClearFlameTransforms,
        )
        Box {
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
                    theme =
                        if (MaterialTheme.colorScheme.background.luminance() < DARK_THEME_LUMINANCE_THRESHOLD) {
                            FlameTheme.DARK
                        } else {
                            FlameTheme.LIGHT
                        },
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
                            .fillMaxWidth()
                            .heightIn(min = 220.dp, max = 520.dp)
                            .semantics { contentDescription = callStacksDescription }
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { event ->
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
            } else {
                FlameGraphEmptyState(snapshot, actions)
            }
            state.contextNodeId?.takeIf { snapshot.emptyReason == null }?.let { contextNodeId ->
                FlameGraphContextMenu(
                    entries =
                        FlameGraphContextCommands.entries(
                            snapshot,
                            contextNodeId,
                            hasTransforms = state.query.transforms.isNotEmpty(),
                        ),
                    anchor = contextAnchor ?: Offset.Zero,
                    onCommand = { command -> dispatchContextCommand(command, actions) },
                    onDismiss = { actions.onOpenFlameContext(null) },
                )
            }
        }
        state.hoveredNodeId
            ?.takeIf { state.contextNodeId == null }
            ?.let(snapshot::tooltipFacts)
            ?.let { facts -> FlameGraphTooltip(facts) }
        FlameGraphDetailsPanel(state.details, actions.onCloseFlameDetails)
        if (snapshot.emptyReason == null) {
            Text("Click a frame to select it. Flame widths always represent the full analyzed sample set.")
        }
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

private const val FLAME_ROW_HEIGHT = 16f
private const val DARK_THEME_LUMINANCE_THRESHOLD = 0.5f
