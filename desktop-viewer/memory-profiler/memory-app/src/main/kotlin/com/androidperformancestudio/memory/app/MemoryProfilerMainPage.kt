@file:Suppress("FunctionName", "LongMethod", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.app

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
import com.androidperformancestudio.memory.memory_app.generated.resources.Res
import com.androidperformancestudio.memory.memory_app.generated.resources.back_to_home
import com.androidperformancestudio.memory.memory_app.generated.resources.export_memory_profiler_data
import com.androidperformancestudio.memory.memory_app.generated.resources.import_hprof
import com.androidperformancestudio.memory.memory_app.generated.resources.refresh_devices
import com.androidperformancestudio.memory.presentation.MemoryProfilerActions
import com.androidperformancestudio.memory.presentation.MemoryProfilerDumpBitmapsButton
import com.androidperformancestudio.memory.presentation.MemoryProfilerDumpHeapButton
import com.androidperformancestudio.memory.presentation.MemoryProfilerScreen
import com.androidperformancestudio.memory.presentation.MemoryProfilerToolbarSelectors
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.button.HomeButton
import com.androidperformancestudio.ui.ProfilerMacOsToolbar
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.launch
import java.awt.Component
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

@Composable
fun FrameWindowScope.MemoryProfilerMainPage(
    language: UiLanguage = UiLanguage.ENGLISH,
    onBack: () -> Unit = {},
    highlightClassName: String? = null,
) {
    val controller =
        remember(language) {
            MemoryProfilerController(DesktopMemoryProfilerBackend(language = language), language = language)
        }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val loaded = controller.loadedHeap
    var showHprofFileDialog by remember { mutableStateOf(false) }

    LaunchedEffect(controller) { controller.refreshDevices() }
    LaunchedEffect(highlightClassName) {
        highlightClassName?.let(controller::highlightClass)
    }

    MemoryProfilerFileMenuBar(
        model =
            memoryProfilerFileMenuModel(
                language = language,
                importEnabled = !state.isDumping,
                rawHprofExportEnabled = loaded?.heapDump?.rawHprofFile != null,
                standardHprofExportEnabled = loaded?.heapDump?.convertedHprofFile != null,
                csvExportEnabled = loaded != null,
                bitmapDumpExportEnabled = controller.loadedBitmapDump != null,
                bitmapComparisonExportEnabled = state.bitmapDumpComparison != null,
            ),
        onImportHprof = { showHprofFileDialog = true },
        onExportRawHprof = {
            chooseSaveFile(window, "heap-raw.hprof", language)?.let(controller::exportRaw)
        },
        onExportStandardHprof = {
            chooseSaveFile(window, "heap-standard.hprof", language)?.let(controller::exportConverted)
        },
        onExportCsv = {
            chooseSaveFile(window, "class-histogram.csv", language)?.let(controller::exportHistogram)
        },
        onExportBitmapDump = {
            chooseSaveFile(window, "bitmap-dump.zip", language)?.let(controller::exportBitmapSession)
        },
        onExportBitmapComparison = {
            chooseSaveFile(window, "bitmap-comparison.md", language)?.let(controller::exportBitmapComparison)
        },
    )

    Column(Modifier.fillMaxSize()) {
        ProfilerMacOsToolbar {
            HomeButton(
                contentDescription = localizedStringResource(Res.string.back_to_home, language),
                onClick = onBack,
            )
            MemoryProfilerToolbarSelectors(
                state = state,
                onSelectDevice = { serial -> scope.launch { controller.selectDevice(serial) } },
                onSelectProcess = controller::selectProcess,
                language = language,
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.refresh_devices, language),
                onClick = { scope.launch { controller.refreshDevices() } },
            )
            Spacer(Modifier.weight(1f))
            MemoryProfilerDumpHeapButton(
                state = state,
                onDumpHeap = { scope.launch { controller.dumpHeap() } },
                language = language,
            )
            MemoryProfilerDumpBitmapsButton(
                state = state,
                onDumpBitmaps = { scope.launch { controller.dumpBitmaps() } },
                language = language,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        MemoryProfilerScreen(
            state = state,
            actions =
                MemoryProfilerActions(
                    onSortHistogram = controller::sort,
                    onRetry = { scope.launch { controller.refreshDevices() } },
                    onHighlightClass = controller::highlightClass,
                ),
            language = language,
            modifier = Modifier.weight(1f),
        )
    }

    if (showHprofFileDialog) {
        HprofOpenFileDialog(
            parent = window,
            language = language,
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
    language: UiLanguage,
    onCloseRequest: (File?) -> Unit,
) {
    AwtWindow(
        create = {
            object : FileDialog(parent, localizedStringResource(Res.string.import_hprof, language), FileDialog.LOAD) {
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
    language: UiLanguage,
): java.nio.file.Path? =
    JFileChooser().run {
        dialogTitle = localizedStringResource(Res.string.export_memory_profiler_data, language)
        selectedFile = File(defaultName)
        if (showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile.toPath() else null
    }
