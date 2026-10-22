@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.memory_compare
import com.androidperformancestudio.memory.presentation.generated.resources.memory_mapping
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

@Composable
public fun MemoryWorkspaceActions(
    state: MemoryProfilerState,
    language: UiLanguage,
    onMapping: () -> Unit,
    onCompare: () -> Unit,
) {
    Row(
        modifier = Modifier.testTag("memory-workspace-actions"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfilerCompactButton(
            localizedStringResource(Res.string.memory_mapping, language),
            onMapping,
            enabled =
                state.snapshotSummary != null && !state.isDumping,
        )
        ProfilerCompactButton(
            localizedStringResource(Res.string.memory_compare, language),
            onCompare,
            enabled = state.snapshotSummaries.size >= 2 && !state.isDumping,
            selected = state.viewMode == MemoryProfilerViewMode.Diff,
        )
    }
}
