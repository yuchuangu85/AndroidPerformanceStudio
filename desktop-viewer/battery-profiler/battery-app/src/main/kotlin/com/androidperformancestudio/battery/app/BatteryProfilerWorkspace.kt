@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
    "LongMethod",
    "MagicNumber",
    "MaxLineLength",
    "ktlint:standard:max-line-length",
    "ktlint:standard:function-naming",
)

package com.androidperformancestudio.battery.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.battery.model.BatteryCaptureMode
import com.androidperformancestudio.battery.presentation.BatteryProfilerActions
import com.androidperformancestudio.battery.presentation.BatteryProfilerScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser

@Composable
public fun FrameWindowScope.BatteryProfilerWorkspace(
    chinese: Boolean = false,
    onBack: () -> Unit = {},
) {
    val controller = remember { BatteryProfilerController() }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var experimentJob by remember { mutableStateOf<Job?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmBugreport by remember { mutableStateOf(false) }

    LaunchedEffect(controller) { controller.refreshDevices() }
    LaunchedEffect(state.isInteractiveActive, state.config.mode, state.config.pollingIntervalSeconds) {
        if (state.isInteractiveActive && state.config.mode == BatteryCaptureMode.ONLINE) {
            while (true) {
                delay(state.config.pollingIntervalSeconds * 1_000L)
                controller.pollInteractive()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    experimentJob?.cancel()
                    onBack()
                }) { Text(if (chinese) "返回主页" else "Back to Home") }
                Selector(
                    if (chinese) "设备" else "Device",
                    state.devices.firstOrNull { it.serial == state.selectedDeviceSerial }?.name,
                    state.devices.filter { it.online }.map {
                        it.serial to
                            it.name
                    },
                    !state.isRunning,
                ) { serial -> scope.launch { controller.selectDevice(serial) } }
                Selector(
                    if (chinese) "应用 / UID" else "App / UID",
                    state.targets
                        .firstOrNull {
                            it.packageName == state.selectedPackageName
                        }?.let { "${it.packageName} · ${it.uid}${if (it.sharedUid) " · shared" else ""}" },
                    state.targets.map { it.packageName to "${it.packageName} · UID ${it.uid}${if (it.sharedUid) " · shared" else ""}" },
                    !state.isRunning && state.selectedDeviceSerial != null,
                    controller::selectTarget,
                )
                OutlinedButton(enabled = !state.isRunning && !state.isRefreshing, onClick = {
                    scope.launch { controller.refreshDevices() }
                }) { Text(if (chinese) "刷新" else "Refresh") }
                Button(
                    enabled = state.selectedPackageName != null,
                    onClick = {
                        when {
                            state.isInteractiveActive -> experimentJob = scope.launch { controller.stopInteractive() }
                            state.isRunning -> experimentJob?.cancel()
                            state.config.mode == BatteryCaptureMode.INTERACTIVE || state.config.mode == BatteryCaptureMode.ONLINE ->
                                experimentJob =
                                    scope.launch { controller.startInteractive() }
                            else -> experimentJob = scope.launch { controller.runAutomatic() }
                        }
                    },
                ) {
                    Text(
                        when {
                            state.isInteractiveActive -> if (chinese) "停止并分析" else "Stop & Analyze"
                            state.isRunning -> if (chinese) "取消实验" else "Cancel Experiment"
                            else -> if (chinese) "开始实验" else "Run Experiment"
                        },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Selector(
                    if (chinese) "采集模式" else "Capture Mode",
                    state.config.mode.label(chinese),
                    BatteryCaptureMode.entries.map {
                        it.name to
                            it.label(chinese)
                    },
                    !state.isRunning,
                ) { value -> controller.updateConfig { it.copy(mode = BatteryCaptureMode.valueOf(value)) } }
                Selector(
                    if (chinese) "时长" else "Duration",
                    "${state.config.durationSeconds}s",
                    listOf(15, 30, 60, 120, 300, 600).map {
                        it.toString() to
                            "${it}s"
                    },
                    !state.isRunning,
                ) { value -> controller.updateConfig { it.copy(durationSeconds = value.toInt()) } }
                Selector(
                    if (chinese) "轮询" else "Polling",
                    "${state.config.pollingIntervalSeconds}s",
                    listOf(5, 10, 15, 30, 60).map {
                        it.toString() to
                            "${it}s"
                    },
                    !state.isRunning,
                ) { value -> controller.updateConfig { it.copy(pollingIntervalSeconds = value.toInt()) } }
                Selector(
                    if (chinese) "轮次" else "Runs",
                    state.config.measuredRuns.toString(),
                    listOf(1, 3, 5, 10).map {
                        it.toString() to
                            it.toString()
                    },
                    !state.isRunning,
                ) { value -> controller.updateConfig { it.copy(measuredRuns = value.toInt()) } }
                Checkbox(checked = state.config.launchApp, enabled = !state.isRunning, onCheckedChange = { checked ->
                    controller.updateConfig { it.copy(launchApp = checked) }
                })
                Text(if (chinese) "自动启动 Launcher Activity" else "Launch app automatically")
                state.operationMessage?.let { Text(it, modifier = Modifier.padding(start = 8.dp)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = state.analysis != null && !state.isRunning, onClick = {
                    chooseSaveFile(window, "battery-analysis.json")?.let { file ->
                        scope.launch { controller.exportJson(file.toPath()) }
                    }
                }) { Text(if (chinese) "导出 JSON" else "Export JSON") }
                OutlinedButton(enabled = state.analysis != null && !state.isRunning, onClick = {
                    chooseSaveFile(window, "battery-analysis.csv")?.let { file ->
                        scope.launch { controller.exportCsv(file.toPath()) }
                    }
                }) { Text(if (chinese) "导出 CSV" else "Export CSV") }
                OutlinedButton(enabled = state.analysis != null && !state.isRunning, onClick = {
                    chooseSaveFile(window, "battery-raw-evidence.zip")?.let { file ->
                        scope.launch { controller.exportRawBundle(file.toPath()) }
                    }
                }) { Text(if (chinese) "导出原始证据" else "Export Raw Bundle") }
                OutlinedButton(enabled = state.selectedDeviceSerial != null && !state.isRunning, onClick = {
                    confirmBugreport = true
                }) { Text("Battery Historian") }
                OutlinedButton(enabled = state.selectedDeviceSerial != null && !state.isRunning, onClick = {
                    confirmReset = true
                }) { Text(if (chinese) "高级：重置统计" else "Advanced: Reset Stats") }
            }
            if (state.isRunning &&
                state.totalSteps > 0
            ) {
                LinearProgressIndicator(
                    progress = { state.completedSteps.toFloat() / state.totalSteps.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        BatteryProfilerScreen(state, BatteryProfilerActions(controller::selectRun), chinese, Modifier.weight(1f))
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(if (chinese) "重置全局 batterystats？" else "Reset global batterystats?") },
            text = {
                Text(
                    if (chinese) "此操作会清除整台设备上所有应用的电池统计与 Battery Historian 历史，无法撤销。普通实验不需要重置。" else "This clears battery statistics and Battery Historian history for every app on the device. It cannot be undone and is not required for normal experiments.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmReset = false
                    scope.launch { controller.resetStatistics() }
                }) { Text(if (chinese) "确认重置" else "Reset") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmReset = false }) { Text(if (chinese) "取消" else "Cancel") } },
        )
    }
    if (confirmBugreport) {
        AlertDialog(
            onDismissRequest = { confirmBugreport = false },
            title = { Text(if (chinese) "生成 Battery Historian 输入？" else "Generate Battery Historian input?") },
            text = {
                Text(
                    if (chinese) "Bugreport 可能包含账户、SSID、应用列表、日志和设备标识。文件仅保存到你选择的本地路径，不会自动上传。" else "Bugreports may contain accounts, SSIDs, app lists, logs, and device identifiers. The file is saved locally and is never uploaded automatically.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmBugreport = false
                    chooseSaveFile(window, "battery-historian-bugreport.zip")?.let { file ->
                        scope.launch { controller.generateBugreport(file.toPath()) }
                    }
                }) { Text(if (chinese) "选择保存位置" else "Choose Location") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmBugreport = false }) { Text(if (chinese) "取消" else "Cancel") } },
        )
    }
}

