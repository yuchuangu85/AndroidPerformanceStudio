@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
    "LongMethod",
    "MagicNumber",
    "MaxLineLength",
    "TooManyFunctions",
    "ktlint:standard:function-naming",
)

package com.androidperformancestudio.startup.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.startup.analysis.StartupAnalysisResult
import com.androidperformancestudio.startup.model.EvidenceConfidence
import com.androidperformancestudio.startup.model.StartupRun
import com.androidperformancestudio.startup.model.StartupStatistics
import java.util.Locale

@Composable
public fun StartupProfilerScreen(
    state: StartupProfilerState,
    actions: StartupProfilerActions,
    chinese: Boolean,
    modifier: Modifier = Modifier,
) {
    val analysis = state.analysis
    when {
        state.errorMessage != null && analysis == null -> MessagePane(state.errorMessage, MaterialTheme.colorScheme.error, modifier)
        analysis == null -> EmptyPane(state, chinese, modifier)
        else -> ResultsPane(state, analysis, actions, chinese, modifier)
    }
}

@Composable
private fun EmptyPane(
    state: StartupProfilerState,
    chinese: Boolean,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (chinese) "Startup Profiler" else "Startup Profiler", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (chinese) {
                "选择设备和应用后运行可重复的冷、温或热启动实验。"
            } else {
                "Choose a device and app to run repeatable cold, warm, or hot startup experiments."
            },
        )
        state.operationMessage?.let { Text(it) }
        if (state.isRunning && state.totalRuns > 0) Text("${state.completedRuns}/${state.totalRuns}")
        state.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.tertiary) }
    }
}

@Composable
private fun ResultsPane(
    state: StartupProfilerState,
    analysis: StartupAnalysisResult,
    actions: StartupProfilerActions,
    chinese: Boolean,
    modifier: Modifier,
) {
    val selected = analysis.runs.firstOrNull { it.id == state.selectedRunId } ?: analysis.runs.first()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(if (chinese) "平台总耗时中位数" else "Median TotalTime", analysis.totalTime)
            MetricCard(if (chinese) "首帧中位数" else "Median First Frame", analysis.firstFrame)
            MetricCard(if (chinese) "完全绘制中位数" else "Median Fully Drawn", analysis.fullyDrawn)
            StabilityCard(analysis, chinese)
        }
        state.baseline?.let { baseline -> BaselineComparison(analysis, baseline, chinese) }
        Text(if (chinese) "采样轮次" else "Measured Runs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            RunRowHeader(chinese)
            analysis.runs.forEach { run -> RunRow(run, run.id == selected.id, actions.onSelectRun) }
        }
        HorizontalDivider()
        RunDetail(selected, chinese)
        if (analysis.warnings.isNotEmpty()) {
            Text(if (chinese) "警告" else "Warnings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            analysis.warnings.forEach { Text("• $it", color = MaterialTheme.colorScheme.tertiary) }
        }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun MetricCard(
    title: String,
    statistics: StartupStatistics,
) {
    Card(Modifier.width(210.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(statistics.medianMs.formatMs(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("p90 ${statistics.p90Ms.formatMs()} · n=${statistics.count}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StabilityCard(
    analysis: StartupAnalysisResult,
    chinese: Boolean,
) {
    val median = analysis.totalTime.medianMs
    val deviation = analysis.totalTime.medianAbsoluteDeviationMs
    val ratio = if (median != null && median > 0 && deviation != null) deviation / median else null
    val label =
        when {
            ratio == null -> if (chinese) "数据不足" else "Insufficient"
            ratio <= 0.05 -> if (chinese) "稳定" else "Stable"
            ratio <= 0.15 -> if (chinese) "有波动" else "Variable"
            else -> if (chinese) "波动较大" else "Unstable"
        }
    Card(Modifier.width(180.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (chinese) "稳定性" else "Stability", style = MaterialTheme.typography.labelLarge)
            Text(label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("MAD ${deviation.formatMs()}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BaselineComparison(
    current: StartupAnalysisResult,
    baseline: StartupAnalysisResult,
    chinese: Boolean,
) {
    val currentMedian = current.totalTime.medianMs
    val baselineMedian = baseline.totalTime.medianMs
    val change =
        if (currentMedian != null &&
            baselineMedian != null &&
            baselineMedian > 0
        ) {
            (currentMedian - baselineMedian) / baselineMedian * 100
        } else {
            null
        }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Text(
            if (chinese) {
                "与上一次实验相比：${change.formatPercent()}（仅表示差异，不声明统计显著）"
            } else {
                "Compared with previous experiment: ${change.formatPercent()} (difference only; no statistical significance inferred)"
            },
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun RunRowHeader(chinese: Boolean) {
    Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) {
        TableCell(if (chinese) "轮次" else "Run", 70)
        TableCell(if (chinese) "实际类型" else "Observed", 100)
        TableCell("Total", 100)
        TableCell("Displayed", 100)
        TableCell("Fully Drawn", 110)
        TableCell("Agent", 80)
    }
}

@Composable
private fun RunRow(
    run: StartupRun,
    selected: Boolean,
    onSelect: (String) -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(Modifier.background(background).clickable { onSelect(run.id) }.padding(8.dp)) {
        TableCell(run.iteration.toString(), 70)
        TableCell(run.observedType.name, 100)
        TableCell(run.platform.totalTimeMs.formatMs(), 100)
        TableCell(run.platform.displayedTimeMs.formatMs(), 100)
        TableCell(run.platform.fullyDrawnTimeMs.formatMs(), 110)
        TableCell(if (run.rawEvidence.agentAvailable) "Full" else "Fallback", 80)
    }
}

@Composable
private fun TableCell(
    text: String,
    width: Int,
) {
    Text(text, modifier = Modifier.width(width.dp), maxLines = 1)
}

@Composable
private fun RunDetail(
    run: StartupRun,
    chinese: Boolean,
) {
    Text(
        if (chinese) "第 ${run.iteration} 轮详情 · ${run.observedType.name}" else "Run ${run.iteration} Detail · ${run.observedType.name}",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(if (chinese) "平台时间轨" else "Platform Timeline", fontWeight = FontWeight.SemiBold)
    TimelineBar(
        listOfNotNull(
            run.platform.thisTimeMs?.let { "ThisTime" to it.toDouble() },
            run.platform.totalTimeMs?.let { "TotalTime" to it.toDouble() },
            run.platform.waitTimeMs?.let { "WaitTime" to it.toDouble() },
            run.platform.fullyDrawnTimeMs?.let { "Fully Drawn" to it.toDouble() },
        ),
    )
    Text(if (chinese) "Agent 阶段（独立时钟域）" else "Agent Phases (separate clock domain)", fontWeight = FontWeight.SemiBold)
    if (run.phases.isEmpty()) {
        Text(if (chinese) "无可用 Agent 阶段" else "No Agent phases available")
    } else {
        run.phases.forEach { phase ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(phase.name, Modifier.width(220.dp))
                Text((phase.durationNs / 1_000_000.0).formatMs())
                ConfidenceBadge(phase.confidence)
            }
        }
    }
    if (run.milestones.isNotEmpty()) {
        Text(if (chinese) "里程碑" else "Milestones", fontWeight = FontWeight.SemiBold)
        run.milestones.forEach { milestone ->
            Text("${milestone.kind.name} · ${milestone.confidence.name} · ${milestone.activityName.orEmpty()}")
        }
    }
    Text(if (chinese) "原始 am start -W 证据" else "Raw am start -W Evidence", fontWeight = FontWeight.SemiBold)
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp)) {
        Text(run.rawEvidence.amStartOutput, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TimelineBar(values: List<Pair<String, Double>>) {
    if (values.isEmpty()) return
    val maximum = values.maxOf { it.second }.coerceAtLeast(1.0)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { (label, duration) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, Modifier.width(100.dp))
                Box(
                    Modifier
                        .width((duration / maximum * 480).coerceAtLeast(2.0).dp)
                        .height(16.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
                )
                Text(duration.formatMs())
            }
        }
    }
}

@Composable
private fun ConfidenceBadge(confidence: EvidenceConfidence) {
    val color = if (confidence == EvidenceConfidence.EXACT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Text(confidence.name.lowercase(), color = color, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun MessagePane(
    message: String,
    color: Color,
    modifier: Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(message, color = color) }
}

private fun Number?.formatMs(): String = this?.let { String.format(Locale.US, "%.1f ms", toDouble()) } ?: "—"

private fun Double?.formatPercent(): String = this?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "—"
