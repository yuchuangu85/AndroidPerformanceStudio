@file:Suppress("FunctionName", "LongParameterList")

package com.androidperformancestudio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
public fun ProfilerMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supportingText: List<String> = emptyList(),
    prominent: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = if (prominent) 6.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (prominent) 4.dp else 0.dp),
        ) {
            Text(
                label,
                style = if (prominent) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                color = if (prominent) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = if (prominent) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            supportingText.forEach { text ->
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
