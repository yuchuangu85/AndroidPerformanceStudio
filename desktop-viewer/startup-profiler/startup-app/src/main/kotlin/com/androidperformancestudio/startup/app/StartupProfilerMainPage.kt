@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
    "LongMethod",
    "MagicNumber",
    "MaxLineLength",
    "ktlint:standard:function-naming",
)

package com.androidperformancestudio.startup.app

import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.startup.startup_app.generated.resources.Res
import com.androidperformancestudio.startup.startup_app.generated.resources.*

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
public fun FrameWindowScope.StartupProfilerMainPage(
    chinese: Boolean = false,
    onBack: () -> Unit = {},
) {
    val controller = remember(chinese) { StartupProfilerController(chinese = chinese) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var experimentJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(controller) { controller.refreshDevices() }

    Column(Modifier.fillMaxSize()) {
        ProfilerMacOsToolbar {
            ProfilerHomeButton(
                contentDescription = localizedStringResource(Res.string.back_to_home, chinese),
                onClick = {
                    experimentJob?.cancel()
                    onBack()
                },
            )
            ProfilerCompactSelector(
                label = localizedStringResource(Res.string.device, chinese),
                selectedLabel = state.devices.firstOrNull { it.serial == state.selectedDeviceSerial }?.name,
                options = state.devices.filter { it.online }.map { it.serial to it.name },
                enabled = !state.isRunning,
                onSelected = { serial -> scope.launch { controller.selectDevice(serial) } },
            )
            ProfilerCompactSelector(
                label = localizedStringResource(Res.string.app_activity, chinese),
                selectedLabel =
                    state.targets.firstOrNull { it.componentName == state.selectedComponentName }?.let {
                        if (it.debuggable) {
                            localizedStringResource(Res.string.package_agent, chinese, it.packageName)
                        } else {
                            it.packageName
                        }
                    },
                options = state.targets.map {
                    it.componentName to
                        localizedStringResource(
                            Res.string.package_activity,
                            chinese,
                            it.packageName,
                            it.componentName.substringAfter('/'),
                        )
                },
                enabled = !state.isRunning && state.selectedDeviceSerial != null,
                onSelected = controller::selectTarget,
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.refresh, chinese),
                enabled = !state.isRunning && !state.isRefreshing,
                onClick = { scope.launch { controller.refreshDevices() } },
            )
            ProfilerCompactButton(
                text =
                    if (state.isRunning) {
                        localizedStringResource(Res.string.stop_experiment, chinese)
                    } else {
                        localizedStringResource(Res.string.run_experiment, chinese)
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
                text = localizedStringResource(Res.string.export_csv, chinese),
                enabled = state.analysis != null && !state.isRunning,
                onClick = {
                    chooseSaveFile(window, "startup-analysis.csv", chinese)?.let { file ->
                        scope.launch { controller.exportCsv(file.toPath()) }
                    }
                },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.export_json, chinese),
                enabled = state.analysis != null && !state.isRunning,
                onClick = {
                    chooseSaveFile(window, "startup-analysis.json", chinese)?.let { file ->
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
                label = localizedStringResource(Res.string.startup_type, chinese),
                selectedLabel = state.config.requestedType.label(chinese),
                options = listOf(StartupType.COLD, StartupType.WARM, StartupType.HOT).map { it.name to it.label(chinese) },
                enabled = !state.isRunning,
                onSelected = { value -> StartupType.valueOf(value).let(controller::selectStartupType) },
            )
            ProfilerCompactSelector(
                label = localizedStringResource(Res.string.compilation, chinese),
                selectedLabel = state.config.compilationMode.label(chinese),
                options = CompilationMode.entries.map { it.name to it.label(chinese) },
                enabled = !state.isRunning,
                onSelected = { value -> CompilationMode.valueOf(value).let(controller::selectCompilationMode) },
            )
            ProfilerCompactSelector(
                label = localizedStringResource(Res.string.warm_ups, chinese),
                selectedLabel = state.config.warmupRuns.toString(),
                options = (0..10).map { it.toString() to it.toString() },
                enabled = !state.isRunning,
                onSelected = { value -> controller.updateCounts(value.toInt(), state.config.measuredRuns) },
            )
            ProfilerCompactSelector(
                label = localizedStringResource(Res.string.measured_runs, chinese),
                selectedLabel = state.config.measuredRuns.toString(),
                options = listOf(1, 3, 5, 10, 20, 30).map { it.toString() to it.toString() },
                enabled = !state.isRunning,
                onSelected = { value -> controller.updateCounts(state.config.warmupRuns, value.toInt()) },
            )
            ProfilerCompactSelector(
                label = localizedStringResource(Res.string.timeout, chinese),
                selectedLabel = localizedStringResource(Res.string.seconds_short, chinese, state.config.timeoutSeconds),
                options = listOf(10, 20, 30, 45, 60, 120).map {
                    it.toString() to localizedStringResource(Res.string.seconds_short, chinese, it)
                },
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
        StartupType.COLD -> localizedStringResource(Res.string.cold, chinese)
        StartupType.WARM -> localizedStringResource(Res.string.warm, chinese)
        StartupType.HOT -> localizedStringResource(Res.string.hot, chinese)
        StartupType.UNKNOWN -> localizedStringResource(Res.string.unknown, chinese)
    }

private fun CompilationMode.label(chinese: Boolean): String =
    when (this) {
        CompilationMode.CURRENT -> localizedStringResource(Res.string.current, chinese)
        CompilationMode.RESET -> localizedStringResource(Res.string.reset, chinese)
        CompilationMode.VERIFY -> localizedStringResource(Res.string.verify, chinese)
        CompilationMode.SPEED_PROFILE -> localizedStringResource(Res.string.speed_profile, chinese)
        CompilationMode.SPEED -> localizedStringResource(Res.string.speed, chinese)
    }

private fun chooseSaveFile(
    parent: java.awt.Component,
    defaultName: String,
    chinese: Boolean,
): File? =
    JFileChooser().run {
        dialogTitle = localizedStringResource(Res.string.export_startup_profiler_report, chinese)
        selectedFile = File(defaultName)
        if (showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }
