@file:Suppress("MagicNumber", "TooManyFunctions")

package com.androidperformancestudio.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.presentation.generated.resources.ViewerRes
import com.androidperformancestudio.profileanalysis.FrameImplementation
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.visualization.FirefoxFlameGraphStyle
import com.androidperformancestudio.visualization.FlameGraphPalette
import com.androidperformancestudio.visualization.FlameTheme
import org.jetbrains.compose.resources.StringResource
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10

@Composable
@Suppress("FunctionName", "LongMethod", "ktlint:standard:function-naming")
internal fun FirefoxFlameGraphTooltip(
    facts: FlameGraphTooltipFacts,
    style: FirefoxFlameGraphStyle,
    modifier: Modifier = Modifier,
) {
    val language = currentSimpleperfLanguage()
    val durationText = firefoxTooltipPercent(facts.percentage)
    val foreground = style.canvasForeground.toComposeColor()
    val accessible =
        buildString {
            append(localizedStringResource(ViewerRes.sp_report_flame_frame, language))
            append(' ')
            append(facts.function)
            append(", ")
            append(durationText)
            append(", ")
            append(localizedStringResource(ViewerRes.sp_report_inclusive, language))
            append(' ')
            append(facts.inclusiveWeight)
            append(", ")
            append(localizedStringResource(ViewerRes.sp_report_self_weight_label, language))
            append(' ')
            append(facts.selfWeight)
        }
    Surface(
        modifier =
            modifier
                .widthIn(max = TOOLTIP_MAX_WIDTH_DP.dp)
                .testTag("firefox-flame-tooltip")
                .semantics { contentDescription = accessible },
        shape = RoundedCornerShape(0.dp),
        color = style.raisedSurface.toComposeColor(),
        contentColor = foreground,
        border = BorderStroke(1.dp, style.surfaceBorder.toComposeColor()),
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.width(IntrinsicSize.Max).padding(TOOLTIP_PADDING_DP.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    durationText,
                    color = style.mutedForeground.toComposeColor(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    facts.function,
                    color = foreground,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FirefoxTooltipDivider(style)
            Column(Modifier.padding(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 5.dp)) {
                FirefoxTooltipDetail(
                    ViewerRes.sp_details_stack_type_value_format,
                    facts.implementation.localizedFirefoxStackType(language),
                    style,
                )
                facts.category?.let { category -> FirefoxTooltipCategoryDetail(category, style) }
                facts.resource?.let { FirefoxTooltipDetail(ViewerRes.sp_details_resource_value_format, it, style) }
            }
            FirefoxTooltipTimings(facts, style)
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxTooltipDetail(
    label: StringResource,
    value: String,
    style: FirefoxFlameGraphStyle,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        FirefoxTooltipLabel(label, style)
        Text(
            value,
            modifier = Modifier.widthIn(max = TOOLTIP_DETAIL_VALUE_WIDTH_DP.dp),
            color = style.canvasForeground.toComposeColor(),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxTooltipCategoryDetail(
    category: String,
    style: FirefoxFlameGraphStyle,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        FirefoxTooltipLabel(ViewerRes.sp_details_category_value_format, style)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .testTag("firefox-tooltip-category-swatch")
                    .background(firefoxCategoryColor(category, style)),
            )
            Spacer(Modifier.width(3.dp))
            Text(
                category,
                modifier = Modifier.widthIn(max = TOOLTIP_DETAIL_VALUE_WIDTH_DP.dp),
                color = style.canvasForeground.toComposeColor(),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxTooltipLabel(
    label: StringResource,
    style: FirefoxFlameGraphStyle,
) {
    Text(
        localizedStringResource(label, currentSimpleperfLanguage(), "").trimEnd(),
        modifier = Modifier.width(TOOLTIP_DETAIL_LABEL_WIDTH_DP.dp),
        color = style.mutedForeground.toComposeColor(),
        fontSize = 11.sp,
        textAlign = TextAlign.End,
        maxLines = 1,
    )
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxTooltipTimings(
    facts: FlameGraphTooltipFacts,
    style: FirefoxFlameGraphStyle,
) {
    FirefoxTooltipDivider(style)
    Row(
        modifier = Modifier.padding(top = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(TOOLTIP_TIMING_LABEL_WIDTH_DP.dp))
        Spacer(Modifier.width(TOOLTIP_METER_WIDTH_DP.dp))
        FirefoxTooltipTimingHeader(ViewerRes.sp_report_running, TOOLTIP_RUNNING_WIDTH_DP, style)
        FirefoxTooltipTimingHeader(ViewerRes.sp_report_self_column, TOOLTIP_SELF_WIDTH_DP, style)
    }
    FirefoxTooltipTimingRow(
        label = localizedStringResource(ViewerRes.sp_report_overall, currentSimpleperfLanguage()),
        running = facts.inclusiveWeight,
        self = facts.selfWeight,
        maximum = facts.inclusiveWeight,
        color = firefoxOverallMeterColor(style.theme),
        style = style,
        tag = "overall",
    )
    facts.category?.let { category ->
        FirefoxTooltipTimingRow(
            label = category,
            running = facts.inclusiveWeight,
            self = facts.selfWeight,
            maximum = facts.inclusiveWeight,
            color = firefoxCategoryColor(category, style),
            style = style,
            tag = "category",
        )
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxTooltipTimingHeader(
    label: StringResource,
    width: Int,
    style: FirefoxFlameGraphStyle,
) {
    Text(
        localizedStringResource(label, currentSimpleperfLanguage()),
        modifier = Modifier.width(width.dp),
        color = style.mutedForeground.toComposeColor(),
        fontSize = 10.sp,
        textAlign = TextAlign.End,
        maxLines = 1,
    )
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun FirefoxTooltipTimingRow(
    label: String,
    running: Long,
    self: Long,
    maximum: Long,
    color: Color,
    style: FirefoxFlameGraphStyle,
    tag: String,
) {
    Row(
        modifier =
            Modifier
                .padding(top = 12.dp)
                .testTag("firefox-tooltip-$tag-row"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.width(TOOLTIP_TIMING_LABEL_WIDTH_DP.dp),
            color = style.canvasForeground.toComposeColor(),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        FirefoxTooltipTotalSelfMeters(running, self, maximum, color, style, tag)
        FirefoxTooltipTimingValue(running.firefoxTooltipWeight(zeroAsDash = false), TOOLTIP_RUNNING_WIDTH_DP, style)
        FirefoxTooltipTimingValue(self.firefoxTooltipWeight(zeroAsDash = true), TOOLTIP_SELF_WIDTH_DP, style)
    }
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun FirefoxTooltipTotalSelfMeters(
    running: Long,
    self: Long,
    maximum: Long,
    color: Color,
    style: FirefoxFlameGraphStyle,
    tag: String,
) {
    Column(
        modifier = Modifier.width(TOOLTIP_METER_WIDTH_DP.dp).height(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FirefoxTooltipMeter(running, maximum, color, style, "$tag-running")
        FirefoxTooltipMeter(self, maximum, color, style, "$tag-self")
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxTooltipMeter(
    value: Long,
    maximum: Long,
    color: Color,
    style: FirefoxFlameGraphStyle,
    tag: String,
) {
    val safeMaximum = maximum.coerceAtLeast(1L)
    val safeValue = value.coerceIn(0L, safeMaximum)
    val fraction = (safeValue.toDouble() / safeMaximum.toDouble()).toFloat()
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .testTag("firefox-tooltip-$tag-meter")
                .background(firefoxMeterTrackColor(style.theme))
                .semantics {
                    progressBarRangeInfo =
                        ProgressBarRangeInfo(
                            current = safeValue.toFloat(),
                            range = 0f..safeMaximum.toFloat(),
                        )
                },
    ) {
        if (safeValue > 0L) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .testTag("firefox-tooltip-$tag-bar")
                    .background(color),
            )
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxTooltipTimingValue(
    value: String,
    width: Int,
    style: FirefoxFlameGraphStyle,
) {
    Text(
        value,
        modifier = Modifier.width(width.dp),
        color = style.canvasForeground.toComposeColor(),
        fontSize = 10.sp,
        textAlign = TextAlign.End,
        maxLines = 1,
    )
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxTooltipDivider(style: FirefoxFlameGraphStyle) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(style.surfaceBorder.toComposeColor()))
}

internal fun firefoxTooltipPercent(percentage: Double): String {
    val safePercentage = percentage.takeIf(Double::isFinite)?.coerceIn(0.0, 100.0) ?: 0.0
    if (safePercentage == 0.0) return "0%"
    val digitsOnLeft = floor(log10(safePercentage)).toInt() + 1
    val fractionDigits = (2 - digitsOnLeft).coerceIn(0, 1)
    val formatter =
        NumberFormat.getNumberInstance(Locale.ROOT).apply {
            minimumFractionDigits = fractionDigits
            maximumFractionDigits = fractionDigits
            isGroupingUsed = true
        }
    return formatter.format(safePercentage) + "%"
}

private fun Long.firefoxTooltipWeight(zeroAsDash: Boolean): String =
    if (zeroAsDash && this == 0L) {
        "—"
    } else {
        NumberFormat.getIntegerInstance(Locale.ROOT).format(this)
    }

private fun FrameImplementation.localizedFirefoxStackType(language: UiLanguage): String =
    when (this) {
        FrameImplementation.NATIVE -> localizedStringResource(ViewerRes.sp_flame_native, language)
        FrameImplementation.MANAGED -> localizedStringResource(ViewerRes.sp_flame_managed, language)
        FrameImplementation.KERNEL -> localizedStringResource(ViewerRes.sp_flame_kernel, language)
        FrameImplementation.UNKNOWN -> localizedStringResource(ViewerRes.sp_flame_unknown, language)
    }

private fun firefoxCategoryColor(
    category: String,
    style: FirefoxFlameGraphStyle,
): Color = style.categoryStyle(FlameGraphPalette.categoryRole(category)).selectedFill.toComposeColor()

private fun firefoxOverallMeterColor(theme: FlameTheme): Color =
    when (theme) {
        FlameTheme.LIGHT -> Color(0xFF45A1FF)
        FlameTheme.DARK -> Color(0xFF0A84FF)
    }

private fun firefoxMeterTrackColor(theme: FlameTheme): Color =
    when (theme) {
        FlameTheme.LIGHT -> Color.Black.copy(alpha = 0.1f)
        FlameTheme.DARK -> Color.White.copy(alpha = 0.1f)
    }

private const val TOOLTIP_MAX_WIDTH_DP = 600
private const val TOOLTIP_PADDING_DP = 8
private const val TOOLTIP_DETAIL_LABEL_WIDTH_DP = 90
private const val TOOLTIP_DETAIL_VALUE_WIDTH_DP = 470
private const val TOOLTIP_TIMING_LABEL_WIDTH_DP = 110
private const val TOOLTIP_METER_WIDTH_DP = 150
private const val TOOLTIP_RUNNING_WIDTH_DP = 78
private const val TOOLTIP_SELF_WIDTH_DP = 62
