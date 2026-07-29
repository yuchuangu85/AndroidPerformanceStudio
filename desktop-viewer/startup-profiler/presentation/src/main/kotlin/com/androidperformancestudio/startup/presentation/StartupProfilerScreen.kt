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

import com.androidperformancestudio.ui.UiLanguage
import org.jetbrains.compose.resources.stringResource

import com.androidperformancestudio.startup.presentation.generated.resources.Res
import com.androidperformancestudio.startup.presentation.generated.resources.*

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
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val analysis = state.analysis
    when {
        state.errorMessage != null && analysis == null -> MessagePane(state.errorMessage, MaterialTheme.colorScheme.error, modifier)
        analysis == null -> EmptyPane(state, language, modifier)
        else -> ResultsPane(state, analysis, actions, language, modifier)
    }
}

@Composable
private fun EmptyPane(
    state: StartupProfilerState,
    language: UiLanguage,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(Res.string.startup_profiler), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(Res.string.choose_a_device_and_app_to_run_repeatable_cold_warm),
        )
        state.operationMessage?.let { Text(it) }
        if (state.isRunning && state.totalRuns > 0) Text(stringResource(Res.string.text, state.completedRuns, state.totalRuns))
        state.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.tertiary) }
    }
}

@Composable
private fun ResultsPane(
    state: StartupProfilerState,
    analysis: StartupAnalysisResult,
    actions: StartupProfilerActions,
    language: UiLanguage,
    modifier: Modifier,
) {
    val selected = analysis.runs.firstOrNull { it.id == state.selectedRunId } ?: analysis.runs.first()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 6.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MetricCard(stringResource(Res.string.median_totaltime), analysis.totalTime, language)
            MetricCard(stringResource(Res.string.median_first_frame), analysis.firstFrame, language)
            MetricCard(stringResource(Res.string.median_fully_drawn), analysis.fullyDrawn, language)
            StabilityCard(analysis, language)
        }
        state.baseline?.let { baseline -> BaselineComparison(analysis, baseline, language) }
        Text(stringResource(Res.string.measured_runs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            RunRowHeader(language)
            analysis.runs.forEach { run -> RunRow(run, run.id == selected.id, actions.onSelectRun, language) }
        }
        HorizontalDivider()
        RunDetail(selected, language)
        if (analysis.warnings.isNotEmpty()) {
            Text(stringResource(Res.string.warnings), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            analysis.warnings.forEach { Text(stringResource(Res.string.text_45f5c8ce, it), color = MaterialTheme.colorScheme.tertiary) }
        }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun MetricCard(
    title: String,
    statistics: StartupStatistics,
    language: UiLanguage,
) {
    Card(Modifier.width(172.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(vertical = 6.dp, horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(statistics.medianMs.formatMs(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(Res.string.p90_n, statistics.p90Ms.formatMs(), statistics.count), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StabilityCard(
    analysis: StartupAnalysisResult,
    language: UiLanguage,
) {
    val median = analysis.totalTime.medianMs
    val deviation = analysis.totalTime.medianAbsoluteDeviationMs
    val ratio = if (median != null && median > 0 && deviation != null) deviation / median else null
    val label =
        when {
            ratio == null -> stringResource(Res.string.insufficient)
            ratio <= 0.05 -> stringResource(Res.string.stable)
            ratio <= 0.15 -> stringResource(Res.string.variable)
            else -> stringResource(Res.string.unstable)
        }
    Card(Modifier.width(156.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(vertical = 6.dp, horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.stability), style = MaterialTheme.typography.labelLarge)
            Text(label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(Res.string.mad, deviation.formatMs()), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BaselineComparison(
    current: StartupAnalysisResult,
    baseline: StartupAnalysisResult,
    language: UiLanguage,
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
            stringResource(Res.string.compared_with_previous_experiment_difference_only_no_statistical_signi, change.formatPercent()),
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp),
        )
    }
}

@Composable
private fun RunRowHeader(language: UiLanguage) {
    Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 6.dp, horizontal = 8.dp)) {
        TableCell(stringResource(Res.string.run), 70)
        TableCell(stringResource(Res.string.observed), 100)
        TableCell(stringResource(Res.string.total), 100)
        TableCell(stringResource(Res.string.displayed), 100)
        TableCell(stringResource(Res.string.fully_drawn), 110)
        TableCell(stringResource(Res.string.agent), 80)
    }
}

@Composable
private fun RunRow(
    run: StartupRun,
    selected: Boolean,
    onSelect: (String) -> Unit,
    language: UiLanguage,
) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(Modifier.background(background).clickable { onSelect(run.id) }.padding(vertical = 6.dp, horizontal = 8.dp)) {
        TableCell(run.iteration.toString(), 70)
        TableCell(run.observedType.name, 100)
        TableCell(run.platform.totalTimeMs.formatMs(), 100)
        TableCell(run.platform.displayedTimeMs.formatMs(), 100)
        TableCell(run.platform.fullyDrawnTimeMs.formatMs(), 110)
        TableCell(
            stringResource(if (run.rawEvidence.agentAvailable) Res.string.full else Res.string.fallback, ),
            80,
        )
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
    language: UiLanguage,
) {
    Text(
        stringResource(Res.string.run_detail, run.iteration, run.observedType.name),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(stringResource(Res.string.platform_timeline), fontWeight = FontWeight.SemiBold)
    TimelineBar(
        listOfNotNull(
            run.platform.thisTimeMs?.let { stringResource(Res.string.this_time) to it.toDouble() },
            run.platform.totalTimeMs?.let { stringResource(Res.string.total_time) to it.toDouble() },
            run.platform.waitTimeMs?.let { stringResource(Res.string.wait_time) to it.toDouble() },
            run.platform.fullyDrawnTimeMs?.let { stringResource(Res.string.fully_drawn_time) to it.toDouble() },
        ),
    )
    Text(stringResource(Res.string.agent_phases_separate_clock_domain), fontWeight = FontWeight.SemiBold)
    if (run.phases.isEmpty()) {
        Text(stringResource(Res.string.no_agent_phases_available))
    } else {
        run.phases.forEach { phase ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(phase.name, Modifier.width(220.dp))
                Text((phase.durationNs / 1_000_000.0).formatMs())
                ConfidenceBadge(phase.confidence)
            }
        }
    }
    if (run.milestones.isNotEmpty()) {
        Text(stringResource(Res.string.milestones), fontWeight = FontWeight.SemiBold)
        run.milestones.forEach { milestone ->
            Text(stringResource(Res.string.text_aeb7e472, milestone.kind.name, milestone.confidence.name, milestone.activityName.orEmpty()))
        }
    }
    Text(stringResource(Res.string.raw_am_start_w_evidence), fontWeight = FontWeight.SemiBold)
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(4.dp),
            ).padding(vertical = 6.dp, horizontal = 8.dp),
    ) {
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
