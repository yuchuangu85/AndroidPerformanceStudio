@file:Suppress("CyclomaticComplexMethod", "FunctionName", "LongMethod", "ktlint:standard:function-naming")

package com.androidperformancestudio.frame.app

import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.frame.frame_app.generated.resources.Res
import com.androidperformancestudio.frame.frame_app.generated.resources.associate_perfetto_trace
import com.androidperformancestudio.frame.frame_app.generated.resources.back_to_home
import com.androidperformancestudio.frame.frame_app.generated.resources.capture_frametimeline
import com.androidperformancestudio.frame.frame_app.generated.resources.capture_stopped_with_frames
import com.androidperformancestudio.frame.frame_app.generated.resources.capture_stopped_without_frames
import com.androidperformancestudio.frame.frame_app.generated.resources.capturing_frame_count
import com.androidperformancestudio.frame.frame_app.generated.resources.capturing_via
import com.androidperformancestudio.frame.frame_app.generated.resources.device
import com.androidperformancestudio.frame.frame_app.generated.resources.export_frame_profiler_report
import com.androidperformancestudio.frame.frame_app.generated.resources.exported
import com.androidperformancestudio.frame.frame_app.generated.resources.import_gfxinfo_framestats
import com.androidperformancestudio.frame.frame_app.generated.resources.import_perfetto_frametimeline
import com.androidperformancestudio.frame.frame_app.generated.resources.imported_frames
import com.androidperformancestudio.frame.frame_app.generated.resources.open_trace_in_perfetto
import com.androidperformancestudio.frame.frame_app.generated.resources.process
import com.androidperformancestudio.frame.frame_app.generated.resources.process_with_pid
import com.androidperformancestudio.frame.frame_app.generated.resources.refresh
import com.androidperformancestudio.frame.frame_app.generated.resources.select_perfetto_trace
import com.androidperformancestudio.frame.frame_app.generated.resources.start_capture
import com.androidperformancestudio.frame.frame_app.generated.resources.stop_capture
import com.androidperformancestudio.frame.presentation.FrameOperationStatus
import com.androidperformancestudio.frame.presentation.FrameProfilerActions
import com.androidperformancestudio.frame.presentation.FrameProfilerScreen
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.DesktopOpenFileDialog
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerMacOsToolbar
import com.androidperformancestudio.ui.ProfilerToolbarStatus
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.button.HomeButton
import com.androidperformancestudio.ui.chooseOpenFile
import com.androidperformancestudio.ui.chooseSaveFile
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Composable
public fun FrameWindowScope.FrameProfilerMainPage(
    darkTheme: Boolean = isSystemInDarkTheme(),
    language: UiLanguage = UiLanguage.ENGLISH,
    onBack: () -> Unit = {},
    onOpenLayoutInspector: (FrameLayoutInspectionRequest) -> Unit = {},
    onOpenPerfetto: (FramePerfettoInspectionRequest) -> Unit = {},
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
                importEnabled = !state.isCapturing && !state.isLoading,
                exportEnabled = state.analysis != null,
            ),
        onImportFrameStats = { showImportDialog = true },
        onExportCsv = {
            chooseSaveFile(
                window,
                localizedStringResource(Res.string.export_frame_profiler_report, language),
                "frame-analysis.csv",
            )?.let { output ->
                scope.launch { controller.exportCsv(output.toPath()) }
            }
        },
        onExportJson = {
            chooseSaveFile(
                window,
                localizedStringResource(Res.string.export_frame_profiler_report, language),
                "frame-analysis.json",
            )?.let { output ->
                scope.launch { controller.exportJson(output.toPath()) }
            }
        },
    )

    ViewerTheme(darkTheme = darkTheme) {
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
                DropdownSelector(
                    items = state.devices,
                    selectedItem = state.devices.firstOrNull { it.serial == state.selectedDeviceSerial },
                    onItemSelected = { device -> scope.launch { controller.selectDevice(device.serial) } },
                    itemLabel = { it.name },
                    placeholder = localizedStringResource(Res.string.device, language),
                    enabled = !state.isCapturing && !state.isLoading,
                    itemEnabled = { it.online },
                )
                DropdownSelector(
                    items = state.processes,
                    selectedItem = state.processes.firstOrNull { it.pid == state.selectedProcessId },
                    onItemSelected = { controller.selectProcess(it.pid) },
                    itemLabel = {
                        localizedStringResource(
                            Res.string.process_with_pid,
                            language,
                            it.name,
                            it.pid,
                        )
                    },
                    placeholder = localizedStringResource(Res.string.process, language),
                    enabled = !state.isCapturing && !state.isLoading && state.selectedDeviceSerial != null,
                )
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.refresh, language),
                    enabled = !state.isCapturing && !state.isLoading && !state.isRefreshingDevices,
                    onClick = { scope.launch { controller.refreshDevices() } },
                )
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.import_perfetto_frametimeline, language),
                    enabled = !state.isCapturing && !state.isLoading,
                    onClick = {
                        chooseOpenFile(
                            window,
                            localizedStringResource(Res.string.select_perfetto_trace, language),
                            "Perfetto trace",
                            "trace",
                            "perfetto-trace",
                            "pftrace",
                        )?.let { trace ->
                            scope.launch { controller.importPerfettoTrace(trace.toPath()) }
                        }
                    },
                )
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.associate_perfetto_trace, language),
                    enabled = !state.isCapturing && !state.isLoading && state.analysis != null,
                    onClick = {
                        chooseOpenFile(
                            window,
                            localizedStringResource(Res.string.select_perfetto_trace, language),
                            "Perfetto trace",
                            "trace",
                            "perfetto-trace",
                            "pftrace",
                        )?.let { trace ->
                            scope.launch { controller.associatePerfettoTrace(trace.toPath()) }
                        }
                    },
                )
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.capture_frametimeline, language),
                    enabled = state.selectedProcessId != null && !state.isCapturing && !state.isLoading,
                    onClick = { scope.launch { controller.captureFrameTimeline() } },
                )
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.open_trace_in_perfetto, language),
                    enabled = state.perfettoTraceFile != null,
                    onClick = {
                        state.perfettoTraceFile?.let { trace ->
                            val sample =
                                state.analysis
                                    ?.frames
                                    ?.firstOrNull { it.sample.frameId == state.selectedFrameId }
                                    ?.sample
                            onOpenPerfetto(
                                FramePerfettoInspectionRequest(
                                    traceFile = trace,
                                    frameId = sample?.frameId,
                                    frameTimelineVsyncId = sample?.frameTimelineVsyncId,
                                    intendedVsyncNs = sample?.intendedVsyncNs,
                                ),
                            )
                        }
                    },
                )
                ProfilerCompactButton(
                    text =
                        if (state.isCapturing) {
                            localizedStringResource(Res.string.stop_capture, language)
                        } else {
                            localizedStringResource(Res.string.start_capture, language)
                        },
                    enabled = state.selectedProcessId != null && (state.isCapturing || !state.isLoading),
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
    }

    if (showImportDialog) {
        DesktopOpenFileDialog(
            parent = window,
            title = localizedStringResource(Res.string.import_gfxinfo_framestats, language),
            acceptFileName = {
                it.endsWith(".txt", ignoreCase = true) || it.endsWith(".framestats", ignoreCase = true)
            },
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

private const val POLL_INTERVAL_MILLIS = 250L
