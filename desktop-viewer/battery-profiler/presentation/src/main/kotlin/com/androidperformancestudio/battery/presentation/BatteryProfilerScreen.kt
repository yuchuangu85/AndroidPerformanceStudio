@file:Suppress(
    "FunctionName",
    "CyclomaticComplexMethod",
    "LongMethod",
    "MagicNumber",
    "MaxLineLength",
    "ktlint:standard:max-line-length",
    "TooManyFunctions",
    "ktlint:standard:function-naming",
)

package com.androidperformancestudio.battery.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import com.androidperformancestudio.battery.analysis.BatteryAnalysisResult
import com.androidperformancestudio.battery.model.BatteryRunDelta
import com.androidperformancestudio.battery.model.BatteryStatistics
import com.androidperformancestudio.battery.model.ResourceTimer
import java.util.Locale

@Composable
public fun BatteryProfilerScreen(
    state: BatteryProfilerState,
    actions: BatteryProfilerActions,
    chinese: Boolean,
    modifier: Modifier = Modifier,
) {
    val analysis = state.analysis
    when {
        state.errorMessage != null && analysis == null ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
            }
        analysis == null -> EmptyPane(state, chinese, modifier)
        else -> ResultsPane(state, analysis, actions, chinese, modifier)
    }
}

@Composable
private fun EmptyPane(
    state: BatteryProfilerState,
    chinese: Boolean,
    modifier: Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Battery / Energy Profiler", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (chinese) "通过前后快照差分分析 Wakelock、Alarm、Job、Network、Sensor 与系统能耗估算。" else "Analyze Wakelocks, alarms, jobs, network, sensors, and system energy estimates using snapshot deltas.",
        )
        Text(
            if (chinese) "所有能耗值都会显示来源、归因范围和置信度；不会默认重置全局 batterystats。" else "Every energy value exposes its source, scope, and confidence. Global batterystats are never reset by default.",
        )
        state.operationMessage?.let { Text(it) }
        state.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.tertiary) }
    }
}

