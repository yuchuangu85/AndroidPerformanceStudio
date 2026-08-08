package com.androidperformancestudio.methodrecording.app

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
import androidx.compose.ui.awt.AwtWindow
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import com.androidperformancestudio.adb.AdbConfiguration
import com.androidperformancestudio.adb.SystemAdbLocator
import com.androidperformancestudio.methodrecording.app.generated.resources.Res
import com.androidperformancestudio.methodrecording.app.generated.resources.back_to_home
import com.androidperformancestudio.methodrecording.app.generated.resources.capture
import com.androidperformancestudio.methodrecording.app.generated.resources.device_selector
import com.androidperformancestudio.methodrecording.app.generated.resources.import_trace
import com.androidperformancestudio.methodrecording.app.generated.resources.method_recording
import com.androidperformancestudio.methodrecording.app.generated.resources.process_selector
import com.androidperformancestudio.methodrecording.app.generated.resources.refresh_devices
import com.androidperformancestudio.methodrecording.app.generated.resources.select_device
import com.androidperformancestudio.methodrecording.app.generated.resources.select_process
import com.androidperformancestudio.methodrecording.app.generated.resources.stop
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.SystemHostPlatformDetector
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerMacOsToolbar
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.button.HomeButton
import com.androidperformancestudio.ui.localizedStringResource
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.launch

/** The CPU Method Recording workspace: capture/import an ART `.trace` and analyze it. */
@Composable
fun FrameWindowScope.MethodRecordingMainPage(
    language: UiLanguage = UiLanguage.ENGLISH,
    darkTheme: Boolean = isSystemInDarkTheme(),
    androidSdkPath: Path? = null,
    initialTraceFile: Path? = null,
    onBack: () -> Unit = {},
) {
    val adbExecutable = remember(androidSdkPath) { locateSystemAdb(androidSdkPath) }
    val controller = remember(adbExecutable) { MethodRecordingController(adbExecutable, language = language) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showTraceFileDialog by remember { mutableStateOf(false) }

    LaunchedEffect(controller) { controller.refreshDevices() }
    LaunchedEffect(initialTraceFile) {
        initialTraceFile?.let { file -> controller.importTrace(file) }
    }

    ViewerTheme(darkTheme = darkTheme) {
        Column(Modifier.fillMaxSize()) {
            ProfilerMacOsToolbar {
                HomeButton(
                    contentDescription = localizedStringResource(Res.string.back_to_home, language),
                    onClick = onBack,
                )
                DropdownSelector(
                    items = state.devices,
                    selectedItem = state.devices.firstOrNull { it.serial == state.selectedSerial },
                    onItemSelected = { device -> scope.launch { controller.selectDevice(device.serial) } },
                    itemLabel = { device -> device.name },
                    placeholder = localizedStringResource(Res.string.select_device, language),
                    selectorDescription = localizedStringResource(Res.string.device_selector, language),
                    enabled = !state.isLoading,
                )
                DropdownSelector(
                    items = state.processes,
                    selectedItem = state.processes.firstOrNull { it.pid == state.selectedPid },
                    onItemSelected = { process -> controller.selectProcess(process.pid) },
                    itemLabel = { process -> process.name },
                    placeholder = localizedStringResource(Res.string.select_process, language),
                    selectorDescription = localizedStringResource(Res.string.process_selector, language),
                    enabled = !state.isLoading,
                )
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.refresh_devices, language),
                    onClick = { scope.launch { controller.refreshDevices() } },
                )
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
        }
    }

    MenuBar {
        Menu(localizedStringResource(Res.string.method_recording, language)) {
            Item(
                text = localizedStringResource(Res.string.import_trace, language),
                enabled = !state.isLoading,
                onClick = { showTraceFileDialog = true },
            )
        }
    }

    if (showTraceFileDialog) {
        TraceOpenFileDialog(
            parent = window,
            language = language,
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
private fun TraceOpenFileDialog(
    parent: Frame,
    language: UiLanguage,
    onCloseRequest: (File?) -> Unit,
) {
    AwtWindow(
        create = {
            object : FileDialog(parent, localizedStringResource(Res.string.import_trace, language), FileDialog.LOAD) {
                init {
                    isMultipleMode = false
                    filenameFilter = java.io.FilenameFilter { _, name -> name.endsWith(".trace", ignoreCase = true) }
                }

                override fun setVisible(value: Boolean) {
                    super.setVisible(value)
                    if (value) {
                        onCloseRequest(files.firstOrNull())
                    }
                }
            }
        },
        dispose = FileDialog::dispose,
    )
}

private fun locateSystemAdb(androidSdkPath: Path?): Path? {
    val platform = (SystemHostPlatformDetector().detect() as? StudioResult.Success)?.value ?: return null
    val location =
        SystemAdbLocator(platform).locate(AdbConfiguration(androidSdkPath = androidSdkPath)) as? StudioResult.Success
            ?: return null
    return location.value.executable
}
