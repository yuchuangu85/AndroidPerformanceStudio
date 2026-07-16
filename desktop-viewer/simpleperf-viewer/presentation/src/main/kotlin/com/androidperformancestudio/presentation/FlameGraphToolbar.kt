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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.ImplementationFilter
import kotlinx.coroutines.delay
import java.nio.file.Path

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
internal fun FlameGraphToolbar(
    sessionIdentity: Path,
    authoritativeSearch: String,
    implementation: ImplementationFilter,
    direction: CallStackDirection,
    hasTransforms: Boolean,
    onSearch: (String) -> Unit,
    onImplementation: (ImplementationFilter) -> Unit,
    onDirection: (CallStackDirection) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
) {
    var searchState by remember(sessionIdentity) { mutableStateOf(FlameSearchDraftState.initial(authoritativeSearch)) }
    val searchDescription = localizedSimpleperfText("Flame graph search")

    LaunchedEffect(sessionIdentity, authoritativeSearch) {
        searchState = searchState.acknowledge(authoritativeSearch)
    }
    LaunchedEffect(sessionIdentity, searchState.draft, searchState.authoritativeQuery) {
        if (searchState.isDirty) {
            delay(SEARCH_DEBOUNCE_MILLIS)
            val query = searchState.draft
            searchState = searchState.markDispatched(query)
            onSearch(query)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = searchState.draft,
            onValueChange = { searchState = searchState.edit(it) },
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

internal data class FlameSearchDraftState(
    val authoritativeQuery: String,
    val draft: String,
    private val pendingDispatches: List<String>,
) {
    val isDirty: Boolean
        get() = draft != authoritativeQuery

    fun edit(value: String): FlameSearchDraftState = copy(draft = value)

    fun markDispatched(value: String): FlameSearchDraftState = copy(pendingDispatches = pendingDispatches + value)

    fun acknowledge(value: String): FlameSearchDraftState {
        if (value == authoritativeQuery) return this
        val pendingIndex = pendingDispatches.indexOf(value)
        return if (pendingIndex >= 0) {
            copy(
                authoritativeQuery = value,
                pendingDispatches = pendingDispatches.drop(pendingIndex + 1),
            )
        } else {
            initial(value)
        }
    }

    companion object {
        fun initial(value: String): FlameSearchDraftState =
            FlameSearchDraftState(
                authoritativeQuery = value,
                draft = value,
                pendingDispatches = emptyList(),
            )
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

private const val SEARCH_DEBOUNCE_MILLIS = 150L
