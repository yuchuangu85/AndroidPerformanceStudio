@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
    "LongMethod",
    "MagicNumber",
    "MaxLineLength",
    "ktlint:standard:function-naming",
)

package com.androidperformancestudio.startup.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.androidperformancestudio.startup.model.StartupProfileSource
import com.androidperformancestudio.startup.model.StartupType
import com.androidperformancestudio.startup.presentation.StartupProfilerActions
import com.androidperformancestudio.startup.presentation.StartupProfilerScreen
import com.androidperformancestudio.startup.startup_app.generated.resources.Res
import com.androidperformancestudio.startup.startup_app.generated.resources.app_activity
import com.androidperformancestudio.startup.startup_app.generated.resources.back_to_home
import com.androidperformancestudio.startup.startup_app.generated.resources.cancel
import com.androidperformancestudio.startup.startup_app.generated.resources.cold
import com.androidperformancestudio.startup.startup_app.generated.resources.compilation
import com.androidperformancestudio.startup.startup_app.generated.resources.compilation_change_message
import com.androidperformancestudio.startup.startup_app.generated.resources.compilation_change_title
import com.androidperformancestudio.startup.startup_app.generated.resources.continue_action
import com.androidperformancestudio.startup.startup_app.generated.resources.current
import com.androidperformancestudio.startup.startup_app.generated.resources.device
import com.androidperformancestudio.startup.startup_app.generated.resources.export_startup_profiler_report
import com.androidperformancestudio.startup.startup_app.generated.resources.hot
import com.androidperformancestudio.startup.startup_app.generated.resources.import_startup_profiler_report
import com.androidperformancestudio.startup.startup_app.generated.resources.measured_runs
import com.androidperformancestudio.startup.startup_app.generated.resources.package_activity
import com.androidperformancestudio.startup.startup_app.generated.resources.package_agent
import com.androidperformancestudio.startup.startup_app.generated.resources.perfetto_trace
import com.androidperformancestudio.startup.startup_app.generated.resources.practical_threshold
import com.androidperformancestudio.startup.startup_app.generated.resources.profile_baseline_plugin
import com.androidperformancestudio.startup.startup_app.generated.resources.profile_build_variant
import com.androidperformancestudio.startup.startup_app.generated.resources.profile_macrobenchmark
import com.androidperformancestudio.startup.startup_app.generated.resources.profile_source
import com.androidperformancestudio.startup.startup_app.generated.resources.profile_unverified
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
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.HeaderDivider
import com.androidperformancestudio.ui.HeaderSpacer
import com.androidperformancestudio.ui.HeaderToolbar
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerMacOsSecondaryToolbar
import com.androidperformancestudio.ui.ProfilerMacOsToolbar
import com.androidperformancestudio.ui.ProfilerToolbarStatus
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.button.HomeButton
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
public fun FrameWindowScope.StartupProfilerMainPage(
    language: UiLanguage = UiLanguage.ENGLISH,
    darkTheme: Boolean = isSystemInDarkTheme(),
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

    ViewerTheme(darkTheme = darkTheme) {
    if (state.compilationConfirmationRequired) {
        AlertDialog(
            onDismissRequest = controller::dismissCompilationConfirmation,
            title = { Text(localizedStringResource(Res.string.compilation_change_title, language)) },
            text = { Text(localizedStringResource(Res.string.compilation_change_message, language)) },
            dismissButton = {
                TextButton(onClick = controller::dismissCompilationConfirmation) {
                    Text(localizedStringResource(Res.string.cancel, language))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.dismissCompilationConfirmation()
                        experimentJob = scope.launch { controller.runExperiment(compilationChangeConfirmed = true) }
                    },
                ) { Text(localizedStringResource(Res.string.continue_action, language)) }
            },
        )
    }
    Column(Modifier.fillMaxSize()) {
        HeaderToolbar(
            language = language,
            onNavigateHome = {
                experimentJob?.cancel()
                onBack()
            },
            onNavigateSettings = null
        ) {
            DropdownSelector(
                items = state.devices,
                selectedItem = state.devices.firstOrNull { it.serial == state.selectedDeviceSerial },
                onItemSelected = { device -> scope.launch { controller.selectDevice(device.serial) } },
                itemLabel = { it.name },
                placeholder = localizedStringResource(Res.string.device, language),
                enabled = !state.isRunning,
                itemEnabled = { it.online },
            )
            HeaderSpacer()
            DropdownSelector(
                items = state.targets,
                selectedItem = state.targets.firstOrNull { it.componentName == state.selectedComponentName },
                onItemSelected = { controller.selectTarget(it.componentName) },
                itemLabel = {
                    localizedStringResource(
                        Res.string.package_activity,
                        language,
                        it.packageName,
                        it.componentName.substringAfter('/'),
                    )
                },
                selectedItemLabel = {
                    if (it.debuggable) {
                        localizedStringResource(Res.string.package_agent, language, it.packageName)
                    } else {
                        it.packageName
                    }
                },
                placeholder = localizedStringResource(Res.string.app_activity, language),
                enabled = !state.isRunning && state.selectedDeviceSerial != null,
            )
            HeaderSpacer()
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.refresh, language),
                enabled = !state.isRunning && !state.isRefreshing,
                onClick = { scope.launch { controller.refreshDevices() } },
            )
            HeaderSpacer()
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
            HeaderSpacer()
            ProfilerToolbarStatus(
                message = state.operationMessage,
                error = state.errorMessage,
            )
            HeaderDivider()
            HeaderSpacer()
            DropdownSelector(
                items = listOf(StartupType.COLD, StartupType.WARM, StartupType.HOT),
                selectedItem = state.config.requestedType,
                onItemSelected = controller::selectStartupType,
                itemLabel = { it.label(language) },
                placeholder = localizedStringResource(Res.string.startup_type, language),
                enabled = !state.isRunning,
            )
            HeaderSpacer()
            DropdownSelector(
                items = CompilationMode.entries,
                selectedItem = state.config.compilationMode,
                onItemSelected = controller::selectCompilationMode,
                itemLabel = { it.label(language) },
                placeholder = localizedStringResource(Res.string.compilation, language),
                enabled = !state.isRunning,
            )
            HeaderSpacer()
            DropdownSelector(
                items = StartupProfileSource.entries,
                selectedItem = state.config.profileSource,
                onItemSelected = controller::selectProfileSource,
                itemLabel = { it.label(language) },
                placeholder = localizedStringResource(Res.string.profile_source, language),
                enabled = !state.isRunning && state.config.compilationMode == CompilationMode.SPEED_PROFILE,
            )
            HeaderSpacer()
            DropdownSelector(
                items = (0..10).toList(),
                selectedItem = state.config.warmupRuns,
                onItemSelected = { controller.updateCounts(it, state.config.measuredRuns) },
                itemLabel = Int::toString,
                selectedItemLabel = {localizedStringResource(Res.string.warm_ups, language, it)},
                placeholder = localizedStringResource(Res.string.warm_ups, language),
                enabled = !state.isRunning && state.config.compilationMode == CompilationMode.SPEED_PROFILE,
            )
            HeaderSpacer()
            DropdownSelector(
                items = listOf(1, 3, 5, 10, 20, 30),
                selectedItem = state.config.measuredRuns,
                onItemSelected = { controller.updateCounts(state.config.warmupRuns, it) },
                itemLabel = Int::toString,
                selectedItemLabel = {localizedStringResource(Res.string.measured_runs, language, it)},
                placeholder = localizedStringResource(Res.string.measured_runs, language),
                enabled = !state.isRunning,
            )
            HeaderSpacer()
            DropdownSelector(
                items = listOf(10, 20, 30, 45, 60, 120),
                selectedItem = state.config.timeoutSeconds,
                onItemSelected = controller::updateTimeout,
                itemLabel = { localizedStringResource(Res.string.seconds_short, language, it) },
                selectedItemLabel = {localizedStringResource(Res.string.timeout, language, it)},
                placeholder = localizedStringResource(Res.string.timeout, language),
                enabled = !state.isRunning,
            )
            HeaderSpacer()
            Checkbox(
                checked = state.config.capturePerfettoTrace,
                onCheckedChange = controller::setPerfettoTraceEnabled,
                enabled = !state.isRunning,
            )
            Text(localizedStringResource(Res.string.perfetto_trace, language), style = MaterialTheme.typography.labelSmall)
            HeaderSpacer()
            DropdownSelector(
                items = listOf(1.0, 3.0, 5.0, 10.0),
                selectedItem = state.config.practicalChangeThresholdPercent,
                onItemSelected = controller::updatePracticalThreshold,
                itemLabel = { it.toString() },
                selectedItemLabel = { localizedStringResource(Res.string.practical_threshold, language, it) },
                placeholder = localizedStringResource(Res.string.practical_threshold, language),
                enabled = !state.isRunning,
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline,
        )
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

private fun StartupProfileSource.label(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            StartupProfileSource.UNVERIFIED -> Res.string.profile_unverified
            StartupProfileSource.BASELINE_PROFILE_PLUGIN -> Res.string.profile_baseline_plugin
            StartupProfileSource.MACROBENCHMARK -> Res.string.profile_macrobenchmark
            StartupProfileSource.BUILD_VARIANT -> Res.string.profile_build_variant
        },
        language,
    )

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
