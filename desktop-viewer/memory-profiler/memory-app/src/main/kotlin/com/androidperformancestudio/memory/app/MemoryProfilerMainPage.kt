@file:Suppress("FunctionName", "LongMethod", "MaxLineLength", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.memory.memory_app.generated.resources.Res
import com.androidperformancestudio.memory.memory_app.generated.resources.class_list_view
import com.androidperformancestudio.memory.memory_app.generated.resources.dashboard_view
import com.androidperformancestudio.memory.memory_app.generated.resources.diff_view
import com.androidperformancestudio.memory.memory_app.generated.resources.dominators_view
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
import com.androidperformancestudio.memory.presentation.MemoryProfilerViewMode
import com.androidperformancestudio.ui.DesktopOpenFileDialog
import com.androidperformancestudio.ui.HeaderSpacer
import com.androidperformancestudio.ui.HeaderToolbar
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.chooseSaveFile
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

@Composable
@Suppress("LongParameterList", "CyclomaticComplexMethod")
fun FrameWindowScope.MemoryProfilerMainPage(
    language: UiLanguage = UiLanguage.ENGLISH,
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
                exportClassInstancesEnabled = loaded != null && state.selectedClassName != null,
                exportObjectInvestigationEnabled = state.selectedInstanceDetail != null,
                exportDiffEnabled = state.heapDiff != null,
                exportSnapshotJsonEnabled = loaded != null,
                exportInvestigationReportEnabled = loaded != null,
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
                ?.toPath()
                ?.let(controller::exportNativeHeap)
        },
        onExportRawHprof = {
            chooseSaveFile(window, localizedStringResource(Res.string.export_memory_profiler_data, language), "heap-raw.hprof")
                ?.toPath()
                ?.let(controller::exportRaw)
        },
        onExportStandardHprof = {
            chooseSaveFile(window, localizedStringResource(Res.string.export_memory_profiler_data, language), "heap-standard.hprof")
                ?.toPath()
                ?.let(controller::exportConverted)
        },
        onExportCsv = {
            chooseSaveFile(window, localizedStringResource(Res.string.export_memory_profiler_data, language), "class-histogram.csv")
                ?.toPath()
                ?.let(controller::exportHistogram)
        },
        onExportClassInstances = {
            chooseSaveFile(window, localizedStringResource(Res.string.export_memory_profiler_data, language), "class-instances.csv")
                ?.toPath()
                ?.let(controller::exportSelectedClassInstances)
        },
        onExportObjectInvestigation = {
            chooseSaveFile(window, localizedStringResource(Res.string.export_memory_profiler_data, language), "object-investigation.json")
                ?.toPath()
                ?.let(controller::exportObjectInvestigation)
        },
        onExportDiff = {
            chooseSaveFile(window, localizedStringResource(Res.string.export_memory_profiler_data, language), "class-diff.csv")
                ?.toPath()
                ?.let(controller::exportHeapDiff)
        },
        onExportSnapshotJson = {
            chooseSaveFile(window, localizedStringResource(Res.string.export_memory_profiler_data, language), "heap-snapshot.json")
                ?.toPath()
                ?.let(controller::exportHeapSnapshotJson)
        },
        onExportInvestigationReport = {
            chooseSaveFile(window, localizedStringResource(Res.string.export_memory_profiler_data, language), "memory-investigation.md")
                ?.toPath()
                ?.let(controller::exportInvestigationReport)
        },
        onExportBitmapDump = {
            chooseSaveFile(window, localizedStringResource(Res.string.export_memory_profiler_data, language), "bitmap-dump.zip")
                ?.toPath()
                ?.let(controller::exportBitmapSession)
        },
        onExportBitmapComparison = {
            chooseSaveFile(window, localizedStringResource(Res.string.export_memory_profiler_data, language), "bitmap-comparison.md")
                ?.toPath()
                ?.let(controller::exportBitmapComparison)
        },
        onLoadSession = { metadata ->
            scope.launch { controller.loadSession(metadata) }
        },
    )

    Column(Modifier.fillMaxSize()) {
        HeaderToolbar(
            language = language,
            onNavigateHome = onBack,
            onNavigateSettings = null,
        ) {
            MemoryProfilerToolbarSelectors(
                state = state,
                onSelectDevice = { serial -> scope.launch { controller.selectDevice(serial) } },
                onSelectProcess = controller::selectProcess,
                language = language,
            )
            HeaderSpacer()
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.refresh_devices, language),
                onClick = { scope.launch { controller.refreshDevices() } },
            )
            HeaderSpacer()
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.dashboard_view, language),
                selected = state.viewMode == MemoryProfilerViewMode.Dashboard,
                onClick = { controller.changeViewMode(MemoryProfilerViewMode.Dashboard) },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.class_list_view, language),
                selected = state.viewMode == MemoryProfilerViewMode.ClassList,
                onClick = { controller.changeViewMode(MemoryProfilerViewMode.ClassList) },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.dominators_view, language),
                selected = state.viewMode == MemoryProfilerViewMode.Dominators,
                onClick = { controller.changeViewMode(MemoryProfilerViewMode.Dominators) },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.diff_view, language),
                selected = state.viewMode == MemoryProfilerViewMode.Diff,
                enabled = state.heapDiff != null,
                onClick = { controller.changeViewMode(MemoryProfilerViewMode.Diff) },
            )
            Spacer(Modifier.weight(1f))
            MemoryProfilerDumpHeapButton(
                state = state,
                onDumpHeap = { scope.launch { controller.dumpHeap() } },
                language = language,
            )
            HeaderSpacer()
            MemoryProfilerDumpBitmapsButton(
                state = state,
                onDumpBitmaps = { scope.launch { controller.dumpBitmaps() } },
                language = language,
            )
            HeaderSpacer()
            MemoryProfilerCaptureNativeHeapButton(
                state = state,
                onCaptureNativeHeap = { scope.launch { controller.captureNativeHeap() } },
                language = language,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        MemoryProfilerSnapshotTabs(
            state = state,
            onSelectSnapshot = controller::selectSnapshot,
            onCloseSnapshot = controller::closeSnapshot,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        MemoryProfilerScreen(
            state = state,
            actions =
                MemoryProfilerActions(
                    onSortHistogram = controller::sort,
                    onRetry = { scope.launch { controller.refreshDevices() } },
                    onCancelOperation = controller::cancelActiveOperation,
                    onHighlightClass = controller::highlightClass,
                    onChangeViewMode = controller::changeViewMode,
                    onSelectClass = controller::selectClass,
                    onSelectInstance = controller::selectInstance,
                    onCopyObjectId = { objectId ->
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(
                            StringSelection("0x${java.lang.Long.toHexString(objectId)}"),
                            null,
                        )
                    },
                    onLoadArrayRange = controller::loadArrayRange,
                    onNavigateInstanceBack = controller::navigateInstanceBack,
                    onNavigateInstanceForward = controller::navigateInstanceForward,
                    onTogglePinnedInstance = controller::togglePinnedInstance,
                    onHeapFilterChange = controller::changeHeapFilter,
                    onClassScopeChange = controller::changeClassScope,
                    onLeakFilterChange = controller::changeLeakFilter,
                    onArrangeByChange = controller::changeArrangeBy,
                    onSearchChange = controller::changeSearchText,
                    onMatchCaseChange = controller::changeMatchCase,
                    onUseRegexChange = controller::changeUseRegex,
                    onSaveFilterPreset = controller::saveFilterPreset,
                    onApplyFilterPreset = controller::applyFilterPreset,
                    onDeleteFilterPreset = controller::deleteActiveFilterPreset,
                    onToggleClassifierColumn = controller::toggleClassifierColumn,
                    onClassifierSort = controller::sortClassifier,
                    onSelectClassifier = controller::selectClassifier,
                ),
            language = language,
            modifier = Modifier.weight(1f),
        )
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

@Composable
private fun MemoryProfilerSnapshotTabs(
    state: com.androidperformancestudio.memory.presentation.MemoryProfilerState,
    onSelectSnapshot: (String) -> Unit,
    onCloseSnapshot: (String) -> Unit,
) {
    if (state.snapshotSummaries.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text("Snapshots", color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.snapshotSummaries.forEach { snapshot ->
            ProfilerCompactButton(
                text = "${snapshot.id} · ${snapshot.objectCount} obj · ${snapshot.warningCount} warn",
                selected = snapshot.id == state.activeSnapshotId,
                onClick = { onSelectSnapshot(snapshot.id) },
            )
            ProfilerCompactButton(
                text = "×",
                onClick = { onCloseSnapshot(snapshot.id) },
            )
        }
    }
}
