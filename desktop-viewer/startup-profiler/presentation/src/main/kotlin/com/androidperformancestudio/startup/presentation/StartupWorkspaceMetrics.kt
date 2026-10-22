@file:Suppress("FunctionName", "LongParameterList", "LongMethod", "MagicNumber", "MaxLineLength", "ktlint:standard:function-naming")

package com.androidperformancestudio.startup.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.startup.analysis.StartupAnalysisResult
import com.androidperformancestudio.startup.model.EvidenceConfidence
import com.androidperformancestudio.startup.model.StartupMetricEvidence
import com.androidperformancestudio.startup.model.StartupRun
import com.androidperformancestudio.startup.model.StartupStatistics
import com.androidperformancestudio.startup.presentation.generated.resources.Res
import com.androidperformancestudio.startup.presentation.generated.resources.cohort_evidence
import com.androidperformancestudio.startup.presentation.generated.resources.low_tail_resolution
import com.androidperformancestudio.startup.presentation.generated.resources.mad
import com.androidperformancestudio.startup.presentation.generated.resources.median_totaltime
import com.androidperformancestudio.startup.presentation.generated.resources.metric_agent_description
import com.androidperformancestudio.startup.presentation.generated.resources.metric_agent_first_frame
import com.androidperformancestudio.startup.presentation.generated.resources.metric_p95
import com.androidperformancestudio.startup.presentation.generated.resources.metric_p95_description
import com.androidperformancestudio.startup.presentation.generated.resources.metric_ttfd
import com.androidperformancestudio.startup.presentation.generated.resources.metric_ttfd_description
import com.androidperformancestudio.startup.presentation.generated.resources.metric_ttid
import com.androidperformancestudio.startup.presentation.generated.resources.metric_ttid_description
import com.androidperformancestudio.startup.presentation.generated.resources.startup_metrics
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerAccentPalette
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioMetricCard
import com.androidperformancestudio.ui.studio.StudioPanel
import com.androidperformancestudio.ui.studio.StudioTokens

@Composable
internal fun StartupMetricOverview(
    analysis: StartupAnalysisResult?,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    StudioPanel(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = StudioTokens.workspacePadding,
    ) {
        Text(localizedStringResource(Res.string.startup_metrics, language), fontWeight = FontWeight.SemiBold)
        run {
            val metrics = @Composable { modifier: Modifier ->
                StartupMetricCard(
                    "TTID",
                    localizedStringResource(Res.string.metric_ttid, language),
                    localizedStringResource(Res.string.metric_ttid_description, language),
                    analysis?.firstFrame?.medianMs.formatMs(),
                    lerp(ViewerAccentPalette.royalPurple, colors.primaryText, 0.38f),
                    metricEvidenceText(analysis, analysis?.firstFrame, { it.ttidEvidence }, language),
                    modifier,
                )
                StartupMetricCard(
                    "TTFD",
                    localizedStringResource(Res.string.metric_ttfd, language),
                    localizedStringResource(Res.string.metric_ttfd_description, language),
                    analysis?.fullyDrawn?.medianMs.formatMs(),
                    colors.info,
                    metricEvidenceText(analysis, analysis?.fullyDrawn, { it.ttfdEvidence }, language),
                    modifier,
                )
                StartupMetricCard(
                    "FIRST_FRAME",
                    localizedStringResource(Res.string.metric_agent_first_frame, language),
                    localizedStringResource(Res.string.metric_agent_description, language),
                    analysis?.agentFirstFrame?.medianMs.formatMs(),
                    colors.success,
                    metricEvidenceText(analysis, analysis?.agentFirstFrame, { it.agentFirstFrameEvidence }, language),
                    modifier,
                )
                StartupMetricCard(
                    "P95",
                    localizedStringResource(Res.string.metric_p95, language),
                    localizedStringResource(Res.string.metric_p95_description, language),
                    analysis?.firstFrame?.p95Ms.formatMs(),
                    colors.warning,
                    listOfNotNull(
                        metricEvidenceText(analysis, analysis?.firstFrame, { it.ttidEvidence }, language),
                    ).joinToString("\n"),
                    modifier,
                )
            }
            Row(
                Modifier.fillMaxWidth().height(104.dp),
                horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
            ) {
                // Compact text wraps within weighted cards instead of dropping metrics at smaller widths.
                metrics(Modifier.weight(1f).fillMaxHeight())
            }
        }
        analysis?.let {
            Text(
                "${localizedStringResource(Res.string.median_totaltime, language)}: ${it.totalTime.medianMs.formatMs()} · " +
                    "P90 TTID ${it.firstFrame.p90Ms.formatMs()} · " +
                    localizedStringResource(Res.string.mad, language, it.firstFrame.medianAbsoluteDeviationMs.formatMs()) +
                    if (listOf(it.firstFrame, it.fullyDrawn, it.agentFirstFrame).any { metric ->
                            metric.p90LowResolution ||
                                metric.p95LowResolution
                        }
                    ) {
                        " · " + localizedStringResource(Res.string.low_tail_resolution, language)
                    } else {
                        ""
                    },
                color = colors.secondaryText,
                fontSize = ViewerTypography.secondary.fontSize,
            )
        }
    }
}

@Composable
private fun StartupMetricCard(
    id: String,
    title: String,
    description: String,
    value: String,
    tone: Color,
    evidence: String,
    modifier: Modifier,
) {
    StudioMetricCard(
        label = title,
        value = value,
        modifier = modifier.testTag("startup-metric-$id"),
        description = description,
        supportingText = evidence,
        tone = tone,
    )
}

internal fun metricEvidenceText(
    analysis: StartupAnalysisResult?,
    statistics: StartupStatistics?,
    evidence: (StartupRun) -> StartupMetricEvidence,
    language: UiLanguage,
): String {
    val confidence =
        analysis
            ?.runs
            .orEmpty()
            .map { evidence(it).confidence }
            .distinct()
            .joinToString(" / ") { it.localizedLabel(language) }
            .ifEmpty { EvidenceConfidence.UNAVAILABLE.localizedLabel(language) }
    return localizedStringResource(
        Res.string.cohort_evidence,
        language,
        confidence,
        statistics?.takeIf { it.medianMs != null }?.count ?: 0,
        statistics?.missingCount ?: 0,
    )
}
