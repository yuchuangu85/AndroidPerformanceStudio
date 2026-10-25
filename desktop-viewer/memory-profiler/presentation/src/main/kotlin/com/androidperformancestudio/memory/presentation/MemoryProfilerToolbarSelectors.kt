@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.device_offline
import com.androidperformancestudio.memory.presentation.generated.resources.process
import com.androidperformancestudio.memory.presentation.generated.resources.process_selector
import com.androidperformancestudio.memory.presentation.generated.resources.text
import com.androidperformancestudio.ui.DeviceDropdownSelector
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.HeaderSpacer
import com.androidperformancestudio.ui.LocalTitleBarTargetHost
import com.androidperformancestudio.ui.LocalWindowMenuBarActive
import com.androidperformancestudio.ui.TitleBarTargetOrInline
import com.androidperformancestudio.ui.TitleBarTargetSlot
import com.androidperformancestudio.ui.TitleBarTargetWidth
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

@Suppress("CyclomaticComplexMethod", "ComplexCondition", "MaxLineLength")
@Composable
public fun MemoryProfilerToolbarSelectors(
    state: MemoryProfilerState,
    onSelectDevice: (String) -> Unit,
    onSelectProcess: (Int) -> Unit,
    language: UiLanguage = UiLanguage.ENGLISH,
) {
    val inTitleBar = LocalWindowMenuBarActive.current && LocalTitleBarTargetHost.current != null
    val host = LocalTitleBarTargetHost.current.takeIf { inTitleBar }
    LaunchedEffect(host, host?.selectedDeviceSerial, state.devices, state.selectedDeviceSerial) {
        if (host != null) {
            val desired = host.selectedDeviceSerial
            val selected = state.devices.firstOrNull { it.serial == state.selectedDeviceSerial }
            if (desired == null && selected != null) {
                host.chooseDevice(selected.serial, selected.name)
            } else if (
                desired != null &&
                desired != state.selectedDeviceSerial &&
                state.devices.any { it.serial == desired && it.online }
            ) {
                onSelectDevice(desired)
            }
        }
    }
    LaunchedEffect(host, host?.selectedPackageName, state.processes, state.selectedProcessId, state.selectedDeviceSerial) {
        if (host != null && host.deviceReady(state.selectedDeviceSerial)) {
            val selected = state.processes.firstOrNull { it.pid == state.selectedProcessId }
            val desired = host.selectedPackageName
            if (desired == null && selected != null) {
                host.choosePackage(selected.packageName)
            } else if (desired != null && selected?.packageName != desired) {
                state.processes.firstOrNull { it.packageName == desired }?.let { onSelectProcess(it.pid) }
            }
        }
    }
    val selectorModifier = if (inTitleBar) Modifier.width(TitleBarTargetWidth) else Modifier
    TitleBarTargetOrInline(TitleBarTargetSlot.DEVICE) {
        MemoryProfilerDeviceSelector(
            state,
            onSelectDevice = { serial ->
                state.devices.firstOrNull { it.serial == serial }?.let { host?.chooseDevice(serial, it.name) }
                onSelectDevice(serial)
            },
            language = language,
            modifier = selectorModifier,
            fillWidth = inTitleBar,
            displayTextOverride = host?.selectedDeviceLabel,
        )
        if (!inTitleBar) HeaderSpacer()
    }
    TitleBarTargetOrInline(TitleBarTargetSlot.PROCESS) {
        MemoryProfilerProcessSelector(
            state,
            onSelectProcess = { pid ->
                state.processes.firstOrNull { it.pid == pid }?.let { host?.choosePackage(it.packageName) }
                onSelectProcess(pid)
            },
            language = language,
            modifier = selectorModifier,
            fillWidth = inTitleBar,
            displayTextOverride =
                host?.selectedPackageName?.takeIf { packageName ->
                    state.processes.firstOrNull { it.pid == state.selectedProcessId }?.packageName != packageName
                },
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun MemoryProfilerDeviceSelector(
    state: MemoryProfilerState,
    onSelectDevice: (String) -> Unit,
    language: UiLanguage,
    modifier: Modifier,
    fillWidth: Boolean,
    displayTextOverride: String?,
) {
    val selected = state.devices.firstOrNull { it.serial == state.selectedDeviceSerial }
    DeviceDropdownSelector(
        language = language,
        items = state.devices,
        selectedItem = selected,
        modifier = modifier,
        fillWidth = fillWidth,
        displayTextOverride = displayTextOverride,
        onItemSelected = { onSelectDevice(it.serial) },
        itemLabel = { device ->
            if (device.online) {
                device.name
            } else {
                localizedStringResource(Res.string.device_offline, language, device.name)
            }
        },
        selectedItemLabel = MemoryDeviceOption::name,
        itemEnabled = MemoryDeviceOption::online,
    )
}

@Suppress("LongParameterList")
@Composable
private fun MemoryProfilerProcessSelector(
    state: MemoryProfilerState,
    onSelectProcess: (Int) -> Unit,
    language: UiLanguage,
    modifier: Modifier,
    fillWidth: Boolean,
    displayTextOverride: String?,
) {
    val selected = state.processes.firstOrNull { it.pid == state.selectedProcessId }
    val selectProcessLabel = localizedStringResource(Res.string.process, language)
    DropdownSelector(
        items = state.processes,
        searchable = true,
        searchLanguage = language,
        itemSearchText = { "${it.name} ${it.packageName} ${it.pid}" },
        selectedItem = selected,
        modifier = modifier,
        fillWidth = fillWidth,
        displayTextOverride = displayTextOverride,
        onItemSelected = { onSelectProcess(it.pid) },
        itemLabel = { process ->
            localizedStringResource(Res.string.text, language, process.name, process.pid)
        },
        selectedItemLabel = MemoryProcessOption::name,
        placeholder = selectProcessLabel,
        selectorDescription = localizedStringResource(Res.string.process_selector, language),
    )
}
