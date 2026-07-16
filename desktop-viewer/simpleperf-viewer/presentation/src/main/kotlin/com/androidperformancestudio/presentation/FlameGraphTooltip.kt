package com.androidperformancestudio.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.profileanalysis.FrameImplementation
import java.util.Locale

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun FlameGraphTooltip(facts: FlameGraphTooltipFacts) {
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
        }
    Card(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = accessible },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                facts.function,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            facts.category?.let {
                DynamicTooltipFact("Category", it, maxLines = 1)
            }
            DynamicTooltipFact("Implementation", localizedSimpleperfText(facts.implementation.displayName()))
            facts.resource?.let { DynamicTooltipFact("Resource", it, maxLines = 1) }
            Text(
                "${localizedSimpleperfText("Inclusive")} ${facts.inclusiveWeight} · " +
                    "${localizedSimpleperfText("Self")} ${facts.selfWeight}",
            )
            Text(
                "${localizedSimpleperfText("Samples")} ${facts.sampleCount} · " +
                    String.format(Locale.ROOT, "%.2f%%", facts.percentage),
            )
            facts.previewRangeWeight?.let { DynamicTooltipFact("Preview range weight", it.toString()) }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun DynamicTooltipFact(
    label: String,
    value: String,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        localizedSimpleperfText("$label: ") + value,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun FrameImplementation.displayName(): String = name.lowercase().replaceFirstChar(Char::uppercase)
