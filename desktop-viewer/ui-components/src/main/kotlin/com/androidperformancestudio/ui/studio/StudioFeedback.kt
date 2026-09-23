@file:Suppress("FunctionNaming", "LongParameterList")

package com.androidperformancestudio.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui_components.generated.resources.Res
import com.androidperformancestudio.ui_components.generated.resources.evidence_complete
import com.androidperformancestudio.ui_components.generated.resources.evidence_content_description
import com.androidperformancestudio.ui_components.generated.resources.evidence_derived
import com.androidperformancestudio.ui_components.generated.resources.evidence_estimated
import com.androidperformancestudio.ui_components.generated.resources.evidence_exact
import com.androidperformancestudio.ui_components.generated.resources.evidence_inferred
import com.androidperformancestudio.ui_components.generated.resources.evidence_partial
import com.androidperformancestudio.ui_components.generated.resources.evidence_unavailable
import com.androidperformancestudio.ui_components.generated.resources.evidence_unknown
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.ViewerTypography

@Composable
public fun StudioStatusChip(
    label: String,
    tone: StudioStatusTone,
    modifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    val textColor = tone.color(colors)
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(StudioTokens.smallRadius))
                .background(textColor.copy(alpha = if (colors.isDark) 0.18f else 0.12f))
                .border(
                    width = StudioTokens.borderWidth,
                    color = textColor.copy(alpha = if (colors.isDark) 0.52f else 0.44f),
                    shape = RoundedCornerShape(StudioTokens.smallRadius),
                )
                .padding(horizontal = StudioTokens.compactContentPadding, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = ViewerTypography.label.fontSize,
            lineHeight = ViewerTypography.label.lineHeight,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
public fun StudioEvidenceBadge(
    evidence: StudioEvidence,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val label = localizedStringResource(evidence.labelResource, language)
    StudioStatusChip(
        label = label,
        tone = evidence.tone,
        modifier =
            modifier.semantics {
                contentDescription =
                    localizedStringResource(Res.string.evidence_content_description, language, label)
            },
    )
}

@Composable
public fun StudioEmptyState(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    StudioFeedbackState(title = title, detail = detail, modifier = modifier)
}

@Composable
public fun StudioLoadingState(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    StudioFeedbackState(
        title = title,
        detail = detail,
        modifier = modifier,
        leading = {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = LocalViewerColors.current.accent,
                strokeWidth = StudioTokens.borderWidth,
            )
        },
    )
}

@Composable
public fun StudioErrorState(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    StudioFeedbackState(
        title = title,
        detail = detail,
        modifier = modifier,
        tone = StudioStatusTone.ERROR,
        actionLabel = retryLabel,
        onAction = onRetry,
    )
}

@Composable
private fun StudioFeedbackState(
    title: String,
    detail: String,
    modifier: Modifier,
    tone: StudioStatusTone = StudioStatusTone.NEUTRAL,
    leading: @Composable (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalViewerColors.current
    StudioPanel(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            leading?.let {
                it()
                Spacer(Modifier.width(StudioTokens.panelGap))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    color = tone.color(colors),
                    fontSize = ViewerTypography.subsectionTitle.fontSize,
                    lineHeight = ViewerTypography.subsectionTitle.lineHeight,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = detail,
                    color = colors.secondaryText,
                    fontSize = ViewerTypography.bodyCompact.fontSize,
                    lineHeight = ViewerTypography.bodyCompact.lineHeight,
                )
            }
            if (actionLabel != null && onAction != null) {
                androidx.compose.material3.TextButton(onClick = onAction) {
                    Text(
                        text = actionLabel,
                        color = colors.accent,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

private val StudioEvidence.tone: StudioStatusTone
    get() =
        when (this) {
            StudioEvidence.COMPLETE,
            StudioEvidence.EXACT,
            -> StudioStatusTone.SUCCESS
            StudioEvidence.PARTIAL,
            StudioEvidence.DERIVED,
            StudioEvidence.INFERRED,
            StudioEvidence.ESTIMATED,
            -> StudioStatusTone.WARNING
            StudioEvidence.UNKNOWN,
            StudioEvidence.UNAVAILABLE,
            -> StudioStatusTone.NEUTRAL
        }

private fun StudioStatusTone.color(colors: ViewerColors) =
    when (this) {
        StudioStatusTone.NEUTRAL -> colors.secondaryText
        StudioStatusTone.INFO -> colors.info
        StudioStatusTone.SUCCESS -> colors.success
        StudioStatusTone.WARNING -> colors.warning
        StudioStatusTone.ERROR -> colors.error
    }


private val StudioEvidence.labelResource
    get() =
        when (this) {
            StudioEvidence.COMPLETE -> Res.string.evidence_complete
            StudioEvidence.PARTIAL -> Res.string.evidence_partial
            StudioEvidence.UNKNOWN -> Res.string.evidence_unknown
            StudioEvidence.EXACT -> Res.string.evidence_exact
            StudioEvidence.DERIVED -> Res.string.evidence_derived
            StudioEvidence.INFERRED -> Res.string.evidence_inferred
            StudioEvidence.ESTIMATED -> Res.string.evidence_estimated
            StudioEvidence.UNAVAILABLE -> Res.string.evidence_unavailable
        }