@Composable
private fun Selector(
    label: String,
    selectedLabel: String?,
    options: List<Pair<String, String>>,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(enabled = enabled && options.isNotEmpty(), onClick = {
            expanded = true
        }, modifier = Modifier.widthIn(min = 110.dp, max = 280.dp)) {
            Text(
                selectedLabel ?: label,
                maxLines = 1,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, optionLabel) ->
                DropdownMenuItem(text = { Text(optionLabel) }, onClick = {
                    expanded = false
                    onSelected(value)
                })
            }
        }
    }
}

private fun BatteryCaptureMode.label(chinese: Boolean): String =
    when (this) {
        BatteryCaptureMode.INTERACTIVE -> if (chinese) "交互实验" else "Interactive"
        BatteryCaptureMode.TIMED -> if (chinese) "定时实验" else "Timed"
        BatteryCaptureMode.REPEATED -> if (chinese) "重复实验" else "Repeated"
        BatteryCaptureMode.ONLINE -> if (chinese) "低频在线观察" else "Low-frequency Online"
    }

private fun chooseSaveFile(
    parent: java.awt.Component,
    defaultName: String,
): File? =
    JFileChooser().run {
        dialogTitle = "Battery / Energy Profiler"
        selectedFile = File(defaultName)
        if (showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }
