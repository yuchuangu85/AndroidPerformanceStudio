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
import com.androidperformancestudio.frame.frame_app.generated.resources.Res
import com.androidperformancestudio.frame.frame_app.generated.resources.back_to_home
import com.androidperformancestudio.frame.frame_app.generated.resources.capture_stopped_with_frames
import com.androidperformancestudio.frame.frame_app.generated.resources.capture_stopped_without_frames
import com.androidperformancestudio.frame.frame_app.generated.resources.capturing_frame_count
import com.androidperformancestudio.frame.frame_app.generated.resources.capturing_via
import com.androidperformancestudio.frame.frame_app.generated.resources.device
import com.androidperformancestudio.frame.frame_app.generated.resources.export_frame_profiler_report
import com.androidperformancestudio.frame.frame_app.generated.resources.exported
import com.androidperformancestudio.frame.frame_app.generated.resources.import_gfxinfo_framestats
import com.androidperformancestudio.frame.frame_app.generated.resources.imported_frames
import com.androidperformancestudio.frame.frame_app.generated.resources.process
import com.androidperformancestudio.frame.frame_app.generated.resources.process_with_pid
import com.androidperformancestudio.frame.frame_app.generated.resources.refresh
import com.androidperformancestudio.frame.frame_app.generated.resources.start_capture
import com.androidperformancestudio.frame.frame_app.generated.resources.stop_capture
import com.androidperformancestudio.frame.presentation.FrameOperationStatus
import com.androidperformancestudio.frame.presentation.FrameProfilerActions
import com.androidperformancestudio.frame.presentation.FrameProfilerScreen
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerCompactSelector
import com.androidperformancestudio.ui.button.HomeButton
import com.androidperformancestudio.ui.ProfilerMacOsToolbar
import com.androidperformancestudio.ui.ProfilerToolbarStatus
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import kotlin.time.Duration.Companion.milliseconds

@Composable
public fun FrameWindowScope.FrameProfilerMainPage(
    language: UiLanguage = UiLanguage.ENGLISH,
    onBack: () -> Unit = {},
    onOpenLayoutInspector: (FrameLayoutInspectionRequest) -> Unit = {},
) {
    val controller = remember { FrameProfilerController() }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showImportDialog by remember { mutableStateOf(false) }
    val operationMessage = state.operationStatus?.localizedText(language)

    LaunchedEffect(controller) { controller.refreshDevices() }
    LaunchedEffect(state.isCapturing) {
        while (controller.state.value.isCapturing) {
            controller.pollOnlineCapture()
            delay(POLL_INTERVAL_MILLIS.milliseconds)
        }
    }

    FrameProfilerFileMenuBar(
        model =
            frameProfilerFileMenuModel(
                language = language,
                importEnabled = !state.isCapturing,
                exportEnabled = state.analysis != null,
            ),
        onImportFrameStats = { showImportDialog = true },
        onExportCsv = {
            chooseSaveFile(window, "frame-analysis.csv", language)?.let { output ->
                scope.launch { controller.exportCsv(output.toPath()) }
            }
        },
        onExportJson = {
            chooseSaveFile(window, "frame-analysis.json", language)?.let { output ->
                scope.launch { controller.exportJson(output.toPath()) }
            }
        },
    )

    Column(Modifier.fillMaxSize()) {
        ProfilerMacOsToolbar {
            HomeButton(
                contentDescription = localizedStringResource(Res.string.back_to_home, language),
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
                label = localizedStringResource(Res.string.device, language),
                selectedLabel =
                    state.devices.firstOrNull { it.serial == state.selectedDeviceSerial }?.name,
                options = state.devices.filter { it.online }.map { it.serial to it.name },
                enabled = !state.isCapturing,
                onSelected = { serial -> scope.launch { controller.selectDevice(serial) } },
            )
            ProfilerCompactSelector(
                label = localizedStringResource(Res.string.process, language),
                selectedLabel =
                    state.processes.firstOrNull { it.pid == state.selectedProcessId }?.let {
                        localizedStringResource(Res.string.process_with_pid, language, it.name, it.pid)
                    },
                options =
                    state.processes.map {
                        it.pid.toString() to
                            localizedStringResource(
                                Res.string.process_with_pid,
                                language,
                                it.name,
                                it.pid,
                            )
                    },
                enabled = !state.isCapturing && state.selectedDeviceSerial != null,
                onSelected = { pid -> pid.toIntOrNull()?.let(controller::selectProcess) },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.refresh, language),
                enabled = !state.isCapturing && !state.isRefreshingDevices,
                onClick = { scope.launch { controller.refreshDevices() } },
            )
            ProfilerCompactButton(
                text =
                    if (state.isCapturing) {
                        localizedStringResource(Res.string.stop_capture, language)
                    } else {
                        localizedStringResource(Res.string.start_capture, language)
                    },
                enabled = state.selectedProcessId != null,
                onClick = {
                    scope.launch {
                        if (state.isCapturing) controller.stopOnlineCapture() else controller.startOnlineCapture()
                    }
                },
            )
            Spacer(Modifier.weight(1f))
            ProfilerToolbarStatus(
                message = operationMessage,
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
            language = language,
            operationMessage = operationMessage,
            modifier = Modifier.weight(1f),
        )
    }

    if (showImportDialog) {
        FrameStatsOpenFileDialog(
            parent = window,
            language = language,
            onCloseRequest = { selected ->
                showImportDialog = false
                selected?.let { file -> scope.launch { controller.importFrameStats(file.toPath()) } }
            },
        )
    }
}

internal fun FrameOperationStatus.localizedText(language: UiLanguage): String =
    when (this) {
        is FrameOperationStatus.Capturing ->
            if (frameCount == null) {
                localizedStringResource(Res.string.capturing_via, language, packageName, source)
            } else {
                localizedStringResource(Res.string.capturing_frame_count, language, packageName, source, frameCount)
            }
        is FrameOperationStatus.CaptureStopped ->
            if (frameCount == 0) {
                localizedStringResource(Res.string.capture_stopped_without_frames, language)
            } else {
                localizedStringResource(Res.string.capture_stopped_with_frames, language, frameCount)
            }
        is FrameOperationStatus.ImportedFrames ->
            localizedStringResource(Res.string.imported_frames, language, frameCount)
        is FrameOperationStatus.Exported ->
            localizedStringResource(Res.string.exported, language, fileName)
    }

@Composable
private fun FrameStatsOpenFileDialog(
    parent: Frame,
    language: UiLanguage,
    onCloseRequest: (File?) -> Unit,
) {
    AwtWindow(
        create = {
            object : FileDialog(
                parent,
                localizedStringResource(Res.string.import_gfxinfo_framestats, language),
                FileDialog.LOAD,
            ) {
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
    language: UiLanguage,
): File? =
    JFileChooser().run {
        dialogTitle = localizedStringResource(Res.string.export_frame_profiler_report, language)
        selectedFile = File(defaultName)
        if (showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }

private const val POLL_INTERVAL_MILLIS = 1_000L
