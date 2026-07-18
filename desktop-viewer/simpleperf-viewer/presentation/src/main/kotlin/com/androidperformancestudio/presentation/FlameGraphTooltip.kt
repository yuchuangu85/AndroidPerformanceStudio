package com.androidperformancestudio.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.visualization.FirefoxFlameGraphStyle
import java.util.Locale

@Composable
@Suppress("FunctionName", "LongMethod", "ktlint:standard:function-naming")
internal fun FirefoxFlameGraphTooltip(
    facts: FlameGraphTooltipFacts,
    style: FirefoxFlameGraphStyle,
    modifier: Modifier = Modifier,
) {
    val flameFrameLabel = localizedSimpleperfText("Flame frame")
    val inclusiveLabel = localizedSimpleperfText("inclusive")
    val selfLabel = localizedSimpleperfText("self")
    val accessible =
        buildString {
            append(flameFrameLabel)
            append(' ')
            append(facts.function)
            append(", ")
            append(inclusiveLabel)
            append(' ')
            append(facts.inclusiveWeight)
            append(", ")
            append(selfLabel)
            append(' ')
            append(facts.selfWeight)
            append(", ")
            append(facts.threadCount)
            append(" threads")
        }
    Surface(
        modifier =
            modifier
                .widthIn(max = TOOLTIP_MAX_WIDTH_DP.dp)
                .semantics { contentDescription = accessible },
        shape = RoundedCornerShape(2.dp),
        color = style.raisedSurface.toComposeColor(),
        contentColor = style.canvasForeground.toComposeColor(),
        border = BorderStroke(1.dp, style.surfaceBorder.toComposeColor()),
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(7.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    String.format(Locale.ROOT, "%.2f%%", facts.percentage),
                    color = style.canvasForeground.toComposeColor(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    facts.function,
                    color = style.canvasForeground.toComposeColor(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FirefoxTooltipFact("Stack Type", facts.implementation.firefoxStackType(), style)
            facts.category?.let { FirefoxTooltipFact("Category", it, style, maxLines = 1) }
            facts.resource?.let { FirefoxTooltipFact("Resource", it, style, maxLines = 1) }
            FirefoxTooltipTimings(facts, style)
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxTooltipTimings(
    facts: FlameGraphTooltipFacts,
    style: FirefoxFlameGraphStyle,
) {
    val foreground = style.canvasForeground.toComposeColor()
    Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
        Text("", modifier = Modifier.width(110.dp), fontSize = 10.sp)
        Text(
            "Running",
            modifier = Modifier.width(78.dp),
            color = foreground,
            fontSize = 10.sp,
            textAlign = TextAlign.End,
        )
        Text(
            "Self",
            modifier = Modifier.width(62.dp),
            color = foreground,
            fontSize = 10.sp,
            textAlign = TextAlign.End,
        )
    }
    FirefoxTooltipTimingRow("Overall", facts.inclusiveWeight, facts.selfWeight, style, bold = true)
    facts.category?.let { FirefoxTooltipTimingRow(it, facts.inclusiveWeight, facts.selfWeight, style) }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxTooltipTimingRow(
    label: String,
    running: Long,
    self: Long,
    style: FirefoxFlameGraphStyle,
    bold: Boolean = false,
) {
    val foreground = style.canvasForeground.toComposeColor()
    val weight = if (bold) FontWeight.SemiBold else FontWeight.Normal
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.width(110.dp),
            color = foreground,
            fontSize = 10.sp,
            fontWeight = weight,
        )
        Text(
            running.firefoxWeight(),
            modifier = Modifier.width(78.dp),
            color = foreground,
            fontSize = 10.sp,
            textAlign = TextAlign.End,
        )
        Text(
            self.firefoxWeight(),
            modifier = Modifier.width(62.dp),
            color = foreground,
            fontSize = 10.sp,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxTooltipFact(
    label: String,
    value: String,
    style: FirefoxFlameGraphStyle,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        localizedSimpleperfText("$label: ") + value,
        color = style.canvasForeground.toComposeColor(),
        fontSize = 11.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun com.androidperformancestudio.profileanalysis.FrameImplementation.firefoxStackType(): String =
    when (this) {
        com.androidperformancestudio.profileanalysis.FrameImplementation.NATIVE -> "Native"
        com.androidperformancestudio.profileanalysis.FrameImplementation.MANAGED -> "Java / Kotlin"
        com.androidperformancestudio.profileanalysis.FrameImplementation.KERNEL -> "Kernel"
        com.androidperformancestudio.profileanalysis.FrameImplementation.UNKNOWN -> "Unsymbolicated"
    }

private const val TOOLTIP_MAX_WIDTH_DP = 380
