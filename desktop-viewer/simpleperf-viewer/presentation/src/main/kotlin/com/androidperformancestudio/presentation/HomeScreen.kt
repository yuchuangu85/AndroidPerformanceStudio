package com.androidperformancestudio.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.application.CapabilityStatus
import com.androidperformancestudio.application.CaptureTarget
import com.androidperformancestudio.application.DeviceOption
import com.androidperformancestudio.application.DeviceTargetState
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.application.WorkspacePage
import com.androidperformancestudio.capture.CaptureState

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
fun HomeScreen(
    state: DeviceTargetState,
    captureState: CaptureState,
    reportState: ReportState,
    actions: DeviceTargetActions,
    reportActions: ReportActions,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (reportState.loadState != ReportLoadState.Closed) {
                ReportPage(reportState, reportActions)
            } else {
                when (state.page) {
                    WorkspacePage.DEVICE_TARGET ->
                        DeviceTargetPage(state, actions, reportActions.onOpenSession)
                    WorkspacePage.CAPTURE ->
                        CapturePage(
                            target = state.selectedTarget,
                            setup = state.captureSetup,
                            availableEvents =
                                state.selection
                                    ?.capabilities
                                    ?.eventNames
                                    .orEmpty(),
                            captureState = captureState,
                            actions = actions,
                        )
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun DeviceTargetPage(
    state: DeviceTargetState,
    actions: DeviceTargetActions,
    onOpenSession: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header(state.isLoading, actions.onRefresh, onOpenSession)
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DeviceList(
                devices = state.devices,
                selectedSerial = state.selectedSerial,
                onSelectDevice = actions.onSelectDevice,
                modifier = Modifier.width(280.dp).fillMaxHeight(),
            )
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CapabilityCard(state)
                TargetSelector(state, actions, Modifier.weight(1f))
                state.error?.let { Text("${it.code}: ${it.message}", color = MaterialTheme.colorScheme.error) }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = actions.onContinue,
                        enabled = state.canEnterCapture,
                    ) {
                        Text("Continue to Capture")
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun Header(
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onOpenSession: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Device & Target", style = MaterialTheme.typography.headlineMedium)
            Text("Select an Android device and a profile target.", style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = onOpenSession) { Text("Open Session") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onRefresh, enabled = !isLoading) {
            Text(if (isLoading) "Refreshing…" else "Refresh")
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun DeviceList(
    devices: List<DeviceOption>,
    selectedSerial: String?,
    onSelectDevice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Devices", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (devices.isEmpty()) {
                Text("No USB devices found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(devices, key = DeviceOption::serial) { device ->
                        val selected = device.serial == selectedSerial
                        Card(
                            modifier =
                                Modifier.fillMaxWidth().clickable(enabled = device.isOnline) {
                                    onSelectDevice(device.serial)
                                },
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        if (selected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                ),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(device.label, fontWeight = FontWeight.SemiBold)
                                Text(device.serial, style = MaterialTheme.typography.bodySmall)
                                Text(if (device.isOnline) "Online" else "Unavailable")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun CapabilityCard(state: DeviceTargetState) {
    val selection = state.selection
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Device capability", style = MaterialTheme.typography.titleMedium)
            if (selection == null) {
                Text("Select an online device to inspect its capabilities.")
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusBadge(selection.capabilities.status)
                    Text("${selection.model} · Android ${selection.androidVersion} / SDK ${selection.sdkInt}")
                }
                Text("ABI: ${selection.abis.joinToString()}")
                Text("Root: ${selection.capabilities.root}")
                Text("Scope: ${selection.capabilities.profilingScope}")
                Text("Simpleperf: ${selection.capabilities.simpleperf}")
                Text(
                    "Events: " +
                        selection.capabilities.eventNames
                            .take(MAX_VISIBLE_EVENTS)
                            .joinToString()
                            .ifBlank { "Unavailable" },
                )
                if (selection.capabilities.limitations.isNotEmpty()) {
                    Text("Limits: ${selection.capabilities.limitations.joinToString()}")
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun StatusBadge(status: CapabilityStatus) {
    val (containerColor, contentColor) =
        when (status) {
            CapabilityStatus.READY ->
                MaterialTheme.colorScheme.tertiary to MaterialTheme.colorScheme.onTertiary
            CapabilityStatus.LIMITED ->
                MaterialTheme.colorScheme.secondary to MaterialTheme.colorScheme.onSecondary
            CapabilityStatus.BLOCKED ->
                MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.onError
        }
    Surface(color = containerColor, contentColor = contentColor, shape = MaterialTheme.shapes.small) {
        Text(status.name, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun TargetSelector(
    state: DeviceTargetState,
    actions: DeviceTargetActions,
    modifier: Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Apps / Processes / PID / Threads", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = actions.onSearch,
                label = { Text("Search package, process, user or PID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TargetColumn("Apps", Modifier.weight(1f)) {
                    items(state.visiblePackages, key = { it.packageName }) { item ->
                        TargetRow(
                            title = item.packageName,
                            selected = state.selectedTarget == CaptureTarget.App(item.packageName),
                            onClick = { actions.onSelectPackage(item.packageName) },
                        )
                    }
                }
                TargetColumn("Processes", Modifier.weight(1f)) {
                    items(state.visibleProcesses, key = { it.pid }) { item ->
                        TargetRow(
                            title = item.name,
                            subtitle = "PID ${item.pid} · ${item.user}",
                            selected = (state.selectedTarget as? CaptureTarget.Process)?.pid == item.pid,
                            onClick = { actions.onSelectProcess(item.pid) },
                        )
                    }
                }
                TargetColumn("Threads", Modifier.weight(1f)) {
                    items(state.threads, key = { it.tid }) { item ->
                        TargetRow(
                            title = item.name,
                            subtitle = "TID ${item.tid}",
                            selected = (state.selectedTarget as? CaptureTarget.Thread)?.tid == item.tid,
                            onClick = { actions.onSelectThread(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun TargetColumn(
    title: String,
    modifier: Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(modifier) {
        Text(title, fontWeight = FontWeight.SemiBold)
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun TargetRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(title, maxLines = 1)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private const val MAX_VISIBLE_EVENTS = 8
