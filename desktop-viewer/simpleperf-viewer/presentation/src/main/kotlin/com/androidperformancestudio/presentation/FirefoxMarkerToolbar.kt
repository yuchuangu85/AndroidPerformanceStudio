@file:Suppress("MaxLineLength")

package com.androidperformancestudio.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.ui.MacOsDeviceTargetStyle
import kotlinx.coroutines.delay

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun FirefoxMarkerToolbar(
    state: ReportState,
    actions: ReportActions,
    style: MacOsDeviceTargetStyle,
) {
    var draft by remember(state.lastReadyReport?.session?.directory) { mutableStateOf(state.workspace.markerSearchText) }
    LaunchedEffect(state.workspace.markerSearchText) {
        if (state.workspace.markerSearchText != draft) draft = state.workspace.markerSearchText
    }
    LaunchedEffect(draft) {
        if (draft != state.workspace.markerSearchText) {
            delay(MARKER_SEARCH_DEBOUNCE_MILLIS)
            actions.onMarkerSearch(draft)
        }
    }
    MacOsInlineTextField(
        label = "Filter markers",
        value = draft,
        enabled = true,
        onValueChange = { draft = it },
        style = style,
        fieldWidth = 240.dp,
    )
}

private const val MARKER_SEARCH_DEBOUNCE_MILLIS = 150L
