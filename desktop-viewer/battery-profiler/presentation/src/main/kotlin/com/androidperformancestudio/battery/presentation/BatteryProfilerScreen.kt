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

import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.battery.presentation.generated.resources.Res
import com.androidperformancestudio.battery.presentation.generated.resources.*

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
        Text(localizedStringResource(Res.string.battery_energy_profiler, chinese), style = MaterialTheme.typography.headlineMedium)
        Text(
            localizedStringResource(Res.string.analyze_wakelocks_alarms_jobs_network_sensors_and_system_energy_estima, chinese),
        )
        Text(
            localizedStringResource(Res.string.every_energy_value_exposes_its_source_scope_and_confidence_global, chinese),
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
            MetricCard(localizedStringResource(Res.string.median_wakelock, chinese), analysis.wakelockDurationMs, "ms", chinese)
            MetricCard(localizedStringResource(Res.string.median_wakeup_alarms, chinese), analysis.wakeupAlarmCount, "", chinese)
            MetricCard(localizedStringResource(Res.string.median_network, chinese), analysis.networkBytes, "B", chinese)
            MetricCard(localizedStringResource(Res.string.modeled_energy, chinese), analysis.energyMah, "mAh", chinese)
        }
        val session = state.experiment?.session
        session?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(
                    localizedStringResource(Res.string.capability_attribution_uid, chinese, it.capabilities.level, it.attributionScope, it.uid),
                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp),
                )
            }
        }
        state.baseline?.let { baseline -> BaselineComparison(analysis, baseline, chinese) }
        Text(localizedStringResource(Res.string.experiment_runs, chinese), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 6.dp, horizontal = 8.dp)) {
                Cell(localizedStringResource(Res.string.run, chinese), 70)
                Cell(localizedStringResource(Res.string.duration, chinese), 100)
                Cell(localizedStringResource(Res.string.wakelock, chinese), 110)
                Cell(localizedStringResource(Res.string.alarm, chinese), 90)
                Cell(localizedStringResource(Res.string.network, chinese), 110)
                Cell(localizedStringResource(Res.string.energy, chinese), 100)
            }
            analysis.runs.forEach { run -> RunRow(run, run.runId == selected.runId, actions.onSelectRun, chinese) }
        }
        HorizontalDivider()
        RunDetail(selected, chinese)
        if (analysis.warnings.isNotEmpty()) {
            Text(
                localizedStringResource(Res.string.diagnostics_and_warnings, chinese),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            analysis.warnings.forEach { Text(localizedStringResource(Res.string.text, chinese, it), color = MaterialTheme.colorScheme.tertiary) }
        }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun MetricCard(
    title: String,
    statistics: BatteryStatistics,
    unit: String,
    chinese: Boolean,
) {
    Card(Modifier.width(168.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(vertical = 6.dp, horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(statistics.median.format(unit), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(localizedStringResource(Res.string.p90_n, chinese, statistics.p90.format(unit), statistics.count), style = MaterialTheme.typography.bodySmall)
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
            localizedStringResource(Res.string.network_use_vs_previous_compatible_experiment_difference_only, chinese, change.percent()),
            Modifier.padding(vertical = 6.dp, horizontal = 8.dp),
        )
    }
}

@Composable
private fun RunRow(
    run: BatteryRunDelta,
    selected: Boolean,
    onSelect: (String) -> Unit,
    chinese: Boolean,
) {
    Row(
        Modifier
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable {
                onSelect(run.runId)
            }.padding(vertical = 6.dp, horizontal = 8.dp),
    ) {
        Cell(run.iteration.toString(), 70)
        Cell(localizedStringResource(Res.string.s, chinese, run.durationMs / 1000), 100)
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
        localizedStringResource(Res.string.run_resource_details, chinese, run.iteration),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    ResourceSection(localizedStringResource(Res.string.wakelocks, chinese), run.wakelocks, chinese)
    ResourceSection(localizedStringResource(Res.string.alarms, chinese), run.alarms, chinese)
    ResourceSection(localizedStringResource(Res.string.jobs, chinese), run.jobs, chinese)
    ResourceSection(localizedStringResource(Res.string.sensors, chinese), run.sensors, chinese)
    Text(localizedStringResource(Res.string.network_b_mobile_radio_ms, chinese, run.network.totalBytes, run.network.mobileRadioActiveMs), fontWeight = FontWeight.SemiBold)
    Text(localizedStringResource(Res.string.energy_evidence, chinese), fontWeight = FontWeight.SemiBold)
    if (run.energy.isEmpty()) Text(localizedStringResource(Res.string.no_attributable_energy_data_was_provided_by_this_device, chinese))
    run.energy.forEach { energy ->
        Text(
            localizedStringResource(
                Res.string.energy_evidence_detail,
                chinese,
                energy.component,
                energy.energyMah.format("mAh"),
                energy.energyUws ?: "—",
                energy.source,
                energy.attributionScope,
                energy.confidence,
            ),
        )
    }
    Text(
        localizedStringResource(Res.string.history_timeline_events, chinese, run.history.size),
        fontWeight = FontWeight.SemiBold,
    )
    run.history.take(100).forEach { event ->
        Text(
            localizedStringResource(
                Res.string.history_event_detail,
                chinese,
                event.elapsedMs ?: "—",
                event.kind,
                event.active ?: "?",
                event.name.orEmpty(),
            ),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    run.warnings.forEach { Text(localizedStringResource(Res.string.text, chinese, it), color = MaterialTheme.colorScheme.tertiary) }
}

@Composable
private fun ResourceSection(
    title: String,
    resources: List<ResourceTimer>,
    chinese: Boolean,
) {
    Text(title, fontWeight = FontWeight.SemiBold)
    if (resources.isEmpty()) Text(localizedStringResource(Res.string.no_delta_or_unavailable, chinese))
    resources.take(100).forEach { timer -> Text(localizedStringResource(Res.string.ms, chinese, timer.name, timer.durationMs, timer.count, timer.confidence)) }
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
