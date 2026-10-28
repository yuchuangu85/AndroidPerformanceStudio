@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "MaxLineLength",
    "TooManyFunctions",
    "UnusedPrivateMember",
    "ktlint:standard:function-naming",
)

package com.androidperformancestudio.frame.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.frame.analysis.AnalyzedFrame
import com.androidperformancestudio.frame.analysis.DeadlineMissCluster
import com.androidperformancestudio.frame.analysis.FrameAnalysisResult
import com.androidperformancestudio.frame.analysis.FrameAttribution
import com.androidperformancestudio.frame.analysis.FrameDeadlineVerdict
import com.androidperformancestudio.frame.analysis.FrameSummary
import com.androidperformancestudio.frame.analysis.JankSeverity
import com.androidperformancestudio.frame.analysis.frameTimelineStatus
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import com.androidperformancestudio.frame.presentation.generated.resources.Res
import com.androidperformancestudio.frame.presentation.generated.resources.activity
import com.androidperformancestudio.frame.presentation.generated.resources.all_frames
import com.androidperformancestudio.frame.presentation.generated.resources.all_sources
import com.androidperformancestudio.frame.presentation.generated.resources.budget
import com.androidperformancestudio.frame.presentation.generated.resources.budget_source
import com.androidperformancestudio.frame.presentation.generated.resources.capture_online_or_import_framestats
import com.androidperformancestudio.frame.presentation.generated.resources.clipboard_unavailable
import com.androidperformancestudio.frame.presentation.generated.resources.cluster_count
import com.androidperformancestudio.frame.presentation.generated.resources.copy_evidence
import com.androidperformancestudio.frame.presentation.generated.resources.copy_evidence_privacy_hint
import com.androidperformancestudio.frame.presentation.generated.resources.correlate_in_layout_inspector
import com.androidperformancestudio.frame.presentation.generated.resources.deadline_miss_rate
import com.androidperformancestudio.frame.presentation.generated.resources.duration
import com.androidperformancestudio.frame.presentation.generated.resources.evidence_copied
import com.androidperformancestudio.frame.presentation.generated.resources.fragment
import com.androidperformancestudio.frame.presentation.generated.resources.frame
import com.androidperformancestudio.frame.presentation.generated.resources.frame_attribution
import com.androidperformancestudio.frame.presentation.generated.resources.frame_attribution_entry
import com.androidperformancestudio.frame.presentation.generated.resources.frame_detail
import com.androidperformancestudio.frame.presentation.generated.resources.frame_filter
import com.androidperformancestudio.frame.presentation.generated.resources.frame_screenshot_unavailable
import com.androidperformancestudio.frame.presentation.generated.resources.frame_signal
import com.androidperformancestudio.frame.presentation.generated.resources.frame_timeline
import com.androidperformancestudio.frame.presentation.generated.resources.frame_timeline_vsync_id
import com.androidperformancestudio.frame.presentation.generated.resources.frames
import com.androidperformancestudio.frame.presentation.generated.resources.interaction_state
import com.androidperformancestudio.frame.presentation.generated.resources.jank_cluster_summary
import com.androidperformancestudio.frame.presentation.generated.resources.jank_clusters
import com.androidperformancestudio.frame.presentation.generated.resources.jank_stats
import com.androidperformancestudio.frame.presentation.generated.resources.jank_stats_reasons
import com.androidperformancestudio.frame.presentation.generated.resources.jank_types
import com.androidperformancestudio.frame.presentation.generated.resources.largest_reported_stage
import com.androidperformancestudio.frame.presentation.generated.resources.layout_preview
import com.androidperformancestudio.frame.presentation.generated.resources.layout_preview_current_only
import com.androidperformancestudio.frame.presentation.generated.resources.missed_vsync
import com.androidperformancestudio.frame.presentation.generated.resources.next_frame
import com.androidperformancestudio.frame.presentation.generated.resources.no_frames_match_filter
import com.androidperformancestudio.frame.presentation.generated.resources.no_jank_clusters_detected
import com.androidperformancestudio.frame.presentation.generated.resources.no_problem_frames
import com.androidperformancestudio.frame.presentation.generated.resources.open_trace_in_perfetto
import com.androidperformancestudio.frame.presentation.generated.resources.p50
import com.androidperformancestudio.frame.presentation.generated.resources.p95
import com.androidperformancestudio.frame.presentation.generated.resources.page
import com.androidperformancestudio.frame.presentation.generated.resources.platform_jank
import com.androidperformancestudio.frame.presentation.generated.resources.platform_jank_rate
import com.androidperformancestudio.frame.presentation.generated.resources.previous_frame
import com.androidperformancestudio.frame.presentation.generated.resources.problem_frames
import com.androidperformancestudio.frame.presentation.generated.resources.refresh_rate
import com.androidperformancestudio.frame.presentation.generated.resources.render_thread
import com.androidperformancestudio.frame.presentation.generated.resources.select_a_debuggable_process_framemetrics_agent_is_preferred_and_gfxinf
import com.androidperformancestudio.frame.presentation.generated.resources.source
import com.androidperformancestudio.frame.presentation.generated.resources.stage_breakdown
import com.androidperformancestudio.frame.presentation.generated.resources.stage_investigation_hint
import com.androidperformancestudio.frame.presentation.generated.resources.state_detail
import com.androidperformancestudio.frame.presentation.generated.resources.surface_flinger_jank
import com.androidperformancestudio.frame.presentation.generated.resources.text
import com.androidperformancestudio.frame.presentation.generated.resources.timeline_cluster_marker
import com.androidperformancestudio.frame.presentation.generated.resources.timeline_duration_axis
import com.androidperformancestudio.frame.presentation.generated.resources.timeline_hover_frame
import com.androidperformancestudio.frame.presentation.generated.resources.timeline_next
import com.androidperformancestudio.frame.presentation.generated.resources.timeline_previous
import com.androidperformancestudio.frame.presentation.generated.resources.timeline_visible_range
import com.androidperformancestudio.frame.presentation.generated.resources.timeline_zoom_in
import com.androidperformancestudio.frame.presentation.generated.resources.timeline_zoom_out
import com.androidperformancestudio.frame.presentation.generated.resources.unknown_stage
import com.androidperformancestudio.frame.presentation.generated.resources.verdict
import com.androidperformancestudio.frame.presentation.generated.resources.waiting_for_live_frame_data
import com.androidperformancestudio.frame.presentation.generated.resources.window
import com.androidperformancestudio.frame.presentation.generated.resources.worst
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioCard
import com.androidperformancestudio.ui.studio.StudioPanelHeader
import com.androidperformancestudio.ui.studio.StudioTableColumn
import com.androidperformancestudio.ui.studio.StudioTokens

