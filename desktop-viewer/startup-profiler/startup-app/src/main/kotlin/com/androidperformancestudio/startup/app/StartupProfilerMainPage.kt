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
import com.androidperformancestudio.startup.startup_app.generated.resources.Res
import com.androidperformancestudio.startup.startup_app.generated.resources.app_activity
import com.androidperformancestudio.startup.startup_app.generated.resources.back_to_home
import com.androidperformancestudio.startup.startup_app.generated.resources.cold
import com.androidperformancestudio.startup.startup_app.generated.resources.compilation
import com.androidperformancestudio.startup.startup_app.generated.resources.current
import com.androidperformancestudio.startup.startup_app.generated.resources.device
import com.androidperformancestudio.startup.startup_app.generated.resources.export_startup_profiler_report
import com.androidperformancestudio.startup.startup_app.generated.resources.hot
import com.androidperformancestudio.startup.startup_app.generated.resources.import_startup_profiler_report
import com.androidperformancestudio.startup.startup_app.generated.resources.measured_runs
import com.androidperformancestudio.startup.startup_app.generated.resources.package_activity
import com.androidperformancestudio.startup.startup_app.generated.resources.package_agent
import com.androidperformancestudio.startup.startup_app.generated.resources.refresh
import com.androidperformancestudio.startup.startup_app.generated.resources.reset
import com.androidperformancestudio.startup.startup_app.generated.resources.run_experiment
import com.androidperformancestudio.startup.startup_app.generated.resources.seconds_short
import com.androidperformancestudio.startup.startup_app.generated.resources.speed
import com.androidperformancestudio.startup.startup_app.generated.resources.speed_profile
import com.androidperformancestudio.startup.startup_app.generated.resources.startup_type
import com.androidperformancestudio.startup.startup_app.generated.resources.stop_experiment
import com.androidperformancestudio.startup.startup_app.generated.resources.timeout
import com.androidperformancestudio.startup.startup_app.generated.resources.unknown
import com.androidperformancestudio.startup.startup_app.generated.resources.verify
import com.androidperformancestudio.startup.startup_app.generated.resources.warm
import com.androidperformancestudio.startup.startup_app.generated.resources.warm_ups
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerCompactSelector
import com.androidperformancestudio.ui.ProfilerHomeButton
import com.androidperformancestudio.ui.ProfilerMacOsSecondaryToolbar
import com.androidperformancestudio.ui.ProfilerMacOsToolbar
import com.androidperformancestudio.ui.ProfilerToolbarStatus
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
public fun FrameWindowScope.StartupProfilerMainPage(
    language: UiLanguage = UiLanguage.ENGLISH,
    onBack: () -> Unit = {},
) {
    val controller = remember(language) { StartupProfilerController(language = language) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var experimentJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(controller) { controller.refreshDevices() }

    StartupProfilerFileMenuBar(
        model =
            startupProfilerFileMenuModel(
                language = language,
                importEnabled = !state.isRunning,
                exportEnabled = state.analysis != null && !state.isRunning,
            ),
        onImport = {
            chooseOpenJsonFile(window, language)?.let { file ->
                scope.launch { controller.importJson(file.toPath()) }
            }
        },
        onExportCsv = {
            chooseSaveFile(window, "startup-analysis.csv", language)?.let { file ->
                scope.launch { controller.exportCsv(file.toPath()) }
            }
        },
        onExportJson = {
            chooseSaveFile(window, "startup-analysis.json", language)?.let { file ->
                scope.launch { controller.exportJson(file.toPath()) }
            }
        },
    )

    Column(Modifier.fillMaxSize()) {
        ProfilerMacOsToolbar {
            ProfilerHomeButton(
                contentDescription = localizedStringResource(Res.string.back_to_home, language),
                onClick = {
                    experimentJob?.cancel()
                    onBack()
                },
            )
            ProfilerCompactSelector(
                label = localizedStringResource(Res.string.device, language),
                selectedLabel = state.devices.firstOrNull { it.serial == state.selectedDeviceSerial }?.name,
                options = state.devices.filter { it.online }.map { it.serial to it.name },
                enabled = !state.isRunning,
                onSelected = { serial -> scope.launch { controller.selectDevice(serial) } },
            )
            ProfilerCompactSelector(
                label = localizedStringResource(Res.string.app_activity, language),
                selectedLabel =
                    state.targets.firstOrNull { it.componentName == state.selectedComponentName }?.let {
                        if (it.debuggable) {
                            localizedStringResource(Res.string.package_agent, language, it.packageName)
                        } else {
                            it.packageName
                        }
                    },
                options =
                    state.targets.map {
                        it.componentName to
                            localizedStringResource(
                                Res.string.package_activity,
                                language,
                                it.packageName,
                                it.componentName.substringAfter('/'),
                            )
                    },
                enabled = !state.isRunning && state.selectedDeviceSerial != null,
                onSelected = controller::selectTarget,
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.refresh, language),
                enabled = !state.isRunning && !state.isRefreshing,
                onClick = { scope.launch { controller.refreshDevices() } },
            )
            ProfilerCompactButton(
                text =
                    if (state.isRunning) {
                        localizedStringResource(Res.string.stop_experiment, language)
                    } else {
                        localizedStringResource(Res.string.run_experiment, language)
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
                label = localizedStringResource(Res.string.startup_type, language),
                selectedLabel = state.config.requestedType.label(language),
                options = listOf(StartupType.COLD, StartupType.WARM, StartupType.HOT).map { it.name to it.label(language) },
                enabled = !state.isRunning,
                onSelected = { value -> StartupType.valueOf(value).let(controller::selectStartupType) },
            )
            ProfilerCompactSelector(
                label = localizedStringResource(Res.string.compilation, language),
                selectedLabel = state.config.compilationMode.label(language),
                options = CompilationMode.entries.map { it.name to it.label(language) },
                enabled = !state.isRunning,
                onSelected = { value -> CompilationMode.valueOf(value).let(controller::selectCompilationMode) },
            )
            ProfilerCompactSelector(
                label = localizedStringResource(Res.string.warm_ups, language),
                selectedLabel = state.config.warmupRuns.toString(),
                options = (0..10).map { it.toString() to it.toString() },
                enabled = !state.isRunning,
                onSelected = { value -> controller.updateCounts(value.toInt(), state.config.measuredRuns) },
            )
            ProfilerCompactSelector(
                label = localizedStringResource(Res.string.measured_runs, language),
                selectedLabel = state.config.measuredRuns.toString(),
                options = listOf(1, 3, 5, 10, 20, 30).map { it.toString() to it.toString() },
                enabled = !state.isRunning,
                onSelected = { value -> controller.updateCounts(state.config.warmupRuns, value.toInt()) },
            )
            ProfilerCompactSelector(
                label = localizedStringResource(Res.string.timeout, language),
                selectedLabel = localizedStringResource(Res.string.seconds_short, language, state.config.timeoutSeconds),
                options =
                    listOf(10, 20, 30, 45, 60, 120).map {
                        it.toString() to localizedStringResource(Res.string.seconds_short, language, it)
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
            language = language,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun StartupType.label(language: UiLanguage): String =
    when (this) {
        StartupType.COLD -> localizedStringResource(Res.string.cold, language)
        StartupType.WARM -> localizedStringResource(Res.string.warm, language)
        StartupType.HOT -> localizedStringResource(Res.string.hot, language)
        StartupType.UNKNOWN -> localizedStringResource(Res.string.unknown, language)
    }

private fun CompilationMode.label(language: UiLanguage): String =
    when (this) {
        CompilationMode.CURRENT -> localizedStringResource(Res.string.current, language)
        CompilationMode.RESET -> localizedStringResource(Res.string.reset, language)
        CompilationMode.VERIFY -> localizedStringResource(Res.string.verify, language)
        CompilationMode.SPEED_PROFILE -> localizedStringResource(Res.string.speed_profile, language)
        CompilationMode.SPEED -> localizedStringResource(Res.string.speed, language)
    }

private fun chooseSaveFile(
    parent: java.awt.Component,
    defaultName: String,
    language: UiLanguage,
): File? =
    JFileChooser().run {
        dialogTitle = localizedStringResource(Res.string.export_startup_profiler_report, language)
        selectedFile = File(defaultName)
        if (showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }

private fun chooseOpenJsonFile(
    parent: java.awt.Component,
    language: UiLanguage,
): File? =
    JFileChooser().run {
        dialogTitle = localizedStringResource(Res.string.import_startup_profiler_report, language)
        fileFilter = FileNameExtensionFilter("JSON (*.json)", "json")
        isAcceptAllFileFilterUsed = false
        if (showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }
