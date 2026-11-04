@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package com.androidperformancestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ChatSparkle

/** Profiler facts enter the existing host-owned approval flow; no provider call belongs here. */
@Composable
public fun ProfilerAiAnalysisButton(
    context: UiAiContext?,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val host = LocalAiContextHost.current ?: return
    val bounded = boundedAiContext(context)
    val enabled = bounded != null && LocalWindowMenuBarActive.current && !host.analysisBusy
    val colors = LocalViewerColors.current
    val shape = RoundedCornerShape(ViewerDimensions.controlRadius)
    val content = colors.accent.copy(alpha = if (enabled) 1f else 0.48f)
    Row(
        modifier =
            modifier
                .height(ViewerDimensions.buttonHeight)
                .clip(shape)
                .background(colors.panel)
                .border(ViewerDimensions.hairline, content, shape)
                .clickable(enabled = enabled, role = Role.Button) { bounded?.let(host::analyze) }
                .padding(horizontal = 8.dp)
                .testTag("profiler-ai-analysis"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Regular.ChatSparkle, contentDescription = null, modifier = Modifier.size(14.dp), tint = content)
        Text(
            if (language ==
                UiLanguage.SIMPLIFIED_CHINESE
            ) {
                "AI 分析"
            } else {
                "AI Analysis"
            },
            color = content,
            style = ViewerTypography.secondary,
            maxLines = 1,
        )
    }
}
