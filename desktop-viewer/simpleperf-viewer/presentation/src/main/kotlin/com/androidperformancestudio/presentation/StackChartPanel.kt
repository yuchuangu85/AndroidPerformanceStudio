package com.androidperformancestudio.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.application.FlameGraphDetailsState
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.profileanalysis.StackChartBlock
import com.androidperformancestudio.profileanalysis.StackChartEmptyReason
import com.androidperformancestudio.profileanalysis.StackChartSnapshot
import com.androidperformancestudio.storage.MarkerProjectionRow
import com.androidperformancestudio.storage.PanelProjection
import com.androidperformancestudio.ui.CopyableText
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.button.MacOSTextButton
import com.androidperformancestudio.ui.localizedStringResource

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun StackChartPanel(
    state: ReportState,
    projection: PanelProjection<StackChartSnapshot>,
    actions: ReportActions,
    style: ViewerColors,
) {
    val markers = (state.lastReadyReport?.userTimings as? PanelProjection.Ready)?.value?.markers.orEmpty()
    val timings =
        remember(markers, state.workspace.stackChartShowUserTimings) {
            if (state.workspace.stackChartShowUserTimings) stackChartUserTimingRows(markers) else emptyList()
        }
    Column(
        modifier = Modifier.fillMaxSize().testTag("stack-chart-panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (projection) {
            is PanelProjection.Failed ->
                StackChartMessage(
                    "${projection.code}: ${projection.message}",
                    style,
                    actions.onRetryFlameProjection,
                )
            is PanelProjection.Ready -> {
                val snapshot = projection.value
                if (snapshot.blocks.isEmpty() && timings.isEmpty()) {
                    StackChartMessage(
                        snapshot.emptyReason.message(currentSimpleperfLanguage()),
                        style,
                        actions.onRetryFlameProjection,
                    )
                } else {
                    val start =
                        state.filter.startNanosInclusive
                            ?: state.lastReadyReport?.sessionOverview?.startNanos ?: snapshot.startNanos
                            ?: timings.flatMap { it.markers }.minOf(MarkerProjectionRow::startNanos)
                    val end =
                        state.filter.endNanosExclusive
                            ?: state.lastReadyReport?.sessionOverview?.endNanosInclusive?.let {
                                if (it == Long.MAX_VALUE) it else it + 1
                            } ?: snapshot.endNanosExclusive
                            ?: timings.flatMap { it.markers }.maxOf(MarkerProjectionRow::endNanosExclusive)
                    StackChartContent(state, snapshot, StackChartViewport(start, end), actions, timings)
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod", "ktlint:standard:function-naming")
private fun StackChartContent(
    state: ReportState,
    snapshot: StackChartSnapshot,
    viewport: StackChartViewport,
    actions: ReportActions,
    timings: List<StackChartUserTimingRow>,
) {
    val style = rememberFirefoxFlameGraphStyle()
    val flame = snapshot.committedAnalysis ?: state.lastReadyReport?.flameGraph
    var hover by remember(snapshot) { mutableStateOf<StackChartBlock?>(null) }
    var hoverAnchor by remember(snapshot) { mutableStateOf(Offset.Zero) }
    var timingHover by remember(snapshot, timings) { mutableStateOf<MarkerProjectionRow?>(null) }
    var context by remember(snapshot) { mutableStateOf<StackChartBlock?>(null) }
    var contextAnchor by remember(snapshot) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var tooltipSize by remember { mutableStateOf(IntSize.Zero) }
    val selectedNodeId = state.workspace.selections.callNodeId
    val selectedBlock = snapshot.selectedBlock(state.workspace.selections.stackChartBlockId, selectedNodeId, viewport)
    val selectedId = selectedBlock?.id
    val facts = remember(flame, hover?.callNodeId) { hover?.callNodeId?.let { flame?.tooltipFacts(it) } }

    fun openDetails(block: StackChartBlock) {
        hover = null
        context = null
        block.callNodeId?.let(actions.onOpenFlameDetails)
    }

    Column(Modifier.fillMaxSize()) {
        FirefoxTransformNavigator(
            state.callStackQuery.transforms,
            style,
            actions.onUndoFlameTransform,
            actions.onClearFlameTransforms,
            onRemoveAfter = actions.onRemoveFlameTransformsAfter,
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .onSizeChanged { size = it },
        ) {
            StackChartCanvas(
                snapshot = snapshot,
                viewport = viewport,
                selectedBlockId = selectedId,
                selectedCallNodeId = selectedNodeId,
                contextBlockId = context?.id,
                onSelect = {
                    hover = null
                    context = null
                    actions.onSelectStackChartBlock(it)
                },
                onViewportChange = { range ->
                    if (range == viewport) {
                        actions.onCancelFlamePreview()
                    } else {
                        actions.onFlamePreviewRange(
                            com.androidperformancestudio.profileanalysis.AnalysisTimeRange(
                                range.startNanos,
                                range.endNanosExclusive,
                            ),
                        )
                    }
                },
                previewViewport =
                    state.callStackQuery.previewRange?.let {
                        StackChartViewport(it.startNanosInclusive, it.endNanosExclusive)
                    },
                onHover = { block, anchor ->
                    hover = block
                    anchor?.let { hoverAnchor = it }
                },
                onContextMenu = { block, anchor ->
                    context = block
                    contextAnchor = anchor
                    hover = null
                },
                onOpenDetails = ::openDetails,
                searchText = state.callStackQuery.searchText,
                sameWidths = state.workspace.stackChartSameWidths,
                userTimings = timings,
                sessionKey = state.lastReadyReport?.session?.directory,
                onHoverUserTiming = { marker, anchor ->
                    timingHover = marker
                    anchor?.let { hoverAnchor = it }
                },
                overlay = {
                    if (facts != null && context == null) {
                        FirefoxFlameGraphTooltip(
                            facts = facts,
                            style = style,
                            durationText =
                                firefoxTooltipDuration(
                                    requireNotNull(hover).let { it.endNanosExclusive - it.startNanos },
                                    null,
                                ),
                            modifier =
                                Modifier.offset { firefoxTooltipOffset(hoverAnchor, tooltipSize, size) }.onSizeChanged {
                                    tooltipSize =
                                        it
                                },
                        )
                    }
                    timingHover?.takeIf { context == null }?.let { marker ->
                        StackChartUserTimingTooltip(
                            marker,
                            style,
                            Modifier
                                .offset { firefoxTooltipOffset(hoverAnchor, tooltipSize, size) }
                                .onSizeChanged { tooltipSize = it },
                        )
                    }
                },
                onKeyAction = { event ->
                    val action =
                        flame?.let {
                            FlameGraphPresenter.keyAction(
                                event.key,
                                event.type,
                                it,
                                context?.callNodeId ?: selectedBlock?.callNodeId ?: selectedNodeId,
                                hasContextMenu = context != null,
                                hasTooltip = hover != null,
                                hasDetails =
                                    state.flameGraph.details != FlameGraphDetailsState.Closed,
                                controlPressed = event.isCtrlPressed,
                                metaPressed = event.isMetaPressed,
                                altPressed = event.isAltPressed,
                                shiftPressed = event.isShiftPressed,
                            )
                        }
                    when (action) {
                        is FlameGraphPanelAction.Copy -> {
                            actions.onCopyFlameFunction(action.text)
                            true
                        }
                        is FlameGraphPanelAction.ApplyTransform -> {
                            context = null
                            actions.onApplyFlameTransform(action.transform)
                            true
                        }
                        is FlameGraphPanelAction.OpenDetails -> {
                            actions.onOpenFlameDetails(action.nodeId)
                            hover = null
                            true
                        }
                        is FlameGraphPanelAction.OpenContextMenu -> {
                            context = selectedBlock
                            contextAnchor = action.anchor
                            hover = null
                            true
                        }
                        FlameGraphPanelAction.DismissContextMenu -> {
                            context = null
                            true
                        }
                        FlameGraphPanelAction.DismissTooltip -> {
                            hover = null
                            true
                        }
                        FlameGraphPanelAction.CloseDetails -> {
                            actions.onCloseFlameDetails()
                            true
                        }
                        else -> false
                    }
                },
            )
            context?.callNodeId?.let { nodeId ->
                if (flame != null) {
                    FirefoxFlameGraphContextMenu(
                        FlameGraphContextCommands.entries(flame, nodeId, state.callStackQuery.transforms.isNotEmpty()),
                        contextAnchor,
                        style,
                        onCommand = {
                            context = null
                            dispatchContextCommand(it, actions)
                        },
                        onDismiss = { context = null },
                    )
                }
            }
        }
        FirefoxFrameDetailsBottomBox(state.flameGraph.details, actions.onCloseFlameDetails, style)
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun StackChartMessage(
    message: String,
    style: ViewerColors,
    onRetry: () -> Unit,
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CopyableText(
            message,
            color = style.secondaryText,
            fontSize = ViewerTypography.secondary.fontSize,
            language = currentSimpleperfLanguage(),
        )
        MacOSTextButton("Retry", onRetry, style)
    }
}

private fun StackChartEmptyReason?.message(language: UiLanguage = UiLanguage.ENGLISH): String =
    when (this) {
        StackChartEmptyReason.NO_SAMPLES ->
            localizedStringResource(SimpleperfViewerRes.sp_stack_no_samples_collected_empty_state, language)
        StackChartEmptyReason.RANGE_EMPTY ->
            localizedStringResource(SimpleperfViewerRes.sp_stack_no_samples_in_range_empty_state, language)
        StackChartEmptyReason.FILTERED_ALL ->
            localizedStringResource(SimpleperfViewerRes.sp_stack_filters_removed_all_samples_empty_state, language)
        null -> localizedStringResource(SimpleperfViewerRes.sp_stack_no_stack_blocks_empty_state, language)
    }
