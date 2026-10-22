@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "MagicNumber")

package com.androidperformancestudio.benchmark.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.benchmark.model.BenchmarkReportEntry
import com.androidperformancestudio.benchmark.model.CompatibilityIssue
import com.androidperformancestudio.benchmark.model.GateDecision
import com.androidperformancestudio.benchmark.model.GateReason
import com.androidperformancestudio.benchmark.model.RegressionClassification
import com.androidperformancestudio.benchmark.presentation.generated.resources.*
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioMetricCard
import com.androidperformancestudio.ui.studio.StudioPanel
import com.androidperformancestudio.ui.studio.StudioPanelHeader
import com.androidperformancestudio.ui.studio.StudioStatusChip
import com.androidperformancestudio.ui.studio.StudioStatusTone
import com.androidperformancestudio.ui.studio.StudioTokens

/** Callbacks the workspace supplies; every action maps to a real artifact or stored run. */
public data class BenchmarkReportsActions(
    val onImportRun: () -> Unit = {},
    val onOrchestrateRun: () -> Unit = {},
    val onSelectBaseline: (String) -> Unit = {},
    val onSelectCurrent: (String) -> Unit = {},
    val onCompare: () -> Unit = {},
    val onExportPdf: () -> Unit = {},
    val onExportCsv: () -> Unit = {},
    val onShareReport: () -> Unit = {},
    val onCompareSettings: () -> Unit = {},
    val onSelectMetric: (String) -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onSeverityFilterChange: (RegressionSeverity?) -> Unit = {},
    val onClassificationFilterChange: (RegressionClassification?) -> Unit = {},
    val onSelectComparison: (String?) -> Unit = {},
    val onOpenReport: (String) -> Unit = {},
    val onGenerateReport: () -> Unit = {},
)

@Composable
public fun BenchmarkReportsScreen(
    state: BenchmarkReportsState,
    language: UiLanguage,
    modifier: Modifier = Modifier,
    actions: BenchmarkReportsActions = BenchmarkReportsActions(),
) {
    val colors = LocalViewerColors.current
    Column(modifier = modifier.fillMaxSize().background(colors.workspace).padding(StudioTokens.workspacePadding)) {
        BenchmarkSummaryRow(state, language)
        Spacer(Modifier.height(StudioTokens.cardGap))
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
            ) {
                BenchmarkComparisonTable(
                    state = state,
                    language = language,
                    onQueryChange = actions.onQueryChange,
                    onSeverityFilterChange = actions.onSeverityFilterChange,
                    onClassificationFilterChange = actions.onClassificationFilterChange,
                    onSelectComparison = actions.onSelectComparison,
                    modifier = Modifier.weight(1f),
                )
                BenchmarkTrendPanel(
                    state = state,
                    language = language,
                    onSelectMetric = actions.onSelectMetric,
                    modifier = Modifier.height(210.dp),
                )
            }
            BenchmarkReportColumn(
                state = state,
                language = language,
                actions = actions,
                modifier = Modifier.width(320.dp),
            )
        }
        StatusLine(state, colors)
    }
}

/**
 * Baseline/current build selectors and comparison actions hosted by the page header.
 *
 * Keeping this row in the shell header makes the baseline version row the first visible control
 * while the reports workspace starts directly with its summary and comparison evidence.
 */
@Composable
@Suppress("ktlint:standard:function-naming")
public fun BenchmarkHeaderActions(
    state: BenchmarkReportsState,
    language: UiLanguage,
    actions: BenchmarkReportsActions,
) {
    val colors = LocalViewerColors.current
    val baseline = state.runOptions.firstOrNull { it.id == state.baselineId }
    val current = state.runOptions.firstOrNull { it.id == state.currentId }
    Text(
        text = localizedStringResource(Res.string.baseline_build, language),
        color = colors.mutedText,
        fontSize = ViewerTypography.label.fontSize,
        lineHeight = ViewerTypography.label.lineHeight,
    )
    DropdownSelector(
        items = state.runOptions,
        selectedItem = baseline,
        onItemSelected = { actions.onSelectBaseline(it.id) },
        itemLabel = { it.label },
        itemSecondary = { it.detail },
        selectedItemLabel = { it.label },
        placeholder = localizedStringResource(Res.string.select_run, language),
        modifier = Modifier.width(220.dp),
        fillWidth = true,
    )
    Text(
        text = localizedStringResource(Res.string.current_build, language),
        color = colors.mutedText,
        fontSize = ViewerTypography.label.fontSize,
        lineHeight = ViewerTypography.label.lineHeight,
    )
    DropdownSelector(
        items = state.runOptions,
        selectedItem = current,
        onItemSelected = { actions.onSelectCurrent(it.id) },
        itemLabel = { it.label },
        itemSecondary = { it.detail },
        selectedItemLabel = { it.label },
        placeholder = localizedStringResource(Res.string.select_run, language),
        modifier = Modifier.width(220.dp),
        fillWidth = true,
    )
    ProfilerCompactButton(
        text = localizedStringResource(Res.string.compare, language),
        onClick = actions.onCompare,
        enabled = state.baselineId != null && state.currentId != null && !state.busy,
    )
    ProfilerCompactButton(
        text = localizedStringResource(Res.string.import_run, language),
        onClick = actions.onImportRun,
        enabled = !state.busy,
    )
    ProfilerCompactButton(
        text = localizedStringResource(Res.string.orchestrate_run, language),
        onClick = actions.onOrchestrateRun,
        enabled = !state.busy,
    )
    ProfilerCompactButton(
        text = localizedStringResource(Res.string.export_pdf, language),
        onClick = actions.onExportPdf,
        enabled = state.report != null,
    )
    ProfilerCompactButton(
        text = localizedStringResource(Res.string.export_csv, language),
        onClick = actions.onExportCsv,
        enabled = state.report != null,
    )
    ProfilerCompactButton(
        text = localizedStringResource(Res.string.share_report, language),
        onClick = actions.onShareReport,
        enabled = state.recentReports.isNotEmpty(),
    )
    ProfilerCompactButton(
        text = localizedStringResource(Res.string.compare_settings, language),
        onClick = actions.onCompareSettings,
    )
}

