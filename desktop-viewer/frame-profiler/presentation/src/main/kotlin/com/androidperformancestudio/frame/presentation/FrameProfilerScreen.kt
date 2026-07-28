@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
    "MagicNumber",
    "MaxLineLength",
    "TooManyFunctions",
    "ktlint:standard:function-naming",
)

package com.androidperformancestudio.frame.presentation

import org.jetbrains.compose.resources.stringResource

import com.androidperformancestudio.frame.presentation.generated.resources.Res
import com.androidperformancestudio.frame.presentation.generated.resources.*

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
import kotlin.math.floor

@Composable
public fun FrameProfilerScreen(
    state: FrameProfilerState,
    actions: FrameProfilerActions,
    chinese: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        val analysis = state.analysis
        if (analysis == null) {
            EmptyState(state = state, chinese = chinese)
        } else {
            AnalysisContent(
                state = state,
                analysis = analysis,
                actions = actions,
                chinese = chinese,
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
    chinese: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text =
                if (state.isCapturing) {
                    stringResource(Res.string.waiting_for_live_frame_data)
                } else {
                    stringResource(Res.string.capture_online_or_import_framestats)
                },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text =
                stringResource(Res.string.select_a_debuggable_process_framemetrics_agent_is_preferred_and_gfxinf),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.operationMessage?.let { Text(it, modifier = Modifier.padding(top = 16.dp)) }
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
    chinese: Boolean,
) {
    val selected =
        analysis.frames.firstOrNull { it.sample.frameId == state.selectedFrameId }
            ?: analysis.frames.firstOrNull()
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.importedFileName?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        SummaryCards(analysis, chinese)
        state.warnings.forEach { warning ->
            Text(warning, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(8.dp)) {
                Text(
                    stringResource(Res.string.frame_timeline),
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
                chinese = chinese,
                onInspectLayout = actions.onInspectLayout,
                modifier = Modifier.weight(1.2f).fillMaxSize(),
            )
            ClusterList(analysis, chinese, Modifier.weight(1f).fillMaxSize())
        }
    }
}

@Composable
private fun SummaryCards(
    analysis: FrameAnalysisResult,
    chinese: Boolean,
) {
    val summary = analysis.summary
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard(stringResource(Res.string.frames), summary.totalFrames.toString(), Modifier.weight(1f))
        MetricCard(
            stringResource(Res.string.jank_rate),
            "%.1f%%".format(summary.jankRate * 100.0),
            Modifier.weight(1f),
        )
        MetricCard(stringResource(Res.string.p50), summary.p50DurationNs.formatMillis(), Modifier.weight(1f))
        MetricCard(stringResource(Res.string.p95), summary.p95DurationNs.formatMillis(), Modifier.weight(1f))
        MetricCard(stringResource(Res.string.worst), summary.worstDurationNs.formatMillis(), Modifier.weight(1f))
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier,
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
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
    chinese: Boolean,
    onInspectLayout: (com.androidperformancestudio.frame.model.FrameSample) -> Unit,
    modifier: Modifier,
) {
    Card(modifier = modifier) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(Res.string.frame_detail),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (frame == null) return@Column
            DetailRow(stringResource(Res.string.frame), "#${frame.sample.frameId}")
            DetailRow(stringResource(Res.string.source), frame.sample.source.name)
            frame.sample.activityName?.let { DetailRow(stringResource(Res.string.activity), it.substringAfterLast('.')) }
            frame.sample.windowId?.let { DetailRow(stringResource(Res.string.window), it) }
            DetailRow(stringResource(Res.string.verdict), frame.verdict.name)
            DetailRow(stringResource(Res.string.platform_jank), frame.sample.platformJank?.toString() ?: "—")
            DetailRow(stringResource(Res.string.duration), frame.sample.resolvedDurationNs().formatMillis())
            DetailRow(stringResource(Res.string.budget), frame.sample.expectedDurationNs.formatMillis())
            DetailRow(stringResource(Res.string.budget_source), frame.sample.expectedDurationSource.name)
            DetailRow(stringResource(Res.string.missed_vsync), frame.missedVsyncCount?.toString() ?: "—")
            DetailRow(stringResource(Res.string.bottleneck), frame.bottleneckStage ?: "—")
            if (frame.jankTypes.isNotEmpty()) {
                DetailRow(stringResource(Res.string.jank_types), frame.jankTypes.joinToString { it.name })
            }
            Spacer(Modifier.height(4.dp))
            frame.sample.stages
                .values()
                .forEach { (name, duration) -> DetailRow(name, duration.formatMillis()) }
            frame.sample.states.forEach { (key, value) ->
                DetailRow(stringResource(Res.string.state_detail, key), value)
            }
            if (frame.sample.packageName != null) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { onInspectLayout(frame.sample) }) {
                    Text(stringResource(Res.string.correlate_in_layout_inspector))
                }
                Text(
                    stringResource(Res.string.opens_the_current_foreground_layout_for_timing_correlation_it_does),
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
    chinese: Boolean,
    modifier: Modifier,
) {
    Card(modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Text(
                stringResource(Res.string.jank_clusters),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            if (analysis.clusters.isEmpty()) {
                Text(
                    stringResource(Res.string.no_jank_clusters_detected),
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
                                Text(stringResource(Res.string.text, cluster.firstFrameId, cluster.lastFrameId), fontWeight = FontWeight.Medium)
                                Text(
                                    stringResource(Res.string.jank_cluster_summary, cluster.jankFrameIds.size, cluster.dominantStage ?: stringResource(Res.string.unknown_stage), ),
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

private const val NANOS_PER_MILLISECOND = 1_000_000.0
