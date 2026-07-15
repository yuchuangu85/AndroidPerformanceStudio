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
    val accessible =
        buildString {
            append("Flame frame ")
            append(facts.function)
            append(", inclusive ")
            append(facts.inclusiveWeight)
            append(", self ")
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
            facts.category?.let { Text("Category: $it", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text("Implementation: ${facts.implementation.displayName()}")
            facts.resource?.let { Text("Resource: $it", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text("Inclusive ${facts.inclusiveWeight} · Self ${facts.selfWeight}")
            Text("Samples ${facts.sampleCount} · ${String.format(Locale.ROOT, "%.2f%%", facts.percentage)}")
            facts.previewRangeWeight?.let { Text("Preview range weight: $it") }
        }
    }
}

private fun FrameImplementation.displayName(): String = name.lowercase().replaceFirstChar(Char::uppercase)
