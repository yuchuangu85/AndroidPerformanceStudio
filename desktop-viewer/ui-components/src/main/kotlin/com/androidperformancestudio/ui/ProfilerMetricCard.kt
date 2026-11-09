@file:Suppress("FunctionName", "LongParameterList")

package com.androidperformancestudio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.studio.StudioCard

@Composable
public fun ProfilerMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supportingText: List<String> = emptyList(),
    prominent: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    tone: Color? = null,
) {
    val colors = LocalViewerColors.current
    val metricTone = tone ?: colors.accent
    StudioCard(
        modifier = modifier,
        containerColor = containerColor,
        borderColor = metricTone.copy(alpha = 0.5f),
        contentColor = contentColorFor(containerColor),
        contentSpacing = 4.dp,
    ) {
        Text(label, style = ViewerTypography.subsectionTitle, color = metricTone, fontWeight = FontWeight.SemiBold)
        Text(
            value,
            style = ViewerTypography.metric,
            fontWeight = if (prominent) FontWeight.Bold else FontWeight.SemiBold,
        )
        supportingText.forEach { text ->
            Text(text, style = ViewerTypography.secondary, color = colors.secondaryText)
        }
    }
}
