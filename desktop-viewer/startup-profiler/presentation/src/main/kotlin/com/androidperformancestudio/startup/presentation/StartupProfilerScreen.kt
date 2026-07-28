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

import com.androidperformancestudio.ui.localizedStringResource
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
        Text(localizedStringResource(Res.string.startup_profiler, chinese), style = MaterialTheme.typography.headlineMedium)
        Text(
            localizedStringResource(Res.string.choose_a_device_and_app_to_run_repeatable_cold_warm, chinese),
        )
        state.operationMessage?.let { Text(it) }
        if (state.isRunning && state.totalRuns > 0) Text(localizedStringResource(Res.string.text, chinese, state.completedRuns, state.totalRuns))
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
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 6.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MetricCard(localizedStringResource(Res.string.median_totaltime, chinese), analysis.totalTime, chinese)
            MetricCard(localizedStringResource(Res.string.median_first_frame, chinese), analysis.firstFrame, chinese)
            MetricCard(localizedStringResource(Res.string.median_fully_drawn, chinese), analysis.fullyDrawn, chinese)
            StabilityCard(analysis, chinese)
        }
        state.baseline?.let { baseline -> BaselineComparison(analysis, baseline, chinese) }
        Text(localizedStringResource(Res.string.measured_runs, chinese), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            RunRowHeader(chinese)
            analysis.runs.forEach { run -> RunRow(run, run.id == selected.id, actions.onSelectRun, chinese) }
        }
        HorizontalDivider()
        RunDetail(selected, chinese)
        if (analysis.warnings.isNotEmpty()) {
            Text(localizedStringResource(Res.string.warnings, chinese), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            analysis.warnings.forEach { Text(localizedStringResource(Res.string.text_45f5c8ce, chinese, it), color = MaterialTheme.colorScheme.tertiary) }
        }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun MetricCard(
    title: String,
    statistics: StartupStatistics,
    chinese: Boolean,
) {
    Card(Modifier.width(172.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(vertical = 6.dp, horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(statistics.medianMs.formatMs(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(localizedStringResource(Res.string.p90_n, chinese, statistics.p90Ms.formatMs(), statistics.count), style = MaterialTheme.typography.bodySmall)
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
            ratio == null -> localizedStringResource(Res.string.insufficient, chinese)
            ratio <= 0.05 -> localizedStringResource(Res.string.stable, chinese)
            ratio <= 0.15 -> localizedStringResource(Res.string.variable, chinese)
            else -> localizedStringResource(Res.string.unstable, chinese)
        }
    Card(Modifier.width(156.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(vertical = 6.dp, horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(localizedStringResource(Res.string.stability, chinese), style = MaterialTheme.typography.labelLarge)
            Text(label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(localizedStringResource(Res.string.mad, chinese, deviation.formatMs()), style = MaterialTheme.typography.bodySmall)
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
            localizedStringResource(Res.string.compared_with_previous_experiment_difference_only_no_statistical_signi, chinese, change.formatPercent()),
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp),
        )
    }
}

@Composable
private fun RunRowHeader(chinese: Boolean) {
    Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 6.dp, horizontal = 8.dp)) {
        TableCell(localizedStringResource(Res.string.run, chinese), 70)
        TableCell(localizedStringResource(Res.string.observed, chinese), 100)
        TableCell(localizedStringResource(Res.string.total, chinese), 100)
        TableCell(localizedStringResource(Res.string.displayed, chinese), 100)
        TableCell(localizedStringResource(Res.string.fully_drawn, chinese), 110)
        TableCell(localizedStringResource(Res.string.agent, chinese), 80)
    }
}

@Composable
private fun RunRow(
    run: StartupRun,
    selected: Boolean,
    onSelect: (String) -> Unit,
    chinese: Boolean,
) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(Modifier.background(background).clickable { onSelect(run.id) }.padding(vertical = 6.dp, horizontal = 8.dp)) {
        TableCell(run.iteration.toString(), 70)
        TableCell(run.observedType.name, 100)
        TableCell(run.platform.totalTimeMs.formatMs(), 100)
        TableCell(run.platform.displayedTimeMs.formatMs(), 100)
        TableCell(run.platform.fullyDrawnTimeMs.formatMs(), 110)
        TableCell(
            localizedStringResource(
                if (run.rawEvidence.agentAvailable) Res.string.full else Res.string.fallback,
                chinese,
            ),
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
    chinese: Boolean,
) {
    Text(
        localizedStringResource(Res.string.run_detail, chinese, run.iteration, run.observedType.name),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(localizedStringResource(Res.string.platform_timeline, chinese), fontWeight = FontWeight.SemiBold)
    TimelineBar(
        listOfNotNull(
            run.platform.thisTimeMs?.let { localizedStringResource(Res.string.this_time, chinese) to it.toDouble() },
            run.platform.totalTimeMs?.let { localizedStringResource(Res.string.total_time, chinese) to it.toDouble() },
            run.platform.waitTimeMs?.let { localizedStringResource(Res.string.wait_time, chinese) to it.toDouble() },
            run.platform.fullyDrawnTimeMs?.let { localizedStringResource(Res.string.fully_drawn_time, chinese) to it.toDouble() },
        ),
    )
    Text(localizedStringResource(Res.string.agent_phases_separate_clock_domain, chinese), fontWeight = FontWeight.SemiBold)
    if (run.phases.isEmpty()) {
        Text(localizedStringResource(Res.string.no_agent_phases_available, chinese))
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
        Text(localizedStringResource(Res.string.milestones, chinese), fontWeight = FontWeight.SemiBold)
        run.milestones.forEach { milestone ->
            Text(localizedStringResource(Res.string.text_aeb7e472, chinese, milestone.kind.name, milestone.confidence.name, milestone.activityName.orEmpty()))
        }
    }
    Text(localizedStringResource(Res.string.raw_am_start_w_evidence, chinese), fontWeight = FontWeight.SemiBold)
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
