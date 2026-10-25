@file:Suppress("CyclomaticComplexMethod", "ComplexCondition", "FunctionName", "LongMethod", "MaxLineLength", "ktlint:standard:function-naming")

package com.androidperformancestudio.frame.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.frame.frame_app.generated.resources.Res
import com.androidperformancestudio.frame.frame_app.generated.resources.associate_perfetto_trace
import com.androidperformancestudio.frame.frame_app.generated.resources.capture_frametimeline
import com.androidperformancestudio.frame.frame_app.generated.resources.capture_stopped_with_frames
import com.androidperformancestudio.frame.frame_app.generated.resources.capture_stopped_without_frames
import com.androidperformancestudio.frame.frame_app.generated.resources.capturing_frame_count
import com.androidperformancestudio.frame.frame_app.generated.resources.capturing_via
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
import com.androidperformancestudio.frame.frame_app.generated.resources.status
import com.androidperformancestudio.frame.frame_app.generated.resources.stop_capture
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.presentation.FrameOperationStatus
import com.androidperformancestudio.frame.presentation.FrameProfilerActions
import com.androidperformancestudio.frame.presentation.FrameProfilerScreen
import com.androidperformancestudio.ui.DesktopOpenFileDialog
import com.androidperformancestudio.ui.DeviceDropdownSelector
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.HeaderSpacer
import com.androidperformancestudio.ui.HeaderToolbar
import com.androidperformancestudio.ui.LocalTitleBarTargetHost
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.LocalWindowMenuBarActive
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.TitleBarRefreshOrInline
import com.androidperformancestudio.ui.TitleBarTargetOrInline
import com.androidperformancestudio.ui.TitleBarTargetSlot
import com.androidperformancestudio.ui.TitleBarTargetWidth
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerDimensions
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.chooseOpenFile
import com.androidperformancestudio.ui.chooseSaveFile
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlin.time.Duration.Companion.milliseconds

@Composable
public fun FrameWindowScope.FrameProfilerMainPage(
    language: UiLanguage = UiLanguage.ENGLISH,
    onBack: () -> Unit = {},
    onOpenLayoutInspector: (FrameLayoutInspectionRequest) -> Unit = {},
    onOpenPerfetto: (FramePerfettoInspectionRequest) -> Unit = {},
    isActive: Boolean = true,
) {
    val controller = remember { FrameProfilerController() }
    DisposableEffect(controller) { onDispose(controller::close) }
    val state by controller.state.collectAsState()
    val selectedSample =
        state.analysis
            ?.frames
            ?.firstOrNull { it.sample.frameId == state.selectedFrameId }
            ?.sample
            ?: state.analysis
                ?.frames
                ?.firstOrNull()
                ?.sample
    val openTraceForSample: (FrameSample?) -> Unit = { sample ->
        state.perfettoTraceFile?.let { trace ->
            onOpenPerfetto(framePerfettoInspectionRequest(trace, sample))
        }
    }
    val scope = rememberCoroutineScope()
    var showImportDialog by remember { mutableStateOf(false) }
    val operationMessage = state.operationStatus?.localizedText(language)
    val titleBarHost = LocalTitleBarTargetHost.current.takeIf { LocalWindowMenuBarActive.current }
    val selectedProcess = state.processes.firstOrNull { it.pid == state.selectedProcessId }
    val sharedTargetsReady = titleBarHost?.deviceReady(state.selectedDeviceSerial) != false &&
        titleBarHost?.packageReady(selectedProcess?.packageName) != false

    LaunchedEffect(controller, isActive) {
        if (isActive) controller.refreshDevices()
    }
    LaunchedEffect(state.isCapturing) {
        while (controller.state.value.isCapturing) {
            controller.pollOnlineCapture()
            delay(POLL_INTERVAL_MILLIS.milliseconds)
        }
    }
    LaunchedEffect(titleBarHost, titleBarHost?.selectedDeviceSerial, state.devices, state.selectedDeviceSerial) {
        if (titleBarHost != null) {
            val desired = titleBarHost.selectedDeviceSerial
            val selectedDevice = state.devices.firstOrNull { it.serial == state.selectedDeviceSerial }
            if (desired == null && selectedDevice != null) {
                titleBarHost.chooseDevice(selectedDevice.serial, selectedDevice.name)
            } else if (desired != null && desired != state.selectedDeviceSerial && state.devices.any { it.serial == desired && it.online } && !state.isCapturing) {
                controller.selectDevice(desired)
            }
        }
    }
    LaunchedEffect(titleBarHost, titleBarHost?.selectedPackageName, state.processes, state.selectedProcessId, state.selectedDeviceSerial) {
        if (titleBarHost != null && titleBarHost.deviceReady(state.selectedDeviceSerial)) {
            val desired = titleBarHost.selectedPackageName
            if (desired == null && selectedProcess != null) {
                titleBarHost.choosePackage(selectedProcess.packageName)
            } else if (desired != null && selectedProcess?.packageName != desired && !state.isCapturing) {
                state.processes.firstOrNull { it.packageName == desired }?.let { controller.selectProcess(it.pid) }
            }
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

    Column(Modifier.fillMaxSize()) {
        HeaderToolbar(
            language = language,
            onNavigateHome = {
                if (state.isCapturing) {
                    scope.launch {
                        controller.stopOnlineCapture()
                        onBack()
                    }
                } else {
                    onBack()
                }
            },
            onNavigateSettings = null,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val inTitleBar = LocalWindowMenuBarActive.current && LocalTitleBarTargetHost.current != null
                val selectorModifier = if (inTitleBar) Modifier.width(TitleBarTargetWidth) else Modifier
                TitleBarTargetOrInline(TitleBarTargetSlot.DEVICE) {
                    DeviceDropdownSelector(
                        language = language,
                        items = state.devices,
                        selectedItem = state.devices.firstOrNull { it.serial == state.selectedDeviceSerial },
                        onItemSelected = { device ->
                            titleBarHost?.chooseDevice(device.serial, device.name)
                            scope.launch { controller.selectDevice(device.serial) }
                        },
                        itemLabel = { it.name },
                        enabled = !state.isCapturing && !state.isLoading,
                        itemEnabled = { it.online },
                        modifier = selectorModifier,
                        fillWidth = inTitleBar,
                        displayTextOverride = titleBarHost?.selectedDeviceLabel,
                    )
                }
                if (!inTitleBar) HeaderSpacer()
                TitleBarTargetOrInline(TitleBarTargetSlot.PROCESS) {
                    DropdownSelector(
                        items = state.processes,
                        searchable = true,
                        searchLanguage = language,
                        itemSearchText = { "${it.name} ${it.packageName} ${it.pid}" },
                        selectedItem = state.processes.firstOrNull { it.pid == state.selectedProcessId },
                        onItemSelected = {
                            titleBarHost?.choosePackage(it.packageName)
                            controller.selectProcess(it.pid)
                        },
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
                        modifier = selectorModifier,
                        fillWidth = inTitleBar,
                        displayTextOverride = titleBarHost?.selectedPackageName,
                    )
                }
                if (!inTitleBar) HeaderSpacer()
                TitleBarRefreshOrInline(
                    enabled = !state.isCapturing && !state.isLoading && !state.isRefreshingDevices,
                    onRefresh = { scope.launch { controller.refreshDevices() } },
                ) {
                    ProfilerCompactButton(
                        text = localizedStringResource(Res.string.refresh, language),
                        enabled = !state.isCapturing && !state.isLoading && !state.isRefreshingDevices,
                        onClick = { scope.launch { controller.refreshDevices() } },
                    )
                }
                if (!inTitleBar) HeaderSpacer()
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
                HeaderSpacer()
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
                HeaderSpacer()
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.capture_frametimeline, language),
                    enabled = sharedTargetsReady && state.selectedProcessId != null && !state.isCapturing && !state.isLoading,
                    onClick = { if (sharedTargetsReady) scope.launch { controller.captureFrameTimeline() } },
                )
                HeaderSpacer()
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.open_trace_in_perfetto, language),
                    enabled = state.perfettoTraceFile != null,
                    onClick = { openTraceForSample(selectedSample) },
                )
                HeaderSpacer()
                ProfilerCompactButton(
                    text =
                        if (state.isCapturing) {
                            localizedStringResource(Res.string.stop_capture, language)
                        } else {
                            localizedStringResource(Res.string.start_capture, language)
                        },
                    enabled = state.selectedProcessId != null && (state.isCapturing || (sharedTargetsReady && !state.isLoading)),
                    onClick = {
                        scope.launch {
                            if (state.isCapturing) controller.stopOnlineCapture() else if (sharedTargetsReady) controller.startOnlineCapture()
                        }
                    },
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        FrameProfilerScreen(
            state = state,
            actions =
                FrameProfilerActions(
                    onSelectFrame = controller::selectFrame,
                    onCopyEvidence = { evidence ->
                        runCatching {
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(evidence), null)
                        }.isSuccess
                    },
                    onOpenTrace =
                        if (state.perfettoTraceFile != null) {
                            { sample -> openTraceForSample(sample) }
                        } else {
                            null
                        },
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
        FrameProfilerStatusBar(
            language = language,
            operationMessage = operationMessage,
            errorMessage = state.errorMessage,
        )
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

@Composable
private fun FrameProfilerStatusBar(
    language: UiLanguage,
    operationMessage: String?,
    errorMessage: String?,
) {
    val colors = LocalViewerColors.current
    val status = errorMessage ?: operationMessage
    SelectionContainer {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(ViewerDimensions.footerHeight)
                    .background(colors.toolbar)
                    .border(
                        ViewerDimensions.hairline,
                        colors.border,
                        RoundedCornerShape(0.dp),
                    ).horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            status?.let {
                Text(
                    text = "${localizedStringResource(Res.string.status, language)}:",
                    color = colors.mutedText,
                    fontSize = ViewerTypography.label.fontSize,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = it,
                    color = if (errorMessage != null) MaterialTheme.colorScheme.error else colors.secondaryText,
                    fontSize = ViewerTypography.secondary.fontSize,
                    lineHeight = ViewerTypography.secondary.lineHeight,
                    maxLines = 1,
                )
            }
        }
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
