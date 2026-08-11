@file:Suppress("FunctionName", "LongMethod", "MaxLineLength", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.app

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
import com.androidperformancestudio.memory.memory_app.generated.resources.Res
import com.androidperformancestudio.memory.memory_app.generated.resources.back_to_home
import com.androidperformancestudio.memory.memory_app.generated.resources.export_memory_profiler_data
import com.androidperformancestudio.memory.memory_app.generated.resources.import_hprof
import com.androidperformancestudio.memory.memory_app.generated.resources.import_java_heap
import com.androidperformancestudio.memory.memory_app.generated.resources.import_mapping
import com.androidperformancestudio.memory.memory_app.generated.resources.import_native_heap
import com.androidperformancestudio.memory.memory_app.generated.resources.refresh_devices
import com.androidperformancestudio.memory.presentation.MemoryProfilerActions
import com.androidperformancestudio.memory.presentation.MemoryProfilerCaptureNativeHeapButton
import com.androidperformancestudio.memory.presentation.MemoryProfilerDumpBitmapsButton
import com.androidperformancestudio.memory.presentation.MemoryProfilerDumpHeapButton
import com.androidperformancestudio.memory.presentation.MemoryProfilerScreen
import com.androidperformancestudio.memory.presentation.MemoryProfilerToolbarSelectors
import com.androidperformancestudio.ui.DesktopOpenFileDialog
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerMacOsToolbar
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.button.HomeButton
import com.androidperformancestudio.ui.chooseSaveFile
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.launch
import java.nio.file.Path

@Composable
@Suppress("LongParameterList")
fun FrameWindowScope.MemoryProfilerMainPage(
    language: UiLanguage = UiLanguage.ENGLISH,
    darkTheme: Boolean = isSystemInDarkTheme(),
    onBack: () -> Unit = {},
    highlightClassName: String? = null,
    initialImportFile: Path? = null,
    initialImportIsJavaHeap: Boolean = false,
) {
    val controller =
        remember(language) {
            MemoryProfilerController(DesktopMemoryProfilerBackend(language = language), language = language)
        }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val loaded = controller.loadedHeap
    var showHprofFileDialog by remember { mutableStateOf(false) }
    var showMappingFileDialog by remember { mutableStateOf(false) }
    var showNativeHeapFileDialog by remember { mutableStateOf(false) }
    var showJavaHeapFileDialog by remember { mutableStateOf(false) }

    LaunchedEffect(controller) { controller.refreshDevices() }
    LaunchedEffect(controller) { controller.refreshSessions() }
    LaunchedEffect(highlightClassName) {
        highlightClassName?.let(controller::highlightClass)
    }
    LaunchedEffect(initialImportFile, initialImportIsJavaHeap) {
        initialImportFile?.let { file ->
            if (initialImportIsJavaHeap) {
                controller.importJavaHeap(file)
            } else {
                controller.importHprof(file)
            }
        }
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
                recentSessions = controller.recentSessions,
                importMappingEnabled = !state.isDumping,
                importNativeHeapEnabled = !state.isDumping,
                importJavaHeapEnabled = !state.isDumping,
                exportNativeHeapEnabled = state.nativeHeapTrace != null,
            ),
        onImportHprof = { showHprofFileDialog = true },
        onImportMapping = { showMappingFileDialog = true },
        onImportNativeHeap = { showNativeHeapFileDialog = true },
        onImportJavaHeap = { showJavaHeapFileDialog = true },
        onExportNativeHeap = {
            chooseSaveFile(window, localizedStringResource(Res.string.export_memory_profiler_data, language), "native-heap.pb")
                ?.toPath()?.let(controller::exportNativeHeap)
        },
        onExportRawHprof = {
            chooseSaveFile(window, localizedStringResource(Res.string.export_memory_profiler_data, language), "heap-raw.hprof")
                ?.toPath()?.let(controller::exportRaw)
        },
        onExportStandardHprof = {
            chooseSaveFile(window, localizedStringResource(Res.string.export_memory_profiler_data, language), "heap-standard.hprof")
                ?.toPath()?.let(controller::exportConverted)
        },
        onExportCsv = {
            chooseSaveFile(window, localizedStringResource(Res.string.export_memory_profiler_data, language), "class-histogram.csv")
                ?.toPath()?.let(controller::exportHistogram)
        },
        onExportBitmapDump = {
            chooseSaveFile(window, localizedStringResource(Res.string.export_memory_profiler_data, language), "bitmap-dump.zip")
                ?.toPath()?.let(controller::exportBitmapSession)
        },
        onExportBitmapComparison = {
            chooseSaveFile(window, localizedStringResource(Res.string.export_memory_profiler_data, language), "bitmap-comparison.md")
                ?.toPath()?.let(controller::exportBitmapComparison)
        },
        onLoadSession = { metadata ->
            scope.launch { controller.loadSession(metadata) }
        },
    )

    ViewerTheme(darkTheme = darkTheme) {
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
                MemoryProfilerCaptureNativeHeapButton(
                    state = state,
                    onCaptureNativeHeap = { scope.launch { controller.captureNativeHeap() } },
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
                        onChangeViewMode = controller::changeViewMode,
                        onSelectClass = controller::selectClass,
                        onSelectInstance = controller::selectInstance,
                        onHeapFilterChange = controller::changeHeapFilter,
                        onClassScopeChange = controller::changeClassScope,
                        onLeakFilterChange = controller::changeLeakFilter,
                        onArrangeByChange = controller::changeArrangeBy,
                        onSearchChange = controller::changeSearchText,
                        onMatchCaseChange = controller::changeMatchCase,
                        onUseRegexChange = controller::changeUseRegex,
                        onClassifierSort = controller::sortClassifier,
                        onSelectClassifier = controller::selectClassifier,
                    ),
                language = language,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showHprofFileDialog) {
        DesktopOpenFileDialog(
            parent = window,
            title = localizedStringResource(Res.string.import_hprof, language),
            acceptFileName = { it.endsWith(".hprof", ignoreCase = true) },
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

    if (showMappingFileDialog) {
        DesktopOpenFileDialog(
            parent = window,
            title = localizedStringResource(Res.string.import_mapping, language),
            acceptFileName = { it == "mapping.txt" || it.endsWith(".txt") },
            onCloseRequest = { selectedFile ->
                showMappingFileDialog = false
                if (selectedFile != null) {
                    scope.launch {
                        controller.importMapping(selectedFile.toPath())
                    }
                }
            },
        )
    }

    if (showNativeHeapFileDialog) {
        DesktopOpenFileDialog(
            parent = window,
            title = localizedStringResource(Res.string.import_native_heap, language),
            acceptFileName = { it.endsWith(".pb", ignoreCase = true) },
            onCloseRequest = { selectedFile ->
                showNativeHeapFileDialog = false
                if (selectedFile != null) {
                    scope.launch {
                        controller.importNativeHeap(selectedFile.toPath())
                    }
                }
            },
        )
    }

    if (showJavaHeapFileDialog) {
        DesktopOpenFileDialog(
            parent = window,
            title = localizedStringResource(Res.string.import_java_heap, language),
            acceptFileName = {
                it.endsWith(".pb", ignoreCase = true) || it.endsWith(".perfetto-trace", ignoreCase = true)
            },
            onCloseRequest = { selectedFile ->
                showJavaHeapFileDialog = false
                if (selectedFile != null) {
                    scope.launch {
                        controller.importJavaHeap(selectedFile.toPath())
                    }
                }
            },
        )
    }
}
