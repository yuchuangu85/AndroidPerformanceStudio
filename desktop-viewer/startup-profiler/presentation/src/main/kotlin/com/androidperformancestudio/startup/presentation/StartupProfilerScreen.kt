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

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.startup.model.EvidenceConfidence
import com.androidperformancestudio.startup.model.StartupMilestoneKind
import com.androidperformancestudio.startup.model.StartupPerfettoSlice
import com.androidperformancestudio.startup.model.StartupPhase
import com.androidperformancestudio.startup.model.StartupRun
import com.androidperformancestudio.startup.model.StartupType
import com.androidperformancestudio.startup.presentation.generated.resources.Res
import com.androidperformancestudio.startup.presentation.generated.resources.activity_created
import com.androidperformancestudio.startup.presentation.generated.resources.activity_pre_create
import com.androidperformancestudio.startup.presentation.generated.resources.activity_resumed
import com.androidperformancestudio.startup.presentation.generated.resources.activity_started
import com.androidperformancestudio.startup.presentation.generated.resources.agent
import com.androidperformancestudio.startup.presentation.generated.resources.agent_first_frame
import com.androidperformancestudio.startup.presentation.generated.resources.agent_ready
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
import com.androidperformancestudio.startup.presentation.generated.resources.export_csv
import com.androidperformancestudio.startup.presentation.generated.resources.export_json
import com.androidperformancestudio.startup.presentation.generated.resources.fallback
import com.androidperformancestudio.startup.presentation.generated.resources.first_draw_callback
import com.androidperformancestudio.startup.presentation.generated.resources.first_frame
import com.androidperformancestudio.startup.presentation.generated.resources.full
import com.androidperformancestudio.startup.presentation.generated.resources.fully_drawn
import com.androidperformancestudio.startup.presentation.generated.resources.fully_drawn_time
import com.androidperformancestudio.startup.presentation.generated.resources.hot
import com.androidperformancestudio.startup.presentation.generated.resources.import_report
import com.androidperformancestudio.startup.presentation.generated.resources.inferred
import com.androidperformancestudio.startup.presentation.generated.resources.initializer_enter
import com.androidperformancestudio.startup.presentation.generated.resources.measured_runs
import com.androidperformancestudio.startup.presentation.generated.resources.metric_evidence
import com.androidperformancestudio.startup.presentation.generated.resources.milestones
import com.androidperformancestudio.startup.presentation.generated.resources.no
import com.androidperformancestudio.startup.presentation.generated.resources.no_platform_durations
import com.androidperformancestudio.startup.presentation.generated.resources.not_correlated
import com.androidperformancestudio.startup.presentation.generated.resources.observed
import com.androidperformancestudio.startup.presentation.generated.resources.open_trace
import com.androidperformancestudio.startup.presentation.generated.resources.optimization_empty
import com.androidperformancestudio.startup.presentation.generated.resources.optimization_evidence
import com.androidperformancestudio.startup.presentation.generated.resources.phase_activity_create
import com.androidperformancestudio.startup.presentation.generated.resources.phase_activity_to_resumed
import com.androidperformancestudio.startup.presentation.generated.resources.phase_agent_initialization
import com.androidperformancestudio.startup.presentation.generated.resources.phase_details
import com.androidperformancestudio.startup.presentation.generated.resources.phase_first_frame_to_fully_drawn
import com.androidperformancestudio.startup.presentation.generated.resources.phase_process_bootstrap
import com.androidperformancestudio.startup.presentation.generated.resources.phase_range
import com.androidperformancestudio.startup.presentation.generated.resources.phase_resumed_to_first_frame
import com.androidperformancestudio.startup.presentation.generated.resources.platform_durations_independent
import com.androidperformancestudio.startup.presentation.generated.resources.platform_timeline
import com.androidperformancestudio.startup.presentation.generated.resources.process_start
import com.androidperformancestudio.startup.presentation.generated.resources.progress_runs
import com.androidperformancestudio.startup.presentation.generated.resources.raw_am_start_w_evidence
import com.androidperformancestudio.startup.presentation.generated.resources.related_actions
import com.androidperformancestudio.startup.presentation.generated.resources.report_fully_drawn_hint
import com.androidperformancestudio.startup.presentation.generated.resources.requested
import com.androidperformancestudio.startup.presentation.generated.resources.run
import com.androidperformancestudio.startup.presentation.generated.resources.run_detail
import com.androidperformancestudio.startup.presentation.generated.resources.startup_waterfall
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
import com.androidperformancestudio.startup.presentation.generated.resources.wait_time
import com.androidperformancestudio.startup.presentation.generated.resources.warm
import com.androidperformancestudio.startup.presentation.generated.resources.waterfall_clock_domain
import com.androidperformancestudio.startup.presentation.generated.resources.waterfall_interval
import com.androidperformancestudio.startup.presentation.generated.resources.waterfall_no_intervals
import com.androidperformancestudio.startup.presentation.generated.resources.waterfall_unplotted_phases
import com.androidperformancestudio.startup.presentation.generated.resources.yes
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioCard
import com.androidperformancestudio.ui.studio.StudioDataTable
import com.androidperformancestudio.ui.studio.StudioPanel
import com.androidperformancestudio.ui.studio.StudioPanelHeader
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
    val selected = state.analysis?.runs?.let { runs -> runs.firstOrNull { it.id == state.selectedRunId } ?: runs.firstOrNull() }
    val colors = LocalViewerColors.current
    BoxWithConstraints(modifier.fillMaxSize().background(colors.workspace).padding(StudioTokens.workspacePadding)) {
        if (maxWidth >= 1100.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)) {
                Column(
                    Modifier.weight(1f).testTag("startup-main").verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
                ) {
                    StartupMainWorkspace(state, selected, actions, language)
                }
                Column(
                    Modifier.width(300.dp).testTag("startup-evidence-rail").verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
                ) {
                    StartupEvidenceRail(state, selected, actions, language)
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().testTag("startup-stacked-workspace").verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
            ) {
                Column(
                    Modifier.fillMaxWidth().testTag("startup-main"),
                    verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
                ) {
                    StartupMainWorkspace(state, selected, actions, language)
                }
                Column(
                    Modifier.fillMaxWidth().testTag("startup-evidence-rail"),
                    verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
                ) {
                    StartupEvidenceRail(state, selected, actions, language)
                }
            }
        }
    }
}

