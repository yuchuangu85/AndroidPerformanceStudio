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
import com.androidperformancestudio.battery.battery_app.generated.resources.Res
import com.androidperformancestudio.battery.battery_app.generated.resources.alarm
import com.androidperformancestudio.battery.battery_app.generated.resources.alarms
import com.androidperformancestudio.battery.battery_app.generated.resources.analyze_wakelocks_alarms_jobs_network_sensors_and_system_energy_estima
import com.androidperformancestudio.battery.battery_app.generated.resources.battery_energy_profiler
import com.androidperformancestudio.battery.battery_app.generated.resources.capability_attribution_uid
import com.androidperformancestudio.battery.battery_app.generated.resources.diagnostics_and_warnings
import com.androidperformancestudio.battery.battery_app.generated.resources.duration
import com.androidperformancestudio.battery.battery_app.generated.resources.energy
import com.androidperformancestudio.battery.battery_app.generated.resources.energy_evidence
import com.androidperformancestudio.battery.battery_app.generated.resources.energy_evidence_detail
import com.androidperformancestudio.battery.battery_app.generated.resources.every_energy_value_exposes_its_source_scope_and_confidence_global
import com.androidperformancestudio.battery.battery_app.generated.resources.experiment_runs
import com.androidperformancestudio.battery.battery_app.generated.resources.history_event_detail
import com.androidperformancestudio.battery.battery_app.generated.resources.history_timeline_events
import com.androidperformancestudio.battery.battery_app.generated.resources.jobs
import com.androidperformancestudio.battery.battery_app.generated.resources.median_network
import com.androidperformancestudio.battery.battery_app.generated.resources.median_wakelock
import com.androidperformancestudio.battery.battery_app.generated.resources.median_wakeup_alarms
import com.androidperformancestudio.battery.battery_app.generated.resources.modeled_energy
import com.androidperformancestudio.battery.battery_app.generated.resources.ms
import com.androidperformancestudio.battery.battery_app.generated.resources.network
import com.androidperformancestudio.battery.battery_app.generated.resources.network_b_mobile_radio_ms
import com.androidperformancestudio.battery.battery_app.generated.resources.network_use_vs_previous_compatible_experiment_difference_only
import com.androidperformancestudio.battery.battery_app.generated.resources.no_attributable_energy_data_was_provided_by_this_device
import com.androidperformancestudio.battery.battery_app.generated.resources.no_delta_or_unavailable
import com.androidperformancestudio.battery.battery_app.generated.resources.p90_n
import com.androidperformancestudio.battery.battery_app.generated.resources.run_resource_details
import com.androidperformancestudio.battery.battery_app.generated.resources.runs
import com.androidperformancestudio.battery.battery_app.generated.resources.s
import com.androidperformancestudio.battery.battery_app.generated.resources.sensors
import com.androidperformancestudio.battery.battery_app.generated.resources.text
import com.androidperformancestudio.battery.battery_app.generated.resources.wakelock
import com.androidperformancestudio.battery.battery_app.generated.resources.wakelocks
import com.androidperformancestudio.battery.model.BatteryRunDelta
import com.androidperformancestudio.battery.model.BatteryStatistics
import com.androidperformancestudio.battery.model.ResourceTimer
import com.androidperformancestudio.ui.ProfilerMetricCard
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.util.Locale

@Composable
public fun BatteryProfilerScreen(
    state: BatteryProfilerState,
    actions: BatteryProfilerActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val analysis = state.analysis
    when {
        state.errorMessage != null && analysis == null ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
            }
        analysis == null -> EmptyPane(state, language, modifier)
        else -> ResultsPane(state, analysis, actions, language, modifier)
    }
}

@Composable
private fun EmptyPane(
    state: BatteryProfilerState,
    language: UiLanguage,
    modifier: Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            localizedStringResource(Res.string.battery_energy_profiler, language),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            localizedStringResource(
                Res.string.analyze_wakelocks_alarms_jobs_network_sensors_and_system_energy_estima,
                language,
            ),
        )
        Text(
            localizedStringResource(
                Res.string.every_energy_value_exposes_its_source_scope_and_confidence_global,
                language,
            ),
        )
        state.operationMessage?.let { Text(it) }
        state.artifact?.let { artifact ->
            Text(
                "Artifact: ${artifact.completeness.name.lowercase()} · " +
                    "${artifact.availableCapabilities.size} capabilities" +
                    if (artifact.limitations.isEmpty()) "" else " · ${artifact.limitations.size} limitation(s)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.tertiary) }
    }
}

@Composable
private fun ResultsPane(
    state: BatteryProfilerState,
    analysis: BatteryAnalysisResult,
    actions: BatteryProfilerActions,
    language: UiLanguage,
    modifier: Modifier,
) {
    val selected = analysis.runs.firstOrNull { it.runId == state.selectedRunId } ?: analysis.runs.first()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 6.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard(localizedStringResource(Res.string.median_wakelock, language), analysis.wakelockDurationMs, "ms", language)
            MetricCard(localizedStringResource(Res.string.median_wakeup_alarms, language), analysis.wakeupAlarmCount, "", language)
            MetricCard(localizedStringResource(Res.string.median_network, language), analysis.networkBytes, "B", language)
            MetricCard(localizedStringResource(Res.string.modeled_energy, language), analysis.energyMah, "mAh", language)
        }
        val session = state.experiment?.session
        session?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(
                    localizedStringResource(
                        Res.string.capability_attribution_uid,
                        language,
                        it.capabilities.level,
                        it.attributionScope,
                        it.uid,
                    ),
                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp),
                )
            }
        }
        state.baseline?.let { baseline -> BaselineComparison(analysis, baseline, language) }
        Text(
            localizedStringResource(Res.string.experiment_runs, language),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 6.dp, horizontal = 8.dp)) {
                Cell(localizedStringResource(Res.string.runs, language), 70)
                Cell(localizedStringResource(Res.string.duration, language), 100)
                Cell(localizedStringResource(Res.string.wakelock, language), 110)
                Cell(localizedStringResource(Res.string.alarm, language), 90)
                Cell(localizedStringResource(Res.string.network, language), 110)
                Cell(localizedStringResource(Res.string.energy, language), 100)
            }
            analysis.runs.forEach { run -> RunRow(run, run.runId == selected.runId, actions.onSelectRun, language) }
        }
        HorizontalDivider()
        RunDetail(selected, language)
        if (analysis.warnings.isNotEmpty()) {
            Text(
                localizedStringResource(Res.string.diagnostics_and_warnings, language),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            analysis.warnings.forEach {
                Text(
                    localizedStringResource(Res.string.text, language, it),
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun MetricCard(
    title: String,
    statistics: BatteryStatistics,
    unit: String,
    language: UiLanguage,
) {
    ProfilerMetricCard(
        label = title,
        value = statistics.median.format(unit),
        modifier = Modifier.width(168.dp),
        supportingText = listOf(localizedStringResource(Res.string.p90_n, language, statistics.p90.format(unit), statistics.count)),
        prominent = true,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

@Composable
private fun BaselineComparison(
    current: BatteryAnalysisResult,
    baseline: BatteryAnalysisResult,
    language: UiLanguage,
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
            localizedStringResource(
                Res.string.network_use_vs_previous_compatible_experiment_difference_only,
                language,
                change.percent(),
            ),
            Modifier.padding(vertical = 6.dp, horizontal = 8.dp),
        )
    }
}

@Composable
private fun RunRow(
    run: BatteryRunDelta,
    selected: Boolean,
    onSelect: (String) -> Unit,
    language: UiLanguage,
) {
    Row(
        Modifier
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable {
                onSelect(run.runId)
            }.padding(vertical = 6.dp, horizontal = 8.dp),
    ) {
        Cell(run.iteration.toString(), 70)
        Cell(localizedStringResource(Res.string.s, language, run.durationMs / 1000), 100)
        Cell(run.wakelocks.sumOf(ResourceTimer::durationMs).toString(), 110)
        Cell(run.alarms.sumOf(ResourceTimer::count).toString(), 90)
        Cell(run.network.totalBytes.toString(), 110)
        Cell(
            run.energy
                .sumOf { it.energyMah ?: 0.0 }
                .takeIf { it > 0.0 }
                .format("mAh"),
            100,
        )
    }
}

@Composable
private fun RunDetail(
    run: BatteryRunDelta,
    language: UiLanguage,
) {
    Text(
        localizedStringResource(Res.string.run_resource_details, language, run.iteration),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    ResourceSection(localizedStringResource(Res.string.wakelocks, language), run.wakelocks, language)
    ResourceSection(localizedStringResource(Res.string.alarms, language), run.alarms, language)
    ResourceSection(localizedStringResource(Res.string.jobs, language), run.jobs, language)
    ResourceSection(localizedStringResource(Res.string.sensors, language), run.sensors, language)
    Text(
        localizedStringResource(
            Res.string.network_b_mobile_radio_ms,
            language,
            run.network.totalBytes,
            run.network.mobileRadioActiveMs,
        ),
        fontWeight = FontWeight.SemiBold,
    )
    Text(localizedStringResource(Res.string.energy_evidence, language), fontWeight = FontWeight.SemiBold)
    if (run.energy.isEmpty()) {
        Text(localizedStringResource(Res.string.no_attributable_energy_data_was_provided_by_this_device, language))
    }
    run.energy.forEach { energy ->
        Text(
            localizedStringResource(
                Res.string.energy_evidence_detail,
                language,
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
        localizedStringResource(Res.string.history_timeline_events, language, run.history.size),
        fontWeight = FontWeight.SemiBold,
    )
    run.history.take(100).forEach { event ->
        Text(
            localizedStringResource(
                Res.string.history_event_detail,
                language,
                event.elapsedMs ?: "—",
                event.kind,
                event.active ?: "?",
                event.name.orEmpty(),
            ),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    run.warnings.forEach {
        Text(
            localizedStringResource(Res.string.text, language, it),
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun ResourceSection(
    title: String,
    resources: List<ResourceTimer>,
    language: UiLanguage,
) {
    Text(title, fontWeight = FontWeight.SemiBold)
    if (resources.isEmpty()) Text(localizedStringResource(Res.string.no_delta_or_unavailable, language))
    resources.take(100).forEach { timer ->
        Text(
            localizedStringResource(
                Res.string.ms,
                language,
                timer.name,
                timer.durationMs,
                timer.count,
                timer.confidence,
            ),
        )
    }
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
