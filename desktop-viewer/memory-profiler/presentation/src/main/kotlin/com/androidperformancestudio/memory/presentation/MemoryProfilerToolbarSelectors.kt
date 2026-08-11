@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.presentation

import androidx.compose.runtime.Composable
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.device_offline
import com.androidperformancestudio.memory.presentation.generated.resources.device_selector
import com.androidperformancestudio.memory.presentation.generated.resources.process_selector
import com.androidperformancestudio.memory.presentation.generated.resources.select_device
import com.androidperformancestudio.memory.presentation.generated.resources.select_process
import com.androidperformancestudio.memory.presentation.generated.resources.text
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

@Composable
public fun MemoryProfilerToolbarSelectors(
    state: MemoryProfilerState,
    onSelectDevice: (String) -> Unit,
    onSelectProcess: (Int) -> Unit,
    language: UiLanguage = UiLanguage.ENGLISH,
) {
    MemoryProfilerDeviceSelector(state, onSelectDevice, language)
    MemoryProfilerProcessSelector(state, onSelectProcess, language)
}

@Composable
private fun MemoryProfilerDeviceSelector(
    state: MemoryProfilerState,
    onSelectDevice: (String) -> Unit,
    language: UiLanguage,
) {
    val selected = state.devices.firstOrNull { it.serial == state.selectedDeviceSerial }
    val selectDeviceLabel = localizedStringResource(Res.string.select_device, language)
    DropdownSelector(
        items = state.devices,
        selectedItem = selected,
        onItemSelected = { onSelectDevice(it.serial) },
        itemLabel = { device ->
            if (device.online) {
                device.name
            } else {
                localizedStringResource(Res.string.device_offline, language, device.name)
            }
        },
        selectedItemLabel = MemoryDeviceOption::name,
        placeholder = selectDeviceLabel,
        selectorDescription = localizedStringResource(Res.string.device_selector, language),
        itemEnabled = MemoryDeviceOption::online,
    )
}

@Composable
private fun MemoryProfilerProcessSelector(
    state: MemoryProfilerState,
    onSelectProcess: (Int) -> Unit,
    language: UiLanguage,
) {
    val selected = state.processes.firstOrNull { it.pid == state.selectedProcessId }
    val selectProcessLabel = localizedStringResource(Res.string.select_process, language)
    DropdownSelector(
        items = state.processes,
        selectedItem = selected,
        onItemSelected = { onSelectProcess(it.pid) },
        itemLabel = { process ->
            localizedStringResource(Res.string.text, language, process.name, process.pid)
        },
        selectedItemLabel = MemoryProcessOption::name,
        placeholder = selectProcessLabel,
        selectorDescription = localizedStringResource(Res.string.process_selector, language),
    )
}
