@file:Suppress("FunctionName", "LongMethod", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.app

import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.memory.memory_app.generated.resources.Res
import com.androidperformancestudio.memory.memory_app.generated.resources.*

import androidx.compose.foundation.layout.Column
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
import com.androidperformancestudio.memory.presentation.MemoryProfilerActions
import com.androidperformancestudio.memory.presentation.MemoryProfilerScreen
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerHomeButton
import com.androidperformancestudio.ui.ProfilerMacOsToolbar
import kotlinx.coroutines.launch
import java.awt.Component
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

@Composable
public fun FrameWindowScope.MemoryProfilerMainPage(
    chinese: Boolean = false,
    onBack: () -> Unit = {},
    highlightClassName: String? = null,
) {
    val controller = remember(chinese) {
        MemoryProfilerController(DesktopMemoryProfilerBackend(chinese = chinese), chinese = chinese)
    }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val loaded = controller.loadedHeap
    var showHprofFileDialog by remember { mutableStateOf(false) }

    LaunchedEffect(controller) { controller.refreshDevices() }
    LaunchedEffect(highlightClassName) {
        highlightClassName?.let(controller::highlightClass)
    }

    Column(Modifier.fillMaxSize()) {
        ProfilerMacOsToolbar {
            ProfilerHomeButton(
                contentDescription = localizedStringResource(Res.string.back_to_home, chinese),
                onClick = onBack,
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.refresh_devices, chinese),
                onClick = { scope.launch { controller.refreshDevices() } },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.export_raw_hprof, chinese),
                enabled = loaded?.heapDump?.rawHprofFile != null,
                onClick = { chooseSaveFile(window, "heap-raw.hprof", chinese)?.let(controller::exportRaw) },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.export_standard_hprof, chinese),
                enabled = loaded?.heapDump?.convertedHprofFile != null,
                onClick = { chooseSaveFile(window, "heap-standard.hprof", chinese)?.let(controller::exportConverted) },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.export_csv, chinese),
                enabled = loaded != null,
                onClick = { chooseSaveFile(window, "class-histogram.csv", chinese)?.let(controller::exportHistogram) },
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        MemoryProfilerScreen(
            state = state,
            actions =
                MemoryProfilerActions(
                    onSelectDevice = { serial -> scope.launch { controller.selectDevice(serial) } },
                    onSelectProcess = controller::selectProcess,
                    onDumpHeap = { scope.launch { controller.dumpHeap() } },
                    onImportHprof = { showHprofFileDialog = true },
                    onSortHistogram = controller::sort,
                    onRetry = { scope.launch { controller.refreshDevices() } },
                    onHighlightClass = controller::highlightClass,
                ),
            chinese = chinese,
            modifier = Modifier.weight(1f),
        )
    }

    if (showHprofFileDialog) {
        HprofOpenFileDialog(
            parent = window,
            chinese = chinese,
            onCloseRequest = { selectedFile ->
                showHprofFileDialog = false
                if (selectedFile != null) {
                    scope.launch {
                        controller.importHprof(selectedFile.toPath())
                    }
                }
            },
        )
    }
}

@Composable
private fun HprofOpenFileDialog(
    parent: Frame,
    chinese: Boolean,
    onCloseRequest: (File?) -> Unit,
) {
    AwtWindow(
        create = {
            object : FileDialog(parent, localizedStringResource(Res.string.import_hprof, chinese), FileDialog.LOAD) {
                init {
                    isMultipleMode = false
                    filenameFilter = java.io.FilenameFilter { _, name -> name.endsWith(".hprof", ignoreCase = true) }
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

private fun chooseSaveFile(
    parent: Component,
    defaultName: String,
    chinese: Boolean,
): java.nio.file.Path? =
    JFileChooser().run {
        dialogTitle = localizedStringResource(Res.string.export_memory_profiler_data, chinese)
        selectedFile = File(defaultName)
        if (showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile.toPath() else null
    }
