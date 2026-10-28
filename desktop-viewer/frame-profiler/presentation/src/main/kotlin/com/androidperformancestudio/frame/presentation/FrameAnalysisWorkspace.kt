@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "MagicNumber", "ktlint:standard:function-naming")

package com.androidperformancestudio.frame.presentation

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.frame.presentation.generated.resources.Res
import com.androidperformancestudio.frame.presentation.generated.resources.frame_attribution
import com.androidperformancestudio.frame.presentation.generated.resources.frame_detail
import com.androidperformancestudio.frame.presentation.generated.resources.frame_timeline
import com.androidperformancestudio.frame.presentation.generated.resources.jank_clusters
import com.androidperformancestudio.frame.presentation.generated.resources.problem_frames
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioCard
import java.awt.Cursor

private val FRAME_SPLITTER_SIZE = 7.dp
private val FRAME_KEYBOARD_STEP = 16.dp
private val FRAME_CLUSTERS_MIN_WIDTH = 180.dp
private val FRAME_PROBLEMS_MIN_WIDTH = 260.dp
private val FRAME_DETAILS_MIN_WIDTH = 260.dp

@Composable
internal fun FrameAnalysisWorkspace(
    language: UiLanguage,
    showContext: Boolean,
    modifier: Modifier,
    timeline: @Composable (Modifier) -> Unit,
    clusters: @Composable (Modifier) -> Unit,
    problems: @Composable (Modifier) -> Unit,
    details: @Composable (Modifier) -> Unit,
    context: @Composable (Modifier) -> Unit,
) {
    val timelineBoundary = remember { mutableStateOf(0.30f) }
    val contextBoundary = remember { mutableStateOf(0.80f) }
    val clustersBoundary = remember { mutableStateOf(0.258f) }
    val detailsBoundary = remember { mutableStateOf(0.697f) }
    val timelineLabel = localizedStringResource(Res.string.frame_timeline, language)
    val clustersLabel = localizedStringResource(Res.string.jank_clusters, language)
    val problemsLabel = localizedStringResource(Res.string.problem_frames, language)
    val detailsLabel = localizedStringResource(Res.string.frame_detail, language)
    val contextLabel = localizedStringResource(Res.string.frame_attribution, language)
    val bodyLabel = "$clustersLabel / $problemsLabel / $detailsLabel"

    StudioCard(
        modifier.testTag("frame-analysis-card"),
        contentPadding = 0.dp,
        contentSpacing = 0.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
            val minimumWidth =
                FRAME_CLUSTERS_MIN_WIDTH + FRAME_PROBLEMS_MIN_WIDTH + FRAME_DETAILS_MIN_WIDTH + FRAME_SPLITTER_SIZE * 2
            val viewportWidth = if (constraints.hasBoundedWidth) maxWidth else minimumWidth
            val bodyWidth = maxOf(viewportWidth, minimumWidth)
            val scrollbarInset = if (bodyWidth > viewportWidth) LocalScrollbarStyle.current.thickness else 0.dp
            val minimumTimelineHeight = (if (viewportWidth >= 760.dp) 184.dp else 248.dp) * fontScale
            val minimumBodyHeight = 140.dp * fontScale + scrollbarInset
            val minimumContextHeight = if (showContext) 96.dp * fontScale else 0.dp
            val dividersHeight = FRAME_SPLITTER_SIZE * if (showContext) 2 else 1
            val minimumHeight = minimumTimelineHeight + minimumBodyHeight + minimumContextHeight + dividersHeight
            val interiorHeight = if (constraints.hasBoundedHeight) maxOf(maxHeight, minimumHeight) else minimumHeight
            val paneHeight = interiorHeight - dividersHeight
            // Clamp only the rendered boundaries, so temporary constraints never overwrite the saved ratios.
            val timelineHeight =
                (paneHeight * timelineBoundary.value).coerceIn(
                    minimumTimelineHeight,
                    (paneHeight - minimumBodyHeight - minimumContextHeight).coerceAtLeast(minimumTimelineHeight),
                )
            val minimumContextBoundary = timelineHeight + minimumBodyHeight
            val maximumContextBoundary = (paneHeight - minimumContextHeight).coerceAtLeast(minimumContextBoundary)
            val contextRange =
                (minimumContextBoundary / paneHeight)..(maximumContextBoundary / paneHeight)
            val contextStart =
                if (showContext) {
                    (paneHeight * contextBoundary.value).coerceIn(minimumContextBoundary, maximumContextBoundary)
                } else {
                    paneHeight
                }
            val maximumTimelineHeight = (contextStart - minimumBodyHeight).coerceAtLeast(minimumTimelineHeight)
            val timelineRange = (minimumTimelineHeight / paneHeight)..(maximumTimelineHeight / paneHeight)
            val verticalScroll = rememberScrollState()

            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(verticalScroll)
                    .height(interiorHeight)
                    .testTag("frame-workspace-interior"),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(timelineHeight)
                        .clipToBounds()
                        .testTag("frame-workspace-timeline"),
                ) {
                    timeline(Modifier.fillMaxSize())
                }
                FrameWorkspaceDivider(
                    timelineBoundary,
                    paneHeight,
                    timelineRange,
                    vertical = false,
                    label = "$timelineLabel / $bodyLabel",
                    tag = "frame-timeline-height-resizer",
                )
                FrameWorkspaceBody(
                    clustersBoundary,
                    detailsBoundary,
                    bodyWidth,
                    scrollbarInset,
                    clustersLabel,
                    problemsLabel,
                    detailsLabel,
                    Modifier.fillMaxWidth().weight(1f),
                    clusters,
                    problems,
                    details,
                )
                if (showContext) {
                    FrameWorkspaceDivider(
                        contextBoundary,
                        paneHeight,
                        contextRange,
                        vertical = false,
                        label = "$bodyLabel / $contextLabel",
                        tag = "frame-context-height-resizer",
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(paneHeight - contextStart)
                            .clipToBounds()
                            .testTag("frame-workspace-context"),
                    ) {
                        context(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameWorkspaceBody(
    clustersBoundary: MutableState<Float>,
    detailsBoundary: MutableState<Float>,
    bodyWidth: Dp,
    scrollbarInset: Dp,
    clustersLabel: String,
    problemsLabel: String,
    detailsLabel: String,
    modifier: Modifier,
    clusters: @Composable (Modifier) -> Unit,
    problems: @Composable (Modifier) -> Unit,
    details: @Composable (Modifier) -> Unit,
) {
    val paneWidth = bodyWidth - FRAME_SPLITTER_SIZE * 2
    val clustersWidth =
        (paneWidth * clustersBoundary.value).coerceIn(
            FRAME_CLUSTERS_MIN_WIDTH,
            paneWidth - FRAME_PROBLEMS_MIN_WIDTH - FRAME_DETAILS_MIN_WIDTH,
        )
    val minimumDetailsBoundary = clustersWidth + FRAME_PROBLEMS_MIN_WIDTH
    val maximumDetailsBoundary = (paneWidth - FRAME_DETAILS_MIN_WIDTH).coerceAtLeast(minimumDetailsBoundary)
    val detailsRange =
        (minimumDetailsBoundary / paneWidth)..(maximumDetailsBoundary / paneWidth)
    val detailsStart = (paneWidth * detailsBoundary.value).coerceIn(minimumDetailsBoundary, maximumDetailsBoundary)
    val maximumClustersWidth = (detailsStart - FRAME_PROBLEMS_MIN_WIDTH).coerceAtLeast(FRAME_CLUSTERS_MIN_WIDTH)
    val clustersRange = (FRAME_CLUSTERS_MIN_WIDTH / paneWidth)..(maximumClustersWidth / paneWidth)
    val scroll = rememberScrollState()

    Box(modifier.clipToBounds().testTag("frame-workspace-body")) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(bottom = scrollbarInset)
                .horizontalScroll(scroll)
                .requiredWidth(bodyWidth),
        ) {
            Box(
                Modifier
                    .width(clustersWidth)
                    .fillMaxHeight()
                    .clipToBounds()
                    .testTag("frame-workspace-clusters"),
            ) {
                clusters(Modifier.fillMaxSize())
            }
            FrameWorkspaceDivider(
                clustersBoundary,
                paneWidth,
                clustersRange,
                vertical = true,
                label = "$clustersLabel / $problemsLabel",
                tag = "frame-clusters-width-resizer",
            )
            Box(
                Modifier
                    .width(detailsStart - clustersWidth)
                    .fillMaxHeight()
                    .clipToBounds()
                    .testTag("frame-workspace-problems"),
            ) {
                problems(Modifier.fillMaxSize())
            }
            FrameWorkspaceDivider(
                detailsBoundary,
                paneWidth,
                detailsRange,
                vertical = true,
                label = "$problemsLabel / $detailsLabel",
                tag = "frame-details-width-resizer",
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clipToBounds()
                    .testTag("frame-workspace-details"),
            ) {
                details(Modifier.fillMaxSize())
            }
        }
        if (scrollbarInset > 0.dp) {
            HorizontalScrollbar(
                rememberScrollbarAdapter(scroll),
                Modifier.align(Alignment.BottomStart).fillMaxWidth().testTag("frame-workspace-horizontal-scrollbar"),
            )
        }
    }
}

@Composable
private fun FrameWorkspaceDivider(
    boundary: MutableState<Float>,
    extent: Dp,
    range: ClosedFloatingPointRange<Float>,
    vertical: Boolean,
    label: String,
    tag: String,
) {
    val density = LocalDensity.current
    val extentPx = with(density) { extent.toPx() }
    val currentRange by rememberUpdatedState(range)
    val boundsModifier =
        if (vertical) {
            Modifier.width(FRAME_SPLITTER_SIZE).fillMaxHeight()
        } else {
            Modifier.fillMaxWidth().height(FRAME_SPLITTER_SIZE)
        }
    val borderColor = LocalViewerColors.current.border

    fun moveBy(delta: Float) {
        if (extentPx > 0f) {
            boundary.value = (boundary.value.coerceIn(currentRange) + delta / extentPx).coerceIn(currentRange)
        }
    }

    Box(
        boundsModifier
            .testTag(tag)
            .pointerHoverIcon(PointerIcon(Cursor(if (vertical) Cursor.E_RESIZE_CURSOR else Cursor.N_RESIZE_CURSOR)))
            .pointerInput(extentPx, vertical) {
                if (vertical) {
                    detectHorizontalDragGestures { change, amount ->
                        change.consume()
                        moveBy(amount)
                    }
                } else {
                    detectVerticalDragGestures { change, amount ->
                        change.consume()
                        moveBy(amount)
                    }
                }
            }.semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(boundary.value.coerceIn(range), range)
                setProgress { target ->
                    if (target.isFinite()) {
                        boundary.value = target.coerceIn(range)
                        true
                    } else {
                        false
                    }
                }
            }.onKeyEvent { event ->
                val direction = frameDividerDirection(event.key, vertical)
                if (event.type == KeyEventType.KeyDown && direction != 0) {
                    moveBy(with(density) { FRAME_KEYBOARD_STEP.toPx() } * direction)
                    true
                } else {
                    false
                }
            }.focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            (if (vertical) Modifier.width(1.dp).fillMaxHeight() else Modifier.fillMaxWidth().height(1.dp))
                .background(borderColor)
                .testTag("$tag-line"),
        )
    }
}

private fun frameDividerDirection(
    key: Key,
    vertical: Boolean,
): Int =
    when (key) {
        if (vertical) Key.DirectionLeft else Key.DirectionUp -> -1
        if (vertical) Key.DirectionRight else Key.DirectionDown -> 1
        else -> 0
    }
