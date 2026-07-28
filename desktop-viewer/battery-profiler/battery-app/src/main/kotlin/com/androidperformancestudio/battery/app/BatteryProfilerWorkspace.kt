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

import org.jetbrains.compose.resources.stringResource

import com.androidperformancestudio.battery.battery_app.generated.resources.Res
import com.androidperformancestudio.battery.battery_app.generated.resources.*

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
    val controller = remember(chinese) { BatteryProfilerController(chinese = chinese) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var experimentJob by remember { mutableStateOf<Job?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmBugreport by remember { mutableStateOf(false) }
    val saveDialogTitle = stringResource(Res.string.battery_energy_profiler)

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
                contentDescription = stringResource(Res.string.back_to_home),
                onClick = {
                    experimentJob?.cancel()
                    onBack()
                },
            )
            ProfilerCompactSelector(
                label = stringResource(Res.string.device),
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
                label = stringResource(Res.string.app_uid),
                selectedLabel =
                    state.targets
                        .firstOrNull {
                            it.packageName == state.selectedPackageName
                        }?.let {
                            stringResource(if (it.sharedUid) Res.string.package_uid_shared else Res.string.package_uid, it.packageName, it.uid, )
                        },
                options =
                    state.targets.map {
                        it.packageName to
                            stringResource(if (it.sharedUid) Res.string.package_uid_shared else Res.string.package_uid, it.packageName, it.uid, )
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
                    when {
                        state.isInteractiveActive -> stringResource(Res.string.stop_analyze)
                        state.isRunning -> stringResource(Res.string.cancel_experiment)
                        else -> stringResource(Res.string.run_experiment)
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
                label = stringResource(Res.string.capture_mode),
                selectedLabel = state.config.mode.label(),
                options =
                    BatteryCaptureMode.entries.map {
                        it.name to
                            it.label()
                    },
                enabled = !state.isRunning,
                onSelected = { value ->
                    controller.updateConfig { it.copy(mode = BatteryCaptureMode.valueOf(value)) }
                },
            )
            ProfilerCompactSelector(
                label = stringResource(Res.string.duration),
                selectedLabel = stringResource(Res.string.seconds_short, state.config.durationSeconds),
                options =
                    listOf(15, 30, 60, 120, 300, 600).map {
                        it.toString() to
                            stringResource(Res.string.seconds_short, it)
                    },
                enabled = !state.isRunning,
                onSelected = { value -> controller.updateConfig { it.copy(durationSeconds = value.toInt()) } },
            )
            ProfilerCompactSelector(
                label = stringResource(Res.string.polling),
                selectedLabel = stringResource(Res.string.seconds_short, state.config.pollingIntervalSeconds),
                options =
                    listOf(5, 10, 15, 30, 60).map {
                        it.toString() to
                            stringResource(Res.string.seconds_short, it)
                    },
                enabled = !state.isRunning,
                onSelected = { value ->
                    controller.updateConfig { it.copy(pollingIntervalSeconds = value.toInt()) }
                },
            )
            ProfilerCompactSelector(
                label = stringResource(Res.string.runs),
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
            Text(stringResource(Res.string.launch_app_automatically))
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline,
        )
        ProfilerMacOsSecondaryToolbar {
            ProfilerCompactButton(
                text = stringResource(Res.string.export_json),
                enabled = state.analysis != null && !state.isRunning,
                onClick = {
                    chooseSaveFile(window, "battery-analysis.json", saveDialogTitle)?.let { file ->
                        scope.launch { controller.exportJson(file.toPath()) }
                    }
                },
            )
            ProfilerCompactButton(
                text = stringResource(Res.string.export_csv),
                enabled = state.analysis != null && !state.isRunning,
                onClick = {
                    chooseSaveFile(window, "battery-analysis.csv", saveDialogTitle)?.let { file ->
                        scope.launch { controller.exportCsv(file.toPath()) }
                    }
                },
            )
            ProfilerCompactButton(
                text = stringResource(Res.string.export_raw_bundle),
                enabled = state.analysis != null && !state.isRunning,
                onClick = {
                    chooseSaveFile(window, "battery-raw-evidence.zip", saveDialogTitle)?.let { file ->
                        scope.launch { controller.exportRawBundle(file.toPath()) }
                    }
                },
            )
            ProfilerCompactButton(
                text = stringResource(Res.string.battery_historian),
                enabled = state.selectedDeviceSerial != null && !state.isRunning,
                onClick = {
                    confirmBugreport = true
                },
            )
            ProfilerCompactButton(
                text = stringResource(Res.string.advanced_reset_stats),
                enabled = state.selectedDeviceSerial != null && !state.isRunning,
                onClick = {
                    confirmReset = true
                },
            )
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
        BatteryProfilerScreen(state, BatteryProfilerActions(controller::selectRun), chinese, Modifier.weight(1f))
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(Res.string.reset_global_batterystats)) },
            text = {
                Text(
                    stringResource(Res.string.this_clears_battery_statistics_and_battery_historian_history_for_every),
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmReset = false
                    scope.launch { controller.resetStatistics() }
                }) { Text(stringResource(Res.string.reset)) }
            },
            dismissButton = { OutlinedButton(onClick = { confirmReset = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
    if (confirmBugreport) {
        AlertDialog(
            onDismissRequest = { confirmBugreport = false },
            title = { Text(stringResource(Res.string.generate_battery_historian_input)) },
            text = {
                Text(
                    stringResource(Res.string.bugreports_may_contain_accounts_ssids_app_lists_logs_and_device),
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmBugreport = false
                    chooseSaveFile(window, "battery-historian-bugreport.zip", saveDialogTitle)?.let { file ->
                        scope.launch { controller.generateBugreport(file.toPath()) }
                    }
                }) { Text(stringResource(Res.string.choose_location)) }
            },
            dismissButton = { OutlinedButton(onClick = { confirmBugreport = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

@Composable
private fun BatteryCaptureMode.label(): String =
    when (this) {
        BatteryCaptureMode.INTERACTIVE -> stringResource(Res.string.interactive)
        BatteryCaptureMode.TIMED -> stringResource(Res.string.timed)
        BatteryCaptureMode.REPEATED -> stringResource(Res.string.repeated)
        BatteryCaptureMode.ONLINE -> stringResource(Res.string.low_frequency_online)
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
