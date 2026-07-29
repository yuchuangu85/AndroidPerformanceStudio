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

import com.androidperformancestudio.ui.localizedStringResource
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
import kotlin.time.Duration.Companion.milliseconds

@Composable
public fun FrameWindowScope.BatteryProfilerMainPage(
    chinese: Boolean = false,
    onBack: () -> Unit = {},
) {
    val controller = remember(chinese) { BatteryProfilerController(chinese = chinese) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var experimentJob by remember { mutableStateOf<Job?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmBugreport by remember { mutableStateOf(false) }

    LaunchedEffect(controller) { controller.refreshDevices() }
    LaunchedEffect(state.isInteractiveActive, state.config.mode, state.config.pollingIntervalSeconds) {
        if (state.isInteractiveActive && state.config.mode == BatteryCaptureMode.ONLINE) {
            while (true) {
                delay((state.config.pollingIntervalSeconds * 1_000L).milliseconds)
                controller.pollInteractive()
            }
        }
    }

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
                options =
                    state.devices.filter { it.online }.map {
                        it.serial to
                            it.name
                    },
                enabled = !state.isRunning,
                onSelected = { serial -> scope.launch { controller.selectDevice(serial) } },
            )
            ProfilerCompactSelector(
                label = localizedStringResource(Res.string.app_uid, chinese),
                selectedLabel =
                    state.targets
                        .firstOrNull {
                            it.packageName == state.selectedPackageName
                        }?.let {
                            localizedStringResource(
                                if (it.sharedUid) Res.string.package_uid_shared else Res.string.package_uid,
                                chinese,
                                it.packageName,
                                it.uid,
                            )
                        },
                options =
                    state.targets.map {
                        it.packageName to
                            localizedStringResource(
                                if (it.sharedUid) Res.string.package_uid_shared else Res.string.package_uid,
                                chinese,
                                it.packageName,
                                it.uid,
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
                    when {
                        state.isInteractiveActive -> localizedStringResource(Res.string.stop_analyze, chinese)
                        state.isRunning -> localizedStringResource(Res.string.cancel_experiment, chinese)
                        else -> localizedStringResource(Res.string.run_experiment, chinese)
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
                label = localizedStringResource(Res.string.capture_mode, chinese),
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
                label = localizedStringResource(Res.string.duration, chinese),
                selectedLabel = localizedStringResource(Res.string.seconds_short, chinese, state.config.durationSeconds),
                options =
                    listOf(15, 30, 60, 120, 300, 600).map {
                        it.toString() to
                            localizedStringResource(Res.string.seconds_short, chinese, it)
                    },
                enabled = !state.isRunning,
                onSelected = { value -> controller.updateConfig { it.copy(durationSeconds = value.toInt()) } },
            )
            ProfilerCompactSelector(
                label = localizedStringResource(Res.string.polling, chinese),
                selectedLabel = localizedStringResource(Res.string.seconds_short, chinese, state.config.pollingIntervalSeconds),
                options =
                    listOf(5, 10, 15, 30, 60).map {
                        it.toString() to
                            localizedStringResource(Res.string.seconds_short, chinese, it)
                    },
                enabled = !state.isRunning,
                onSelected = { value ->
                    controller.updateConfig { it.copy(pollingIntervalSeconds = value.toInt()) }
                },
            )
            ProfilerCompactSelector(
                label = localizedStringResource(Res.string.runs, chinese),
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
            Text(localizedStringResource(Res.string.launch_app_automatically, chinese))
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline,
        )
        ProfilerMacOsSecondaryToolbar {
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.export_json, chinese),
                enabled = state.analysis != null && !state.isRunning,
                onClick = {
                    chooseSaveFile(window, "battery-analysis.json", chinese)?.let { file ->
                        scope.launch { controller.exportJson(file.toPath()) }
                    }
                },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.export_csv, chinese),
                enabled = state.analysis != null && !state.isRunning,
                onClick = {
                    chooseSaveFile(window, "battery-analysis.csv", chinese)?.let { file ->
                        scope.launch { controller.exportCsv(file.toPath()) }
                    }
                },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.export_raw_bundle, chinese),
                enabled = state.analysis != null && !state.isRunning,
                onClick = {
                    chooseSaveFile(window, "battery-raw-evidence.zip", chinese)?.let { file ->
                        scope.launch { controller.exportRawBundle(file.toPath()) }
                    }
                },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.battery_historian, chinese),
                enabled = state.selectedDeviceSerial != null && !state.isRunning,
                onClick = {
                    confirmBugreport = true
                },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.advanced_reset_stats, chinese),
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
            title = { Text(localizedStringResource(Res.string.reset_global_batterystats, chinese)) },
            text = {
                Text(
                    localizedStringResource(Res.string.this_clears_battery_statistics_and_battery_historian_history_for_every, chinese),
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmReset = false
                    scope.launch { controller.resetStatistics() }
                }) { Text(localizedStringResource(Res.string.reset, chinese)) }
            },
            dismissButton = { OutlinedButton(onClick = { confirmReset = false }) { Text(localizedStringResource(Res.string.cancel, chinese)) } },
        )
    }
    if (confirmBugreport) {
        AlertDialog(
            onDismissRequest = { confirmBugreport = false },
            title = { Text(localizedStringResource(Res.string.generate_battery_historian_input, chinese)) },
            text = {
                Text(
                    localizedStringResource(Res.string.bugreports_may_contain_accounts_ssids_app_lists_logs_and_device, chinese),
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmBugreport = false
                    chooseSaveFile(window, "battery-historian-bugreport.zip", chinese)?.let { file ->
                        scope.launch { controller.generateBugreport(file.toPath()) }
                    }
                }) { Text(localizedStringResource(Res.string.choose_location, chinese)) }
            },
            dismissButton = { OutlinedButton(onClick = { confirmBugreport = false }) { Text(localizedStringResource(Res.string.cancel, chinese)) } },
        )
    }
}

private fun BatteryCaptureMode.label(chinese: Boolean): String =
    when (this) {
        BatteryCaptureMode.INTERACTIVE -> localizedStringResource(Res.string.interactive, chinese)
        BatteryCaptureMode.TIMED -> localizedStringResource(Res.string.timed, chinese)
        BatteryCaptureMode.REPEATED -> localizedStringResource(Res.string.repeated, chinese)
        BatteryCaptureMode.ONLINE -> localizedStringResource(Res.string.low_frequency_online, chinese)
    }

private fun chooseSaveFile(
    parent: java.awt.Component,
    defaultName: String,
    chinese: Boolean,
): File? =
    JFileChooser().run {
        dialogTitle = localizedStringResource(Res.string.battery_energy_profiler, chinese)
        selectedFile = File(defaultName)
        if (showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }
