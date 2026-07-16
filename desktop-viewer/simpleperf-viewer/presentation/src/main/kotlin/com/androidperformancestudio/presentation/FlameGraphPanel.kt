@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.androidperformancestudio.presentation

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.application.FlameGraphPanelState
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.visualization.FlameGraphCanvas
import com.androidperformancestudio.visualization.FlameGraphLayout
import com.androidperformancestudio.visualization.FlameTheme
import com.androidperformancestudio.visualization.FlameViewport
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
@Suppress("CyclomaticComplexMethod", "FunctionName", "LongMethod", "ktlint:standard:function-naming")
internal fun FlameGraphPanel(
    state: FlameGraphPanelState,
    snapshot: FlameGraphSnapshot,
    actions: ReportActions,
) {
    var searchDraft by remember(snapshot) { mutableStateOf(state.query.searchText) }
    var widthPixels by remember { mutableIntStateOf(0) }
    var heightPixels by remember { mutableIntStateOf(0) }
    var scrollRow by remember(snapshot) { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }
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

    LaunchedEffect(snapshot, state.query.searchText) {
        if (searchDraft != state.query.searchText) searchDraft = state.query.searchText
    }
    LaunchedEffect(snapshot, searchDraft) {
        if (searchDraft != state.query.searchText) {
            delay(SEARCH_DEBOUNCE_MILLIS)
            actions.onFlameSearch(searchDraft)
        }
    }
    LaunchedEffect(snapshot, state.selectedNodeId, heightPixels) {
        val next =
            state.selectedNodeId?.let { selected ->
                FlameGraphPresenter.scrollRowToReveal(snapshot, selected, viewport)
            } ?: clampedScrollRow
        if (scrollRow != next) scrollRow = next
    }
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlameGraphToolbar(
            searchDraft = searchDraft,
            implementation = state.query.implementation,
            direction = state.query.direction,
            hasTransforms = state.query.transforms.isNotEmpty(),
            onSearchDraft = { searchDraft = it },
            onImplementation = actions.onFlameImplementation,
            onDirection = actions.onCallTreeDirection,
            onUndo = actions.onUndoFlameTransform,
            onClear = actions.onClearFlameTransforms,
        )
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
                dispatchFlameAction(FlameGraphPresenter.actionFor(intent), actions, snapshot, viewport) { row ->
                    scrollRow = row
                }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 520.dp)
                    .semantics { contentDescription = "Flame graph call stacks" }
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        val action =
                            FlameGraphPresenter.keyAction(
                                key = event.key,
                                eventType = event.type,
                                snapshot = snapshot,
                                selectedNodeId = state.selectedNodeId,
                                hasContextMenu = state.contextNodeId != null,
                                hasTooltip = state.hoveredNodeId != null,
                                controlPressed = event.isCtrlPressed,
                                metaPressed = event.isMetaPressed,
                            )
                        if (action == null) {
                            false
                        } else {
                            dispatchFlameAction(action, actions, snapshot, viewport) { row -> scrollRow = row }
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
        state.hoveredNodeId
            ?.takeIf { state.contextNodeId == null }
            ?.let(snapshot::tooltipFacts)
            ?.let { facts -> FlameGraphTooltip(facts) }
        Text("Click a frame to select it. Flame widths always represent the full analyzed sample set.")
    }
}

private fun dispatchFlameAction(
    action: FlameGraphPanelAction,
    actions: ReportActions,
    snapshot: FlameGraphSnapshot,
    viewport: FlameViewport,
    onScrollRow: (Int) -> Unit,
) {
    when (action) {
        is FlameGraphPanelAction.Hover -> actions.onHoverFlameNode(action.nodeId)
        is FlameGraphPanelAction.Select -> actions.onSelectFlameNode(action.nodeId)
        is FlameGraphPanelAction.OpenContextMenu -> actions.onOpenFlameContext(action.nodeId)
        is FlameGraphPanelAction.OpenDetails -> Unit // Task 12 owns the authoritative details surface.
        is FlameGraphPanelAction.Copy -> actions.onCopyFlameFunction(action.text)
        FlameGraphPanelAction.DismissContextMenu -> actions.onOpenFlameContext(null)
        FlameGraphPanelAction.DismissTooltip -> actions.onHoverFlameNode(null)
        is FlameGraphPanelAction.Navigate -> {
            actions.onNavigateFlameNode(action.command)?.let { target ->
                onScrollRow(FlameGraphPresenter.scrollRowToReveal(snapshot, target, viewport))
            }
        }
    }
}

private const val FLAME_ROW_HEIGHT = 16f
private const val DARK_THEME_LUMINANCE_THRESHOLD = 0.5f
private const val SEARCH_DEBOUNCE_MILLIS = 150L
