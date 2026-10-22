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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.battery.analysis.BatteryAnalysisResult
import com.androidperformancestudio.battery.battery_app.generated.resources.Res
import com.androidperformancestudio.battery.battery_app.generated.resources.alarm
import com.androidperformancestudio.battery.battery_app.generated.resources.capability_attribution_uid
import com.androidperformancestudio.battery.battery_app.generated.resources.diagnostics_and_warnings
import com.androidperformancestudio.battery.battery_app.generated.resources.duration
import com.androidperformancestudio.battery.battery_app.generated.resources.energy
import com.androidperformancestudio.battery.battery_app.generated.resources.energy_evidence
import com.androidperformancestudio.battery.battery_app.generated.resources.experiment_runs
import com.androidperformancestudio.battery.battery_app.generated.resources.median_network
import com.androidperformancestudio.battery.battery_app.generated.resources.median_wakelock
import com.androidperformancestudio.battery.battery_app.generated.resources.median_wakeup_alarms
import com.androidperformancestudio.battery.battery_app.generated.resources.modeled_energy
import com.androidperformancestudio.battery.battery_app.generated.resources.network
import com.androidperformancestudio.battery.battery_app.generated.resources.network_use_vs_previous_compatible_experiment_difference_only
import com.androidperformancestudio.battery.battery_app.generated.resources.no_delta_or_unavailable
import com.androidperformancestudio.battery.battery_app.generated.resources.p90_n
import com.androidperformancestudio.battery.battery_app.generated.resources.peak_temperature
import com.androidperformancestudio.battery.battery_app.generated.resources.runs
import com.androidperformancestudio.battery.battery_app.generated.resources.s
import com.androidperformancestudio.battery.battery_app.generated.resources.text
import com.androidperformancestudio.battery.battery_app.generated.resources.thermal_status
import com.androidperformancestudio.battery.battery_app.generated.resources.wakelock
import com.androidperformancestudio.battery.model.BatteryRunDelta
import com.androidperformancestudio.battery.model.BatteryStatistics
import com.androidperformancestudio.battery.model.ResourceTimer
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioCard
import com.androidperformancestudio.ui.studio.StudioDataTable
import com.androidperformancestudio.ui.studio.StudioMetricCard
import com.androidperformancestudio.ui.studio.StudioPanel
import com.androidperformancestudio.ui.studio.StudioPanelHeader
import com.androidperformancestudio.ui.studio.StudioTableColumn
import com.androidperformancestudio.ui.studio.StudioTokens
import java.util.Locale

@Composable
public fun BatteryProfilerScreen(
    state: BatteryProfilerState,
    actions: BatteryProfilerActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val analysis = state.analysis
    val workspaceModifier =
        modifier
            .fillMaxSize()
            .background(LocalViewerColors.current.workspace)
            .padding(StudioTokens.workspacePadding)
    when {
        state.errorMessage != null && analysis == null ->
            Box(workspaceModifier, contentAlignment = Alignment.Center) {
                Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
            }
        analysis == null || analysis.runs.isEmpty() -> EmptyPane(state, language, workspaceModifier)
        else -> ResultsPane(state, analysis, actions, language, workspaceModifier)
    }
}