@Composable
public fun FrameProfilerScreen(
    state: FrameProfilerState,
    actions: FrameProfilerActions,
    language: UiLanguage,
    operationMessage: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(LocalViewerColors.current.workspace)) {
        Box(Modifier.fillMaxSize().padding(StudioTokens.workspacePadding)) {
            AnalysisContent(
                state = state,
                analysis = state.analysis ?: EMPTY_FRAME_ANALYSIS,
                actions = actions,
                language = language,
                operationMessage = operationMessage,
            )
        }
        if (state.isLoading) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun AnalysisContent(
    state: FrameProfilerState,
    analysis: FrameAnalysisResult,
    actions: FrameProfilerActions,
    language: UiLanguage,
    operationMessage: String?,
) {
    val sessionId =
        analysis.frames
            .firstOrNull()
            ?.sample
            ?.sessionId
    var sourceFilter by remember(sessionId) { mutableStateOf<FrameSource?>(null) }
    var frameFilter by remember(sessionId) { mutableStateOf(FrameViewFilter.ALL) }
    val availableSources = remember(analysis) { analysis.frames.map { it.sample.source }.distinct() }
    val filteredAnalysis =
        remember(analysis, sourceFilter, frameFilter, state.isCapturing, state.isLoading) {
            if (state.isCapturing || state.isLoading) analysis else analysis.filtered(sourceFilter, frameFilter)
        }
    val selected =
        filteredAnalysis.frames.firstOrNull { it.sample.frameId == state.selectedFrameId }
            ?: filteredAnalysis.frames.firstOrNull()

    LaunchedEffect(sessionId, sourceFilter, frameFilter, state.selectedFrameId, state.isCapturing, state.isLoading) {
        if (state.isCapturing || state.isLoading) return@LaunchedEffect
        val selectedFrameId = state.selectedFrameId ?: return@LaunchedEffect
        val visibleAnalysis = analysis.filtered(sourceFilter, frameFilter)
        if (visibleAnalysis.frames.none { it.sample.frameId == selectedFrameId }) {
            visibleAnalysis.frames
                .firstOrNull()
                ?.sample
                ?.frameId
                ?.let(actions.onSelectFrame)
        }
    }

    val filters: @Composable () -> Unit = {
        FrameFilterControls(
            sources = availableSources,
            source = sourceFilter,
            onSourceSelected = { sourceFilter = it },
            filter = frameFilter,
            onFilterSelected = { frameFilter = it },
            enabled = !state.isCapturing && !state.isLoading,
            language = language,
        )
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)) {
        SummaryCards(filteredAnalysis, state.observedRefreshRatesHz, language)
        FrameAnalysisWorkspace(
            language = language,
            showContext = filteredAnalysis.attributions.isNotEmpty() || state.warnings.isNotEmpty(),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            timeline = { paneModifier ->
                val timelineModifier = paneModifier.testTag("frame-timeline-panel").padding(StudioTokens.compactContentPadding)
                if (filteredAnalysis.frames.isEmpty()) {
                    Column(timelineModifier.verticalScroll(rememberScrollState())) {
                        FrameTimelineHeading(language = language, filters = filters)
                        Text(
                            localizedStringResource(Res.string.no_frames_match_filter, language),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.analysis == null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text =
                                    if (state.isCapturing) {
                                        localizedStringResource(Res.string.waiting_for_live_frame_data, language)
                                    } else {
                                        localizedStringResource(Res.string.capture_online_or_import_framestats, language)
                                    },
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                localizedStringResource(
                                    Res.string.select_a_debuggable_process_framemetrics_agent_is_preferred_and_gfxinf,
                                    language,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            operationMessage?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
                            state.errorMessage?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                } else {
                    FrameTimeline(
                        frames = filteredAnalysis.frames,
                        selectedFrameId = selected?.sample?.frameId,
                        clusterFrameIds =
                            remember(
                                filteredAnalysis,
                            ) { filteredAnalysis.clusters.flatMapTo(mutableSetOf()) { it.deadlineMissFrameIds } },
                        onSelectFrame = actions.onSelectFrame,
                        language = language,
                        filters = filters,
                        modifier = timelineModifier,
                    )
                }
            },
            clusters = { paneModifier ->
                ClusterList(
                    analysis = filteredAnalysis,
                    selectedFrameId = selected?.sample?.frameId,
                    onSelectFrame = actions.onSelectFrame,
                    language = language,
                    modifier = paneModifier.testTag("frame-clusters-panel"),
                )
            },
            problems = { paneModifier ->
                ProblemFramesTable(
                    analysis = filteredAnalysis,
                    selectedFrameId = selected?.sample?.frameId,
                    onSelectFrame = actions.onSelectFrame,
                    language = language,
                    modifier = paneModifier.testTag("frame-problems-panel"),
                )
            },
            details = { paneModifier ->
                val selectedIndex = filteredAnalysis.frames.indexOfFirst { it.sample.frameId == selected?.sample?.frameId }
                FrameDetail(
                    frame = selected,
                    previousFrameId =
                        filteredAnalysis.frames
                            .getOrNull(selectedIndex - 1)
                            ?.sample
                            ?.frameId,
                    nextFrameId =
                        filteredAnalysis.frames
                            .getOrNull(selectedIndex + 1)
                            ?.sample
                            ?.frameId,
                    language = language,
                    onSelectFrame = actions.onSelectFrame,
                    onInspectLayout = actions.onInspectLayout,
                    onOpenTrace = actions.onOpenTrace,
                    onCopyEvidence = actions.onCopyEvidence,
                    modifier = paneModifier.testTag("frame-detail-panel"),
                )
            },
            context = { paneModifier ->
                Column(
                    paneModifier
                        .testTag("frame-context-panel")
                        .padding(StudioTokens.compactContentPadding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap)) {
                            FrameAttributionSection(filteredAnalysis.attributions, language)
                            state.warnings.forEach { warning ->
                                Text(warning, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun FrameFilterControls(
    sources: List<FrameSource>,
    source: FrameSource?,
    onSourceSelected: (FrameSource?) -> Unit,
    filter: FrameViewFilter,
    onFilterSelected: (FrameViewFilter) -> Unit,
    enabled: Boolean,
    language: UiLanguage,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            localizedStringResource(Res.string.source, language),
            modifier = Modifier.testTag("frame-source-label"),
            color = LocalViewerColors.current.secondaryText,
        )
        Spacer(Modifier.width(8.dp))
        DropdownSelector(
            items = sources,
            selectedItem = source,
            onItemSelected = onSourceSelected,
            itemLabel = { it.displayName(language) },
            selectedItemLabel = { it.displayName(language) },
            placeholder = localizedStringResource(Res.string.all_sources, language),
            onPlaceholderSelected = { onSourceSelected(null) },
            modifier = Modifier.widthIn(min = 150.dp, max = 220.dp).testTag("frame-source-filter"),
            enabled = enabled && sources.isNotEmpty(),
            selectorDescription = localizedStringResource(Res.string.source, language),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            localizedStringResource(Res.string.frame_filter, language),
            modifier = Modifier.testTag("frame-filter-label"),
            color = LocalViewerColors.current.secondaryText,
        )
        Spacer(Modifier.width(8.dp))
        DropdownSelector(
            items = FrameViewFilter.entries,
            selectedItem = filter,
            onItemSelected = onFilterSelected,
            itemLabel = { it.displayName(language) },
            placeholder = localizedStringResource(Res.string.all_frames, language),
            modifier = Modifier.widthIn(min = 150.dp, max = 210.dp).testTag("frame-result-filter"),
            enabled = enabled,
            selectorDescription = localizedStringResource(Res.string.frame_filter, language),
        )
    }
}

@Composable
private fun FrameTimelineHeading(
    language: UiLanguage,
    filters: @Composable () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 760.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StudioTokens.panelGap),
            ) {
                Text(
                    localizedStringResource(Res.string.frame_timeline, language),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(Modifier.weight(1f)) { filters() }
                actions()
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap)) {
                Text(
                    localizedStringResource(Res.string.frame_timeline, language),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                filters()
                actions()
            }
        }
    }
}

@Composable
private fun SummaryCards(
    analysis: FrameAnalysisResult,
    observedRefreshRatesHz: Set<Double>,
    language: UiLanguage,
) {
    val summary = analysis.summary
    val durationSeries = remember(analysis) { analysis.frames.map { it.sample.resolvedDurationNs()?.toDouble() } }
    val deadlineSeries =
        remember(analysis) {
            analysis.frames.map {
                when (it.deadlineVerdict) {
                    FrameDeadlineVerdict.MET -> 0.0
                    FrameDeadlineVerdict.MISSED -> 1.0
                    FrameDeadlineVerdict.UNKNOWN -> null
                }
            }
        }
    val frameRefreshRates =
        remember(analysis) {
            analysis.frames
                .mapNotNull { it.sample.refreshRateHz?.takeIf { rate -> rate > 0.0 } }
                .distinct()
                .sorted()
        }
    val refreshRates =
        if (frameRefreshRates.isNotEmpty()) {
            frameRefreshRates
        } else {
            observedRefreshRatesHz.filter { it > 0.0 }.distinct().sorted()
        }
    val refreshRateText =
        when (refreshRates.size) {
            0 -> "—"
            1 -> "%.0f Hz".format(refreshRates.single())
            else -> "%.0f–%.0f Hz".format(refreshRates.first(), refreshRates.last())
        }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)) {
        FrameMetricCard(
            localizedStringResource(Res.string.frames, language),
            summary.totalFrames.toString(),
            emptyList(),
            MaterialTheme.colorScheme.primary,
            Modifier.weight(1f),
            "frames",
        )
        FrameMetricCard(
            localizedStringResource(Res.string.deadline_miss_rate, language),
            summary.deadlineMissRate?.let { "%.1f%%".format(it * 100.0) } ?: "—",
            deadlineSeries,
            MaterialTheme.colorScheme.error,
            Modifier.weight(1f),
            "deadline",
        )
        FrameMetricCard(
            localizedStringResource(Res.string.p50, language),
            summary.p50DurationNs.formatMillis(),
            durationSeries,
            MaterialTheme.colorScheme.primary,
            Modifier.weight(1f),
            "p50",
        )
        FrameMetricCard(
            localizedStringResource(Res.string.p95, language),
            summary.p95DurationNs.formatMillis(),
            durationSeries,
            MaterialTheme.colorScheme.secondary,
            Modifier.weight(1f),
            "p95",
        )
        FrameMetricCard(
            localizedStringResource(Res.string.worst, language),
            summary.worstDurationNs.formatMillis(),
            durationSeries,
            MaterialTheme.colorScheme.tertiary,
            Modifier.weight(1f),
            "worst",
        )
        FrameMetricCard(
            localizedStringResource(Res.string.platform_jank_rate, language),
            summary.platformJankRate?.let { "%.1f%%".format(it * 100.0) } ?: "—",
            emptyList(),
            MaterialTheme.colorScheme.error,
            Modifier.weight(1f),
            "platform-jank",
        )
        FrameMetricCard(
            localizedStringResource(Res.string.refresh_rate, language),
            refreshRateText,
            emptyList(),
            MaterialTheme.colorScheme.secondary,
            Modifier.weight(1f),
            "refresh-rate",
        )
    }
}

@Composable
private fun FrameAttributionSection(
    attributions: List<FrameAttribution>,
    language: UiLanguage,
) {
    if (attributions.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap)) {
        StudioPanelHeader(localizedStringResource(Res.string.frame_attribution, language))
        attributions.take(5).forEachIndexed { index, attribution ->
            Text(
                localizedStringResource(
                    Res.string.frame_attribution_entry,
                    language,
                    attribution.activityName,
                    attribution.uiState,
                    attribution.platformJankFrames,
                    attribution.totalFrames,
                    attribution.p95DurationNs.formatMillis(),
                ),
                modifier = Modifier.testTag("frame-attribution-entry-$index"),
                style = ViewerTypography.secondary,
                color = LocalViewerColors.current.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FrameMetricCard(
    label: String,
    value: String,
    samples: List<Double?>,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
    metricId: String,
) {
    val colors = LocalViewerColors.current
    StudioCard(
        modifier = modifier,
        containerColor = color.copy(alpha = 0.10f).compositeOver(colors.panel),
        borderColor = color.copy(alpha = 0.5f),
        contentSpacing = 4.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(3.dp).height(40.dp).background(color))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    label,
                    color = color,
                    style = ViewerTypography.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    value,
                    modifier = Modifier.testTag("frame-metric-$metricId"),
                    color = colors.primaryText,
                    style = ViewerTypography.metric,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (samples.isNotEmpty()) MetricSparkline(samples, color)
        }
    }
}

@Composable
private fun MetricSparkline(
    samples: List<Double?>,
    color: androidx.compose.ui.graphics.Color,
) {
    Canvas(Modifier.width(40.dp).height(34.dp)) {
        val visible = samples.takeLast(METRIC_SPARKLINE_SAMPLE_COUNT)
        val values = visible.filterNotNull()
        if (values.isEmpty()) return@Canvas
        val minimum = values.minOrNull() ?: return@Canvas
        val maximum = values.maxOrNull() ?: return@Canvas
        val span = maximum - minimum
        val slotWidth = size.width / visible.size.coerceAtLeast(1)
        visible.forEachIndexed { index, sample ->
            sample?.let { value ->
                val normalized =
                    if (span <= 0.0) {
                        0.55f
                    } else {
                        ((value - minimum) / span).toFloat().coerceIn(0f, 1f)
                    }
                val barHeight = (size.height * (0.18f + normalized * 0.82f)).coerceAtLeast(2f)
                drawRect(
                    color = color.copy(alpha = 0.45f + normalized * 0.5f),
                    topLeft = Offset(index * slotWidth + slotWidth * 0.18f, size.height - barHeight),
                    size = Size((slotWidth * 0.62f).coerceAtLeast(1f), barHeight),
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FrameTimeline(
    frames: List<AnalyzedFrame>,
    selectedFrameId: Long?,
    clusterFrameIds: Set<Long>,
    onSelectFrame: (Long) -> Unit,
    language: UiLanguage,
    filters: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val evidenceColors = LocalViewerColors.current
    var visibleCount by remember(frames) { mutableStateOf(frames.size.coerceIn(1, MAX_TIMELINE_INITIAL_FRAMES)) }
    var firstVisibleIndex by remember(frames) { mutableStateOf(0) }
    var canvasWidth by remember { mutableStateOf(0f) }
    var hoveredIndex by remember(frames) { mutableStateOf<Int?>(null) }
    val window = frameTimelineWindow(frames.size, firstVisibleIndex, visibleCount)
    val selectedIndex = remember(frames, selectedFrameId) { frames.indexOfFirst { it.sample.frameId == selectedFrameId } }
    val visibleFrames = frames.subList(window.startIndex, window.endExclusive)
    val maxDuration = visibleFrames.maxOfOrNull { it.sample.resolvedDurationNs() ?: 0L }?.coerceAtLeast(1L) ?: 1L
    val durationTicksMs = remember(maxDuration) { frameTimelineDurationTicks(maxDuration) }
    val maximumVisibleCount = frames.size.coerceAtMost(MAX_TIMELINE_VISIBLE_FRAMES)

    LaunchedEffect(frames, selectedFrameId) {
        if (selectedIndex >= 0 && selectedIndex !in window.startIndex until window.endExclusive) {
            firstVisibleIndex = centeredFrameTimelineWindow(frames.size, selectedIndex, visibleCount).startIndex
        }
    }
    LaunchedEffect(window) { hoveredIndex = null }

    fun zoomTo(count: Int) {
        val anchor = selectedIndex.takeIf { it in window.startIndex until window.endExclusive } ?: window.startIndex + window.count / 2
        visibleCount = count
        firstVisibleIndex = centeredFrameTimelineWindow(frames.size, anchor, count).startIndex
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FrameTimelineHeading(
            language = language,
            filters = filters,
            actions = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProfilerCompactButton(
                        text = localizedStringResource(Res.string.timeline_previous, language),
                        enabled = window.startIndex > 0,
                        onClick = { firstVisibleIndex = (window.startIndex - window.count).coerceAtLeast(0) },
                    )
                    ProfilerCompactButton(
                        text = localizedStringResource(Res.string.timeline_next, language),
                        enabled = window.endExclusive < frames.size,
                        onClick = { firstVisibleIndex = (window.startIndex + window.count).coerceAtMost(frames.size - window.count) },
                    )
                    ProfilerCompactButton(
                        text = localizedStringResource(Res.string.timeline_zoom_in, language),
                        enabled = window.count > MIN_TIMELINE_VISIBLE_FRAMES,
                        onClick = { zoomTo((window.count / 2).coerceAtLeast(MIN_TIMELINE_VISIBLE_FRAMES)) },
                    )
                    ProfilerCompactButton(
                        text = localizedStringResource(Res.string.timeline_zoom_out, language),
                        enabled = window.count < maximumVisibleCount,
                        onClick = { zoomTo((window.count * 2).coerceAtMost(maximumVisibleCount)) },
                    )
                }
            },
        )
        FrameTimelineLegend(language, evidenceColors.secondaryText)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                localizedStringResource(Res.string.timeline_duration_axis, language),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                localizedStringResource(
                    Res.string.timeline_visible_range,
                    language,
                    if (window.count == 0) 0 else window.startIndex + 1,
                    window.endExclusive,
                    frames.size,
                ),
                modifier = Modifier.testTag("frame-timeline-range"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FrameTimelineAxes(
            durationTicksMs,
            window,
            frames,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            Canvas(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .testTag("frame-timeline-canvas")
                        .onSizeChanged { canvasWidth = it.width.toFloat() }
                        .onPointerEvent(PointerEventType.Move) { event ->
                            val x =
                                event.changes
                                    .lastOrNull()
                                    ?.position
                                    ?.x ?: return@onPointerEvent
                            hoveredIndex = window.indexAt(x, canvasWidth)
                        }.onPointerEvent(PointerEventType.Exit) { hoveredIndex = null }
                        .pointerInput(frames, window) {
                            detectTapGestures { position ->
                                window.indexAt(position.x, size.width.toFloat())?.let { index ->
                                    onSelectFrame(frames[index].sample.frameId)
                                }
                            }
                        },
            ) {
                if (window.count == 0) return@Canvas
                val barWidth = size.width / window.count
                val gridStyle = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                durationTicksMs.forEach { tickMs ->
                    val y = (size.height * (1.0 - tickMs / durationTicksMs.last())).toFloat()
                    drawLine(evidenceColors.border, Offset(0f, y), Offset(size.width, y), pathEffect = gridStyle)
                }
                window.axisIndices().forEach { frameIndex ->
                    val x = (frameIndex - window.startIndex + 0.5f) * barWidth
                    drawLine(evidenceColors.border, Offset(x, 0f), Offset(x, size.height), pathEffect = gridStyle)
                }
                drawLine(evidenceColors.border, Offset.Zero, Offset(0f, size.height))
                visibleFrames.forEachIndexed { index, frame ->
                    val duration = frame.sample.resolvedDurationNs() ?: 0L
                    val height = (duration / NANOS_PER_MILLISECOND / durationTicksMs.last() * size.height).toFloat().coerceAtLeast(2f)
                    val color = frame.sample.frameTimelineStatus().color(evidenceColors.secondaryText)
                    drawRect(
                        color = color,
                        topLeft = Offset(index * barWidth, size.height - height),
                        size = Size((barWidth - 1f).coerceAtLeast(1f), height),
                    )
                    if (frame.sample.frameId in clusterFrameIds) {
                        drawRect(
                            color = colors.error,
                            topLeft = Offset(index * barWidth, 0f),
                            size = Size(barWidth.coerceAtLeast(1f), 4f),
                        )
                    }
                    if (frame.sample.frameId == selectedFrameId) {
                        drawLine(
                            color = colors.onSurface,
                            start = Offset(index * barWidth + barWidth / 2f, 0f),
                            end = Offset(index * barWidth + barWidth / 2f, size.height),
                            strokeWidth = 2f,
                        )
                    }
                }
            }
        }
        val clusterHint =
            if (clusterFrameIds.isNotEmpty()) localizedStringResource(Res.string.timeline_cluster_marker, language) else ""
        val hoverText =
            frames.getOrNull(hoveredIndex ?: -1)?.let { frame ->
                localizedStringResource(
                    Res.string.timeline_hover_frame,
                    language,
                    frame.sample.frameId,
                    frame.sample.resolvedDurationNs().formatMillis(),
                    frame.deadlineVerdict.name,
                )
            }
        Text(
            text = listOfNotNull(clusterHint.takeIf { it.isNotEmpty() }, hoverText).joinToString("  |  "),
            modifier = Modifier.fillMaxWidth().testTag("frame-timeline-caption"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val MAX_TIMELINE_INITIAL_FRAMES = 500
private const val MAX_TIMELINE_VISIBLE_FRAMES = 1_000
private const val MIN_TIMELINE_VISIBLE_FRAMES = 20
private const val METRIC_SPARKLINE_SAMPLE_COUNT = 32
private val EMPTY_FRAME_ANALYSIS = FrameAnalysisResult(emptyList(), FrameSummary(), emptyList())

@Composable
private fun FrameDetail(
    frame: AnalyzedFrame?,
    previousFrameId: Long?,
    nextFrameId: Long?,
    language: UiLanguage,
    onSelectFrame: (Long) -> Unit,
    onInspectLayout: (com.androidperformancestudio.frame.model.FrameSample) -> Unit,
    onOpenTrace: ((FrameSample) -> Unit)?,
    onCopyEvidence: ((String) -> Boolean)?,
    modifier: Modifier,
) {
    var copySucceeded by remember(frame?.sample?.sessionId, frame?.sample?.frameId) { mutableStateOf<Boolean?>(null) }
    Box(modifier.padding(StudioTokens.compactContentPadding)) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        localizedStringResource(Res.string.frame_detail, language),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    frame?.let {
                        Text(
                            "#${it.sample.frameId} · ${it.sample.source.displayName(language)}",
                            style = ViewerTypography.dense,
                            color = LocalViewerColors.current.secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                TextButton(
                    onClick = { previousFrameId?.let(onSelectFrame) },
                    enabled = previousFrameId != null,
                ) {
                    Text(localizedStringResource(Res.string.previous_frame, language))
                }
                TextButton(
                    onClick = { nextFrameId?.let(onSelectFrame) },
                    enabled = nextFrameId != null,
                ) {
                    Text(localizedStringResource(Res.string.next_frame, language))
                }
            }
            if (frame == null) return@Column
            val colors = LocalViewerColors.current
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    Modifier.weight(1.2f).fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DetailMetric(
                            localizedStringResource(Res.string.duration, language),
                            frame.sample.resolvedDurationNs().formatMillis(),
                            Modifier.weight(1f),
                            if (frame.deadlineVerdict ==
                                FrameDeadlineVerdict.MISSED
                            ) {
                                MaterialTheme.colorScheme.error
                            } else {
                                colors.primaryText
                            },
                        )
                        DetailMetric(
                            localizedStringResource(Res.string.budget, language),
                            frame.sample.displayBudget(),
                            Modifier.weight(1f).testTag("frame-selected-budget"),
                            colors.primaryText,
                        )
                    }
                    HorizontalDivider(color = colors.border, modifier = Modifier.testTag("frame-detail-metrics-divider"))
                    DetailRow(localizedStringResource(Res.string.budget_source, language), frame.sample.expectedDurationSource.name)
                    DetailRow(localizedStringResource(Res.string.verdict, language), frame.deadlineVerdict.name)
                    DetailRow(localizedStringResource(Res.string.missed_vsync, language), frame.missedVsyncCount?.toString() ?: "—")
                    DetailRow(localizedStringResource(Res.string.platform_jank, language), frame.sample.platformJank?.toString() ?: "—")
                    frame.sample.frameTimelineVsyncId?.let {
                        DetailRow(localizedStringResource(Res.string.frame_timeline_vsync_id, language), it.toString())
                    } ?: DetailRow(localizedStringResource(Res.string.frame_timeline_vsync_id, language), "—")
                    DetailRow(localizedStringResource(Res.string.largest_reported_stage, language), frame.largestReportedStage ?: "—")
                    val stageValues = frame.sample.stages.values()
                    if (stageValues.isNotEmpty()) {
                        val maximumStage = stageValues.maxOf { it.second }.coerceAtLeast(1L)
                        Text(
                            localizedStringResource(Res.string.stage_breakdown, language),
                            style = ViewerTypography.subsectionTitle,
                            fontWeight = FontWeight.SemiBold,
                        )
                        stageValues.forEachIndexed { index, (name, duration) ->
                            FrameStageRow(name, duration, maximumStage, index)
                        }
                    }
                    DetailRow(localizedStringResource(Res.string.source, language), frame.sample.source.displayName(language))
                    frame.sample.activityName?.let {
                        DetailRow(
                            localizedStringResource(Res.string.activity, language),
                            it.substringAfterLast('.'),
                        )
                    }
                    frame.sample.fragmentName?.let { DetailRow(localizedStringResource(Res.string.fragment, language), it) }
                    frame.sample.pageName?.let { DetailRow(localizedStringResource(Res.string.page, language), it) }
                    frame.sample.interactionState?.let { DetailRow(localizedStringResource(Res.string.interaction_state, language), it) }
                    frame.sample.renderThreadName?.let { DetailRow(localizedStringResource(Res.string.render_thread, language), it) }
                    frame.sample.windowId?.let { DetailRow(localizedStringResource(Res.string.window, language), it) }
                    frame.sample.surfaceFlingerJankType?.let {
                        DetailRow(
                            localizedStringResource(Res.string.surface_flinger_jank, language),
                            it,
                        )
                    }
                    frame.sample.jankStatsJank?.let { DetailRow(localizedStringResource(Res.string.jank_stats, language), it.toString()) }
                    if (frame.sample.jankStatsReasons.isNotEmpty()) {
                        DetailRow(
                            localizedStringResource(Res.string.jank_stats_reasons, language),
                            frame.sample.jankStatsReasons.joinToString(),
                        )
                    }
                    frame.largestReportedStage?.let { stage ->
                        Text(
                            localizedStringResource(Res.string.stage_investigation_hint, language, stage),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (frame.platformJankTypes.isNotEmpty()) {
                        DetailRow(
                            localizedStringResource(Res.string.jank_types, language),
                            frame.platformJankTypes.joinToString { it.name },
                        )
                    }
                    frame.sample.states.forEach { (key, value) ->
                        DetailRow(localizedStringResource(Res.string.state_detail, language, key), value)
                    }
                    onOpenTrace?.let { openTrace ->
                        ProfilerCompactButton(
                            text = localizedStringResource(Res.string.open_trace_in_perfetto, language),
                            onClick = { openTrace(frame.sample) },
                        )
                    }
                    onCopyEvidence?.let { copy ->
                        ProfilerCompactButton(
                            text = localizedStringResource(Res.string.copy_evidence, language),
                            onClick = { copySucceeded = copy(frame.copyEvidenceText(language)) },
                        )
                        Text(
                            localizedStringResource(Res.string.copy_evidence_privacy_hint, language),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        copySucceeded?.let { succeeded ->
                            Text(
                                localizedStringResource(
                                    if (succeeded) Res.string.evidence_copied else Res.string.clipboard_unavailable,
                                    language,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (succeeded) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                FrameLayoutPreview(
                    frame = frame,
                    language = language,
                    onInspectLayout = onInspectLayout,
                    modifier = Modifier.weight(0.8f).fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun FrameLayoutPreview(
    frame: AnalyzedFrame,
    language: UiLanguage,
    onInspectLayout: (FrameSample) -> Unit,
    modifier: Modifier,
) {
    val colors = LocalViewerColors.current
    BoxWithConstraints(modifier.clipToBounds().testTag("frame-layout-preview-pane")) {
        val contentHeight = maxOf(maxHeight, 240.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).height(contentHeight),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                localizedStringResource(Res.string.layout_preview, language),
                style = ViewerTypography.subsectionTitle,
                fontWeight = FontWeight.SemiBold,
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(0.62f)
                        .testTag("frame-layout-preview-image")
                        .background(colors.previewSurface)
                        .border(1.dp, colors.border),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            localizedStringResource(Res.string.frame_screenshot_unavailable, language),
                            color = colors.secondaryText,
                            style = ViewerTypography.dense,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        frame.sample.layoutSnapshotId?.let {
                            Text(
                                it,
                                color = colors.mutedText,
                                style = ViewerTypography.dense,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Text(
                localizedStringResource(Res.string.layout_preview_current_only, language),
                style = ViewerTypography.dense,
                color = colors.secondaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (frame.sample.packageName != null) {
                OutlinedButton(
                    onClick = { onInspectLayout(frame.sample) },
                    modifier = Modifier.fillMaxWidth().testTag("frame-layout-preview-action"),
                ) {
                    Text(
                        localizedStringResource(Res.string.correlate_in_layout_inspector, language),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailMetric(
    label: String,
    value: String,
    modifier: Modifier,
    valueColor: androidx.compose.ui.graphics.Color,
) {
    val colors = LocalViewerColors.current
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = colors.secondaryText, style = ViewerTypography.bodyCompact, maxLines = 1)
        Text(value, color = valueColor, style = ViewerTypography.bodyCompact, maxLines = 1)
    }
}

@Composable
private fun FrameStageRow(
    name: String,
    durationNs: Long,
    maximumStageNs: Long,
    index: Int,
) {
    val colors = LocalViewerColors.current
    val barColor =
        when (index % 4) {
            0 -> MaterialTheme.colorScheme.primary
            1 -> MaterialTheme.colorScheme.tertiary
            2 -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.secondary
        }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                name,
                modifier = Modifier.widthIn(min = 72.dp, max = 104.dp),
                style = ViewerTypography.bodyCompact,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.weight(1f).height(7.dp).background(colors.sectionBackground)) {
                Box(
                    Modifier
                        .fillMaxWidth((durationNs.toDouble() / maximumStageNs).toFloat().coerceIn(0f, 1f))
                        .fillMaxSize()
                        .background(barColor),
                )
            }
            Text(
                durationNs.formatMillis(),
                modifier = Modifier.widthIn(min = 48.dp, max = 72.dp),
                style = ViewerTypography.bodyCompact,
                maxLines = 1,
            )
        }
        HorizontalDivider(color = colors.border, modifier = Modifier.testTag("frame-stage-divider-$index"))
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = ViewerTypography.bodyCompact,
            )
            Text(
                value,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Medium,
                style = ViewerTypography.bodyCompact,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        HorizontalDivider(color = LocalViewerColors.current.border, modifier = Modifier.testTag("frame-detail-divider-$label"))
    }
}

@Composable
private fun ClusterList(
    analysis: FrameAnalysisResult,
    selectedFrameId: Long?,
    onSelectFrame: (Long) -> Unit,
    language: UiLanguage,
    modifier: Modifier,
) {
    val framesById = remember(analysis) { analysis.frames.associateBy { it.sample.frameId } }
    Column(
        modifier.padding(StudioTokens.compactContentPadding),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap),
    ) {
        StudioPanelHeader(
            title = localizedStringResource(Res.string.jank_clusters, language),
            subtitle = localizedStringResource(Res.string.cluster_count, language, analysis.clusters.size),
        )
        if (analysis.clusters.isEmpty()) {
            Text(
                localizedStringResource(Res.string.no_jank_clusters_detected, language),
                color = LocalViewerColors.current.secondaryText,
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(analysis.clusters, key = { it.id }) { cluster ->
                    val target = remember(analysis, cluster) { analysis.clusterTargetFrameId(cluster) }
                    val missFrames = cluster.deadlineMissFrameIds.takeLast(CLUSTER_SPARKLINE_FRAME_COUNT).mapNotNull(framesById::get)
                    ClusterRow(
                        cluster = cluster,
                        missFrames = missFrames,
                        targetFrameId = target,
                        selected = selectedFrameId != null && selectedFrameId in cluster.deadlineMissFrameIds,
                        onSelectFrame = onSelectFrame,
                        language = language,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProblemFramesTable(
    analysis: FrameAnalysisResult,
    selectedFrameId: Long?,
    onSelectFrame: (Long) -> Unit,
    language: UiLanguage,
    modifier: Modifier,
) {
    val problemFrames = remember(analysis) { analysis.frames.filter(AnalyzedFrame::isProblemFrame) }
    val problemListState = rememberLazyListState()
    LaunchedEffect(problemFrames, selectedFrameId) {
        val selectedIndex = problemFrames.indexOfFirst { it.sample.frameId == selectedFrameId }
        if (selectedIndex >= 0 && selectedIndex !in problemListState.layoutInfo.visibleItemsInfo.map { it.index }) {
            problemListState.scrollToItem(selectedIndex)
        }
    }
    val columns =
        listOf(
            StudioTableColumn(localizedStringResource(Res.string.frame, language), 0.55f),
            StudioTableColumn(localizedStringResource(Res.string.duration, language), 0.8f),
            StudioTableColumn(localizedStringResource(Res.string.frame_signal, language), 1.2f),
            StudioTableColumn(localizedStringResource(Res.string.activity, language), 1f),
            StudioTableColumn(localizedStringResource(Res.string.largest_reported_stage, language), 1f),
        )
    Column(
        modifier.padding(StudioTokens.compactContentPadding),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap),
    ) {
        StudioPanelHeader(localizedStringResource(Res.string.problem_frames, language))
        Row(
            Modifier
                .fillMaxWidth()
                .background(LocalViewerColors.current.sectionBackground)
                .padding(horizontal = StudioTokens.compactContentPadding, vertical = 6.dp),
        ) {
            columns.forEach { column ->
                Text(
                    column.label,
                    Modifier.weight(column.weight),
                    color = LocalViewerColors.current.secondaryText,
                    style = ViewerTypography.label,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (problemFrames.isEmpty()) {
            Text(
                localizedStringResource(Res.string.no_problem_frames, language),
                color = LocalViewerColors.current.secondaryText,
            )
        } else {
            LazyColumn(
                state = problemListState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(problemFrames, key = { it.sample.frameId }) { frame ->
                    val selected = frame.sample.frameId == selectedFrameId
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selected) {
                                        LocalViewerColors.current.accent.copy(
                                            alpha = 0.12f,
                                        )
                                    } else {
                                        LocalViewerColors.current.transparent
                                    },
                                ).clickable { onSelectFrame(frame.sample.frameId) }
                                .padding(horizontal = StudioTokens.compactContentPadding, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "#${frame.sample.frameId}",
                            modifier = Modifier.weight(0.55f).testTag("problem-frame-row-${frame.sample.frameId}"),
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(frame.sample.resolvedDurationNs().formatMillis(), modifier = Modifier.weight(0.8f), maxLines = 1)
                        TooltipArea(
                            modifier = Modifier.weight(1.2f),
                            tooltip = {
                                val tooltipColors = LocalViewerColors.current
                                Box(
                                    Modifier
                                        .widthIn(min = 220.dp, max = 420.dp)
                                        .background(
                                            tooltipColors.previewSurface,
                                            RoundedCornerShape(StudioTokens.smallRadius),
                                        ).border(
                                            1.dp,
                                            tooltipColors.accent.copy(alpha = 0.7f),
                                            RoundedCornerShape(StudioTokens.smallRadius),
                                        ).padding(10.dp),
                                ) {
                                    SelectionContainer {
                                        Text(
                                            frame.problemEvidence(language),
                                            style = ViewerTypography.dense,
                                            color = tooltipColors.primaryText,
                                        )
                                    }
                                }
                            },
                        ) {
                            Text(
                                frame.problemSignal(language),
                                modifier =
                                    Modifier
                                        .testTag("problem-frame-signal-${frame.sample.frameId}")
                                        .semantics { contentDescription = frame.problemEvidence(language) },
                                color = LocalViewerColors.current.secondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            frame.sample.activityName?.substringAfterLast('.') ?: "—",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            frame.largestReportedStage ?: "—",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClusterRow(
    cluster: DeadlineMissCluster,
    missFrames: List<AnalyzedFrame>,
    targetFrameId: Long?,
    selected: Boolean,
    onSelectFrame: (Long) -> Unit,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    val severityColor =
        when (cluster.worstSeverity) {
            JankSeverity.MINOR -> colors.success
            JankSeverity.MAJOR -> colors.warning
            JankSeverity.SEVERE, JankSeverity.FROZEN -> colors.error
            JankSeverity.SMOOTH, JankSeverity.UNKNOWN -> colors.secondaryText
        }
    val selectionModifier =
        if (targetFrameId == null) {
            Modifier
        } else {
            Modifier.selectable(
                selected = selected,
                onClick = { onSelectFrame(targetFrameId) },
                role = Role.RadioButton,
            )
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (selected) colors.accent.copy(alpha = 0.12f) else colors.transparent)
                .then(selectionModifier)
                .padding(horizontal = StudioTokens.compactContentPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(28.dp).height(28.dp).background(severityColor), contentAlignment = Alignment.Center) {
            Text(
                "#${cluster.id + 1}",
                color = MaterialTheme.colorScheme.onError,
                fontSize = ViewerTypography.dense.fontSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                localizedStringResource(Res.string.text, language, cluster.firstFrameId, cluster.lastFrameId),
                color = colors.primaryText,
                fontSize = ViewerTypography.label.fontSize,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                localizedStringResource(
                    Res.string.jank_cluster_summary,
                    language,
                    cluster.deadlineMissFrameIds.size,
                    cluster.dominantReportedStage ?: localizedStringResource(Res.string.unknown_stage, language),
                ),
                color = colors.secondaryText,
                fontSize = ViewerTypography.dense.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${cluster.worstSeverity.name} · ${cluster.durationNs.formatMillis()}",
                color = severityColor,
                fontSize = ViewerTypography.dense.fontSize,
                maxLines = 1,
            )
        }
        ClusterSparkline(missFrames, severityColor)
    }
}

@Composable
private fun ClusterSparkline(
    frames: List<AnalyzedFrame>,
    color: androidx.compose.ui.graphics.Color,
) {
    Canvas(Modifier.width(54.dp).height(32.dp)) {
        val durations = frames.mapNotNull { it.sample.resolvedDurationNs() }
        val maximum = durations.maxOrNull()?.coerceAtLeast(1L) ?: return@Canvas
        val barWidth = size.width / (durations.size * 1.5f)
        durations.forEachIndexed { index, duration ->
            val height = (duration.toFloat() / maximum * size.height).coerceAtLeast(2f)
            drawRect(
                color = color,
                topLeft = Offset(index * barWidth * 1.5f, size.height - height),
                size = Size(barWidth, height),
            )
        }
    }
}

internal fun FrameAnalysisResult.clusterTargetFrameId(cluster: DeadlineMissCluster): Long? =
    cluster.deadlineMissFrameIds.firstOrNull { id ->
        frames.any { it.sample.frameId == id && it.deadlineVerdict == FrameDeadlineVerdict.MISSED }
    }

private const val CLUSTER_SPARKLINE_FRAME_COUNT = 8

internal fun Long?.formatMillis(): String = this?.let { "%.2f ms".format(it / NANOS_PER_MILLISECOND) } ?: "—"

internal fun FrameSample.displayBudget(): String = knownExpectedDurationNs().formatMillis()

private const val NANOS_PER_MILLISECOND = 1_000_000.0
