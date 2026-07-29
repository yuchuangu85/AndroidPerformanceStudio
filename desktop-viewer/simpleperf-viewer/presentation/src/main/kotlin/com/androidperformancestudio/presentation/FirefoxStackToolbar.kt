package com.androidperformancestudio.presentation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.ImplementationFilter
import com.androidperformancestudio.ui.MacOsDeviceTargetStyle
import kotlinx.coroutines.delay

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun FirefoxStackToolbar(
    state: ReportState,
    actions: ReportActions,
    style: MacOsDeviceTargetStyle,
) {
    val sessionIdentity = state.lastReadyReport?.session?.directory
    var searchState by
        remember(sessionIdentity) {
            mutableStateOf(FlameSearchDraftState.initial(state.callStackQuery.searchText))
        }

    LaunchedEffect(sessionIdentity, state.callStackQuery.searchText) {
        searchState = searchState.acknowledge(state.callStackQuery.searchText)
    }
    LaunchedEffect(sessionIdentity, searchState.draft, searchState.authoritativeQuery) {
        if (searchState.isDirty) {
            delay(STACK_SEARCH_DEBOUNCE_MILLIS)
            val query = searchState.draft
            searchState = searchState.markDispatched(query)
            actions.onFlameSearch(query)
        }
    }

    Row(
        modifier = Modifier.testTag("stack-toolbar").horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ImplementationFilter.entries.forEach { filter ->
            MacOsChoiceChip(
                label = filter.displayName(),
                selected = state.callStackQuery.implementation == filter,
                enabled = true,
                style = style,
            ) { actions.onFlameImplementation(filter) }
        }
        Spacer(Modifier.width(4.dp))
        MacOsChoiceChip(
            label = "Invert Call Stack",
            selected = state.callStackQuery.direction == CallStackDirection.INVERTED,
            enabled = true,
            style = style,
        ) {
            actions.onCallTreeDirection(
                if (state.callStackQuery.direction == CallStackDirection.FORWARD) {
                    CallStackDirection.INVERTED
                } else {
                    CallStackDirection.FORWARD
                },
            )
        }
        Spacer(Modifier.width(8.dp))
        MacOsInlineTextField(
            label = "Filter Stacks",
            value = searchState.draft,
            enabled = true,
            onValueChange = { searchState = searchState.edit(it) },
            style = style,
            fieldWidth = 180.dp,
        )
    }
}

private fun ImplementationFilter.displayName(): String =
    when (this) {
        ImplementationFilter.ALL -> "All Frames"
        ImplementationFilter.SCRIPT -> "Script"
        ImplementationFilter.NATIVE -> "Native"
    }

private const val STACK_SEARCH_DEBOUNCE_MILLIS = 150L

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