@Composable
private fun EmptyPane(
    state: BatteryProfilerState,
    language: UiLanguage,
    modifier: Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
    ) {
        StudioPanel(Modifier.fillMaxWidth()) {
            StudioPanelHeader(localizedStringResource(Res.string.energy_evidence, language))
            Text(localizedStringResource(Res.string.no_delta_or_unavailable, language))
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
    val colors = LocalViewerColors.current
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
    ) {
        val metrics =
            buildList {
                add(
                    BatteryMetric(
                        localizedStringResource(Res.string.median_wakelock, language),
                        analysis.wakelockDurationMs,
                        "ms",
                        colors.warning,
                    ),
                )
                add(
                    BatteryMetric(
                        localizedStringResource(Res.string.median_wakeup_alarms, language),
                        analysis.wakeupAlarmCount,
                        "",
                        colors.accent,
                    ),
                )
                add(
                    BatteryMetric(
                        localizedStringResource(Res.string.median_network, language),
                        analysis.networkBytes,
                        "B",
                        colors.info,
                    ),
                )
                if (analysis.energyMah.count > 0) {
                    add(
                        BatteryMetric(
                            localizedStringResource(Res.string.modeled_energy, language),
                            analysis.energyMah,
                            "mAh",
                            colors.accent,
                        ),
                    )
                }
                add(
                    BatteryMetric(
                        localizedStringResource(Res.string.peak_temperature, language),
                        analysis.peakTemperatureCelsius,
                        "°C",
                        colors.warning,
                    ),
                )
                add(
                    BatteryMetric(
                        localizedStringResource(Res.string.thermal_status, language),
                        analysis.thermalStatus,
                        "",
                        colors.info,
                    ),
                )
            }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columnCount = ((maxWidth.value + 8) / 176).toInt().coerceIn(1, metrics.size)
            Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)) {
                metrics.chunked(columnCount).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
                    ) {
                        row.forEach { metric ->
                            MetricCard(
                                metric.title,
                                metric.statistics,
                                metric.unit,
                                language,
                                metric.tone,
                                Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                        repeat(columnCount - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        val session = state.experiment?.session
        session?.let {
            StudioCard(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                contentPadding = 0.dp,
            ) {
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
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val tableWidth = maxWidth.coerceAtLeast(580.dp)
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                StudioDataTable(
                    columns =
                        listOf(
                            StudioTableColumn(localizedStringResource(Res.string.runs, language), 70f),
                            StudioTableColumn(localizedStringResource(Res.string.duration, language), 100f),
                            StudioTableColumn(localizedStringResource(Res.string.wakelock, language), 110f),
                            StudioTableColumn(localizedStringResource(Res.string.alarm, language), 90f),
                            StudioTableColumn(localizedStringResource(Res.string.network, language), 110f),
                            StudioTableColumn(localizedStringResource(Res.string.energy, language), 100f),
                        ),
                    title = localizedStringResource(Res.string.experiment_runs, language),
                    modifier = Modifier.width(tableWidth),
                ) {
                    analysis.runs.forEach { run -> RunRow(run, run.runId == selected.runId, actions.onSelectRun, language) }
                }
            }
        }
        BatteryRunWorkspace(
            run = selected,
            capturedRun = (state.experiment?.runs ?: state.storedRuns).firstOrNull { it.id == selected.runId },
            language = language,
        )
        if (analysis.warnings.isNotEmpty()) {
            StudioPanel(Modifier.fillMaxWidth()) {
                StudioPanelHeader(localizedStringResource(Res.string.diagnostics_and_warnings, language))
                analysis.warnings.forEach {
                    Text(
                        localizedStringResource(Res.string.text, language, it),
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

private data class BatteryMetric(
    val title: String,
    val statistics: BatteryStatistics,
    val unit: String,
    val tone: Color,
)

@Composable
private fun MetricCard(
    title: String,
    statistics: BatteryStatistics,
    unit: String,
    language: UiLanguage,
    tone: Color,
    modifier: Modifier,
) {
    StudioMetricCard(
        label = title,
        value = statistics.median.format(unit),
        modifier = modifier,
        supportingText = localizedStringResource(Res.string.p90_n, language, statistics.p90.format(unit), statistics.count),
        tone = tone,
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
    StudioCard(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        contentPadding = 0.dp,
    ) {
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
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else LocalViewerColors.current.transparent)
            .clickable {
                onSelect(run.runId)
            }.padding(vertical = 6.dp, horizontal = 8.dp),
    ) {
        Cell(run.iteration.toString(), 70f)
        Cell(localizedStringResource(Res.string.s, language, run.durationMs / 1000), 100f)
        Cell(run.wakelocks.sumOf(ResourceTimer::durationMs).toString(), 110f)
        Cell(run.alarms.sumOf(ResourceTimer::count).toString(), 90f)
        Cell(run.networkTotalBytes.toString(), 110f)
        Cell(
            run.energy
                .sumOf { it.energyMah ?: 0.0 }
                .takeIf { it > 0.0 }
                .format("mAh"),
            100f,
        )
    }
}

@Composable
private fun RowScope.Cell(
    text: String,
    weight: Float,
) {
    Text(text, modifier = Modifier.weight(weight), maxLines = 1)
}

private fun Number?.format(unit: String): String =
    this?.let { String.format(Locale.US, "%.2f%s", toDouble(), unit.takeIf(String::isNotEmpty)?.let { " $it" }.orEmpty()) } ?: "—"

private fun Double?.percent(): String = this?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "—"