@Composable
private fun StartupMainWorkspace(
    state: StartupProfilerState,
    selected: StartupRun?,
    actions: StartupProfilerActions,
    language: UiLanguage,
) {
    StartupTypeTabs(state, actions, language)
    StartupScenarioControls(state, actions, language)
    StartupMetricOverview(state.analysis, language)
    val analysis = state.analysis
    if (analysis == null || selected == null) {
        StudioPanel(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = StudioTokens.compactContentPadding,
        ) {
            StudioPanelHeader(localizedStringResource(Res.string.startup_waterfall, language))
            Text(localizedStringResource(Res.string.choose_a_device_and_app_to_run_repeatable_cold_warm, language))
        }
    } else {
        StartupRunsWaterfall(analysis.runs, selected, actions, language, state.isRunning)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val tableWidth = maxOf(maxWidth, RUN_TABLE_MIN_WIDTH)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                StudioDataTable(
                    columns = runTableColumns(language),
                    title = localizedStringResource(Res.string.measured_runs, language),
                    modifier = Modifier.width(tableWidth),
                    contentPadding = StudioTokens.compactContentPadding,
                ) {
                    analysis.runs.forEach { run -> RunRow(run, run.id == selected.id, actions.onSelectRun, language) }
                }
            }
        }
    }
    StartupBaselineComparison(state, actions, language)
    state.operationMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    if (state.isRunning && state.totalRuns > 0) {
        Text(localizedStringResource(Res.string.progress_runs, language, state.completedRuns, state.totalRuns))
        LinearProgressIndicator(
            progress = { (state.completedRuns.toFloat() / state.totalRuns).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().testTag("startup-progress"),
        )
    }
}

