@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
    "LongMethod",
    "MagicNumber",
    "MaxLineLength",
    "TooManyFunctions",
    "ktlint:standard:function-naming",
)

package com.androidperformancestudio.frame.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.frame.analysis.AnalyzedFrame
import com.androidperformancestudio.frame.analysis.DeadlineMissCluster
import com.androidperformancestudio.frame.analysis.FrameAnalysisResult
import com.androidperformancestudio.frame.analysis.FrameAttribution
import com.androidperformancestudio.frame.analysis.FrameDeadlineVerdict
import com.androidperformancestudio.frame.analysis.JankSeverity
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.presentation.generated.resources.Res
import com.androidperformancestudio.frame.presentation.generated.resources.activity
import com.androidperformancestudio.frame.presentation.generated.resources.budget
import com.androidperformancestudio.frame.presentation.generated.resources.budget_source
import com.androidperformancestudio.frame.presentation.generated.resources.capture_online_or_import_framestats
import com.androidperformancestudio.frame.presentation.generated.resources.clipboard_unavailable
import com.androidperformancestudio.frame.presentation.generated.resources.cluster_evidence
import com.androidperformancestudio.frame.presentation.generated.resources.cluster_range
import com.androidperformancestudio.frame.presentation.generated.resources.cluster_severity
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
import com.androidperformancestudio.frame.presentation.generated.resources.missed_vsync
import com.androidperformancestudio.frame.presentation.generated.resources.no_jank_clusters_detected
import com.androidperformancestudio.frame.presentation.generated.resources.open_trace_in_perfetto
import com.androidperformancestudio.frame.presentation.generated.resources.opens_the_current_foreground_layout_for_timing_correlation_it_does
import com.androidperformancestudio.frame.presentation.generated.resources.p50
import com.androidperformancestudio.frame.presentation.generated.resources.p95
import com.androidperformancestudio.frame.presentation.generated.resources.page
import com.androidperformancestudio.frame.presentation.generated.resources.platform_jank
import com.androidperformancestudio.frame.presentation.generated.resources.platform_jank_rate
import com.androidperformancestudio.frame.presentation.generated.resources.render_thread
import com.androidperformancestudio.frame.presentation.generated.resources.select_a_debuggable_process_framemetrics_agent_is_preferred_and_gfxinf
import com.androidperformancestudio.frame.presentation.generated.resources.source
import com.androidperformancestudio.frame.presentation.generated.resources.stage_investigation_hint
import com.androidperformancestudio.frame.presentation.generated.resources.state_detail
import com.androidperformancestudio.frame.presentation.generated.resources.surface_flinger_jank
import com.androidperformancestudio.frame.presentation.generated.resources.text
import com.androidperformancestudio.frame.presentation.generated.resources.timeline_cluster_marker
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
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioDataTable
import com.androidperformancestudio.ui.studio.StudioMetricCard
import com.androidperformancestudio.ui.studio.StudioPanel
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
    Box(modifier.fillMaxSize()) {
        val analysis = state.analysis
        if (analysis == null) {
            EmptyState(state = state, language = language, operationMessage = operationMessage)
        } else {
            AnalysisContent(
                state = state,
                analysis = analysis,
                actions = actions,
                language = language,
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
private fun EmptyState(
    state: FrameProfilerState,
    language: UiLanguage,
    operationMessage: String?,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text =
                if (state.isCapturing) {
                    localizedStringResource(Res.string.waiting_for_live_frame_data, language)
                } else {
                    localizedStringResource(Res.string.capture_online_or_import_framestats, language)
                },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text =
                localizedStringResource(Res.string.select_a_debuggable_process_framemetrics_agent_is_preferred_and_gfxinf, language),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        operationMessage?.let { Text(it, modifier = Modifier.padding(top = 16.dp)) }
        state.errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
private fun AnalysisContent(
    state: FrameProfilerState,
    analysis: FrameAnalysisResult,
    actions: FrameProfilerActions,
    language: UiLanguage,
) {
    val selected =
        analysis.frames.firstOrNull { it.sample.frameId == state.selectedFrameId }
            ?: analysis.frames.firstOrNull()
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.importedFileName?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        state.artifact?.let { artifact ->
            Text(
                "Artifact: ${artifact.completeness.name.lowercase()} · ${artifact.availableCapabilities.size} capabilities" +
                    if (artifact.limitations.isEmpty()) "" else " · ${artifact.limitations.size} limitation(s)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SummaryCards(analysis, language)
        FrameAttributionSection(analysis.attributions, language)
        state.warnings.forEach { warning ->
            Text(warning, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
        StudioPanel(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp)) {
                Text(
                    localizedStringResource(Res.string.frame_timeline, language),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                FrameTimeline(
                    frames = analysis.frames,
                    selectedFrameId = selected?.sample?.frameId,
                    clusterFrameIds = remember(analysis) { analysis.clusters.flatMapTo(mutableSetOf()) { it.deadlineMissFrameIds } },
                    onSelectFrame = actions.onSelectFrame,
                    language = language,
                )
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ClusterList(
                analysis = analysis,
                selectedFrameId = selected?.sample?.frameId,
                onSelectFrame = actions.onSelectFrame,
                language = language,
                modifier = Modifier.weight(1.2f).fillMaxSize(),
            )
            FrameDetail(
                frame = selected,
                language = language,
                onInspectLayout = actions.onInspectLayout,
                onOpenTrace = actions.onOpenTrace,
                onCopyEvidence = actions.onCopyEvidence,
                modifier = Modifier.weight(1f).fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SummaryCards(
    analysis: FrameAnalysisResult,
    language: UiLanguage,
) {
    val summary = analysis.summary
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioMetricCard(localizedStringResource(Res.string.frames, language), summary.totalFrames.toString(), Modifier.weight(1f))
        StudioMetricCard(
            localizedStringResource(Res.string.deadline_miss_rate, language),
            summary.deadlineMissRate.formatRate(),
            Modifier.weight(1f),
        )
        StudioMetricCard(
            localizedStringResource(Res.string.platform_jank_rate, language),
            summary.platformJankRate.formatRate(),
            Modifier.weight(1f),
        )
        StudioMetricCard(localizedStringResource(Res.string.p50, language), summary.p50DurationNs.formatMillis(), Modifier.weight(1f))
        StudioMetricCard(localizedStringResource(Res.string.p95, language), summary.p95DurationNs.formatMillis(), Modifier.weight(1f))
        StudioMetricCard(localizedStringResource(Res.string.worst, language), summary.worstDurationNs.formatMillis(), Modifier.weight(1f))
    }
}

@Composable
private fun FrameAttributionSection(
    attributions: List<FrameAttribution>,
    language: UiLanguage,
) {
    if (attributions.isEmpty()) return
    StudioPanel(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                localizedStringResource(Res.string.frame_attribution, language),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            attributions.take(5).forEach { attribution ->
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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
) {
    val colors = MaterialTheme.colorScheme
    var visibleCount by remember(frames) { mutableStateOf(frames.size.coerceIn(1, MAX_TIMELINE_INITIAL_FRAMES)) }
    var firstVisibleIndex by remember(frames) { mutableStateOf(0) }
    var canvasWidth by remember { mutableStateOf(0f) }
    var hoveredIndex by remember(frames) { mutableStateOf<Int?>(null) }
    val window = frameTimelineWindow(frames.size, firstVisibleIndex, visibleCount)
    val selectedIndex = remember(frames, selectedFrameId) { frames.indexOfFirst { it.sample.frameId == selectedFrameId } }
    val visibleFrames = frames.subList(window.startIndex, window.endExclusive)
    val maxDuration = visibleFrames.maxOfOrNull { it.sample.resolvedDurationNs() ?: 0L }?.coerceAtLeast(1L) ?: 1L
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

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = window.startIndex > 0,
                onClick = { firstVisibleIndex = (window.startIndex - window.count).coerceAtLeast(0) },
            ) { Text(localizedStringResource(Res.string.timeline_previous, language)) }
            OutlinedButton(
                enabled = window.count > MIN_TIMELINE_VISIBLE_FRAMES,
                onClick = { zoomTo((window.count / 2).coerceAtLeast(MIN_TIMELINE_VISIBLE_FRAMES)) },
            ) { Text(localizedStringResource(Res.string.timeline_zoom_in, language)) }
            OutlinedButton(
                enabled = window.count < maximumVisibleCount,
                onClick = { zoomTo((window.count * 2).coerceAtMost(maximumVisibleCount)) },
            ) { Text(localizedStringResource(Res.string.timeline_zoom_out, language)) }
            OutlinedButton(
                enabled = window.endExclusive < frames.size,
                onClick = { firstVisibleIndex = (window.startIndex + window.count).coerceAtMost(frames.size - window.count) },
            ) { Text(localizedStringResource(Res.string.timeline_next, language)) }
        }
        Text(
            localizedStringResource(
                Res.string.timeline_visible_range,
                language,
                if (window.count == 0) 0 else window.startIndex + 1,
                window.endExclusive,
                frames.size,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(144.dp)
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
            visibleFrames.forEachIndexed { index, frame ->
                val duration = frame.sample.resolvedDurationNs() ?: 0L
                val height = (duration.toFloat() / maxDuration * size.height).coerceAtLeast(2f)
                val color =
                    when (frame.severity) {
                        JankSeverity.SMOOTH -> colors.tertiary
                        JankSeverity.MINOR -> colors.secondary
                        JankSeverity.MAJOR -> colors.errorContainer
                        JankSeverity.SEVERE, JankSeverity.FROZEN -> colors.error
                        JankSeverity.UNKNOWN -> colors.outline
                    }
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
        frames.getOrNull(hoveredIndex ?: -1)?.let { frame ->
            Text(
                localizedStringResource(
                    Res.string.timeline_hover_frame,
                    language,
                    frame.sample.frameId,
                    frame.sample.resolvedDurationNs().formatMillis(),
                    frame.deadlineVerdict.name,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (clusterFrameIds.isNotEmpty()) {
            Text(
                localizedStringResource(Res.string.timeline_cluster_marker, language),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val MAX_TIMELINE_INITIAL_FRAMES = 500
private const val MAX_TIMELINE_VISIBLE_FRAMES = 1_000
private const val MIN_TIMELINE_VISIBLE_FRAMES = 20

@Composable
private fun FrameDetail(
    frame: AnalyzedFrame?,
    language: UiLanguage,
    onInspectLayout: (com.androidperformancestudio.frame.model.FrameSample) -> Unit,
    onOpenTrace: ((FrameSample) -> Unit)?,
    onCopyEvidence: ((String) -> Boolean)?,
    modifier: Modifier,
) {
    var copySucceeded by remember(frame?.sample?.sessionId, frame?.sample?.frameId) { mutableStateOf<Boolean?>(null) }
    StudioPanel(modifier = modifier) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                localizedStringResource(Res.string.frame_detail, language),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (frame == null) return@Column
            DetailRow(localizedStringResource(Res.string.frame, language), "#${frame.sample.frameId}")
            DetailRow(localizedStringResource(Res.string.source, language), frame.sample.source.name)
            frame.sample.activityName?.let { DetailRow(localizedStringResource(Res.string.activity, language), it.substringAfterLast('.')) }
            frame.sample.fragmentName?.let { DetailRow(localizedStringResource(Res.string.fragment, language), it) }
            frame.sample.pageName?.let { DetailRow(localizedStringResource(Res.string.page, language), it) }
            frame.sample.interactionState?.let { DetailRow(localizedStringResource(Res.string.interaction_state, language), it) }
            frame.sample.renderThreadName?.let { DetailRow(localizedStringResource(Res.string.render_thread, language), it) }
            frame.sample.windowId?.let { DetailRow(localizedStringResource(Res.string.window, language), it) }
            DetailRow(localizedStringResource(Res.string.verdict, language), frame.deadlineVerdict.name)
            DetailRow(localizedStringResource(Res.string.platform_jank, language), frame.sample.platformJank?.toString() ?: "—")
            frame.sample.surfaceFlingerJankType?.let { DetailRow(localizedStringResource(Res.string.surface_flinger_jank, language), it) }
            frame.sample.jankStatsJank?.let { DetailRow(localizedStringResource(Res.string.jank_stats, language), it.toString()) }
            if (frame.sample.jankStatsReasons.isNotEmpty()) {
                DetailRow(localizedStringResource(Res.string.jank_stats_reasons, language), frame.sample.jankStatsReasons.joinToString())
            }
            DetailRow(localizedStringResource(Res.string.duration, language), frame.sample.resolvedDurationNs().formatMillis())
            DetailRow(localizedStringResource(Res.string.budget, language), frame.sample.displayBudget())
            DetailRow(localizedStringResource(Res.string.budget_source, language), frame.sample.expectedDurationSource.name)
            DetailRow(localizedStringResource(Res.string.missed_vsync, language), frame.missedVsyncCount?.toString() ?: "—")
            frame.sample.frameTimelineVsyncId?.let {
                DetailRow(localizedStringResource(Res.string.frame_timeline_vsync_id, language), it.toString())
            }
            DetailRow(localizedStringResource(Res.string.largest_reported_stage, language), frame.largestReportedStage ?: "—")
            frame.largestReportedStage?.let { stage ->
                Text(
                    localizedStringResource(Res.string.stage_investigation_hint, language, stage),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (frame.platformJankTypes.isNotEmpty()) {
                DetailRow(localizedStringResource(Res.string.jank_types, language), frame.platformJankTypes.joinToString { it.name })
            }
            Spacer(Modifier.height(4.dp))
            frame.sample.stages
                .values()
                .forEach { (name, duration) -> DetailRow(name, duration.formatMillis()) }
            frame.sample.states.forEach { (key, value) ->
                DetailRow(localizedStringResource(Res.string.state_detail, language, key), value)
            }
            if (frame.sample.packageName != null) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { onInspectLayout(frame.sample) }) {
                    Text(localizedStringResource(Res.string.correlate_in_layout_inspector, language))
                }
                Text(
                    localizedStringResource(Res.string.opens_the_current_foreground_layout_for_timing_correlation_it_does, language),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            onOpenTrace?.let { openTrace ->
                OutlinedButton(onClick = { openTrace(frame.sample) }) {
                    Text(localizedStringResource(Res.string.open_trace_in_perfetto, language))
                }
            }
            onCopyEvidence?.let { copy ->
                OutlinedButton(onClick = { copySucceeded = copy(frame.copyEvidenceText(language)) }) {
                    Text(localizedStringResource(Res.string.copy_evidence, language))
                }
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
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
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
    StudioDataTable(
        columns =
            listOf(
                StudioTableColumn(localizedStringResource(Res.string.cluster_range, language), CLUSTER_RANGE_WEIGHT),
                StudioTableColumn(localizedStringResource(Res.string.cluster_evidence, language), CLUSTER_EVIDENCE_WEIGHT),
                StudioTableColumn(localizedStringResource(Res.string.cluster_severity, language), CLUSTER_SEVERITY_WEIGHT),
            ),
        modifier = modifier,
        title = localizedStringResource(Res.string.jank_clusters, language),
    ) {
        if (analysis.clusters.isEmpty()) {
            Text(
                localizedStringResource(Res.string.no_jank_clusters_detected, language),
                color = LocalViewerColors.current.secondaryText,
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(analysis.clusters, key = { it.id }) { cluster ->
                    val target = remember(analysis, cluster) { analysis.clusterTargetFrameId(cluster) }
                    ClusterRow(
                        cluster = cluster,
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

@Composable
private fun ClusterRow(
    cluster: DeadlineMissCluster,
    targetFrameId: Long?,
    selected: Boolean,
    onSelectFrame: (Long) -> Unit,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
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
                .padding(horizontal = StudioTokens.compactContentPadding, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            localizedStringResource(Res.string.text, language, cluster.firstFrameId, cluster.lastFrameId),
            modifier = Modifier.weight(CLUSTER_RANGE_WEIGHT),
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
            modifier = Modifier.weight(CLUSTER_EVIDENCE_WEIGHT),
            color = colors.secondaryText,
            fontSize = ViewerTypography.label.fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            cluster.worstSeverity.name,
            modifier = Modifier.weight(CLUSTER_SEVERITY_WEIGHT),
            color = colors.primaryText,
            fontSize = ViewerTypography.label.fontSize,
            maxLines = 1,
        )
    }
}

internal fun FrameAnalysisResult.clusterTargetFrameId(cluster: DeadlineMissCluster): Long? =
    cluster.deadlineMissFrameIds.firstOrNull { id ->
        frames.any { it.sample.frameId == id && it.deadlineVerdict == FrameDeadlineVerdict.MISSED }
    }

private const val CLUSTER_RANGE_WEIGHT = 1.1f
private const val CLUSTER_EVIDENCE_WEIGHT = 2f
private const val CLUSTER_SEVERITY_WEIGHT = 0.9f

internal fun Long?.formatMillis(): String = this?.let { "%.2f ms".format(it / NANOS_PER_MILLISECOND) } ?: "—"

internal fun FrameSample.displayBudget(): String = knownExpectedDurationNs().formatMillis()

private fun Double?.formatRate(): String = this?.let { "%.1f%%".format(it * 100.0) } ?: "—"

private const val NANOS_PER_MILLISECOND = 1_000_000.0
