@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.presentation

import androidx.compose.runtime.Composable
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.capture_native_heap
import com.androidperformancestudio.memory.presentation.generated.resources.dump_bitmaps
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

@Composable
public fun MemoryProfilerCaptureNativeHeapButton(
    state: MemoryProfilerState,
    onCaptureNativeHeap: () -> Unit,
    language: UiLanguage = UiLanguage.ENGLISH,
) {
    ProfilerCompactButton(
        text = localizedStringResource(Res.string.capture_native_heap, language),
        enabled =
            !state.isDumping &&
                state.selectedDeviceSerial != null &&
                state.selectedProcessId != null,
        onClick = onCaptureNativeHeap,
    )
}

@Composable
public fun MemoryProfilerDumpBitmapsButton(
    state: MemoryProfilerState,
    onDumpBitmaps: () -> Unit,
    language: UiLanguage = UiLanguage.ENGLISH,
) {
    ProfilerCompactButton(
        text = localizedStringResource(Res.string.dump_bitmaps, language),
        enabled =
            !state.isDumping &&
                state.selectedDeviceSerial != null &&
                state.selectedProcessId != null &&
                state.devices.firstOrNull { it.serial == state.selectedDeviceSerial }?.supportsBitmapDump == true,
        onClick = onDumpBitmaps,
    )
}
