@file:Suppress("FunctionNaming")

package com.androidperformancestudio.perfetto.presentation

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.perfetto_presentation.generated.resources.Res
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_capture
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_export
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_open_viewer
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_preset
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_run_preset
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_running
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_search
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_search_trace
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

/**
 * Page-level trace actions merged into the window header.
 *
 * "Open Trace" intentionally stays in the native File menu so the analyzer workspace no longer
 * spends a second row on page-level actions.
 */
@Composable
@Suppress("ktlint:standard:function-naming", "LongMethod")
public fun TraceAnalyzerHeaderActions(
    state: TraceAnalyzerWorkspaceState,
    actions: TraceAnalyzerWorkspaceActions,
    language: UiLanguage,
) {
    val activeTrace = state.activeTrace
    val canRunPreset = activeTrace != null && state.selectedPresetId != null && !state.presetRunning
    PerfettoCompactButton(
        text = localizedStringResource(Res.string.trace_workspace_capture, language),
        onClick = actions.onToggleCapture,
        selected = state.captureVisible,
        modifier = Modifier.testTag("toolbar-capture-trace"),
    )
    PerfettoCompactButton(
        text = localizedStringResource(Res.string.trace_workspace_export, language),
        onClick = { activeTrace?.let(actions.onExport) },
        enabled = activeTrace != null,
        modifier = Modifier.testTag("toolbar-export-trace"),
    )
    PerfettoCompactButton(
        text = localizedStringResource(Res.string.trace_workspace_open_viewer, language),
        onClick = { activeTrace?.let(actions.onOpenViewer) },
        enabled = activeTrace?.viewerAvailable == true,
        modifier = Modifier.testTag("toolbar-open-perfetto"),
    )
    PerfettoCompactTextField(
        value = state.traceSearchQuery,
        onValueChange = actions.onTraceSearchChange,
        placeholder = localizedStringResource(Res.string.trace_workspace_search_trace, language),
        enabled = activeTrace != null,
        modifier = Modifier.width(220.dp).testTag("toolbar-trace-search"),
    )
    PerfettoCompactButton(
        text = localizedStringResource(Res.string.trace_workspace_search, language),
        onClick = actions.onRunTraceSearch,
        enabled = activeTrace != null && state.traceSearchQuery.isNotBlank() && !state.searchRunning,
        modifier = Modifier.testTag("toolbar-run-search"),
    )
    val presetLabel = localizedStringResource(Res.string.trace_workspace_preset, language)
    DropdownSelector(
        items = state.presets,
        selectedItem = state.presets.firstOrNull { it.id == state.selectedPresetId },
        onItemSelected = { actions.onSelectPreset(it.id) },
        itemLabel = TraceDiagnosticPresetOption::title,
        placeholder = presetLabel,
        selectorDescription = presetLabel,
        enabled = state.presets.isNotEmpty() && !state.presetRunning,
        fillWidth = true,
        modifier = Modifier.width(190.dp).testTag("toolbar-preset-selector"),
    )
    PerfettoCompactButton(
        text =
            localizedStringResource(
                if (state.presetRunning) Res.string.trace_workspace_running else Res.string.trace_workspace_run_preset,
                language,
            ),
        onClick = actions.onRunPreset,
        enabled = canRunPreset,
        selected = canRunPreset,
        modifier = Modifier.testTag("toolbar-run-preset"),
    )
}
