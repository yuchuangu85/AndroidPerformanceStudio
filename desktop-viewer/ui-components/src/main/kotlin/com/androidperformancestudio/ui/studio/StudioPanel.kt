@file:Suppress("FunctionNaming", "LongParameterList")

package com.androidperformancestudio.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ViewerTypography
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Composable
public fun StudioPanel(
    modifier: Modifier = Modifier,
    contentPadding: Dp = StudioTokens.compactContentPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    StudioCard(
        modifier = modifier,
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
public fun StudioPanelHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalViewerColors.current
    Row(
        modifier = modifier.fillMaxWidth().height(StudioTokens.panelHeaderHeight),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = colors.primaryText,
                fontSize = ViewerTypography.subsectionTitle.fontSize,
                lineHeight = ViewerTypography.subsectionTitle.lineHeight,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    color = colors.secondaryText,
                    fontSize = ViewerTypography.secondary.fontSize,
                    lineHeight = ViewerTypography.secondary.lineHeight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

@Composable
public fun StudioMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    icon: DrawableResource? = null,
    iconContentDescription: String? = null,
    description: String? = null,
    tone: Color? = null,
) {
    val colors = LocalViewerColors.current
    val metricTone = tone ?: colors.accent
    StudioCard(
        modifier =
            modifier.semantics {
                contentDescription = "$label: $value"
            },
        containerColor = colors.panel,
        borderColor = metricTone.copy(alpha = 0.5f),
        contentSpacing = 4.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioTokens.panelGap),
        ) {
            icon?.let {
                Icon(
                    painter = painterResource(it),
                    contentDescription = iconContentDescription,
                    modifier = Modifier.size(StudioTokens.navigationIconSize),
                    tint = metricTone,
                )
            }
            Text(
                text = label,
                color = metricTone,
                fontSize = ViewerTypography.subsectionTitle.fontSize,
                lineHeight = ViewerTypography.subsectionTitle.lineHeight,
                fontWeight = FontWeight.SemiBold,
            )
        }
        description?.let {
            Text(text = it, color = colors.secondaryText, style = ViewerTypography.secondary)
        }
        Text(
            text = value,
            color = colors.primaryText,
            fontSize = ViewerTypography.metric.fontSize,
            lineHeight = ViewerTypography.metric.lineHeight,
            fontWeight = FontWeight.SemiBold,
        )
        supportingText?.let {
            Text(
                text = it,
                color = colors.secondaryText,
                fontSize = ViewerTypography.bodyCompact.fontSize,
                lineHeight = ViewerTypography.bodyCompact.lineHeight,
            )
        }
    }
}

@Composable
public fun StudioDataTable(
    columns: List<StudioTableColumn>,
    modifier: Modifier = Modifier,
    title: String? = null,
    contentPadding: Dp = StudioTokens.compactContentPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    require(columns.isNotEmpty()) { "StudioDataTable requires at least one column" }
    val colors = LocalViewerColors.current
    StudioPanel(modifier = modifier, contentPadding = contentPadding) {
        title?.let { StudioPanelHeader(it) }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(colors.sectionBackground)
                    .padding(horizontal = StudioTokens.compactContentPadding, vertical = 6.dp),
        ) {
            columns.forEach { column ->
                Text(
                    text = column.label,
                    modifier = Modifier.weight(column.weight),
                    color = colors.secondaryText,
                    fontSize = ViewerTypography.label.fontSize,
                    lineHeight = ViewerTypography.label.lineHeight,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        content()
    }
}
