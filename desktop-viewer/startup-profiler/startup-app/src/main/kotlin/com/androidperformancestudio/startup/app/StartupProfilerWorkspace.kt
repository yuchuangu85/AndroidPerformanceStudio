@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
    "LongMethod",
    "MagicNumber",
    "MaxLineLength",
    "ktlint:standard:function-naming",
)

package com.androidperformancestudio.startup.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.androidperformancestudio.startup.model.CompilationMode
import com.androidperformancestudio.startup.model.StartupType
import com.androidperformancestudio.startup.presentation.StartupProfilerActions
import com.androidperformancestudio.startup.presentation.StartupProfilerScreen
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerCompactSelector
import com.androidperformancestudio.ui.ProfilerHomeButton
import com.androidperformancestudio.ui.ProfilerMacOsSecondaryToolbar
import com.androidperformancestudio.ui.ProfilerMacOsToolbar
import com.androidperformancestudio.ui.ProfilerToolbarStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser

@Composable
public fun FrameWindowScope.StartupProfilerWorkspace(
    chinese: Boolean = false,
    onBack: () -> Unit = {},
) {
    val controller = remember { StartupProfilerController() }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var experimentJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(controller) { controller.refreshDevices() }

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
                options = state.devices.filter { it.online }.map { it.serial to it.name },
                enabled = !state.isRunning,
                onSelected = { serial -> scope.launch { controller.selectDevice(serial) } },
            )
            ProfilerCompactSelector(
                label = if (chinese) "应用 / Activity" else "App / Activity",
                selectedLabel =
                    state.targets.firstOrNull { it.componentName == state.selectedComponentName }?.let {
                        if (it.debuggable) "${it.packageName} · Agent" else it.packageName
                    },
                options = state.targets.map { it.componentName to "${it.packageName} · ${it.componentName.substringAfter('/')}" },
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
                    if (state.isRunning) {
                        if (chinese) "停止实验" else "Stop Experiment"
                    } else {
                        if (chinese) "开始实验" else "Run Experiment"
                    },
                enabled = state.selectedComponentName != null,
                onClick = {
                    if (state.isRunning) {
                        experimentJob?.cancel()
                    } else {
                        experimentJob = scope.launch { controller.runExperiment() }
                    }
                },
            )
            ProfilerCompactButton(
                text = if (chinese) "导出 CSV" else "Export CSV",
                enabled = state.analysis != null && !state.isRunning,
                onClick = {
                    chooseSaveFile(window, "startup-analysis.csv")?.let { file ->
                        scope.launch { controller.exportCsv(file.toPath()) }
                    }
                },
            )
            ProfilerCompactButton(
                text = if (chinese) "导出 JSON" else "Export JSON",
                enabled = state.analysis != null && !state.isRunning,
                onClick = {
                    chooseSaveFile(window, "startup-analysis.json")?.let { file ->
                        scope.launch { controller.exportJson(file.toPath()) }
                    }
                },
            )
            Spacer(Modifier.weight(1f))
            ProfilerToolbarStatus(
                message = state.operationMessage,
                error = state.errorMessage,
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline,
        )
        ProfilerMacOsSecondaryToolbar {
            ProfilerCompactSelector(
                label = if (chinese) "启动类型" else "Startup Type",
                selectedLabel = state.config.requestedType.label(chinese),
                options = listOf(StartupType.COLD, StartupType.WARM, StartupType.HOT).map { it.name to it.label(chinese) },
                enabled = !state.isRunning,
                onSelected = { value -> StartupType.valueOf(value).let(controller::selectStartupType) },
            )
            ProfilerCompactSelector(
                label = if (chinese) "编译模式" else "Compilation",
                selectedLabel = state.config.compilationMode.label(),
                options = CompilationMode.entries.map { it.name to it.label() },
                enabled = !state.isRunning,
                onSelected = { value -> CompilationMode.valueOf(value).let(controller::selectCompilationMode) },
            )
            ProfilerCompactSelector(
                label = if (chinese) "预热次数" else "Warm-ups",
                selectedLabel = state.config.warmupRuns.toString(),
                options = (0..10).map { it.toString() to it.toString() },
                enabled = !state.isRunning,
                onSelected = { value -> controller.updateCounts(value.toInt(), state.config.measuredRuns) },
            )
            ProfilerCompactSelector(
                label = if (chinese) "采样次数" else "Measured Runs",
                selectedLabel = state.config.measuredRuns.toString(),
                options = listOf(1, 3, 5, 10, 20, 30).map { it.toString() to it.toString() },
                enabled = !state.isRunning,
                onSelected = { value -> controller.updateCounts(state.config.warmupRuns, value.toInt()) },
            )
            ProfilerCompactSelector(
                label = if (chinese) "超时" else "Timeout",
                selectedLabel = "${state.config.timeoutSeconds}s",
                options = listOf(10, 20, 30, 45, 60, 120).map { it.toString() to "${it}s" },
                enabled = !state.isRunning,
                onSelected = { value -> controller.updateTimeout(value.toInt()) },
            )
        }
        if (state.isRunning && state.totalRuns > 0) {
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline,
            )
            LinearProgressIndicator(
                progress = { state.completedRuns.toFloat() / state.totalRuns.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline,
        )
        StartupProfilerScreen(
            state = state,
            actions = StartupProfilerActions(onSelectRun = controller::selectRun),
            chinese = chinese,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun StartupType.label(chinese: Boolean): String =
    when (this) {
        StartupType.COLD -> if (chinese) "冷启动" else "Cold"
        StartupType.WARM -> if (chinese) "温启动" else "Warm"
        StartupType.HOT -> if (chinese) "热启动" else "Hot"
        StartupType.UNKNOWN -> if (chinese) "未知" else "Unknown"
    }

private fun CompilationMode.label(): String =
    when (this) {
        CompilationMode.CURRENT -> "Current"
        CompilationMode.RESET -> "Reset"
        CompilationMode.VERIFY -> "Verify"
        CompilationMode.SPEED_PROFILE -> "Speed Profile"
        CompilationMode.SPEED -> "Speed"
    }

private fun chooseSaveFile(
    parent: java.awt.Component,
    defaultName: String,
): File? =
    JFileChooser().run {
        dialogTitle = "Export Startup Profiler Report"
        selectedFile = File(defaultName)
        if (showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }
