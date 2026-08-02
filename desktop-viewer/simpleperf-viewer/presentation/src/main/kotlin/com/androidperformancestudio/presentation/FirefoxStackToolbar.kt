package com.androidperformancestudio.presentation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
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
import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.ImplementationFilter
import com.androidperformancestudio.ui.radiobutton.MacOSChoiceChip
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun FirefoxStackToolbar(
    state: ReportState,
    actions: ReportActions,
    style: ViewerColors,
) {
    val language = currentSimpleperfLanguage()
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
            delay(STACK_SEARCH_DEBOUNCE_MILLIS.milliseconds)
            val query = searchState.draft
            searchState = searchState.markDispatched(query)
            actions.onFlameSearch(query)
        }
    }

    Row(
        modifier = Modifier.testTag("stack-toolbar").horizontalScroll(rememberScrollState()).padding(start = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ImplementationFilter.entries.forEach { filter ->
            MacOSChoiceChip(
                label = filter.displayName(language),
                selected = state.callStackQuery.implementation == filter,
                enabled = true,
                style = style,
            ) { actions.onFlameImplementation(filter) }
            Spacer(Modifier.width(8.dp))
        }
        Spacer(Modifier.width(8.dp))
        MacOsInlineTextField(
            label = localizedStringResource(SimpleperfViewerRes.sp_calltree_filter_stacks, language),
            value = searchState.draft,
            enabled = true,
            onValueChange = { searchState = searchState.edit(it) },
            style = style,
            fieldWidth = 180.dp,
        )
        Spacer(Modifier.width(8.dp))
        MacOSChoiceChip(
            label = localizedStringResource(SimpleperfViewerRes.sp_calltree_invert_call_stack, language),
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
    }
}

private fun ImplementationFilter.displayName(language: UiLanguage): String =
    when (this) {
        ImplementationFilter.ALL -> localizedStringResource(SimpleperfViewerRes.sp_calltree_all_frames, language)
        ImplementationFilter.SCRIPT -> localizedStringResource(SimpleperfViewerRes.sp_calltree_script, language)
        ImplementationFilter.NATIVE -> localizedStringResource(SimpleperfViewerRes.sp_flame_native, language)
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
