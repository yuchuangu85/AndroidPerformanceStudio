@file:Suppress("CyclomaticComplexMethod", "FunctionName", "LongMethod", "ktlint:standard:function-naming")

package com.androidperformancestudio.frame.app

import org.jetbrains.compose.resources.stringResource

import com.androidperformancestudio.frame.frame_app.generated.resources.Res
import com.androidperformancestudio.frame.frame_app.generated.resources.*

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.awt.AwtWindow
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.frame.presentation.FrameProfilerActions
import com.androidperformancestudio.frame.presentation.FrameProfilerScreen
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerCompactSelector
import com.androidperformancestudio.ui.ProfilerHomeButton
import com.androidperformancestudio.ui.ProfilerMacOsToolbar
import com.androidperformancestudio.ui.ProfilerToolbarStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

@Composable
public fun FrameWindowScope.FrameProfilerWorkspace(
    chinese: Boolean = false,
    onBack: () -> Unit = {},
    onOpenLayoutInspector: (FrameLayoutInspectionRequest) -> Unit = {},
) {
    val controller = remember(chinese) { FrameProfilerController(chinese = chinese) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showImportDialog by remember { mutableStateOf(false) }
    val importDialogTitle = stringResource(Res.string.import_gfxinfo_framestats)
    val saveDialogTitle = stringResource(Res.string.export_frame_profiler_report)

    LaunchedEffect(controller) { controller.refreshDevices() }
    LaunchedEffect(state.isCapturing) {
        while (controller.state.value.isCapturing) {
            controller.pollOnlineCapture()
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ProfilerMacOsToolbar {
            ProfilerHomeButton(
                contentDescription = stringResource(Res.string.back_to_home),
                onClick = {
                    if (state.isCapturing) {
                        scope.launch {
                            controller.stopOnlineCapture()
                            onBack()
                        }
                    } else {
                        onBack()
                    }
                },
            )
            ProfilerCompactSelector(
                label = stringResource(Res.string.device),
                selectedLabel =
                    state.devices.firstOrNull { it.serial == state.selectedDeviceSerial }?.name,
                options = state.devices.filter { it.online }.map { it.serial to it.name },
                enabled = !state.isCapturing,
                onSelected = { serial -> scope.launch { controller.selectDevice(serial) } },
            )
            ProfilerCompactSelector(
                label = stringResource(Res.string.process),
                selectedLabel =
                    state.processes.firstOrNull { it.pid == state.selectedProcessId }?.let {
                        stringResource(Res.string.process_with_pid, it.name, it.pid)
                    },
                options = state.processes.map {
                    it.pid.toString() to stringResource(Res.string.process_with_pid, it.name, it.pid)
                },
                enabled = !state.isCapturing && state.selectedDeviceSerial != null,
                onSelected = { pid -> pid.toIntOrNull()?.let(controller::selectProcess) },
            )
            ProfilerCompactButton(
                text = stringResource(Res.string.refresh),
                enabled = !state.isCapturing && !state.isRefreshingDevices,
                onClick = { scope.launch { controller.refreshDevices() } },
            )
            ProfilerCompactButton(
                text =
                    if (state.isCapturing) {
                        stringResource(Res.string.stop_capture)
                    } else {
                        stringResource(Res.string.start_capture)
                    },
                enabled = state.selectedProcessId != null,
                onClick = {
                    scope.launch {
                        if (state.isCapturing) controller.stopOnlineCapture() else controller.startOnlineCapture()
                    }
                },
            )
            ProfilerCompactButton(
                text = stringResource(Res.string.import_framestats),
                enabled = !state.isCapturing,
                onClick = { showImportDialog = true },
            )
            ProfilerCompactButton(
                text = stringResource(Res.string.export_csv),
                enabled = state.analysis != null,
                onClick = {
                    chooseSaveFile(window, "frame-analysis.csv", saveDialogTitle)?.let { output ->
                        scope.launch { controller.exportCsv(output.toPath()) }
                    }
                },
            )
            ProfilerCompactButton(
                text = stringResource(Res.string.export_json),
                enabled = state.analysis != null,
                onClick = {
                    chooseSaveFile(window, "frame-analysis.json", saveDialogTitle)?.let { output ->
                        scope.launch { controller.exportJson(output.toPath()) }
                    }
                },
            )
            Spacer(Modifier.weight(1f))
            ProfilerToolbarStatus(
                message = state.operationMessage,
                error = state.errorMessage,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        FrameProfilerScreen(
            state = state,
            actions =
                FrameProfilerActions(
                    onSelectFrame = controller::selectFrame,
                    onInspectLayout = { sample ->
                        sample.packageName?.let { packageName ->
                            scope.launch {
                                if (controller.state.value.isCapturing) controller.stopOnlineCapture()
                                onOpenLayoutInspector(
                                    FrameLayoutInspectionRequest(
                                        deviceSerial = state.selectedDeviceSerial,
                                        packageName = packageName,
                                        activityName = sample.activityName,
                                        windowId = sample.windowId,
                                        frameId = sample.frameId,
                                    ),
                                )
                            }
                        }
                    },
                ),
            chinese = chinese,
            modifier = Modifier.weight(1f),
        )
    }

    if (showImportDialog) {
        FrameStatsOpenFileDialog(
            parent = window,
            dialogTitle = importDialogTitle,
            onCloseRequest = { selected ->
                showImportDialog = false
                selected?.let { file -> scope.launch { controller.importFrameStats(file.toPath()) } }
            },
        )
    }
}

@Composable
private fun FrameStatsOpenFileDialog(
    parent: Frame,
    dialogTitle: String,
    onCloseRequest: (File?) -> Unit,
) {
    AwtWindow(
        create = {
            object : FileDialog(parent, dialogTitle, FileDialog.LOAD) {
                init {
                    isMultipleMode = false
                    filenameFilter =
                        java.io.FilenameFilter { _, name ->
                            name.endsWith(".txt", ignoreCase = true) || name.endsWith(".framestats", ignoreCase = true)
                        }
                }

                override fun setVisible(value: Boolean) {
                    super.setVisible(value)
                    if (value) onCloseRequest(files.firstOrNull())
                }
            }
        },
        dispose = FileDialog::dispose,
    )
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

private const val POLL_INTERVAL_MILLIS = 1_000L
