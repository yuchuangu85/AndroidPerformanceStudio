@file:Suppress("FunctionNaming", "LongParameterList", "MatchingDeclarationName", "ktlint:standard:function-naming")

package com.androidperformancestudio.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.app_desktop.generated.resources.Res
import com.androidperformancestudio.app_desktop.generated.resources.sp_ai_close
import com.androidperformancestudio.app_desktop.generated.resources.sp_ai_confidence
import com.androidperformancestudio.app_desktop.generated.resources.sp_ai_evidence_scope_label
import com.androidperformancestudio.app_desktop.generated.resources.sp_ai_evidence_summary
import com.androidperformancestudio.app_desktop.generated.resources.sp_ai_explanation_label
import com.androidperformancestudio.app_desktop.generated.resources.sp_ai_findings
import com.androidperformancestudio.app_desktop.generated.resources.sp_ai_no_findings
import com.androidperformancestudio.app_desktop.generated.resources.sp_ai_open_source
import com.androidperformancestudio.app_desktop.generated.resources.sp_ai_open_source_candidate
import com.androidperformancestudio.app_desktop.generated.resources.sp_ai_recommendation_label
import com.androidperformancestudio.app_desktop.generated.resources.sp_ai_result_title
import com.androidperformancestudio.app_desktop.generated.resources.sp_ai_session_label
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioPanel
import com.androidperformancestudio.ui.studio.StudioPanelHeader
import com.androidperformancestudio.ui.studio.StudioStatusChip
import com.androidperformancestudio.ui.studio.StudioStatusTone
import com.androidperformancestudio.ui.viewerColors
import com.androidperformancestudio.ui.viewerMaterialColorScheme

internal data class SimpleperfAiInsight(
    val result: SimpleperfAiAnalysisReport,
    val sessionName: String,
    val evidenceScope: String,
    val evidenceCount: Int,
    val sampleCount: Long,
)

@Composable
internal fun SimpleperfAiWorkspace(
    insight: SimpleperfAiInsight?,
    language: UiLanguage,
    darkTheme: Boolean,
    onCloseInsight: () -> Unit,
    onOpenSourceCandidate: ((String) -> Unit)?,
    workspaceContent: @Composable () -> Unit,
) {
    val activeColors = LocalViewerColors.current
    val drawerColors =
        if (activeColors.isDark == darkTheme) activeColors else viewerColors(darkTheme, activeColors.accent)

    @Composable
    fun Drawer(
        current: SimpleperfAiInsight,
        modifier: Modifier,
    ) {
        CompositionLocalProvider(LocalViewerColors provides drawerColors) {
            MaterialTheme(colorScheme = viewerMaterialColorScheme(darkTheme, drawerColors.accent)) {
                SimpleperfAiInsightDrawer(current, language, onCloseInsight, onOpenSourceCandidate, modifier)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compactDrawerWidth = minOf(maxWidth, INSIGHT_DOCK_WIDTH)
        when {
            insight == null -> workspaceContent()
            maxWidth >= INSIGHT_DOCK_BREAKPOINT ->
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight().testTag("simpleperf-workspace-content")) {
                        workspaceContent()
                    }
                    Drawer(insight, Modifier.width(INSIGHT_DOCK_WIDTH).fillMaxHeight())
                }
            else ->
                Box(Modifier.fillMaxSize()) {
                    workspaceContent()
                    Drawer(insight, Modifier.align(Alignment.CenterEnd).width(compactDrawerWidth).fillMaxHeight())
                }
        }
    }
}

private val INSIGHT_DOCK_WIDTH = 360.dp
private val INSIGHT_DOCK_BREAKPOINT = 1100.dp

@Composable
internal fun SimpleperfAiInsightDrawer(
    insight: SimpleperfAiInsight,
    language: UiLanguage,
    onClose: () -> Unit,
    onOpenSourceCandidate: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    StudioPanel(modifier.testTag("simpleperf-ai-insight-drawer")) {
        StudioPanelHeader(
            title = localizedStringResource(Res.string.sp_ai_result_title, language, insight.result.model),
        ) {
            TextButton(onClick = onClose) {
                Text(localizedStringResource(Res.string.sp_ai_close, language))
            }
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = localizedStringResource(Res.string.sp_ai_session_label, language, insight.sessionName),
                color = colors.secondaryText,
                fontSize = ViewerTypography.bodyCompact.fontSize,
            )
            Text(
                text = localizedStringResource(Res.string.sp_ai_evidence_scope_label, language, insight.evidenceScope),
                color = colors.secondaryText,
                fontSize = ViewerTypography.bodyCompact.fontSize,
            )
            Text(
                text =
                    localizedStringResource(
                        Res.string.sp_ai_evidence_summary,
                        language,
                        insight.evidenceCount,
                        insight.sampleCount,
                    ),
                color = colors.secondaryText,
                fontSize = ViewerTypography.bodyCompact.fontSize,
            )
            Text(insight.result.summary, color = colors.primaryText, fontSize = ViewerTypography.body.fontSize)
            Text(
                localizedStringResource(Res.string.sp_ai_findings, language),
                color = colors.primaryText,
                fontSize = ViewerTypography.subsectionTitle.fontSize,
            )
            if (insight.result.findings.isEmpty()) {
                Text(localizedStringResource(Res.string.sp_ai_no_findings, language), color = colors.secondaryText)
            }
            insight.result.findings.forEach { finding ->
                SimpleperfAiFindingCard(finding, language, onOpenSourceCandidate)
            }
        }
    }
}

@Composable
private fun SimpleperfAiFindingCard(
    finding: SimpleperfAiFinding,
    language: UiLanguage,
    onOpenSourceCandidate: ((String) -> Unit)?,
) {
    val colors = LocalViewerColors.current
    StudioPanel(Modifier.fillMaxWidth()) {
        Text(finding.title, color = colors.primaryText, fontSize = ViewerTypography.body.fontSize)
        StudioStatusChip(
            label =
                localizedStringResource(
                    Res.string.sp_ai_confidence,
                    language,
                    (finding.confidence * PERCENT_MULTIPLIER).toInt(),
                ),
            tone = StudioStatusTone.INFO,
        )
        Text(
            localizedStringResource(Res.string.sp_ai_explanation_label, language),
            color = colors.secondaryText,
            fontSize = ViewerTypography.secondary.fontSize,
        )
        Text(finding.explanation, color = colors.primaryText, fontSize = ViewerTypography.bodyCompact.fontSize)
        Text(
            localizedStringResource(Res.string.sp_ai_recommendation_label, language),
            color = colors.secondaryText,
            fontSize = ViewerTypography.secondary.fontSize,
        )
        Text(finding.recommendation, color = colors.primaryText, fontSize = ViewerTypography.bodyCompact.fontSize)
        if (onOpenSourceCandidate != null) {
            finding.sourceCandidateIds.forEachIndexed { index, candidateId ->
                TextButton(onClick = { onOpenSourceCandidate(candidateId) }) {
                    Text(
                        if (finding.sourceCandidateIds.size == 1) {
                            localizedStringResource(Res.string.sp_ai_open_source, language)
                        } else {
                            localizedStringResource(Res.string.sp_ai_open_source_candidate, language, index + 1)
                        },
                    )
                }
            }
        }
    }
}

private const val PERCENT_MULTIPLIER = 100
