package com.androidperformancestudio.presentation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.ImplementationFilter

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
internal fun FlameGraphToolbar(
    searchDraft: String,
    implementation: ImplementationFilter,
    direction: CallStackDirection,
    hasTransforms: Boolean,
    onSearchDraft: (String) -> Unit,
    onImplementation: (ImplementationFilter) -> Unit,
    onDirection: (CallStackDirection) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
) {
    val searchDescription = localizedSimpleperfText("Flame graph search")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = searchDraft,
            onValueChange = onSearchDraft,
            singleLine = true,
            label = { Text("Search function or library") },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = searchDescription },
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CallStackDirection.entries.forEach { option ->
                FilterChip(
                    selected = direction == option,
                    onClick = { onDirection(option) },
                    label = { Text(option.displayName()) },
                )
            }
            if (hasTransforms) {
                OutlinedButton(onClick = onUndo) { Text("Undo transform") }
                OutlinedButton(onClick = onClear) { Text("Clear transforms") }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ImplementationFilter.entries.forEach { option ->
                FilterChip(
                    selected = implementation == option,
                    onClick = { onImplementation(option) },
                    label = { Text(option.displayName()) },
                )
            }
        }
    }
}

private fun CallStackDirection.displayName(): String =
    when (this) {
        CallStackDirection.FORWARD -> "Forward"
        CallStackDirection.INVERTED -> "Inverted"
    }

private fun ImplementationFilter.displayName(): String =
    when (this) {
        ImplementationFilter.ALL -> "All"
        ImplementationFilter.NATIVE -> "Native"
        ImplementationFilter.MANAGED -> "Managed"
        ImplementationFilter.KERNEL -> "Kernel"
        ImplementationFilter.UNKNOWN -> "Unknown"
    }
