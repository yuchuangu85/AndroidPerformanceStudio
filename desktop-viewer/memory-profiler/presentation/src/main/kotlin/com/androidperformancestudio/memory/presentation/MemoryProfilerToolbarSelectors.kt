@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.device_offline
import com.androidperformancestudio.memory.presentation.generated.resources.device_selector
import com.androidperformancestudio.memory.presentation.generated.resources.no_devices
import com.androidperformancestudio.memory.presentation.generated.resources.no_processes
import com.androidperformancestudio.memory.presentation.generated.resources.process_selector
import com.androidperformancestudio.memory.presentation.generated.resources.select_device
import com.androidperformancestudio.memory.presentation.generated.resources.select_process
import com.androidperformancestudio.memory.presentation.generated.resources.text
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
    var expanded by remember { mutableStateOf(false) }
    val selected = state.devices.firstOrNull { it.serial == state.selectedDeviceSerial }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier =
                Modifier
                    .height(MEMORY_TOOLBAR_BUTTON_HEIGHT_DP.dp)
                    .semantics { contentDescription = localizedStringResource(Res.string.device_selector, language) },
            shape = RoundedCornerShape(MEMORY_TOOLBAR_BUTTON_RADIUS_DP.dp),
            contentPadding = MEMORY_TOOLBAR_BUTTON_CONTENT_PADDING,
        ) {
            Text(
                text = selected?.name ?: localizedStringResource(Res.string.select_device, language),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (state.devices.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(localizedStringResource(Res.string.no_devices, language)) },
                    onClick = {},
                    enabled = false,
                )
            }
            state.devices.forEach { device ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (device.online) device.name
                            else localizedStringResource(Res.string.device_offline, language, device.name)
                        )
                    },
                    enabled = device.online,
                    onClick = {
                        expanded = false
                        onSelectDevice(device.serial)
                    },
                )
            }
        }
    }
}

@Composable
private fun MemoryProfilerProcessSelector(
    state: MemoryProfilerState,
    onSelectProcess: (Int) -> Unit,
    language: UiLanguage,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.processes.firstOrNull { it.pid == state.selectedProcessId }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier =
                Modifier
                    .height(MEMORY_TOOLBAR_BUTTON_HEIGHT_DP.dp)
                    .semantics { contentDescription = localizedStringResource(Res.string.process_selector, language) },
            shape = RoundedCornerShape(MEMORY_TOOLBAR_BUTTON_RADIUS_DP.dp),
            contentPadding = MEMORY_TOOLBAR_BUTTON_CONTENT_PADDING,
        ) {
            Text(
                text = selected?.name ?: localizedStringResource(Res.string.select_process, language),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (state.processes.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(localizedStringResource(Res.string.no_processes, language)) },
                    onClick = {},
                    enabled = false,
                )
            }
            state.processes.forEach { process ->
                DropdownMenuItem(
                    text = { Text(localizedStringResource(Res.string.text, language, process.name, process.pid)) },
                    onClick = {
                        expanded = false
                        onSelectProcess(process.pid)
                    },
                )
            }
        }
    }
}

internal const val MEMORY_TOOLBAR_BUTTON_HEIGHT_DP = 22
internal const val MEMORY_TOOLBAR_BUTTON_RADIUS_DP = 7
internal val MEMORY_TOOLBAR_BUTTON_CONTENT_PADDING = PaddingValues(horizontal = 8.dp)
