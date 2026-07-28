@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
    "LongMethod",
    "MagicNumber",
    "MaxLineLength",
    "ktlint:standard:function-naming",
)

package com.androidperformancestudio.startup.app

import org.jetbrains.compose.resources.stringResource

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
public fun FrameWindowScope.StartupProfilerWorkspace(
    chinese: Boolean = false,
    onBack: () -> Unit = {},
) {
    val controller = remember(chinese) { StartupProfilerController(chinese = chinese) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var experimentJob by remember { mutableStateOf<Job?>(null) }
    val saveDialogTitle = stringResource(Res.string.export_startup_profiler_report)

    LaunchedEffect(controller) { controller.refreshDevices() }

    Column(Modifier.fillMaxSize()) {
        ProfilerMacOsToolbar {
            ProfilerHomeButton(
                contentDescription = stringResource(Res.string.back_to_home),
                onClick = {
                    experimentJob?.cancel()
                    onBack()
                },
            )
            ProfilerCompactSelector(
                label = stringResource(Res.string.device),
                selectedLabel = state.devices.firstOrNull { it.serial == state.selectedDeviceSerial }?.name,
                options = state.devices.filter { it.online }.map { it.serial to it.name },
                enabled = !state.isRunning,
                onSelected = { serial -> scope.launch { controller.selectDevice(serial) } },
            )
            ProfilerCompactSelector(
                label = stringResource(Res.string.app_activity),
                selectedLabel =
                    state.targets.firstOrNull { it.componentName == state.selectedComponentName }?.let {
                        if (it.debuggable) {
                            stringResource(Res.string.package_agent, it.packageName)
                        } else {
                            it.packageName
                        }
                    },
                options = state.targets.map {
                    it.componentName to
                        stringResource(Res.string.package_activity, it.packageName, it.componentName.substringAfter('/'), )
                },
                enabled = !state.isRunning && state.selectedDeviceSerial != null,
                onSelected = controller::selectTarget,
            )
            ProfilerCompactButton(
                text = stringResource(Res.string.refresh),
                enabled = !state.isRunning && !state.isRefreshing,
                onClick = { scope.launch { controller.refreshDevices() } },
            )
            ProfilerCompactButton(
                text =
                    if (state.isRunning) {
                        stringResource(Res.string.stop_experiment)
                    } else {
                        stringResource(Res.string.run_experiment)
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
                text = stringResource(Res.string.export_csv),
                enabled = state.analysis != null && !state.isRunning,
                onClick = {
                    chooseSaveFile(window, "startup-analysis.csv", saveDialogTitle)?.let { file ->
                        scope.launch { controller.exportCsv(file.toPath()) }
                    }
                },
            )
            ProfilerCompactButton(
                text = stringResource(Res.string.export_json),
                enabled = state.analysis != null && !state.isRunning,
                onClick = {
                    chooseSaveFile(window, "startup-analysis.json", saveDialogTitle)?.let { file ->
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
                label = stringResource(Res.string.startup_type),
                selectedLabel = state.config.requestedType.label(),
                options = listOf(StartupType.COLD, StartupType.WARM, StartupType.HOT).map { it.name to it.label() },
                enabled = !state.isRunning,
                onSelected = { value -> StartupType.valueOf(value).let(controller::selectStartupType) },
            )
            ProfilerCompactSelector(
                label = stringResource(Res.string.compilation),
                selectedLabel = state.config.compilationMode.label(),
                options = CompilationMode.entries.map { it.name to it.label() },
                enabled = !state.isRunning,
                onSelected = { value -> CompilationMode.valueOf(value).let(controller::selectCompilationMode) },
            )
            ProfilerCompactSelector(
                label = stringResource(Res.string.warm_ups),
                selectedLabel = state.config.warmupRuns.toString(),
                options = (0..10).map { it.toString() to it.toString() },
                enabled = !state.isRunning,
                onSelected = { value -> controller.updateCounts(value.toInt(), state.config.measuredRuns) },
            )
            ProfilerCompactSelector(
                label = stringResource(Res.string.measured_runs),
                selectedLabel = state.config.measuredRuns.toString(),
                options = listOf(1, 3, 5, 10, 20, 30).map { it.toString() to it.toString() },
                enabled = !state.isRunning,
                onSelected = { value -> controller.updateCounts(state.config.warmupRuns, value.toInt()) },
            )
            ProfilerCompactSelector(
                label = stringResource(Res.string.timeout),
                selectedLabel = stringResource(Res.string.seconds_short, state.config.timeoutSeconds),
                options = listOf(10, 20, 30, 45, 60, 120).map {
                    it.toString() to stringResource(Res.string.seconds_short, it)
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

@Composable
private fun StartupType.label(): String =
    when (this) {
        StartupType.COLD -> stringResource(Res.string.cold)
        StartupType.WARM -> stringResource(Res.string.warm)
        StartupType.HOT -> stringResource(Res.string.hot)
        StartupType.UNKNOWN -> stringResource(Res.string.unknown)
    }

@Composable
private fun CompilationMode.label(): String =
    when (this) {
        CompilationMode.CURRENT -> stringResource(Res.string.current)
        CompilationMode.RESET -> stringResource(Res.string.reset)
        CompilationMode.VERIFY -> stringResource(Res.string.verify)
        CompilationMode.SPEED_PROFILE -> stringResource(Res.string.speed_profile)
        CompilationMode.SPEED -> stringResource(Res.string.speed)
    }

private fun chooseSaveFile(
    parent: java.awt.Component,
    defaultName: String,
    dialogTitle: String,
): File? =
    JFileChooser().run {
        this.dialogTitle = dialogTitle
        selectedFile = File(defaultName)
        if (showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }
