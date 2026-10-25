package com.androidperformancestudio.methodrecording.app

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
import com.androidperformancestudio.adb.AdbConfiguration
import com.androidperformancestudio.adb.defaultAdbExecutable
import com.androidperformancestudio.methodrecording.app.generated.resources.Res
import com.androidperformancestudio.methodrecording.app.generated.resources.capture
import com.androidperformancestudio.methodrecording.app.generated.resources.device_connected
import com.androidperformancestudio.methodrecording.app.generated.resources.device_offline
import com.androidperformancestudio.methodrecording.app.generated.resources.device_status
import com.androidperformancestudio.methodrecording.app.generated.resources.devices_connected
import com.androidperformancestudio.methodrecording.app.generated.resources.import_trace
import com.androidperformancestudio.methodrecording.app.generated.resources.no_devices
import com.androidperformancestudio.methodrecording.app.generated.resources.process_selector
import com.androidperformancestudio.methodrecording.app.generated.resources.refresh_devices
import com.androidperformancestudio.methodrecording.app.generated.resources.select_process
import com.androidperformancestudio.methodrecording.app.generated.resources.stop
import com.androidperformancestudio.ui.ActivePageMenu
import com.androidperformancestudio.ui.DesktopOpenFileDialog
import com.androidperformancestudio.ui.DeviceDropdownSelector
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.HeaderSpacer
import com.androidperformancestudio.ui.HeaderToolbar
import com.androidperformancestudio.ui.LocalTitleBarTargetHost
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.LocalWindowMenuBarActive
import com.androidperformancestudio.ui.PageMenu
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.TitleBarRefreshOrInline
import com.androidperformancestudio.ui.TitleBarTargetHost
import com.androidperformancestudio.ui.TitleBarTargetOrInline
import com.androidperformancestudio.ui.TitleBarTargetSlot
import com.androidperformancestudio.ui.TitleBarTargetWidth
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerDimensions
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.launch
import java.nio.file.Path

/** Follows the application-wide device and process target owned by the fixed title bar. */
@Composable
@Suppress("FunctionName", "FunctionNaming", "ktlint:standard:function-naming")
private fun MethodRecordingTargetSync(
    controller: MethodRecordingController,
    state: MethodRecordingState,
    titleBarHost: TitleBarTargetHost?,
) {
    LaunchedEffect(
        titleBarHost,
        titleBarHost?.selectedDeviceSerial,
        state.devices,
        state.selectedSerial,
    ) {
        if (titleBarHost != null && !state.isLoading) {
            val desired = titleBarHost.selectedDeviceSerial
            val selectedDevice = state.devices.firstOrNull { it.serial == state.selectedSerial }
            if (desired == null && selectedDevice != null) {
                titleBarHost.chooseDevice(selectedDevice.serial, selectedDevice.name)
            } else if (
                desired != null &&
                desired != state.selectedSerial &&
                state.devices.any { it.serial == desired }
            ) {
                controller.selectDevice(desired)
            }
        }
    }
    LaunchedEffect(
        titleBarHost,
        titleBarHost?.selectedProcessPid,
        state.processes,
        state.selectedPid,
        state.selectedSerial,
    ) {
        if (titleBarHost != null && !state.isLoading && titleBarHost.deviceReady(state.selectedSerial)) {
            val desired = titleBarHost.selectedProcessPid
            if (desired != null && desired != state.selectedPid && state.processes.any { it.pid == desired }) {
                controller.selectProcess(desired)
            }
        }
    }
}

/** The CPU Method Recording workspace: capture/import an ART `.trace` and analyze it. */
@Suppress("FunctionName", "LongMethod", "ktlint:standard:function-naming")
@Composable
fun FrameWindowScope.MethodRecordingMainPage(
    language: UiLanguage = UiLanguage.ENGLISH,
    androidSdkPath: Path? = null,
    initialTraceFile: Path? = null,
    onBack: () -> Unit = {},
) {
    val adbExecutable =
        remember(androidSdkPath) {
            defaultAdbExecutable(AdbConfiguration(androidSdkPath = androidSdkPath))
        }
    val controller = remember(adbExecutable) { MethodRecordingController(adbExecutable, language = language) }
    DisposableEffect(controller) { onDispose(controller::close) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showTraceFileDialog by remember { mutableStateOf(false) }

    LaunchedEffect(controller) { controller.refreshDevices() }
    LaunchedEffect(initialTraceFile) {
        initialTraceFile?.let { file -> controller.importTrace(file) }
    }
    // The fixed title bar owns the application-wide device and process target; follow it here.
    MethodRecordingTargetSync(
        controller = controller,
        state = state,
        titleBarHost = LocalTitleBarTargetHost.current.takeIf { LocalWindowMenuBarActive.current },
    )

    ActivePageMenu(PageMenu(onOpen = { showTraceFileDialog = true }, openEnabled = !state.isLoading))

    Column(Modifier.fillMaxSize()) {
        HeaderToolbar(
            language = language,
            onNavigateHome = onBack,
            onNavigateSettings = null,
        ) {
            val inTitleBar = LocalWindowMenuBarActive.current && LocalTitleBarTargetHost.current != null
            val selectorModifier = if (inTitleBar) Modifier.width(TitleBarTargetWidth) else Modifier
            TitleBarTargetOrInline(TitleBarTargetSlot.DEVICE) {
                DeviceDropdownSelector(
                    language = language,
                    items = state.devices,
                    selectedItem = state.devices.firstOrNull { it.serial == state.selectedSerial },
                    onItemSelected = { device -> scope.launch { controller.selectDevice(device.serial) } },
                    itemLabel = { device -> device.name },
                    enabled = !state.isLoading,
                    modifier = selectorModifier,
                    fillWidth = inTitleBar,
                )
            }
            if (!inTitleBar) HeaderSpacer()
            TitleBarTargetOrInline(TitleBarTargetSlot.PROCESS) {
                DropdownSelector(
                    items = state.processes,
                    searchable = true,
                    searchLanguage = language,
                    itemSearchText = { "${it.name} ${it.packageName} ${it.pid}" },
                    selectedItem = state.processes.firstOrNull { it.pid == state.selectedPid },
                    onItemSelected = { process -> controller.selectProcess(process.pid) },
                    itemLabel = { process -> process.name },
                    placeholder = localizedStringResource(Res.string.select_process, language),
                    selectorDescription = localizedStringResource(Res.string.process_selector, language),
                    enabled = !state.isLoading,
                    modifier = selectorModifier,
                    fillWidth = inTitleBar,
                )
            }
            if (!inTitleBar) HeaderSpacer()
            TitleBarRefreshOrInline(
                enabled = true,
                onRefresh = { scope.launch { controller.refreshDevices() } },
            ) {
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.refresh_devices, language),
                    onClick = { scope.launch { controller.refreshDevices() } },
                )
            }
            Spacer(Modifier.weight(1f))
            val isRecording = state.capturePhase is MethodTraceCapturePhase.Recording
            ProfilerCompactButton(
                text =
                    if (isRecording) {
                        localizedStringResource(Res.string.stop, language)
                    } else {
                        localizedStringResource(Res.string.capture, language)
                    },
                onClick = {
                    if (isRecording) {
                        controller.requestStop()
                    } else {
                        scope.launch { controller.startCapture() }
                    }
                },
                enabled = state.selectedPid != null && !state.isLoading,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        MethodRecordingScreen(
            state = state,
            language = language,
            modifier = Modifier.weight(1f),
        )
        MethodRecordingStatusBar(state = state, language = language)
    }

    if (showTraceFileDialog) {
        DesktopOpenFileDialog(
            parent = window,
            title = localizedStringResource(Res.string.import_trace, language),
            acceptFileName = { it.endsWith(".trace", ignoreCase = true) },
            onCloseRequest = { selectedFile ->
                showTraceFileDialog = false
                if (selectedFile != null) {
                    scope.launch { controller.importTrace(selectedFile.toPath()) }
                }
            },
        )
    }
}

@Composable
private fun MethodRecordingStatusBar(
    state: MethodRecordingState,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    val selectedDevice = state.devices.firstOrNull { it.serial == state.selectedSerial }
    val onlineDeviceCount = state.devices.count { it.online }
    val deviceStatus =
        when {
            selectedDevice?.online == true ->
                localizedStringResource(Res.string.device_connected, language, selectedDevice.name)
            selectedDevice != null ->
                localizedStringResource(Res.string.device_offline, language, selectedDevice.name)
            onlineDeviceCount > 0 ->
                localizedStringResource(Res.string.devices_connected, language, onlineDeviceCount)
            else -> localizedStringResource(Res.string.no_devices, language)
        }
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
            Text(
                text = "${localizedStringResource(Res.string.device_status, language)}:",
                color = colors.mutedText,
                fontSize = ViewerTypography.label.fontSize,
                maxLines = 1,
            )
            Text(
                text = deviceStatus,
                color = if (onlineDeviceCount > 0) colors.secondaryText else colors.mutedText,
                fontSize = ViewerTypography.secondary.fontSize,
                lineHeight = ViewerTypography.secondary.lineHeight,
                maxLines = 1,
            )
        }
    }
}