@Composable
private fun StartupEvidenceRail(
    state: StartupProfilerState,
    selected: StartupRun?,
    actions: StartupProfilerActions,
    language: UiLanguage,
) {
    StartupProfileSummary(selected, language)
    StudioPanel(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = StudioTokens.compactContentPadding,
    ) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap)) {
                StudioPanelHeader(localizedStringResource(Res.string.optimization_evidence, language))
                val findings =
                    (state.warnings + state.analysis?.warnings.orEmpty() + selected?.warnings.orEmpty() + selected?.diagnostics.orEmpty())
                        .distinct()
                if (selected != null && selected.platform.fullyDrawnTimeMs == null) {
                    Text(
                        localizedStringResource(Res.string.report_fully_drawn_hint, language),
                        color = LocalViewerColors.current.warning,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (findings.isEmpty()) {
                    Text(localizedStringResource(Res.string.optimization_empty, language), style = MaterialTheme.typography.bodySmall)
                }
                findings.forEach { Text("• $it", color = LocalViewerColors.current.warning, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    StudioCard(Modifier.fillMaxWidth()) {
        StudioPanelHeader(localizedStringResource(Res.string.related_actions, language))
        ProfilerCompactButton(
            localizedStringResource(Res.string.import_report, language),
            enabled = !state.isRunning,
            onClick = actions.onImport,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfilerCompactButton(
                localizedStringResource(Res.string.export_csv, language),
                enabled =
                    state.analysis != null && !state.isRunning,
                onClick = actions.onExportCsv,
            )
            ProfilerCompactButton(
                localizedStringResource(Res.string.export_json, language),
                enabled =
                    state.analysis != null && !state.isRunning,
                onClick = actions.onExportJson,
            )
        }
    }
    selected?.let { run ->
        StudioPanel(
            modifier = Modifier.fillMaxWidth().testTag("startup-run-detail"),
            contentPadding = StudioTokens.compactContentPadding,
        ) {
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodySmall) {
                RunDetail(run, actions, language)
                ExpandableEvidence(localizedStringResource(Res.string.phase_details, language), run.id) {
                    StartupWaterfallChart(run, language)
                    PlatformDurationBars(run, language)
                }
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
                .testTag("startup-table-${run.id}")
                .background(background)
                .selectable(selected = selected, onClick = { onSelect(run.id) }, role = Role.RadioButton)
                .padding(horizontal = StudioTokens.compactContentPadding, vertical = 4.dp),
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
    ExpandableEvidence(localizedStringResource(Res.string.compilation_details, language), run.id) {
        Text(localizedStringResource(Res.string.compilation_details, language), fontWeight = FontWeight.SemiBold)
        val compilationEvidence = run.compilationEvidence
        if (compilationEvidence == null) {
            Text(localizedStringResource(Res.string.unavailable, language))
        } else {
            compilationEvidence.detailRows(language).forEach { row -> Text("${row.label}: ${row.value}") }
        }
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
    RunTraceEvidence(run, actions, language)
    if (run.diagnostics.isNotEmpty()) {
        Text(localizedStringResource(Res.string.diagnostics, language, run.diagnostics.joinToString()))
    }
    ExpandableEvidence(localizedStringResource(Res.string.raw_am_start_w_evidence, language), run.id) {
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
}

@Composable
private fun RunTraceEvidence(
    run: StartupRun,
    actions: StartupProfilerActions,
    language: UiLanguage,
) {
    val evidence = run.traceEvidence ?: return
    var traceOpenFailed by remember(run.id) { mutableStateOf(false) }
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
            OutlinedButton(modifier = Modifier.testTag("startup-open-trace"), onClick = { traceOpenFailed = !openTrace(traceFile) }) {
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

@Composable
private fun PlatformDurationBars(
    run: StartupRun,
    language: UiLanguage,
) {
    Text(localizedStringResource(Res.string.platform_durations_independent, language), style = MaterialTheme.typography.bodySmall)
    val values =
        listOfNotNull(
            run.platform.thisTimeMs?.takeIf { it >= 0 }?.let {
                localizedStringResource(Res.string.this_time, language) to it.toDouble()
            },
            run.platform.totalTimeMs?.takeIf { it >= 0 }?.let {
                localizedStringResource(Res.string.total_time, language) to it.toDouble()
            },
            run.platform.waitTimeMs?.takeIf { it >= 0 }?.let {
                localizedStringResource(Res.string.wait_time, language) to it.toDouble()
            },
            run.platform.fullyDrawnTimeMs?.takeIf { it >= 0 }?.let {
                localizedStringResource(Res.string.fully_drawn_time, language) to it.toDouble()
            },
        )
    if (values.isEmpty()) {
        Text(localizedStringResource(Res.string.no_platform_durations, language))
    } else {
        TimelineBar(values)
    }
}

@Composable
private fun StartupWaterfallChart(
    run: StartupRun,
    language: UiLanguage,
) {
    val waterfall = remember(run) { run.waterfallModel() }
    val colors = MaterialTheme.colorScheme
    Text(localizedStringResource(Res.string.startup_waterfall, language), fontWeight = FontWeight.SemiBold)
    if (waterfall.groups.isEmpty()) {
        Text(localizedStringResource(Res.string.waterfall_no_intervals, language))
    }
    waterfall.groups.forEach { group ->
        Text(
            localizedStringResource(Res.string.waterfall_clock_domain, language, group.source.name),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        group.segments.forEach { segment ->
            val offsetNs = segment.startNs - group.originNs
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(segment.phase.localizedName(language), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(
                    localizedStringResource(
                        Res.string.waterfall_interval,
                        language,
                        (offsetNs / 1_000_000.0).formatMs(),
                        (segment.phase.durationNs / 1_000_000.0).formatMs(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                ConfidenceBadge(segment.phase.confidence, language)
            }
            Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                drawRect(colors.surfaceVariant)
                val extent = group.extentNs.coerceAtLeast(1L).toDouble()
                val x = (offsetNs / extent * size.width).toFloat().coerceIn(0f, size.width)
                val width =
                    (segment.phase.durationNs / extent * size.width)
                        .toFloat()
                        .coerceAtLeast(2f)
                        .coerceAtMost(size.width - x)
                val color =
                    when (segment.phase.confidence) {
                        EvidenceConfidence.EXACT -> colors.primary
                        EvidenceConfidence.UNAVAILABLE -> colors.outline
                        else -> colors.tertiary
                    }
                if (segment.phase.durationNs == 0L) {
                    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
                } else {
                    drawRect(color, Offset(x, 0f), Size(width, size.height))
                }
            }
        }
    }
    if (waterfall.unplottedPhaseCount > 0) {
        Text(
            localizedStringResource(Res.string.waterfall_unplotted_phases, language, waterfall.unplottedPhaseCount),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
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
                        .weight(1f)
                        .height(16.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((duration / maximum).toFloat().coerceIn(0f, 1f))
                            .height(16.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
                    )
                }
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

internal fun Number?.formatMs(): String = this?.let { String.format(Locale.US, "%.1f ms", toDouble()) } ?: "—"

private val RUN_TABLE_MIN_WIDTH = 760.dp
private const val RUN_COLUMN_WEIGHT = 0.7f
private const val TYPE_COLUMN_WEIGHT = 1f
private const val TIME_COLUMN_WEIGHT = 1f
private const val FULLY_DRAWN_COLUMN_WEIGHT = 1.1f
private const val AGENT_COLUMN_WEIGHT = 0.8f

internal fun StartupType.localizedLabel(language: UiLanguage): String =
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

internal fun StartupPhase.localizedName(language: UiLanguage): String =
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
