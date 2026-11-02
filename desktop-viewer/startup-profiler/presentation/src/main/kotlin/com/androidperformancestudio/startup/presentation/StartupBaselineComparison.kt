@file:Suppress("FunctionName", "LongMethod", "MagicNumber", "MaxLineLength", "ktlint:standard:function-naming")

package com.androidperformancestudio.startup.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.startup.analysis.StartupAnalysisResult
import com.androidperformancestudio.startup.analysis.StartupComparisonStatus
import com.androidperformancestudio.startup.presentation.generated.resources.Res
import com.androidperformancestudio.startup.presentation.generated.resources.add_comparison
import com.androidperformancestudio.startup.presentation.generated.resources.baseline_comparison
import com.androidperformancestudio.startup.presentation.generated.resources.baseline_comparison_empty
import com.androidperformancestudio.startup.presentation.generated.resources.baseline_comparison_title
import com.androidperformancestudio.startup.presentation.generated.resources.comparison_change
import com.androidperformancestudio.startup.presentation.generated.resources.comparison_cohort
import com.androidperformancestudio.startup.presentation.generated.resources.comparison_current
import com.androidperformancestudio.startup.presentation.generated.resources.comparison_descriptive
import com.androidperformancestudio.startup.presentation.generated.resources.comparison_evidence
import com.androidperformancestudio.startup.presentation.generated.resources.comparison_improvement
import com.androidperformancestudio.startup.presentation.generated.resources.comparison_incompatible
import com.androidperformancestudio.startup.presentation.generated.resources.comparison_inconclusive
import com.androidperformancestudio.startup.presentation.generated.resources.comparison_insufficient
import com.androidperformancestudio.startup.presentation.generated.resources.comparison_no_change
import com.androidperformancestudio.startup.presentation.generated.resources.comparison_previous
import com.androidperformancestudio.startup.presentation.generated.resources.comparison_regression
import com.androidperformancestudio.startup.presentation.generated.resources.comparison_samples
import com.androidperformancestudio.startup.presentation.generated.resources.device
import com.androidperformancestudio.startup.presentation.generated.resources.metric_agent_first_frame
import com.androidperformancestudio.startup.presentation.generated.resources.phase_comparison
import com.androidperformancestudio.startup.presentation.generated.resources.unavailable
import com.androidperformancestudio.ui.CopyableText
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioPanel
import com.androidperformancestudio.ui.studio.StudioTokens
import java.util.Locale

@Composable
internal fun StartupBaselineComparison(
    state: StartupProfilerState,
    actions: StartupProfilerActions,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    StudioPanel(
        modifier = Modifier.fillMaxWidth().testTag("startup-baseline-comparison"),
        contentPadding = StudioTokens.compactContentPadding,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                localizedStringResource(Res.string.baseline_comparison_title, language),
                Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
            )
            ProfilerCompactButton(
                localizedStringResource(Res.string.add_comparison, language),
                modifier = Modifier.testTag("startup-import-comparison"),
                enabled = !state.isRunning,
                onClick = actions.onImport,
            )
        }
        val cohorts =
            listOfNotNull(
                state.analysis?.let { localizedStringResource(Res.string.comparison_current, language) to it },
                state.baseline?.let { localizedStringResource(Res.string.comparison_previous, language) to it },
            )
        if (cohorts.isNotEmpty()) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val tableWidth = maxOf(maxWidth, 800.dp)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Column(Modifier.width(tableWidth)) {
                        Row(
                            Modifier.fillMaxWidth().background(colors.sectionBackground).padding(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ComparisonCell(localizedStringResource(Res.string.comparison_cohort, language), 1.5f, true)
                            ComparisonCell(localizedStringResource(Res.string.device, language), 1.3f, true)
                            listOf(
                                "TTID",
                                "TTFD",
                                localizedStringResource(Res.string.metric_agent_first_frame, language),
                                "P95 TTID",
                            ).forEach {
                                ComparisonCell(it, 1f, true)
                            }
                            ComparisonCell(localizedStringResource(Res.string.comparison_samples, language), 0.7f, true)
                        }
                        cohorts.forEachIndexed { index, (label, analysis) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .testTag(if (index == 0) "startup-comparison-current" else "startup-comparison-previous")
                                    .background(if (index == 0) colors.selectedRow else colors.transparent)
                                    .padding(6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                ComparisonCell(label, 1.5f)
                                ComparisonCell(
                                    analysis.runs
                                        .map { it.environmentEvidence?.deviceModel ?: "—" }
                                        .distinct()
                                        .joinToString(" / "),
                                    1.3f,
                                )
                                ComparisonCell(analysis.firstFrame.medianMs.formatMs(), 1f)
                                ComparisonCell(analysis.fullyDrawn.medianMs.formatMs(), 1f)
                                ComparisonCell(analysis.agentFirstFrame.medianMs.formatMs(), 1f)
                                ComparisonCell(analysis.firstFrame.p95Ms.formatMs(), 1f)
                                ComparisonCell(analysis.runs.size.toString(), 0.7f)
                            }
                        }
                    }
                }
            }
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                cohorts.forEachIndexed { index, (label, analysis) ->
                    CopyableText(
                        comparisonEvidenceText(label, analysis, language),
                        modifier = Modifier.testTag(if (index == 0) "startup-current-evidence" else "startup-baseline-evidence"),
                        color = colors.secondaryText,
                        fontSize = ViewerTypography.secondary.fontSize,
                    )
                }
                val comparison = state.comparison
                if (comparison == null) {
                    CopyableText(
                        localizedStringResource(Res.string.baseline_comparison_empty, language),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    val phaseNames =
                        state.analysis
                            ?.runs
                            ?.firstOrNull()
                            ?.phases
                            .orEmpty()
                            .associate { it.name to it.localizedName(language) }
                    CopyableText(
                        localizedStringResource(
                            Res.string.baseline_comparison,
                            language,
                            comparison.status.localizedLabel(language),
                            comparison.medianDifferenceMs.formatMs(),
                            comparison.confidenceIntervalLowMs.formatMs(),
                            comparison.confidenceIntervalHighMs.formatMs(),
                        ),
                        modifier = Modifier.testTag("startup-comparison-result"),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        localizedStringResource(
                            Res.string.comparison_change,
                            language,
                            comparison.metric,
                            comparison.percentDifference?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "—",
                            comparison.practicalThresholdMs.formatMs(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    comparison.reasons.forEach { CopyableText("• $it", color = colors.warning, style = MaterialTheme.typography.bodySmall) }
                    comparison.phaseDifferences.forEach { phase ->
                        Text(
                            localizedStringResource(
                                Res.string.phase_comparison,
                                language,
                                phaseNames.getOrDefault(phase.name, phase.name),
                                phase.medianDifferenceMs.formatMs(),
                                phase.confidence.localizedLabel(language),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    CopyableText(
                        localizedStringResource(Res.string.comparison_descriptive, language),
                        color = colors.secondaryText,
                        fontSize = ViewerTypography.secondary.fontSize,
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.ComparisonCell(
    text: String,
    weight: Float,
    header: Boolean = false,
) {
    Text(
        text,
        Modifier.weight(weight),
        color = if (header) LocalViewerColors.current.secondaryText else LocalViewerColors.current.primaryText,
        fontSize = ViewerTypography.secondary.fontSize,
        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

internal fun comparisonEvidenceText(
    label: String,
    analysis: StartupAnalysisResult,
    language: UiLanguage,
): String =
    localizedStringResource(
        Res.string.comparison_evidence,
        language,
        label,
        analysis.runs
            .map {
                it.ttidEvidence.source?.name ?: localizedStringResource(Res.string.unavailable, language)
            }.distinct()
            .joinToString(" / "),
        metricEvidenceText(analysis, analysis.firstFrame, { it.ttidEvidence }, language),
    )

internal fun StartupComparisonStatus.localizedLabel(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            StartupComparisonStatus.INCOMPATIBLE -> Res.string.comparison_incompatible
            StartupComparisonStatus.INSUFFICIENT -> Res.string.comparison_insufficient
            StartupComparisonStatus.REGRESSION -> Res.string.comparison_regression
            StartupComparisonStatus.IMPROVEMENT -> Res.string.comparison_improvement
            StartupComparisonStatus.NO_CHANGE -> Res.string.comparison_no_change
            StartupComparisonStatus.INCONCLUSIVE -> Res.string.comparison_inconclusive
        },
        language,
    )
