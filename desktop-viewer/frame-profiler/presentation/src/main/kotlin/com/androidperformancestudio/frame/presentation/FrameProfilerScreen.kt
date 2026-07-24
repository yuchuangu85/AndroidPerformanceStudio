@file:Suppress("FunctionName", "MagicNumber", "MaxLineLength", "TooManyFunctions", "ktlint:standard:function-naming")

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
                    if (chinese) "正在等待设备帧数据" else "Waiting for live frame data"
                } else {
                    if (chinese) "在线采集或导入 FrameStats" else "Capture online or import FrameStats"
                },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text =
                if (chinese) {
                    "选择设备和 debuggable 进程开始采集；优先使用 FrameMetrics Agent，不可用时自动回退 gfxinfo。"
                } else {
                    "Select a debuggable process; FrameMetrics Agent is preferred and gfxinfo is used as fallback."
                },
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
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.importedFileName?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        SummaryCards(analysis, chinese)
        state.warnings.forEach { warning ->
            Text(warning, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (chinese) "帧时间线" else "Frame Timeline",
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
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FrameDetail(selected, chinese, Modifier.weight(1.2f).fillMaxSize())
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
        MetricCard(if (chinese) "总帧数" else "Frames", summary.totalFrames.toString(), Modifier.weight(1f))
        MetricCard(
            if (chinese) "卡顿率" else "Jank Rate",
            "%.1f%%".format(summary.jankRate * 100.0),
            Modifier.weight(1f),
        )
        MetricCard("P50", summary.p50DurationNs.formatMillis(), Modifier.weight(1f))
        MetricCard("P95", summary.p95DurationNs.formatMillis(), Modifier.weight(1f))
        MetricCard(if (chinese) "最差帧" else "Worst", summary.worstDurationNs.formatMillis(), Modifier.weight(1f))
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier,
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp)) {
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
    val smoothColor = Color(0xFF4CAF50)
    val minorColor = Color(0xFFFFB300)
    val majorColor = Color(0xFFF57C00)
    val severeColor = Color(0xFFD32F2F)
    val unknownColor = MaterialTheme.colorScheme.outline
    val selectedColor = MaterialTheme.colorScheme.onSurface
    val maxDuration = frames.maxOfOrNull { it.sample.resolvedDurationNs() ?: 0L }?.coerceAtLeast(1L) ?: 1L
    Canvas(
        modifier =
            Modifier.fillMaxWidth().height(180.dp).pointerInput(frames) {
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
                    JankSeverity.SMOOTH -> smoothColor
                    JankSeverity.MINOR -> minorColor
                    JankSeverity.MAJOR -> majorColor
                    JankSeverity.SEVERE, JankSeverity.FROZEN -> severeColor
                    JankSeverity.UNKNOWN -> unknownColor
                }
            drawRect(
                color = color,
                topLeft = Offset(index * barWidth, size.height - height),
                size = Size((barWidth - 1f).coerceAtLeast(1f), height),
            )
            if (frame.sample.frameId == selectedFrameId) {
                drawLine(
                    color = selectedColor,
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
    modifier: Modifier,
) {
    Card(modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (chinese) "单帧详情" else "Frame Detail",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (frame == null) return@Column
            DetailRow("Frame", "#${frame.sample.frameId}")
            DetailRow(if (chinese) "判定" else "Verdict", frame.verdict.name)
            DetailRow(if (chinese) "耗时" else "Duration", frame.sample.resolvedDurationNs().formatMillis())
            DetailRow(if (chinese) "预算" else "Budget", frame.sample.expectedDurationNs.formatMillis())
            DetailRow(if (chinese) "预算来源" else "Budget Source", frame.sample.expectedDurationSource.name)
            DetailRow(if (chinese) "错过 VSync" else "Missed VSync", frame.missedVsyncCount?.toString() ?: "—")
            DetailRow(if (chinese) "主要阶段" else "Bottleneck", frame.bottleneckStage ?: "—")
            Spacer(Modifier.height(4.dp))
            frame.sample.stages
                .values()
                .forEach { (name, duration) -> DetailRow(name, duration.formatMillis()) }
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
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                if (chinese) "卡顿区间" else "Jank Clusters",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            if (analysis.clusters.isEmpty()) {
                Text(
                    if (chinese) "未检测到卡顿区间" else "No jank clusters detected",
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
                                    RoundedCornerShape(8.dp),
                                ).padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text("#${cluster.firstFrameId} – #${cluster.lastFrameId}", fontWeight = FontWeight.Medium)
                                Text(
                                    "${cluster.jankFrameIds.size} jank · ${cluster.dominantStage ?: "unknown stage"}",
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
