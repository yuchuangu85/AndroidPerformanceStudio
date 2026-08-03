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
import com.androidperformancestudio.battery.battery_app.generated.resources.Res
import com.androidperformancestudio.battery.battery_app.generated.resources.app_uid
import com.androidperformancestudio.battery.battery_app.generated.resources.back_to_home
import com.androidperformancestudio.battery.battery_app.generated.resources.battery_energy_profiler
import com.androidperformancestudio.battery.battery_app.generated.resources.battery_historian
import com.androidperformancestudio.battery.battery_app.generated.resources.bugreports_may_contain_accounts_ssids_app_lists_logs_and_device
import com.androidperformancestudio.battery.battery_app.generated.resources.cancel
import com.androidperformancestudio.battery.battery_app.generated.resources.cancel_experiment
import com.androidperformancestudio.battery.battery_app.generated.resources.capture_mode
import com.androidperformancestudio.battery.battery_app.generated.resources.choose_location
import com.androidperformancestudio.battery.battery_app.generated.resources.device
import com.androidperformancestudio.battery.battery_app.generated.resources.duration
import com.androidperformancestudio.battery.battery_app.generated.resources.duration_value
import com.androidperformancestudio.battery.battery_app.generated.resources.generate_battery_historian_input
import com.androidperformancestudio.battery.battery_app.generated.resources.import_analysis
import com.androidperformancestudio.battery.battery_app.generated.resources.interactive
import com.androidperformancestudio.battery.battery_app.generated.resources.launch_app_automatically
import com.androidperformancestudio.battery.battery_app.generated.resources.low_frequency_online
import com.androidperformancestudio.battery.battery_app.generated.resources.package_uid
import com.androidperformancestudio.battery.battery_app.generated.resources.package_uid_shared
import com.androidperformancestudio.battery.battery_app.generated.resources.polling
import com.androidperformancestudio.battery.battery_app.generated.resources.polling_value
import com.androidperformancestudio.battery.battery_app.generated.resources.refresh
import com.androidperformancestudio.battery.battery_app.generated.resources.repeated
import com.androidperformancestudio.battery.battery_app.generated.resources.reset
import com.androidperformancestudio.battery.battery_app.generated.resources.reset_global_batterystats
import com.androidperformancestudio.battery.battery_app.generated.resources.run_experiment
import com.androidperformancestudio.battery.battery_app.generated.resources.runs
import com.androidperformancestudio.battery.battery_app.generated.resources.runs_value
import com.androidperformancestudio.battery.battery_app.generated.resources.seconds_short
import com.androidperformancestudio.battery.battery_app.generated.resources.stop_analyze
import com.androidperformancestudio.battery.battery_app.generated.resources.this_clears_battery_statistics_and_battery_historian_history_for_every
import com.androidperformancestudio.battery.battery_app.generated.resources.timed
import com.androidperformancestudio.battery.model.BatteryCaptureMode
import com.androidperformancestudio.battery.presentation.BatteryProfilerActions
import com.androidperformancestudio.battery.presentation.BatteryProfilerScreen
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerMacOsSecondaryToolbar
import com.androidperformancestudio.ui.ProfilerMacOsToolbar
import com.androidperformancestudio.ui.ProfilerToolbarStatus
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.button.HomeButton
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.time.Duration.Companion.milliseconds

@Composable
public fun FrameWindowScope.BatteryProfilerMainPage(
    language: UiLanguage = UiLanguage.ENGLISH,
    onBack: () -> Unit = {},
) {
    val controller = remember(language) { BatteryProfilerController(language = language) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var experimentJob by remember { mutableStateOf<Job?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmBugreport by remember { mutableStateOf(false) }
    val recentStore = remember { RecentBatteryAnalysisStore.desktop() }
    var recentFiles by remember { mutableStateOf(recentStore.load()) }

    LaunchedEffect(controller) { controller.refreshDevices() }
    LaunchedEffect(state.isInteractiveActive, state.config.mode, state.config.pollingIntervalSeconds) {
        if (state.isInteractiveActive && state.config.mode == BatteryCaptureMode.ONLINE) {
            while (true) {
                delay((state.config.pollingIntervalSeconds * 1_000L).milliseconds)
                controller.pollInteractive()
            }
        }
    }

    val openAnalysis: (Path) -> Unit = { input ->
        scope.launch {
            if (controller.importJson(input)) {
                recentFiles = recentStore.record(input)
            }
        }
    }
    BatteryProfilerMenuBar(
        model =
            batteryProfilerMenuModel(
                language = language,
                recentFiles = recentFiles,
                importEnabled = !state.isRunning,
                jsonExportEnabled = state.analysis != null && state.experiment != null && !state.isRunning,
                csvExportEnabled = state.analysis != null && !state.isRunning,
                rawEvidenceExportEnabled = state.experiment != null && !state.isRunning,
                resetEnabled = state.selectedDeviceSerial != null && !state.isRunning,
            ),
        onImport = {
            chooseOpenJsonFile(window, language)?.let { file -> openAnalysis(file.toPath()) }
        },
        onExportJson = {
            chooseSaveFile(window, "battery-analysis.json", language)?.let { file ->
                scope.launch { controller.exportJson(file.toPath()) }
            }
        },
        onExportCsv = {
            chooseSaveFile(window, "battery-analysis.csv", language)?.let { file ->
                scope.launch { controller.exportCsv(file.toPath()) }
            }
        },
        onExportRawEvidence = {
            chooseSaveFile(window, "battery-raw-evidence.zip", language)?.let { file ->
                scope.launch { controller.exportRawBundle(file.toPath()) }
            }
        },
        onOpenRecent = openAnalysis,
        onClearRecent = {
            recentStore.clear()
            recentFiles = emptyList()
        },
        onResetStatistics = { confirmReset = true },
    )

    Column(Modifier.fillMaxSize()) {
        ProfilerMacOsToolbar {
            HomeButton(
                contentDescription = localizedStringResource(Res.string.back_to_home, language),
                onClick = {
                    experimentJob?.cancel()
                    onBack()
                },
            )
            DropdownSelector(
                items = state.devices,
                selectedItem = state.devices.firstOrNull { it.serial == state.selectedDeviceSerial },
                onItemSelected = { device -> scope.launch { controller.selectDevice(device.serial) } },
                itemLabel = { it.name },
                placeholder = localizedStringResource(Res.string.device, language),
                enabled = !state.isRunning,
                itemEnabled = { it.online },
            )
            DropdownSelector(
                items = state.targets,
                selectedItem = state.targets.firstOrNull { it.packageName == state.selectedPackageName },
                onItemSelected = { controller.selectTarget(it.packageName) },
                itemLabel = {
                    localizedStringResource(
                        if (it.sharedUid) Res.string.package_uid_shared else Res.string.package_uid,
                        language,
                        it.packageName,
                        it.uid,
                    )
                },
                placeholder = localizedStringResource(Res.string.app_uid, language),
                enabled = !state.isRunning && state.selectedDeviceSerial != null,
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.refresh, language),
                enabled = !state.isRunning && !state.isRefreshing,
                onClick = { scope.launch { controller.refreshDevices() } },
            )
            ProfilerCompactButton(
                text =
                    when {
                        state.isInteractiveActive -> localizedStringResource(Res.string.stop_analyze, language)
                        state.isRunning -> localizedStringResource(Res.string.cancel_experiment, language)
                        else -> localizedStringResource(Res.string.run_experiment, language)
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
            Spacer(Modifier.weight(1f))
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.battery_historian, language),
                enabled = state.selectedDeviceSerial != null && !state.isRunning,
                onClick = {
                    confirmBugreport = true
                },
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline,
        )
        ProfilerMacOsSecondaryToolbar {
            DropdownSelector(
                items = BatteryCaptureMode.entries,
                selectedItem = state.config.mode,
                onItemSelected = { mode -> controller.updateConfig { it.copy(mode = mode) } },
                itemLabel = { it.label(language) },
                placeholder = localizedStringResource(Res.string.capture_mode, language),
                enabled = !state.isRunning,
            )
            DropdownSelector(
                items = listOf(15, 30, 60, 120, 300, 600),
                selectedItem = state.config.durationSeconds,
                onItemSelected = { value -> controller.updateConfig { it.copy(durationSeconds = value) } },
                itemLabel = { localizedStringResource(Res.string.seconds_short, language, it) },
                selectedItemLabel = { localizedStringResource(Res.string.duration_value, language, it) },
                placeholder = localizedStringResource(Res.string.duration, language),
                enabled = !state.isRunning,
            )
            DropdownSelector(
                items = listOf(5, 10, 15, 30, 60),
                selectedItem = state.config.pollingIntervalSeconds,
                onItemSelected = { value -> controller.updateConfig { it.copy(pollingIntervalSeconds = value) } },
                itemLabel = { localizedStringResource(Res.string.seconds_short, language, it) },
                selectedItemLabel = { localizedStringResource(Res.string.polling_value, language, it) },
                placeholder = localizedStringResource(Res.string.polling, language),
                enabled = !state.isRunning,
            )
            DropdownSelector(
                items = listOf(1, 3, 5, 10),
                selectedItem = state.config.measuredRuns,
                onItemSelected = { value -> controller.updateConfig { it.copy(measuredRuns = value) } },
                itemLabel = Int::toString,
                selectedItemLabel = { localizedStringResource(Res.string.runs_value, language, it) },
                placeholder = localizedStringResource(Res.string.runs, language),
                enabled = !state.isRunning,
            )
            Checkbox(
                checked = state.config.launchApp,
                enabled = !state.isRunning,
                onCheckedChange = { checked ->
                    controller.updateConfig { it.copy(launchApp = checked) }
                },
            )
            Text(localizedStringResource(Res.string.launch_app_automatically, language))
            Spacer(Modifier.weight(1f))
            ProfilerToolbarStatus(state.operationMessage, state.errorMessage)
        }
        if (state.isRunning &&
            state.totalSteps > 0
        ) {
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline,
            )
            LinearProgressIndicator(
                progress = { state.completedSteps.toFloat() / state.totalSteps.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline,
        )
        BatteryProfilerScreen(state, BatteryProfilerActions(controller::selectRun), language, Modifier.weight(1f))
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(localizedStringResource(Res.string.reset_global_batterystats, language)) },
            text = {
                Text(
                    localizedStringResource(Res.string.this_clears_battery_statistics_and_battery_historian_history_for_every, language),
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmReset = false
                    scope.launch { controller.resetStatistics() }
                }) { Text(localizedStringResource(Res.string.reset, language)) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { confirmReset = false },
                ) { Text(localizedStringResource(Res.string.cancel, language)) }
            },
        )
    }
    if (confirmBugreport) {
        AlertDialog(
            onDismissRequest = { confirmBugreport = false },
            title = { Text(localizedStringResource(Res.string.generate_battery_historian_input, language)) },
            text = {
                Text(
                    localizedStringResource(Res.string.bugreports_may_contain_accounts_ssids_app_lists_logs_and_device, language),
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmBugreport = false
                    chooseSaveFile(window, "battery-historian-bugreport.zip", language)?.let { file ->
                        scope.launch { controller.generateBugreport(file.toPath()) }
                    }
                }) { Text(localizedStringResource(Res.string.choose_location, language)) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { confirmBugreport = false },
                ) { Text(localizedStringResource(Res.string.cancel, language)) }
            },
        )
    }
}

private fun BatteryCaptureMode.label(language: UiLanguage): String =
    when (this) {
        BatteryCaptureMode.INTERACTIVE -> localizedStringResource(Res.string.interactive, language)
        BatteryCaptureMode.TIMED -> localizedStringResource(Res.string.timed, language)
        BatteryCaptureMode.REPEATED -> localizedStringResource(Res.string.repeated, language)
        BatteryCaptureMode.ONLINE -> localizedStringResource(Res.string.low_frequency_online, language)
    }

private fun chooseSaveFile(
    parent: java.awt.Component,
    defaultName: String,
    language: UiLanguage,
): File? =
    JFileChooser().run {
        dialogTitle = localizedStringResource(Res.string.battery_energy_profiler, language)
        selectedFile = File(defaultName)
        if (showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }

private fun chooseOpenJsonFile(
    parent: java.awt.Component,
    language: UiLanguage,
): File? =
    JFileChooser().run {
        dialogTitle = localizedStringResource(Res.string.import_analysis, language)
        fileFilter = FileNameExtensionFilter("JSON (*.json)", "json")
        isAcceptAllFileFilterUsed = false
        if (showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }
