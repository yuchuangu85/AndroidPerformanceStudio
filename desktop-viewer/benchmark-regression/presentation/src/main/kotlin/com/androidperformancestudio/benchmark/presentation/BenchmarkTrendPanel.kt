@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber")

package com.androidperformancestudio.benchmark.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.benchmark.presentation.generated.resources.*
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.CopyableText
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioPanel
import com.androidperformancestudio.ui.studio.StudioPanelHeader
import com.androidperformancestudio.ui.studio.StudioTokens
import java.util.Locale

private const val TREND_WINDOW_VERSIONS = 10
private const val TREND_CHART_HEIGHT_DP = 132

/**
 * Metric history chart. The panel only plots stored values and states when the history mixes
 * environments or carries too few samples, because the implementation plan forbids a trend that
 * silently compares incompatible runs.
 */
@Composable
internal fun BenchmarkTrendPanel(
    state: BenchmarkReportsState,
    language: UiLanguage,
    onSelectMetric: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    val selectedMetric = state.metrics.firstOrNull { it.key == state.selectedMetricKey }
    StudioPanel(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StudioPanelHeader(
                title = localizedStringResource(Res.string.benchmark_trend, language),
                modifier = Modifier.weight(1f),
            )
            DropdownSelector(
                items = state.metrics,
                selectedItem = selectedMetric,
                onItemSelected = { onSelectMetric(it.key) },
                itemLabel = { option -> metricLabel(option) },
                placeholder = localizedStringResource(Res.string.trend_empty, language),
                enabled = state.metrics.isNotEmpty(),
                fillWidth = false,
            )
            Spacer(Modifier.width(StudioTokens.panelGap))
            Text(
                text = localizedStringResource(Res.string.trend_window, language, TREND_WINDOW_VERSIONS),
                color = colors.secondaryText,
                fontSize = ViewerTypography.label.fontSize,
                lineHeight = ViewerTypography.label.lineHeight,
            )
        }
        val trend = state.trend
        if (trend == null || trend.points.size < 2) {
            CopyableText(
                text = localizedStringResource(Res.string.trend_empty, language),
                color = colors.secondaryText,
                fontSize = ViewerTypography.bodyCompact.fontSize,
                lineHeight = ViewerTypography.bodyCompact.lineHeight,
                language = language,
            )
            return@StudioPanel
        }
        if (trend.environmentVaries) {
            TrendWarning(localizedStringResource(Res.string.trend_environment_varies, language), colors.warning)
        }
        if (trend.hasLowSamplePoints) {
            TrendWarning(
                localizedStringResource(Res.string.trend_low_sample, language, trend.minimumSampleCount),
                MaterialTheme.colorScheme.error,
            )
        }
        TrendChart(trend, colors)
    }
}

@Composable
private fun TrendWarning(text: String, color: androidx.compose.ui.graphics.Color) {
    CopyableText(
        text = text,
        color = color,
        fontSize = ViewerTypography.label.fontSize,
        lineHeight = ViewerTypography.label.lineHeight,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TrendChart(trend: BenchmarkTrend, colors: ViewerColors) {
    val values = trend.points.map { it.value }
    val rawMin = values.min()
    val rawMax = values.max()
    val padding = ((rawMax - rawMin).takeIf { it > 0.0 } ?: (kotlin.math.abs(rawMax).takeIf { it > 0.0 } ?: 1.0)) * 0.1
    val min = rawMin - padding
    val max = rawMax + padding
    Row(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.width(64.dp).height(TREND_CHART_HEIGHT_DP.dp).padding(end = 6.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            Text(formatAxis(max), color = colors.mutedText, fontFamily = FontFamily.Monospace, fontSize = ViewerTypography.label.fontSize)
            Text(formatAxis((max + min) / 2), color = colors.mutedText, fontFamily = FontFamily.Monospace, fontSize = ViewerTypography.label.fontSize)
            Text(formatAxis(min), color = colors.mutedText, fontFamily = FontFamily.Monospace, fontSize = ViewerTypography.label.fontSize)
        }
        Column(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(TREND_CHART_HEIGHT_DP.dp)) {
                val width = size.width
                val height = size.height
                repeat(3) { index ->
                    val y = height * index / 2f
                    drawLine(
                        color = colors.border,
                        start = Offset(0f, y),
                        end = Offset(width, y),
                        strokeWidth = 1f,
                    )
                }
                if (trend.points.size < 2) return@Canvas
                val stepX = width / (trend.points.size - 1).toFloat()
                val path = Path()
                trend.points.forEachIndexed { index, point ->
                    val x = stepX * index
                    val ratio = ((point.value - min) / (max - min)).toFloat()
                    val y = height - (ratio * height)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path = path, color = colors.accent, style = Stroke(width = 2f))
                trend.points.forEachIndexed { index, point ->
                    val x = stepX * index
                    val ratio = ((point.value - min) / (max - min)).toFloat()
                    val y = height - (ratio * height)
                    if (point.comparable) {
                        drawCircle(color = colors.accent, radius = 3.5f, center = Offset(x, y))
                    } else {
                        drawCircle(
                            color = colors.warning,
                            radius = 3.5f,
                            center = Offset(x, y),
                            style = Stroke(width = 2f),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                trend.points.filterIndexed { index, _ -> index % xLabelStride(trend.points.size) == 0 }.forEach { point ->
                    Text(
                        text = point.label,
                        color = colors.mutedText,
                        fontSize = ViewerTypography.label.fontSize,
                        lineHeight = ViewerTypography.label.lineHeight,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun xLabelStride(pointCount: Int): Int = if (pointCount <= 7) 1 else (pointCount / 7) + 1

private fun formatAxis(value: Double): String = "%.4g".format(Locale.ROOT, value)

private fun metricLabel(option: BenchmarkMetricOption): String =
    if (option.unit.isBlank()) {
        option.caseIdentity + " · " + option.metricName
    } else {
        option.caseIdentity + " · " + option.metricName + " (" + option.unit + ")"
    }
