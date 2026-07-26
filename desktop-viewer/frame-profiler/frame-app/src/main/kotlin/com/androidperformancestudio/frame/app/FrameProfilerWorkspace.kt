@file:Suppress("CyclomaticComplexMethod", "FunctionName", "LongMethod", "ktlint:standard:function-naming")

package com.androidperformancestudio.frame.app

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
    val controller = remember { FrameProfilerController() }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showImportDialog by remember { mutableStateOf(false) }

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
                contentDescription = if (chinese) "返回主页" else "Back to home",
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
                label = if (chinese) "设备" else "Device",
                selectedLabel =
                    state.devices.firstOrNull { it.serial == state.selectedDeviceSerial }?.name,
                options = state.devices.filter { it.online }.map { it.serial to it.name },
                enabled = !state.isCapturing,
                onSelected = { serial -> scope.launch { controller.selectDevice(serial) } },
            )
            ProfilerCompactSelector(
                label = if (chinese) "进程" else "Process",
                selectedLabel =
                    state.processes.firstOrNull { it.pid == state.selectedProcessId }?.let { "${it.name} (${it.pid})" },
                options = state.processes.map { it.pid.toString() to "${it.name} (${it.pid})" },
                enabled = !state.isCapturing && state.selectedDeviceSerial != null,
                onSelected = { pid -> pid.toIntOrNull()?.let(controller::selectProcess) },
            )
            ProfilerCompactButton(
                text = if (chinese) "刷新" else "Refresh",
                enabled = !state.isCapturing && !state.isRefreshingDevices,
                onClick = { scope.launch { controller.refreshDevices() } },
            )
            ProfilerCompactButton(
                text =
                    if (state.isCapturing) {
                        if (chinese) "停止采集" else "Stop Capture"
                    } else {
                        if (chinese) "开始采集" else "Start Capture"
                    },
                enabled = state.selectedProcessId != null,
                onClick = {
                    scope.launch {
                        if (state.isCapturing) controller.stopOnlineCapture() else controller.startOnlineCapture()
                    }
                },
            )
            ProfilerCompactButton(
                text = if (chinese) "导入 FrameStats" else "Import FrameStats",
                enabled = !state.isCapturing,
                onClick = { showImportDialog = true },
            )
            ProfilerCompactButton(
                text = if (chinese) "导出 CSV" else "Export CSV",
                enabled = state.analysis != null,
                onClick = {
                    chooseSaveFile(window, "frame-analysis.csv")?.let { output ->
                        scope.launch { controller.exportCsv(output.toPath()) }
                    }
                },
            )
            ProfilerCompactButton(
                text = if (chinese) "导出 JSON" else "Export JSON",
                enabled = state.analysis != null,
                onClick = {
                    chooseSaveFile(window, "frame-analysis.json")?.let { output ->
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
    onCloseRequest: (File?) -> Unit,
) {
    AwtWindow(
        create = {
            object : FileDialog(parent, "Import gfxinfo FrameStats", FileDialog.LOAD) {
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
): File? =
    JFileChooser().run {
        dialogTitle = "Export Frame Profiler Report"
        selectedFile = File(defaultName)
        if (showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }

private const val POLL_INTERVAL_MILLIS = 1_000L
