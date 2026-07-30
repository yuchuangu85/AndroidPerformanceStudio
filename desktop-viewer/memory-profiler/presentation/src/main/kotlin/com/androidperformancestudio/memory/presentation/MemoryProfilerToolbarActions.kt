@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.presentation

import androidx.compose.runtime.Composable
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.dump_heap
import com.androidperformancestudio.memory.presentation.generated.resources.dumping
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

@Composable
public fun MemoryProfilerDumpHeapButton(
    state: MemoryProfilerState,
    onDumpHeap: () -> Unit,
    language: UiLanguage = UiLanguage.ENGLISH,
) {
    ProfilerCompactButton(
        text =
            localizedStringResource(
                if (state.isDumping) Res.string.dumping else Res.string.dump_heap,
                language,
            ),
        enabled =
            !state.isDumping &&
                state.selectedDeviceSerial != null &&
                state.selectedProcessId != null,
        onClick = onDumpHeap,
    )
}
