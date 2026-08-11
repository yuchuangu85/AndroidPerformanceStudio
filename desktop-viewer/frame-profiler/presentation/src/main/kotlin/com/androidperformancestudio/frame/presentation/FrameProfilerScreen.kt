@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.frame.analysis.AnalyzedFrame
import com.androidperformancestudio.frame.analysis.FrameAnalysisResult
import com.androidperformancestudio.frame.analysis.JankSeverity
import com.androidperformancestudio.frame.presentation.generated.resources.Res
import com.androidperformancestudio.frame.presentation.generated.resources.activity
import com.androidperformancestudio.frame.presentation.generated.resources.budget
import com.androidperformancestudio.frame.presentation.generated.resources.budget_source
import com.androidperformancestudio.frame.presentation.generated.resources.capture_online_or_import_framestats
import com.androidperformancestudio.frame.presentation.generated.resources.correlate_in_layout_inspector
import com.androidperformancestudio.frame.presentation.generated.resources.deadline_miss_rate
import com.androidperformancestudio.frame.presentation.generated.resources.duration
import com.androidperformancestudio.frame.presentation.generated.resources.frame
import com.androidperformancestudio.frame.presentation.generated.resources.frame_detail
import com.androidperformancestudio.frame.presentation.generated.resources.frame_timeline
import com.androidperformancestudio.frame.presentation.generated.resources.frame_timeline_vsync_id
import com.androidperformancestudio.frame.presentation.generated.resources.frames
import com.androidperformancestudio.frame.presentation.generated.resources.jank_cluster_summary
import com.androidperformancestudio.frame.presentation.generated.resources.jank_clusters
import com.androidperformancestudio.frame.presentation.generated.resources.jank_types
import com.androidperformancestudio.frame.presentation.generated.resources.largest_reported_stage
import com.androidperformancestudio.frame.presentation.generated.resources.missed_vsync
import com.androidperformancestudio.frame.presentation.generated.resources.no_jank_clusters_detected
import com.androidperformancestudio.frame.presentation.generated.resources.opens_the_current_foreground_layout_for_timing_correlation_it_does
import com.androidperformancestudio.frame.presentation.generated.resources.p50
import com.androidperformancestudio.frame.presentation.generated.resources.p95
import com.androidperformancestudio.frame.presentation.generated.resources.platform_jank
import com.androidperformancestudio.frame.presentation.generated.resources.platform_jank_rate
import com.androidperformancestudio.frame.presentation.generated.resources.select_a_debuggable_process_framemetrics_agent_is_preferred_and_gfxinf
import com.androidperformancestudio.frame.presentation.generated.resources.source
import com.androidperformancestudio.frame.presentation.generated.resources.stage_investigation_hint
import com.androidperformancestudio.frame.presentation.generated.resources.state_detail
import com.androidperformancestudio.frame.presentation.generated.resources.text
import com.androidperformancestudio.frame.presentation.generated.resources.unknown_stage
import com.androidperformancestudio.frame.presentation.generated.resources.verdict
import com.androidperformancestudio.frame.presentation.generated.resources.waiting_for_live_frame_data
import com.androidperformancestudio.frame.presentation.generated.resources.window
import com.androidperformancestudio.frame.presentation.generated.resources.worst
import com.androidperformancestudio.ui.ProfilerMetricCard
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import kotlin.math.floor

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
        state.warnings.forEach { warning ->
            Text(warning, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
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
                    onSelectFrame = actions.onSelectFrame,
                )
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FrameDetail(
                frame = selected,
                language = language,
                onInspectLayout = actions.onInspectLayout,
                modifier = Modifier.weight(1.2f).fillMaxSize(),
            )
            ClusterList(analysis, language, Modifier.weight(1f).fillMaxSize())
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
        ProfilerMetricCard(localizedStringResource(Res.string.frames, language), summary.totalFrames.toString(), Modifier.weight(1f))
        ProfilerMetricCard(
            localizedStringResource(Res.string.deadline_miss_rate, language),
            summary.deadlineMissRate.formatRate(),
            Modifier.weight(1f),
        )
        ProfilerMetricCard(
            localizedStringResource(Res.string.platform_jank_rate, language),
            summary.platformJankRate.formatRate(),
            Modifier.weight(1f),
        )
        ProfilerMetricCard(localizedStringResource(Res.string.p50, language), summary.p50DurationNs.formatMillis(), Modifier.weight(1f))
        ProfilerMetricCard(localizedStringResource(Res.string.p95, language), summary.p95DurationNs.formatMillis(), Modifier.weight(1f))
        ProfilerMetricCard(localizedStringResource(Res.string.worst, language), summary.worstDurationNs.formatMillis(), Modifier.weight(1f))
    }
}

@Composable
private fun FrameTimeline(
    frames: List<AnalyzedFrame>,
    selectedFrameId: Long?,
    onSelectFrame: (Long) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val maxDuration = frames.maxOfOrNull { it.sample.resolvedDurationNs() ?: 0L }?.coerceAtLeast(1L) ?: 1L
    Canvas(
        modifier =
            Modifier.fillMaxWidth().height(144.dp).pointerInput(frames) {
                detectTapGestures { position ->
                    if (frames.isNotEmpty()) {
                        val index = floor(position.x / size.width * frames.size).toInt().coerceIn(frames.indices)
                        onSelectFrame(frames[index].sample.frameId)
                    }
                }
            },
    ) {
        if (frames.isEmpty()) return@Canvas
        val barWidth = size.width / frames.size
        frames.forEachIndexed { index, frame ->
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

@Composable
private fun FrameDetail(
    frame: AnalyzedFrame?,
    language: UiLanguage,
    onInspectLayout: (com.androidperformancestudio.frame.model.FrameSample) -> Unit,
    modifier: Modifier,
) {
    Card(modifier = modifier) {
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
            frame.sample.windowId?.let { DetailRow(localizedStringResource(Res.string.window, language), it) }
            DetailRow(localizedStringResource(Res.string.verdict, language), frame.deadlineVerdict.name)
            DetailRow(localizedStringResource(Res.string.platform_jank, language), frame.sample.platformJank?.toString() ?: "—")
            DetailRow(localizedStringResource(Res.string.duration, language), frame.sample.resolvedDurationNs().formatMillis())
            DetailRow(localizedStringResource(Res.string.budget, language), frame.sample.expectedDurationNs.formatMillis())
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
    language: UiLanguage,
    modifier: Modifier,
) {
    Card(modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Text(
                localizedStringResource(Res.string.jank_clusters, language),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            if (analysis.clusters.isEmpty()) {
                Text(
                    localizedStringResource(Res.string.no_jank_clusters_detected, language),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(analysis.clusters, key = { it.id }) { cluster ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainer,
                                    RoundedCornerShape(4.dp),
                                ).padding(6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(
                                    localizedStringResource(
                                        Res.string.text,
                                        language,
                                        cluster.firstFrameId,
                                        cluster.lastFrameId,
                                    ),
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    localizedStringResource(
                                        Res.string.jank_cluster_summary,
                                        language,
                                        cluster.deadlineMissFrameIds.size,
                                        cluster.dominantReportedStage ?: localizedStringResource(Res.string.unknown_stage, language),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(cluster.worstSeverity.name)
                        }
                    }
                }
            }
        }
    }
}

private fun Long?.formatMillis(): String = this?.let { "%.2f ms".format(it / NANOS_PER_MILLISECOND) } ?: "—"

private fun Double?.formatRate(): String = this?.let { "%.1f%%".format(it * 100.0) } ?: "—"

private const val NANOS_PER_MILLISECOND = 1_000_000.0
