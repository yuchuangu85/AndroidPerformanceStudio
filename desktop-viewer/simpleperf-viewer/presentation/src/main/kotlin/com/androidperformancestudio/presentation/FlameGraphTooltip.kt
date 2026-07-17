package com.androidperformancestudio.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.profileanalysis.FrameImplementation
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
        Column(Modifier.padding(5.dp)) {
            Text(
                facts.function,
                color = style.canvasForeground.toComposeColor(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            facts.resource?.let { FirefoxTooltipFact("Resource", it, style, maxLines = 1) }
            facts.category?.let { FirefoxTooltipFact("Category", it, style, maxLines = 1) }
            FirefoxTooltipFact("Implementation", localizedSimpleperfText(facts.implementation.displayName()), style)
            Text(
                "${localizedSimpleperfText("Inclusive")} ${facts.inclusiveWeight} · " +
                    "${localizedSimpleperfText("Self")} ${facts.selfWeight}",
                color = style.canvasForeground.toComposeColor(),
                fontSize = 11.sp,
            )
            Text(
                "${localizedSimpleperfText("Samples")} ${facts.sampleCount} · " +
                    "${localizedSimpleperfText("Threads")} ${facts.threadCount} · " +
                    String.format(Locale.ROOT, "%.2f%%", facts.percentage),
                color = style.canvasForeground.toComposeColor(),
                fontSize = 11.sp,
            )
            facts.previewRangeWeight?.let { FirefoxTooltipFact("Preview range weight", it.toString(), style) }
        }
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

private fun FrameImplementation.displayName(): String = name.lowercase().replaceFirstChar(Char::uppercase)

private const val TOOLTIP_MAX_WIDTH_DP = 380
