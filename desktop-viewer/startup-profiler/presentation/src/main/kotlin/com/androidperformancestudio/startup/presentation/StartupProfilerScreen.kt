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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.startup.analysis.StartupAnalysisResult
import com.androidperformancestudio.startup.analysis.StartupComparison
import com.androidperformancestudio.startup.model.EvidenceConfidence
import com.androidperformancestudio.startup.model.StartupMilestoneKind
import com.androidperformancestudio.startup.model.StartupPerfettoSlice
import com.androidperformancestudio.startup.model.StartupPhase
import com.androidperformancestudio.startup.model.StartupRun
import com.androidperformancestudio.startup.model.StartupStatistics
import com.androidperformancestudio.startup.model.StartupType
import com.androidperformancestudio.startup.presentation.generated.resources.Res
import com.androidperformancestudio.startup.presentation.generated.resources.activity_created
import com.androidperformancestudio.startup.presentation.generated.resources.activity_pre_create
import com.androidperformancestudio.startup.presentation.generated.resources.activity_resumed
import com.androidperformancestudio.startup.presentation.generated.resources.activity_started
import com.androidperformancestudio.startup.presentation.generated.resources.agent
import com.androidperformancestudio.startup.presentation.generated.resources.agent_first_frame
import com.androidperformancestudio.startup.presentation.generated.resources.agent_phases_separate_clock_domain
import com.androidperformancestudio.startup.presentation.generated.resources.agent_ready
import com.androidperformancestudio.startup.presentation.generated.resources.baseline_comparison
import com.androidperformancestudio.startup.presentation.generated.resources.choose_a_device_and_app_to_run_repeatable_cold_warm
import com.androidperformancestudio.startup.presentation.generated.resources.cold
import com.androidperformancestudio.startup.presentation.generated.resources.compilation_details
import com.androidperformancestudio.startup.presentation.generated.resources.correlated
import com.androidperformancestudio.startup.presentation.generated.resources.correlation_error_bound
import com.androidperformancestudio.startup.presentation.generated.resources.diagnostics
import com.androidperformancestudio.startup.presentation.generated.resources.displayed
import com.androidperformancestudio.startup.presentation.generated.resources.environment_evidence
import com.androidperformancestudio.startup.presentation.generated.resources.estimated
import com.androidperformancestudio.startup.presentation.generated.resources.exact
import com.androidperformancestudio.startup.presentation.generated.resources.fallback
import com.androidperformancestudio.startup.presentation.generated.resources.first_draw_callback
import com.androidperformancestudio.startup.presentation.generated.resources.first_frame
import com.androidperformancestudio.startup.presentation.generated.resources.full
import com.androidperformancestudio.startup.presentation.generated.resources.fully_drawn
import com.androidperformancestudio.startup.presentation.generated.resources.fully_drawn_time
import com.androidperformancestudio.startup.presentation.generated.resources.hot
import com.androidperformancestudio.startup.presentation.generated.resources.inferred
import com.androidperformancestudio.startup.presentation.generated.resources.initializer_enter
import com.androidperformancestudio.startup.presentation.generated.resources.insufficient
import com.androidperformancestudio.startup.presentation.generated.resources.low_tail_resolution
import com.androidperformancestudio.startup.presentation.generated.resources.mad
import com.androidperformancestudio.startup.presentation.generated.resources.measured_runs
import com.androidperformancestudio.startup.presentation.generated.resources.median_first_frame
import com.androidperformancestudio.startup.presentation.generated.resources.median_fully_drawn
import com.androidperformancestudio.startup.presentation.generated.resources.median_totaltime
import com.androidperformancestudio.startup.presentation.generated.resources.metric_evidence
import com.androidperformancestudio.startup.presentation.generated.resources.milestones
import com.androidperformancestudio.startup.presentation.generated.resources.no
import com.androidperformancestudio.startup.presentation.generated.resources.no_agent_phases_available
import com.androidperformancestudio.startup.presentation.generated.resources.not_correlated
import com.androidperformancestudio.startup.presentation.generated.resources.observed
import com.androidperformancestudio.startup.presentation.generated.resources.open_trace
import com.androidperformancestudio.startup.presentation.generated.resources.p90_n
import com.androidperformancestudio.startup.presentation.generated.resources.p95_n
import com.androidperformancestudio.startup.presentation.generated.resources.phase_activity_create
import com.androidperformancestudio.startup.presentation.generated.resources.phase_activity_to_resumed
import com.androidperformancestudio.startup.presentation.generated.resources.phase_agent_initialization
import com.androidperformancestudio.startup.presentation.generated.resources.phase_comparison
import com.androidperformancestudio.startup.presentation.generated.resources.phase_first_frame_to_fully_drawn
import com.androidperformancestudio.startup.presentation.generated.resources.phase_process_bootstrap
import com.androidperformancestudio.startup.presentation.generated.resources.phase_range
import com.androidperformancestudio.startup.presentation.generated.resources.phase_resumed_to_first_frame
import com.androidperformancestudio.startup.presentation.generated.resources.platform_timeline
import com.androidperformancestudio.startup.presentation.generated.resources.process_start
import com.androidperformancestudio.startup.presentation.generated.resources.raw_am_start_w_evidence
import com.androidperformancestudio.startup.presentation.generated.resources.report_fully_drawn_hint
import com.androidperformancestudio.startup.presentation.generated.resources.requested
import com.androidperformancestudio.startup.presentation.generated.resources.run
import com.androidperformancestudio.startup.presentation.generated.resources.run_detail
import com.androidperformancestudio.startup.presentation.generated.resources.stability
import com.androidperformancestudio.startup.presentation.generated.resources.stable
import com.androidperformancestudio.startup.presentation.generated.resources.startup_profiler
import com.androidperformancestudio.startup.presentation.generated.resources.text
import com.androidperformancestudio.startup.presentation.generated.resources.text_45f5c8ce
import com.androidperformancestudio.startup.presentation.generated.resources.text_aeb7e472
import com.androidperformancestudio.startup.presentation.generated.resources.this_time
import com.androidperformancestudio.startup.presentation.generated.resources.total
import com.androidperformancestudio.startup.presentation.generated.resources.total_time
import com.androidperformancestudio.startup.presentation.generated.resources.trace_binder_slices
import com.androidperformancestudio.startup.presentation.generated.resources.trace_correlation
import com.androidperformancestudio.startup.presentation.generated.resources.trace_evidence
import com.androidperformancestudio.startup.presentation.generated.resources.trace_failure_reason
import com.androidperformancestudio.startup.presentation.generated.resources.trace_frame_slices
import com.androidperformancestudio.startup.presentation.generated.resources.trace_limitations
import com.androidperformancestudio.startup.presentation.generated.resources.trace_main_thread_slices
import com.androidperformancestudio.startup.presentation.generated.resources.trace_more_slices
import com.androidperformancestudio.startup.presentation.generated.resources.trace_open_failed
import com.androidperformancestudio.startup.presentation.generated.resources.trace_scheduling_slices
import com.androidperformancestudio.startup.presentation.generated.resources.trace_slice_count
import com.androidperformancestudio.startup.presentation.generated.resources.trace_slice_detail
import com.androidperformancestudio.startup.presentation.generated.resources.unavailable
import com.androidperformancestudio.startup.presentation.generated.resources.unknown
import com.androidperformancestudio.startup.presentation.generated.resources.unstable
import com.androidperformancestudio.startup.presentation.generated.resources.variable
import com.androidperformancestudio.startup.presentation.generated.resources.wait_time
import com.androidperformancestudio.startup.presentation.generated.resources.warm
import com.androidperformancestudio.startup.presentation.generated.resources.warnings
import com.androidperformancestudio.startup.presentation.generated.resources.yes
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioDataTable
import com.androidperformancestudio.ui.studio.StudioMetricCard
import com.androidperformancestudio.ui.studio.StudioTableColumn
import com.androidperformancestudio.ui.studio.StudioTokens
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
        Text(
            localizedStringResource(Res.string.startup_profiler, language),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            localizedStringResource(
                Res.string.choose_a_device_and_app_to_run_repeatable_cold_warm,
                language,
            ),
        )
        state.operationMessage?.let { Text(it) }
        if (state.isRunning && state.totalRuns > 0) {
            Text(localizedStringResource(Res.string.text, language, state.completedRuns, state.totalRuns))
        }
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
            MetricCard(localizedStringResource(Res.string.median_totaltime, language), analysis.totalTime, language)
            MetricCard(localizedStringResource(Res.string.median_first_frame, language), analysis.firstFrame, language)
            MetricCard(localizedStringResource(Res.string.median_fully_drawn, language), analysis.fullyDrawn, language)
            StabilityCard(analysis, language)
        }
        state.comparison?.let { comparison -> BaselineComparison(comparison, language) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val tableWidth = maxOf(maxWidth, RUN_TABLE_MIN_WIDTH)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                StudioDataTable(
                    columns = runTableColumns(language),
                    title = localizedStringResource(Res.string.measured_runs, language),
                    modifier = Modifier.width(tableWidth),
                ) {
                    analysis.runs.forEach { run -> RunRow(run, run.id == selected.id, actions.onSelectRun, language) }
                }
            }
        }
        HorizontalDivider()
        RunDetail(selected, actions, language)
        if (analysis.warnings.isNotEmpty()) {
            Text(
                localizedStringResource(Res.string.warnings, language),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            analysis.warnings.forEach {
                Text(
                    localizedStringResource(Res.string.text_45f5c8ce, language, it),
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
    statistics: StartupStatistics,
    language: UiLanguage,
) {
    StudioMetricCard(
        label = title,
        value = statistics.medianMs.formatMs(),
        modifier = Modifier.width(172.dp),
        supportingText = statistics.metricSupportingText(language),
    )
}

internal fun StartupStatistics.metricSupportingText(language: UiLanguage): String =
    listOfNotNull(
        localizedStringResource(Res.string.p90_n, language, p90Ms.formatMs(), count),
        localizedStringResource(Res.string.p95_n, language, p95Ms.formatMs(), count),
        localizedStringResource(Res.string.low_tail_resolution, language)
            .takeIf { p90LowResolution || p95LowResolution },
    ).joinToString("\n")

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
            ratio == null -> localizedStringResource(Res.string.insufficient, language)
            ratio <= 0.05 -> localizedStringResource(Res.string.stable, language)
            ratio <= 0.15 -> localizedStringResource(Res.string.variable, language)
            else -> localizedStringResource(Res.string.unstable, language)
        }
    StudioMetricCard(
        label = localizedStringResource(Res.string.stability, language),
        value = label,
        modifier = Modifier.width(156.dp),
        supportingText = localizedStringResource(Res.string.mad, language, deviation.formatMs()),
    )
}

@Composable
private fun BaselineComparison(
    comparison: StartupComparison,
    language: UiLanguage,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(vertical = 6.dp, horizontal = 8.dp)) {
            Text(
                localizedStringResource(
                    Res.string.baseline_comparison,
                    language,
                    comparison.status.name
                        .lowercase()
                        .replace('_', ' '),
                    comparison.medianDifferenceMs.formatMs(),
                    comparison.confidenceIntervalLowMs.formatMs(),
                    comparison.confidenceIntervalHighMs.formatMs(),
                ),
            )
            comparison.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            comparison.phaseDifferences.forEach { phase ->
                Text(
                    localizedStringResource(
                        Res.string.phase_comparison,
                        language,
                        phase.name,
                        phase.medianDifferenceMs.formatMs(),
                        phase.confidence.localizedLabel(language),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun runTableColumns(language: UiLanguage): List<StudioTableColumn> =
    listOf(
        StudioTableColumn(localizedStringResource(Res.string.run, language), RUN_COLUMN_WEIGHT),
        StudioTableColumn(localizedStringResource(Res.string.requested, language), TYPE_COLUMN_WEIGHT),
        StudioTableColumn(localizedStringResource(Res.string.observed, language), TYPE_COLUMN_WEIGHT),
        StudioTableColumn(localizedStringResource(Res.string.total, language), TIME_COLUMN_WEIGHT),
        StudioTableColumn(localizedStringResource(Res.string.displayed, language), TIME_COLUMN_WEIGHT),
        StudioTableColumn(localizedStringResource(Res.string.fully_drawn, language), FULLY_DRAWN_COLUMN_WEIGHT),
        StudioTableColumn(localizedStringResource(Res.string.agent, language), AGENT_COLUMN_WEIGHT),
    )

@Composable
private fun RunRow(
    run: StartupRun,
    selected: Boolean,
    onSelect: (String) -> Unit,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    val background = if (selected) colors.accent.copy(alpha = 0.12f) else colors.transparent
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(background)
                .selectable(selected = selected, onClick = { onSelect(run.id) }, role = Role.RadioButton)
                .padding(horizontal = StudioTokens.compactContentPadding, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TableCell(run.iteration.toString(), RUN_COLUMN_WEIGHT, emphasized = selected)
        TableCell(run.requestedType.localizedLabel(language), TYPE_COLUMN_WEIGHT)
        TableCell(run.observedType.localizedLabel(language), TYPE_COLUMN_WEIGHT)
        TableCell(run.platform.totalTimeMs.formatMs(), TIME_COLUMN_WEIGHT)
        TableCell(run.platform.displayedTimeMs.formatMs(), TIME_COLUMN_WEIGHT)
        TableCell(run.platform.fullyDrawnTimeMs.formatMs(), FULLY_DRAWN_COLUMN_WEIGHT)
        TableCell(
            localizedStringResource(
                if (run.rawEvidence.agentAvailable) Res.string.full else Res.string.fallback,
                language,
            ),
            AGENT_COLUMN_WEIGHT,
        )
    }
}

@Composable
private fun RowScope.TableCell(
    text: String,
    weight: Float,
    emphasized: Boolean = false,
) {
    Text(
        text,
        modifier = Modifier.weight(weight),
        color = LocalViewerColors.current.primaryText,
        fontSize = ViewerTypography.label.fontSize,
        fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RunDetail(
    run: StartupRun,
    actions: StartupProfilerActions,
    language: UiLanguage,
) {
    var traceOpenFailed by remember(run.id) { mutableStateOf(false) }
    Text(
        localizedStringResource(
            Res.string.run_detail,
            language,
            run.iteration,
            run.requestedType.localizedLabel(language),
            run.observedType.localizedLabel(language),
        ),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(localizedStringResource(Res.string.platform_timeline, language), fontWeight = FontWeight.SemiBold)
    Text(
        localizedStringResource(
            Res.string.metric_evidence,
            language,
            "TTID",
            run.ttidEvidence.source?.name ?: localizedStringResource(Res.string.unavailable, language),
            run.ttidEvidence.statusLabel(language),
        ),
    )
    Text(
        localizedStringResource(
            Res.string.metric_evidence,
            language,
            localizedStringResource(Res.string.agent_first_frame, language),
            run.agentFirstFrameEvidence.source?.name ?: localizedStringResource(Res.string.unavailable, language),
            run.agentFirstFrameEvidence.statusLabel(language),
        ),
    )
    Text(
        localizedStringResource(
            Res.string.metric_evidence,
            language,
            "TTFD",
            run.ttfdEvidence.source?.name ?: localizedStringResource(Res.string.unavailable, language),
            run.ttfdEvidence.statusLabel(language),
        ),
    )
    if (run.platform.fullyDrawnTimeMs == null) Text(localizedStringResource(Res.string.report_fully_drawn_hint, language))
    Text(localizedStringResource(Res.string.compilation_details, language), fontWeight = FontWeight.SemiBold)
    val compilationEvidence = run.compilationEvidence
    if (compilationEvidence == null) {
        Text(localizedStringResource(Res.string.unavailable, language))
    } else {
        compilationEvidence.detailRows(language).forEach { row -> Text("${row.label}: ${row.value}") }
    }
    run.environmentEvidence?.let { evidence ->
        Text(
            localizedStringResource(
                Res.string.environment_evidence,
                language,
                evidence.deviceModel.orEmpty().ifEmpty { "—" },
                evidence.apiLevel ?: "—",
                evidence.emulator ?: "—",
                evidence.batteryPercent?.let { "$it%" } ?: "—",
                evidence.charging ?: "—",
                evidence.thermalStatus ?: "—",
                evidence.capturedAt?.toString() ?: "—",
            ),
        )
    }
    run.traceEvidence?.let { evidence ->
        Text(
            localizedStringResource(
                Res.string.trace_evidence,
                language,
                evidence.file ?: localizedStringResource(Res.string.unavailable, language),
                localizedStringResource(if (evidence.captured) Res.string.yes else Res.string.no, language),
                localizedStringResource(if (evidence.truncated) Res.string.yes else Res.string.no, language),
            ),
        )
        evidence.failureReason?.let { reason -> Text(localizedStringResource(Res.string.trace_failure_reason, language, reason)) }
        evidence.artifact?.let { artifact ->
            Text(
                "Artifact: ${artifact.completeness.name.lowercase()} · ${artifact.availableCapabilities.size} capabilities",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val traceFile = evidence.file
        if (evidence.captured && !traceFile.isNullOrBlank()) {
            actions.onOpenTrace?.let { openTrace ->
                OutlinedButton(onClick = { traceOpenFailed = !openTrace(traceFile) }) {
                    Text(localizedStringResource(Res.string.open_trace, language))
                }
                if (traceOpenFailed) {
                    Text(localizedStringResource(Res.string.trace_open_failed, language), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        evidence.rootCause?.let { rootCause ->
            Text(
                localizedStringResource(Res.string.trace_correlation, language) + ": " +
                    localizedStringResource(
                        if (rootCause.correlated) Res.string.correlated else Res.string.not_correlated,
                        language,
                    ),
            )
            Text(
                localizedStringResource(Res.string.correlation_error_bound, language) + ": " +
                    (rootCause.correlationErrorBoundNs?.let { "±$it ns" } ?: localizedStringResource(Res.string.unavailable, language)),
            )
            TraceSliceGroup(localizedStringResource(Res.string.trace_scheduling_slices, language), rootCause.schedulingSlices, language)
            TraceSliceGroup(localizedStringResource(Res.string.trace_binder_slices, language), rootCause.binderSlices, language)
            TraceSliceGroup(localizedStringResource(Res.string.trace_main_thread_slices, language), rootCause.mainThreadSlices, language)
            TraceSliceGroup(localizedStringResource(Res.string.trace_frame_slices, language), rootCause.frameSlices, language)
            if (rootCause.limitations.isNotEmpty()) {
                Text(localizedStringResource(Res.string.trace_limitations, language), fontWeight = FontWeight.SemiBold)
                rootCause.limitations.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
            rootCause.phaseAttributions.forEach { phase ->
                Text(
                    "${phase.phaseName}: GC ${phase.gcNs / 1_000_000.0} ms · " +
                        "JIT ${phase.jitNs / 1_000_000.0} ms · " +
                        "class load ${phase.classLoadingNs / 1_000_000.0} ms · " +
                        "verify/init ${phase.classVerificationNs / 1_000_000.0} ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (run.diagnostics.isNotEmpty()) {
        Text(localizedStringResource(Res.string.diagnostics, language, run.diagnostics.joinToString()))
    }
    TimelineBar(
        listOfNotNull(
            run.platform.thisTimeMs?.let {
                localizedStringResource(Res.string.this_time, language) to it.toDouble()
            },
            run.platform.totalTimeMs?.let {
                localizedStringResource(Res.string.total_time, language) to it.toDouble()
            },
            run.platform.waitTimeMs?.let {
                localizedStringResource(Res.string.wait_time, language) to it.toDouble()
            },
            run.platform.fullyDrawnTimeMs?.let {
                localizedStringResource(Res.string.fully_drawn_time, language) to it.toDouble()
            },
        ),
    )
    Text(
        localizedStringResource(Res.string.agent_phases_separate_clock_domain, language),
        fontWeight = FontWeight.SemiBold,
    )
    if (run.phases.isEmpty()) {
        Text(localizedStringResource(Res.string.no_agent_phases_available, language))
    } else {
        run.phases.forEach { phase ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(phase.localizedName(language), Modifier.width(220.dp))
                Text((phase.durationNs / 1_000_000.0).formatMs())
                ConfidenceBadge(phase.confidence, language)
            }
        }
    }
    if (run.milestones.isNotEmpty()) {
        Text(localizedStringResource(Res.string.milestones, language), fontWeight = FontWeight.SemiBold)
        run.milestones.forEach { milestone ->
            Text(
                localizedStringResource(
                    Res.string.text_aeb7e472,
                    language,
                    milestone.kind.localizedLabel(language),
                    milestone.confidence.localizedLabel(language),
                    milestone.activityName.orEmpty(),
                ),
            )
        }
    }
    Text(localizedStringResource(Res.string.raw_am_start_w_evidence, language), fontWeight = FontWeight.SemiBold)
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
private fun TraceSliceGroup(
    label: String,
    slices: List<StartupPerfettoSlice>,
    language: UiLanguage,
) {
    Text(localizedStringResource(Res.string.trace_slice_count, language, label, slices.size), fontWeight = FontWeight.SemiBold)
    slices.take(MAX_VISIBLE_TRACE_SLICES).forEach { slice ->
        Text(
            localizedStringResource(
                Res.string.trace_slice_detail,
                language,
                slice.name,
                slice.threadName ?: "—",
                slice.timestampNs,
                slice.durationNs,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (slices.size > MAX_VISIBLE_TRACE_SLICES) {
        Text(localizedStringResource(Res.string.trace_more_slices, language, MAX_VISIBLE_TRACE_SLICES, slices.size))
    }
}

private const val MAX_VISIBLE_TRACE_SLICES = 5

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
private fun ConfidenceBadge(
    confidence: EvidenceConfidence,
    language: UiLanguage,
) {
    val color = if (confidence == EvidenceConfidence.EXACT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Text(confidence.localizedLabel(language), color = color, style = MaterialTheme.typography.labelSmall)
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

private val RUN_TABLE_MIN_WIDTH = 760.dp
private const val RUN_COLUMN_WEIGHT = 0.7f
private const val TYPE_COLUMN_WEIGHT = 1f
private const val TIME_COLUMN_WEIGHT = 1f
private const val FULLY_DRAWN_COLUMN_WEIGHT = 1.1f
private const val AGENT_COLUMN_WEIGHT = 0.8f

private fun StartupType.localizedLabel(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            StartupType.COLD -> Res.string.cold
            StartupType.WARM -> Res.string.warm
            StartupType.HOT -> Res.string.hot
            StartupType.UNKNOWN -> Res.string.unknown
        },
        language,
    )

internal fun EvidenceConfidence.localizedLabel(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            EvidenceConfidence.EXACT -> Res.string.exact
            EvidenceConfidence.ESTIMATED -> Res.string.estimated
            EvidenceConfidence.INFERRED -> Res.string.inferred
            EvidenceConfidence.UNAVAILABLE -> Res.string.unavailable
        },
        language,
    )

private fun StartupMilestoneKind.localizedLabel(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            StartupMilestoneKind.PROCESS_START -> Res.string.process_start
            StartupMilestoneKind.INITIALIZER_ENTER -> Res.string.initializer_enter
            StartupMilestoneKind.AGENT_READY -> Res.string.agent_ready
            StartupMilestoneKind.ACTIVITY_PRE_CREATE -> Res.string.activity_pre_create
            StartupMilestoneKind.ACTIVITY_CREATED -> Res.string.activity_created
            StartupMilestoneKind.ACTIVITY_STARTED -> Res.string.activity_started
            StartupMilestoneKind.ACTIVITY_RESUMED -> Res.string.activity_resumed
            StartupMilestoneKind.FIRST_FRAME -> Res.string.first_frame
            StartupMilestoneKind.FIRST_DRAW_CALLBACK -> Res.string.first_draw_callback
            StartupMilestoneKind.FULLY_DRAWN -> Res.string.fully_drawn
        },
        language,
    )

private fun StartupPhase.localizedName(language: UiLanguage): String =
    localizedStringResource(
        when (start to end) {
            StartupMilestoneKind.PROCESS_START to StartupMilestoneKind.INITIALIZER_ENTER ->
                Res.string.phase_process_bootstrap
            StartupMilestoneKind.INITIALIZER_ENTER to StartupMilestoneKind.AGENT_READY ->
                Res.string.phase_agent_initialization
            StartupMilestoneKind.ACTIVITY_PRE_CREATE to StartupMilestoneKind.ACTIVITY_CREATED ->
                Res.string.phase_activity_create
            StartupMilestoneKind.ACTIVITY_CREATED to StartupMilestoneKind.ACTIVITY_RESUMED ->
                Res.string.phase_activity_to_resumed
            StartupMilestoneKind.ACTIVITY_RESUMED to StartupMilestoneKind.FIRST_FRAME ->
                Res.string.phase_resumed_to_first_frame
            StartupMilestoneKind.FIRST_FRAME to StartupMilestoneKind.FULLY_DRAWN ->
                Res.string.phase_first_frame_to_fully_drawn
            else ->
                return localizedStringResource(
                    Res.string.phase_range,
                    language,
                    start.localizedLabel(language),
                    end.localizedLabel(language),
                )
        },
        language,
    )
