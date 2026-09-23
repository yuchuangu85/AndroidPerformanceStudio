@file:Suppress("FunctionNaming", "LongParameterList")

package com.androidperformancestudio.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ViewerTypography

@Composable
public fun StudioPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalViewerColors.current
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(StudioTokens.panelRadius))
                .background(colors.panel)
                .border(
                    width = StudioTokens.borderWidth,
                    color = colors.border,
                    shape = RoundedCornerShape(StudioTokens.panelRadius),
                )
                .padding(StudioTokens.contentPadding),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap),
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
) {
    val colors = LocalViewerColors.current
    StudioPanel(
        modifier =
            modifier.semantics {
                contentDescription = "$label: $value"
            },
    ) {
        Text(
            text = label,
            color = colors.secondaryText,
            fontSize = ViewerTypography.secondary.fontSize,
            lineHeight = ViewerTypography.secondary.lineHeight,
        )
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
    content: @Composable ColumnScope.() -> Unit,
) {
    require(columns.isNotEmpty()) { "StudioDataTable requires at least one column" }
    val colors = LocalViewerColors.current
    StudioPanel(modifier = modifier) {
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
