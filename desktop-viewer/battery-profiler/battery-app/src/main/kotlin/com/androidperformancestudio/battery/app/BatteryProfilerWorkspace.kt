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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.battery.model.BatteryCaptureMode
import com.androidperformancestudio.battery.presentation.BatteryProfilerActions
import com.androidperformancestudio.battery.presentation.BatteryProfilerScreen
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerCompactSelector
import com.androidperformancestudio.ui.ProfilerHomeButton
import com.androidperformancestudio.ui.ProfilerMacOsSecondaryToolbar
import com.androidperformancestudio.ui.ProfilerMacOsToolbar
import com.androidperformancestudio.ui.ProfilerToolbarStatus
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
        ProfilerMacOsToolbar {
            ProfilerHomeButton(
                contentDescription = if (chinese) "返回主页" else "Back to home",
                onClick = {
                    experimentJob?.cancel()
                    onBack()
                },
            )
            ProfilerCompactSelector(
                label = if (chinese) "设备" else "Device",
                selectedLabel = state.devices.firstOrNull { it.serial == state.selectedDeviceSerial }?.name,
                options =
                    state.devices.filter { it.online }.map {
                        it.serial to
                            it.name
                    },
                enabled = !state.isRunning,
                onSelected = { serial -> scope.launch { controller.selectDevice(serial) } },
            )
            ProfilerCompactSelector(
                label = if (chinese) "应用 / UID" else "App / UID",
                selectedLabel =
                    state.targets
                        .firstOrNull {
                            it.packageName == state.selectedPackageName
                        }?.let { "${it.packageName} · ${it.uid}${if (it.sharedUid) " · shared" else ""}" },
                options =
                    state.targets.map {
                        it.packageName to "${it.packageName} · UID ${it.uid}${if (it.sharedUid) " · shared" else ""}"
                    },
                enabled = !state.isRunning && state.selectedDeviceSerial != null,
                onSelected = controller::selectTarget,
            )
            ProfilerCompactButton(
                text = if (chinese) "刷新" else "Refresh",
                enabled = !state.isRunning && !state.isRefreshing,
                onClick = { scope.launch { controller.refreshDevices() } },
            )
            ProfilerCompactButton(
                text =
                    when {
                        state.isInteractiveActive -> if (chinese) "停止并分析" else "Stop & Analyze"
                        state.isRunning -> if (chinese) "取消实验" else "Cancel Experiment"
                        else -> if (chinese) "开始实验" else "Run Experiment"
                    },
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
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline,
        )
        ProfilerMacOsSecondaryToolbar {
            ProfilerCompactSelector(
                label = if (chinese) "采集模式" else "Capture Mode",
                selectedLabel = state.config.mode.label(chinese),
                options =
                    BatteryCaptureMode.entries.map {
                        it.name to
                            it.label(chinese)
                    },
                enabled = !state.isRunning,
                onSelected = { value ->
                    controller.updateConfig { it.copy(mode = BatteryCaptureMode.valueOf(value)) }
                },
            )
            ProfilerCompactSelector(
                label = if (chinese) "时长" else "Duration",
                selectedLabel = "${state.config.durationSeconds}s",
                options =
                    listOf(15, 30, 60, 120, 300, 600).map {
                        it.toString() to
                            "${it}s"
                    },
                enabled = !state.isRunning,
                onSelected = { value -> controller.updateConfig { it.copy(durationSeconds = value.toInt()) } },
            )
            ProfilerCompactSelector(
                label = if (chinese) "轮询" else "Polling",
                selectedLabel = "${state.config.pollingIntervalSeconds}s",
                options =
                    listOf(5, 10, 15, 30, 60).map {
                        it.toString() to
                            "${it}s"
                    },
                enabled = !state.isRunning,
                onSelected = { value ->
                    controller.updateConfig { it.copy(pollingIntervalSeconds = value.toInt()) }
                },
            )
            ProfilerCompactSelector(
                label = if (chinese) "轮次" else "Runs",
                selectedLabel = state.config.measuredRuns.toString(),
                options =
                    listOf(1, 3, 5, 10).map {
                        it.toString() to
                            it.toString()
                    },
                enabled = !state.isRunning,
                onSelected = { value -> controller.updateConfig { it.copy(measuredRuns = value.toInt()) } },
            )
            Checkbox(
                checked = state.config.launchApp,
                enabled = !state.isRunning,
                onCheckedChange = { checked ->
                    controller.updateConfig { it.copy(launchApp = checked) }
                },
            )
            Text(if (chinese) "自动启动 Launcher Activity" else "Launch app automatically")
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline,
        )
        ProfilerMacOsSecondaryToolbar {
            ProfilerCompactButton(
                text = if (chinese) "导出 JSON" else "Export JSON",
                enabled = state.analysis != null && !state.isRunning,
                onClick = {
                    chooseSaveFile(window, "battery-analysis.json")?.let { file ->
                        scope.launch { controller.exportJson(file.toPath()) }
                    }
                },
            )
            ProfilerCompactButton(
                text = if (chinese) "导出 CSV" else "Export CSV",
                enabled = state.analysis != null && !state.isRunning,
                onClick = {
                    chooseSaveFile(window, "battery-analysis.csv")?.let { file ->
                        scope.launch { controller.exportCsv(file.toPath()) }
                    }
                },
            )
            ProfilerCompactButton(
                text = if (chinese) "导出原始证据" else "Export Raw Bundle",
                enabled = state.analysis != null && !state.isRunning,
                onClick = {
                    chooseSaveFile(window, "battery-raw-evidence.zip")?.let { file ->
                        scope.launch { controller.exportRawBundle(file.toPath()) }
                    }
                },
            )
            ProfilerCompactButton(
                text = "Battery Historian",
                enabled = state.selectedDeviceSerial != null && !state.isRunning,
                onClick = {
                    confirmBugreport = true
                },
            )
            ProfilerCompactButton(
                text = if (chinese) "高级：重置统计" else "Advanced: Reset Stats",
                enabled = state.selectedDeviceSerial != null && !state.isRunning,
                onClick = {
                    confirmReset = true
                },
            )
            Spacer(Modifier.weight(1f))
            ProfilerToolbarStatus(state.operationMessage, state.errorMessage)
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline,
        )
        if (state.isRunning &&
            state.totalSteps > 0
        ) {
            LinearProgressIndicator(
                progress = { state.completedSteps.toFloat() / state.totalSteps.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline,
        )
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