@Composable
private fun BenchmarkSummaryRow(
    state: BenchmarkReportsState,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    val gate = state.gate
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
    ) {
        StudioMetricCard(
            label = localizedStringResource(Res.string.compared_benchmarks, language),
            value = state.comparedCount.toString(),
            supportingText = state.report?.let { localizedStringResource(Res.string.gate_policy_version, language, state.gatePolicy.policyVersion) },
            modifier = Modifier.weight(1f),
            tone = colors.info,
        )
        StudioMetricCard(
            label = localizedStringResource(Res.string.regressions, language),
            value = state.regressionCount.toString(),
            supportingText = localizedStringResource(Res.string.count_stable, language, state.stableCount),
            modifier = Modifier.weight(1f),
            tone = if (state.regressionCount > 0) MaterialTheme.colorScheme.error else colors.secondaryText,
        )
        StudioMetricCard(
            label = localizedStringResource(Res.string.improvements, language),
            value = state.improvementCount.toString(),
            modifier = Modifier.weight(1f),
            tone = if (state.improvementCount > 0) colors.success else colors.secondaryText,
        )
        StudioMetricCard(
            label = localizedStringResource(Res.string.release_gate, language),
            value = gate?.decision?.displayName(language) ?: "—",
            supportingText = gate?.let { localizedStringResource(Res.string.gate_policy_version, language, it.policyVersion) },
            modifier = Modifier.weight(1f),
            tone =
                when (gate?.decision) {
                    GateDecision.PASS -> colors.success
                    GateDecision.FAIL -> MaterialTheme.colorScheme.error
                    GateDecision.INCONCLUSIVE -> colors.warning
                    null -> colors.secondaryText
                },
        )
    }
}

@Composable
private fun BenchmarkReportColumn(
    state: BenchmarkReportsState,
    language: UiLanguage,
    actions: BenchmarkReportsActions,
    modifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
    ) {
        StudioPanel(Modifier.fillMaxWidth()) {
            StudioPanelHeader(localizedStringResource(Res.string.report_summary, language))
            val gate = state.gate
            if (gate == null) {
                Text(
                    text = localizedStringResource(Res.string.no_comparisons, language),
                    color = colors.secondaryText,
                    fontSize = ViewerTypography.bodyCompact.fontSize,
                    lineHeight = ViewerTypography.bodyCompact.lineHeight,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = localizedStringResource(Res.string.release_decision, language),
                        color = colors.secondaryText,
                        fontSize = ViewerTypography.bodyCompact.fontSize,
                        lineHeight = ViewerTypography.bodyCompact.lineHeight,
                        modifier = Modifier.weight(1f),
                    )
                    StudioStatusChip(gate.decision.displayName(language), gate.decision.tone)
                }
                Text(
                    text = localizedStringResource(Res.string.gate_policy_version, language, gate.policyVersion),
                    color = colors.mutedText,
                    fontSize = ViewerTypography.label.fontSize,
                    lineHeight = ViewerTypography.label.lineHeight,
                )
                gate.reasons.forEach { reason ->
                    Text(
                        text = reason.displayName(language),
                        color = colors.secondaryText,
                        fontSize = ViewerTypography.bodyCompact.fontSize,
                        lineHeight = ViewerTypography.bodyCompact.lineHeight,
                    )
                }
                if (gate.blockingComparisons.isNotEmpty()) {
                    Text(
                        text = localizedStringResource(Res.string.blocking_comparisons, language, gate.blockingComparisons.size),
                        color = colors.error,
                        fontSize = ViewerTypography.label.fontSize,
                        lineHeight = ViewerTypography.label.lineHeight,
                    )
                }
                HorizontalDivider(color = colors.border)
                SummaryCounts(state, language)
                PolicySummary(state, language)
            }
            HorizontalDivider(color = colors.border)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = localizedStringResource(Res.string.recent_reports, language),
                    color = colors.primaryText,
                    fontSize = ViewerTypography.label.fontSize,
                    lineHeight = ViewerTypography.label.lineHeight,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            if (state.recentReports.isEmpty()) {
                Text(
                    text = localizedStringResource(Res.string.no_reports, language),
                    color = colors.mutedText,
                    fontSize = ViewerTypography.label.fontSize,
                    lineHeight = ViewerTypography.label.lineHeight,
                )
            } else {
                state.recentReports.forEach { report ->
                    ReportRow(report, language, onOpen = { actions.onOpenReport(report.path) })
                }
            }
            TextButton(onClick = actions.onGenerateReport, enabled = state.report != null) {
                Text(
                    text = localizedStringResource(Res.string.generate_new_report, language),
                    color = if (state.report != null) colors.accent else colors.mutedText,
                )
            }
        }
        BenchmarkInspector(state, language)
    }
}

@Composable
private fun SummaryCounts(state: BenchmarkReportsState, language: UiLanguage) {
    val colors = LocalViewerColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.panelGap)) {
        CountCell(localizedStringResource(Res.string.count_regressions, language, state.regressionCount), colors.error, Modifier.weight(1f))
        CountCell(localizedStringResource(Res.string.count_improvements, language, state.improvementCount), colors.success, Modifier.weight(1f))
        CountCell(localizedStringResource(Res.string.count_stable, language, state.stableCount), colors.secondaryText, Modifier.weight(1f))
    }
}

@Composable
private fun CountCell(text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = ViewerTypography.label.fontSize,
        lineHeight = ViewerTypography.label.lineHeight,
        maxLines = 2,
    )
}

@Composable
private fun PolicySummary(state: BenchmarkReportsState, language: UiLanguage) {
    val colors = LocalViewerColors.current
    val blocking = state.gatePolicy.blockingMetricPatterns.joinToString(", ").ifBlank {
        localizedStringResource(Res.string.policy_any_metric, language)
    }
    Text(
        text = localizedStringResource(Res.string.policy_blocking_patterns, language, blocking),
        color = colors.mutedText,
        fontSize = ViewerTypography.label.fontSize,
        lineHeight = ViewerTypography.label.lineHeight,
    )
    Text(
        text = localizedStringResource(
            Res.string.policy_max_regressions,
            language,
            state.gatePolicy.maxAllowedRegressions,
        ),
        color = colors.mutedText,
        fontSize = ViewerTypography.label.fontSize,
        lineHeight = ViewerTypography.label.lineHeight,
    )
    Text(
        text = localizedStringResource(
            Res.string.policy_min_confidence,
            language,
            state.gatePolicy.minimumConfidence.displayName(language),
        ),
        color = colors.mutedText,
        fontSize = ViewerTypography.label.fontSize,
        lineHeight = ViewerTypography.label.lineHeight,
    )
}

@Composable
private fun ReportRow(
    report: BenchmarkReportEntry,
    language: UiLanguage,
    onOpen: () -> Unit,
) {
    val colors = LocalViewerColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StudioStatusChip(report.format.uppercase(), StudioStatusTone.INFO)
        Spacer(Modifier.width(StudioTokens.panelGap))
        Column(Modifier.weight(1f)) {
            Text(
                text = report.title,
                color = colors.primaryText,
                fontSize = ViewerTypography.label.fontSize,
                lineHeight = ViewerTypography.label.lineHeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = REPORT_DATE_FORMAT.format(report.createdAt) + " · " + formatBytes(report.sizeBytes),
                color = colors.mutedText,
                fontSize = ViewerTypography.label.fontSize,
                lineHeight = ViewerTypography.label.lineHeight,
            )
        }
        TextButton(onClick = onOpen) {
            Text(text = localizedStringResource(Res.string.view_all, language), color = colors.accent)
        }
    }
}

@Composable
private fun BenchmarkInspector(
    state: BenchmarkReportsState,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    val comparison = state.selectedComparison
    StudioPanel(Modifier.fillMaxWidth()) {
        StudioPanelHeader(localizedStringResource(Res.string.comparison_inspector, language))
        if (comparison == null) {
            Text(
                text = localizedStringResource(Res.string.inspect_hint, language),
                color = colors.secondaryText,
                fontSize = ViewerTypography.bodyCompact.fontSize,
                lineHeight = ViewerTypography.bodyCompact.lineHeight,
            )
            return@StudioPanel
        }
        Text(
            text = comparison.metricName,
            color = colors.primaryText,
            fontSize = ViewerTypography.bodyCompact.fontSize,
            lineHeight = ViewerTypography.bodyCompact.lineHeight,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = comparison.caseIdentity,
            color = colors.mutedText,
            fontFamily = FontFamily.Monospace,
            fontSize = ViewerTypography.label.fontSize,
            lineHeight = ViewerTypography.label.lineHeight,
        )
        Text(
            text = localizedStringResource(Res.string.inspector_samples, language, state.sampleCount(comparison)?.toString() ?: "—"),
            color = colors.secondaryText,
            fontSize = ViewerTypography.label.fontSize,
            lineHeight = ViewerTypography.label.lineHeight,
        )
        val traces = state.traceArtifacts(comparison)
        Text(
            text = localizedStringResource(Res.string.inspector_traces, language, traces.size),
            color = colors.secondaryText,
            fontSize = ViewerTypography.label.fontSize,
            lineHeight = ViewerTypography.label.lineHeight,
        )
        Text(
            text = localizedStringResource(Res.string.inspector_compatibility, language),
            color = colors.primaryText,
            fontSize = ViewerTypography.label.fontSize,
            lineHeight = ViewerTypography.label.lineHeight,
            fontWeight = FontWeight.SemiBold,
        )
        val issues = state.compatibilityIssues()
        if (issues.isEmpty()) {
            Text(
                text = localizedStringResource(Res.string.inspector_no_issues, language),
                color = colors.secondaryText,
                fontSize = ViewerTypography.label.fontSize,
                lineHeight = ViewerTypography.label.lineHeight,
            )
        } else {
            issues.forEach { issue -> Text(issue.format(), color = colors.warning, fontSize = ViewerTypography.label.fontSize) }
        }
        Text(
            text = localizedStringResource(Res.string.inspector_reasons, language),
            color = colors.primaryText,
            fontSize = ViewerTypography.label.fontSize,
            lineHeight = ViewerTypography.label.lineHeight,
            fontWeight = FontWeight.SemiBold,
        )
        if (comparison.reasons.isEmpty()) {
            Text(
                text = localizedStringResource(Res.string.inspector_no_issues, language),
                color = colors.secondaryText,
                fontSize = ViewerTypography.label.fontSize,
                lineHeight = ViewerTypography.label.lineHeight,
            )
        } else {
            comparison.reasons.forEach { reason ->
                Text(
                    text = reason,
                    color = colors.secondaryText,
                    fontSize = ViewerTypography.label.fontSize,
                    lineHeight = ViewerTypography.label.lineHeight,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(state: BenchmarkReportsState, colors: ViewerColors) {
    val status = state.error ?: state.message ?: return
    Text(
        text = status,
        modifier = Modifier.padding(top = StudioTokens.cardGap),
        color = if (state.error != null) MaterialTheme.colorScheme.error else colors.secondaryText,
        fontSize = ViewerTypography.label.fontSize,
        lineHeight = ViewerTypography.label.lineHeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun GateDecision.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            GateDecision.PASS -> Res.string.gate_pass
            GateDecision.FAIL -> Res.string.gate_fail
            GateDecision.INCONCLUSIVE -> Res.string.gate_inconclusive
        },
        language,
    )

private val GateDecision.tone: StudioStatusTone
    get() =
        when (this) {
            GateDecision.PASS -> StudioStatusTone.SUCCESS
            GateDecision.FAIL -> StudioStatusTone.ERROR
            GateDecision.INCONCLUSIVE -> StudioStatusTone.WARNING
        }

@Composable
private fun GateReason.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            GateReason.INCOMPATIBLE_EVIDENCE -> Res.string.gate_reason_incompatible_evidence
            GateReason.BLOCKING_REGRESSIONS -> Res.string.gate_reason_blocking_regressions
            GateReason.INCONCLUSIVE_EVIDENCE -> Res.string.gate_reason_inconclusive_evidence
            GateReason.INCONCLUSIVE_DISALLOWED -> Res.string.gate_reason_inconclusive_disallowed
            GateReason.NO_BLOCKING_REGRESSION -> Res.string.gate_reason_no_blocking_regression
        },
        language,
    )

private val REPORT_DATE_FORMAT: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault())

internal fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024L -> "%.1f MB".format(java.util.Locale.ROOT, bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "%.1f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
        else -> bytes.toString() + " B"
    }

private fun CompatibilityIssue.format(): String = field + ": " + (baseline ?: "—") + " → " + (current ?: "—")
