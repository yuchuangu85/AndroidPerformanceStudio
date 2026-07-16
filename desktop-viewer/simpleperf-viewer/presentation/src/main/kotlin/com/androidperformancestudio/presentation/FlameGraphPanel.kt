@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.androidperformancestudio.presentation

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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
    var unavailableFeedback by remember(snapshot) { mutableStateOf<FlameGraphUnavailableFeedback?>(null) }
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
        if (state.contextNodeId == null && unavailableFeedback is FlameGraphUnavailableFeedback.ContextActions) {
            unavailableFeedback = null
        }
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
                        onFeedback = { unavailableFeedback = it },
                        onScrollRow = { scrollRow = it },
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
                                    selectedNodeId = state.selectedNodeId,
                                    hasContextMenu = state.contextNodeId != null,
                                    hasTooltip = state.hoveredNodeId != null,
                                    hasUnavailableFeedback = unavailableFeedback != null,
                                    controlPressed = event.isCtrlPressed,
                                    metaPressed = event.isMetaPressed,
                                )
                            if (action == null) {
                                false
                            } else {
                                dispatchFlameAction(
                                    action = action,
                                    actions = actions,
                                    snapshot = snapshot,
                                    viewport = viewport,
                                    onFeedback = { unavailableFeedback = it },
                                    onScrollRow = { scrollRow = it },
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
            unavailableFeedback?.let { feedback ->
                val feedbackModifier =
                    when (feedback) {
                        is FlameGraphUnavailableFeedback.ContextActions ->
                            Modifier.offset {
                                IntOffset(feedback.anchor.x.roundToInt(), feedback.anchor.y.roundToInt())
                            }
                        is FlameGraphUnavailableFeedback.Details -> Modifier.align(Alignment.TopEnd).padding(8.dp)
                    }
                FlameGraphUnavailableNotice(feedback, feedbackModifier)
            }
        }
        state.hoveredNodeId
            ?.takeIf { state.contextNodeId == null }
            ?.let(snapshot::tooltipFacts)
            ?.let { facts -> FlameGraphTooltip(facts) }
        Text("Click a frame to select it. Flame widths always represent the full analyzed sample set.")
    }
}

@Suppress("LongParameterList")
private fun dispatchFlameAction(
    action: FlameGraphPanelAction,
    actions: ReportActions,
    snapshot: FlameGraphSnapshot,
    viewport: FlameViewport,
    onFeedback: (FlameGraphUnavailableFeedback?) -> Unit,
    onScrollRow: (Int) -> Unit,
) {
    if (action !is FlameGraphPanelAction.Hover) onFeedback(null)
    when (action) {
        is FlameGraphPanelAction.Hover -> actions.onHoverFlameNode(action.nodeId)
        is FlameGraphPanelAction.Select -> actions.onSelectFlameNode(action.nodeId)
        is FlameGraphPanelAction.OpenContextMenu -> {
            actions.onOpenFlameContext(action.nodeId)
            onFeedback(FlameGraphPresenter.unavailableFeedbackFor(action))
        }
        is FlameGraphPanelAction.OpenDetails -> {
            actions.onOpenFlameContext(null)
            onFeedback(FlameGraphPresenter.unavailableFeedbackFor(action))
        }
        is FlameGraphPanelAction.Copy -> {
            actions.onOpenFlameContext(null)
            actions.onCopyFlameFunction(action.text)
        }
        FlameGraphPanelAction.DismissContextMenu -> actions.onOpenFlameContext(null)
        FlameGraphPanelAction.DismissUnavailableFeedback -> actions.onOpenFlameContext(null)
        FlameGraphPanelAction.DismissTooltip -> actions.onHoverFlameNode(null)
        is FlameGraphPanelAction.Navigate -> {
            actions.onOpenFlameContext(null)
            actions.onNavigateFlameNode(action.command)?.let { target ->
                onScrollRow(FlameGraphPresenter.scrollRowToReveal(snapshot, target, viewport))
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FlameGraphUnavailableNotice(
    feedback: FlameGraphUnavailableFeedback,
    modifier: Modifier,
) {
    val message = localizedSimpleperfText(feedback.message)
    Surface(
        modifier =
            modifier.semantics {
                contentDescription = message
                liveRegion = LiveRegionMode.Polite
            },
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 2.dp,
    ) {
        Text(message, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
    }
}

private const val FLAME_ROW_HEIGHT = 16f
private const val DARK_THEME_LUMINANCE_THRESHOLD = 0.5f