@Composable
private fun ResultsPane(
    state: BatteryProfilerState,
    analysis: BatteryAnalysisResult,
    actions: BatteryProfilerActions,
    chinese: Boolean,
    modifier: Modifier,
) {
    val selected = analysis.runs.firstOrNull { it.runId == state.selectedRunId } ?: analysis.runs.first()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 6.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard(if (chinese) "Wakelock 中位数" else "Median Wakelock", analysis.wakelockDurationMs, "ms")
            MetricCard(if (chinese) "唤醒 Alarm 中位数" else "Median Wakeup Alarms", analysis.wakeupAlarmCount, "")
            MetricCard(if (chinese) "网络中位数" else "Median Network", analysis.networkBytes, "B")
            MetricCard(if (chinese) "系统能耗估算" else "Modeled Energy", analysis.energyMah, "mAh")
        }
        val session = state.experiment?.session
        session?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(
                    if (chinese) "能力：${it.capabilities.level} · 归因：${it.attributionScope} · UID ${it.uid}" else "Capability: ${it.capabilities.level} · Attribution: ${it.attributionScope} · UID ${it.uid}",
                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp),
                )
            }
        }
        state.baseline?.let { baseline -> BaselineComparison(analysis, baseline, chinese) }
        Text(if (chinese) "实验轮次" else "Experiment Runs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 6.dp, horizontal = 8.dp)) {
                Cell(if (chinese) "轮次" else "Run", 70)
                Cell(if (chinese) "时长" else "Duration", 100)
                Cell("Wakelock", 110)
                Cell("Alarm", 90)
                Cell("Network", 110)
                Cell("Energy", 100)
            }
            analysis.runs.forEach { run -> RunRow(run, run.runId == selected.runId, actions.onSelectRun) }
        }
        HorizontalDivider()
        RunDetail(selected, chinese)
        if (analysis.warnings.isNotEmpty()) {
            Text(
                if (chinese) "诊断与警告" else "Diagnostics and Warnings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            analysis.warnings.forEach { Text("• $it", color = MaterialTheme.colorScheme.tertiary) }
        }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun MetricCard(
    title: String,
    statistics: BatteryStatistics,
    unit: String,
) {
    Card(Modifier.width(168.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(vertical = 6.dp, horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(statistics.median.format(unit), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("p90 ${statistics.p90.format(unit)} · n=${statistics.count}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BaselineComparison(
    current: BatteryAnalysisResult,
    baseline: BatteryAnalysisResult,
    chinese: Boolean,
) {
    val currentValue = current.networkBytes.median
    val baselineValue = baseline.networkBytes.median
    val change =
        if (currentValue != null &&
            baselineValue != null &&
            baselineValue > 0
        ) {
            (currentValue - baselineValue) / baselineValue * 100
        } else {
            null
        }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Text(
            if (chinese) "网络用量与上次兼容实验相比：${change.percent()}（仅表示差异）" else "Network use vs previous compatible experiment: ${change.percent()} (difference only)",
            Modifier.padding(vertical = 6.dp, horizontal = 8.dp),
        )
    }
}

@Composable
private fun RunRow(
    run: BatteryRunDelta,
    selected: Boolean,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable {
                onSelect(run.runId)
            }.padding(vertical = 6.dp, horizontal = 8.dp),
    ) {
        Cell(run.iteration.toString(), 70)
        Cell("${run.durationMs / 1000}s", 100)
        Cell(run.wakelocks.sumOf(ResourceTimer::durationMs).toString(), 110)
        Cell(run.alarms.sumOf(ResourceTimer::count).toString(), 90)
        Cell(run.network.totalBytes.toString(), 110)
        Cell(
            run.energy
                .sumOf { it.energyMah ?: 0.0 }
                .takeIf { it > 0 }
                .format("mAh"),
            100,
        )
    }
}

@Composable
private fun RunDetail(
    run: BatteryRunDelta,
    chinese: Boolean,
) {
    Text(
        if (chinese) "第 ${run.iteration} 轮资源明细" else "Run ${run.iteration} Resource Details",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    ResourceSection("Wakelocks", run.wakelocks, chinese)
    ResourceSection("Alarms", run.alarms, chinese)
    ResourceSection("Jobs", run.jobs, chinese)
    ResourceSection("Sensors", run.sensors, chinese)
    Text("Network · ${run.network.totalBytes} B · mobile radio ${run.network.mobileRadioActiveMs} ms", fontWeight = FontWeight.SemiBold)
    Text(if (chinese) "能耗证据" else "Energy Evidence", fontWeight = FontWeight.SemiBold)
    if (run.energy.isEmpty()) Text(if (chinese) "当前设备未提供可归因能耗数据" else "No attributable energy data was provided by this device")
    run.energy.forEach { energy ->
        Text(
            "${energy.component}: ${energy.energyMah.format(
                "mAh",
            )} / ${energy.energyUws ?: "—"} µWs · ${energy.source} · ${energy.attributionScope} · ${energy.confidence}",
        )
    }
    Text(
        if (chinese) "History 时间线事件：${run.history.size}" else "History timeline events: ${run.history.size}",
        fontWeight = FontWeight.SemiBold,
    )
    run.history.take(100).forEach { event ->
        Text(
            "${event.elapsedMs ?: "—"} ms · ${event.kind} · ${event.active ?: "?"} · ${event.name.orEmpty()}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    run.warnings.forEach { Text("• $it", color = MaterialTheme.colorScheme.tertiary) }
}

@Composable
private fun ResourceSection(
    title: String,
    resources: List<ResourceTimer>,
    chinese: Boolean,
) {
    Text(title, fontWeight = FontWeight.SemiBold)
    if (resources.isEmpty()) Text(if (chinese) "无增量或设备未提供" else "No delta or unavailable")
    resources.take(100).forEach { timer -> Text("${timer.name} · ${timer.durationMs} ms · ${timer.count}× · ${timer.confidence}") }
}

@Composable
private fun Cell(
    text: String,
    width: Int,
) {
    Text(text, modifier = Modifier.width(width.dp), maxLines = 1)
}

private fun Number?.format(unit: String): String =
    this?.let { String.format(Locale.US, "%.2f%s", toDouble(), unit.takeIf(String::isNotEmpty)?.let { " $it" }.orEmpty()) } ?: "—"

private fun Double?.percent(): String = this?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "—"
