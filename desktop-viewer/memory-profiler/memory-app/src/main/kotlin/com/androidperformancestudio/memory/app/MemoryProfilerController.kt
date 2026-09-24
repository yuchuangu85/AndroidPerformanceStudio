@file:Suppress("MaxLineLength", "LongMethod", "ReturnCount", "MagicNumber")

package com.androidperformancestudio.memory.app

import com.androidperformancestudio.adb.AdbTargetSnapshot
import com.androidperformancestudio.adb.AndroidDeviceInfosResult
import com.androidperformancestudio.adb.AndroidProcessSelection
import com.androidperformancestudio.adb.AndroidTargetListener
import com.androidperformancestudio.adb.AndroidTargetSubscription
import com.androidperformancestudio.memory.analysis.HeapDiffAnalyzer
import com.androidperformancestudio.memory.analysis.InstanceQueryDetail
import com.androidperformancestudio.memory.analysis.InstanceQueryRow
import com.androidperformancestudio.memory.analysis.InstanceReferenceQuery
import com.androidperformancestudio.memory.analysis.ProguardMapping
import com.androidperformancestudio.memory.memory_app.generated.resources.Res
import com.androidperformancestudio.memory.memory_app.generated.resources.analyzing_memory_leaks
import com.androidperformancestudio.memory.memory_app.generated.resources.bitmap_dump_failed
import com.androidperformancestudio.memory.memory_app.generated.resources.capturing_native_heap
import com.androidperformancestudio.memory.memory_app.generated.resources.connecting_leak_canary_agent
import com.androidperformancestudio.memory.memory_app.generated.resources.dumping_bitmaps_for
import com.androidperformancestudio.memory.memory_app.generated.resources.dumping_heap_for
import com.androidperformancestudio.memory.memory_app.generated.resources.hprof_parser_out_of_memory
import com.androidperformancestudio.memory.memory_app.generated.resources.importing
import com.androidperformancestudio.memory.memory_app.generated.resources.importing_ad13e4da
import com.androidperformancestudio.memory.memory_app.generated.resources.importing_mapping
import com.androidperformancestudio.memory.memory_app.generated.resources.loading_session
import com.androidperformancestudio.memory.memory_app.generated.resources.mapping_imported
import com.androidperformancestudio.memory.memory_app.generated.resources.memory_leaks_analysis_failed
import com.androidperformancestudio.memory.memory_app.generated.resources.monitoring_leak_canary
import com.androidperformancestudio.memory.memory_app.generated.resources.unable_to_analyze_hprof
import com.androidperformancestudio.memory.memory_app.generated.resources.unable_to_capture_native_heap
import com.androidperformancestudio.memory.memory_app.generated.resources.unable_to_import_java_heap
import com.androidperformancestudio.memory.memory_app.generated.resources.unable_to_import_native_heap
import com.androidperformancestudio.memory.memory_app.generated.resources.unable_to_load_mapping
import com.androidperformancestudio.memory.model.BitmapDumpComparison
import com.androidperformancestudio.memory.model.BitmapDumpSession
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapCapability
import com.androidperformancestudio.memory.model.HeapDiffMatchMode
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapExportContext
import com.androidperformancestudio.memory.model.HeapHistogram
import com.androidperformancestudio.memory.model.HeapLoadPhase
import com.androidperformancestudio.memory.model.HeapObjectFieldEvidence
import com.androidperformancestudio.memory.model.HeapObjectInvestigation
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.memory.model.LeakCanaryLiveSession
import com.androidperformancestudio.memory.model.LeakCanaryLiveStatus
import com.androidperformancestudio.memory.model.LeakCanaryReport
import com.androidperformancestudio.memory.model.NativeHeapAnalysis
import com.androidperformancestudio.memory.model.NativeHeapTrace
import com.androidperformancestudio.memory.presentation.MemoryArrangeBy
import com.androidperformancestudio.memory.presentation.MemoryClassScope
import com.androidperformancestudio.memory.presentation.MemoryClassifierColumn
import com.androidperformancestudio.memory.presentation.MemoryClassifierRow
import com.androidperformancestudio.memory.presentation.MemoryDeviceOption
import com.androidperformancestudio.memory.presentation.MemoryDominatorRow
import com.androidperformancestudio.memory.presentation.MemoryFilterPreset
import com.androidperformancestudio.memory.presentation.MemoryHistogramSort
import com.androidperformancestudio.memory.presentation.MemoryInstanceDetail
import com.androidperformancestudio.memory.presentation.MemoryInstanceField
import com.androidperformancestudio.memory.presentation.MemoryInstanceRow
import com.androidperformancestudio.memory.presentation.MemoryLeakFilter
import com.androidperformancestudio.memory.presentation.MemoryProcessOption
import com.androidperformancestudio.memory.presentation.MemoryProfilerError
import com.androidperformancestudio.memory.presentation.MemoryProfilerState
import com.androidperformancestudio.memory.presentation.MemoryProfilerViewMode
import com.androidperformancestudio.memory.presentation.MemorySortDirection
import com.androidperformancestudio.memory.storage.MemorySessionFilterPreset
import com.androidperformancestudio.memory.storage.MemorySessionMetadata
import com.androidperformancestudio.memory.storage.MemorySessionUiSettings
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

internal data class LoadedHeap(
    val heapDump: HeapDump,
    val histogram: HeapHistogram,
    val warning: String? = null,
    val cleanupWarning: String? = null,
    val mapping: ProguardMapping? = null,
    val availableHeaps: List<String> = emptyList(),
    val perHeapClasses: Map<String, List<ClassStats>> = emptyMap(),
    val uiSettings: MemorySessionUiSettings = MemorySessionUiSettings(),
    val sourceFileDigest: String? = null,
    val mappingDigest: String? = null,
    val indexFile: Path? = null,
)

internal data class LoadedNativeHeap(
    val trace: NativeHeapTrace,
    val analysis: NativeHeapAnalysis,
)

internal data class LoadedBitmapDump(
    val session: BitmapDumpSession,
    val warning: String? = null,
    val cleanupWarning: String? = null,
)

internal sealed interface MemoryBackendResult<out T> {
    data class Success<T>(
        val value: T,
    ) : MemoryBackendResult<T>

    data class Failure(
        val title: String,
        val detail: String,
    ) : MemoryBackendResult<Nothing>
}

@Suppress("TooManyFunctions")
internal interface MemoryProfilerBackend {
    fun registerTargetListener(listener: AndroidTargetListener): AndroidTargetSubscription

    suspend fun listDevices(): MemoryBackendResult<List<MemoryDeviceOption>>

    suspend fun listProcesses(serial: String): MemoryBackendResult<List<MemoryProcessOption>>

    suspend fun capture(
        serial: String,
        process: MemoryProcessOption,
    ): MemoryBackendResult<LoadedHeap>

    suspend fun captureBitmaps(
        serial: String,
        process: MemoryProcessOption,
        onProgress: (Int) -> Unit = {},
    ): MemoryBackendResult<LoadedBitmapDump> =
        MemoryBackendResult.Failure("Bitmap dump unavailable", "The selected backend does not support Bitmap dumps.")

    suspend fun importHprof(file: Path): MemoryBackendResult<LoadedHeap>

    suspend fun importHprof(
        file: Path,
        onProgress: (Int) -> Unit,
    ): MemoryBackendResult<LoadedHeap> = importHprof(file)

    /**
     * Imports an R8/ProGuard mapping.txt and returns the re-analyzed heap when one was already
     * loaded, or null when only the mapping was stored.
     */
    suspend fun importMapping(file: Path): MemoryBackendResult<LoadedHeap?> =
        MemoryBackendResult.Failure("Mapping import unavailable", "The selected backend does not support mapping.txt.")

    suspend fun analyzeLeaks(): MemoryBackendResult<LeakCanaryReport> =
        MemoryBackendResult.Failure("Leak analysis unavailable", "The selected backend does not support LeakCanary analysis.")

    suspend fun injectLeakCanaryAgent(projectRoot: Path): MemoryBackendResult<String> =
        MemoryBackendResult.Failure("LeakCanary Agent unavailable", "The selected backend does not support source-project injection.")

    suspend fun openLeakCanaryAgent(
        serial: String,
        process: MemoryProcessOption,
    ): MemoryBackendResult<LeakCanaryLiveSession> =
        MemoryBackendResult.Failure("LeakCanary Agent unavailable", "The selected backend does not support live LeakCanary monitoring.")

    suspend fun pollLeakCanaryAgent(): MemoryBackendResult<LeakCanaryLiveSession> =
        MemoryBackendResult.Failure("LeakCanary Agent unavailable", "The selected backend does not support live LeakCanary monitoring.")

    fun closeLeakCanaryAgent() = Unit

    suspend fun listSessions(): MemoryBackendResult<List<MemorySessionMetadata>> = MemoryBackendResult.Success(emptyList())

    fun loadWorkspaceSettings(sessionId: String): MemorySessionUiSettings = MemorySessionUiSettings()

    fun saveWorkspaceSettings(
        sessionId: String,
        settings: MemorySessionUiSettings,
    ) = Unit

    suspend fun loadSession(metadata: MemorySessionMetadata): MemoryBackendResult<LoadedHeap> =
        importHprof(metadata.convertedHprofFile ?: metadata.rawHprofFile)

    fun exportRaw(
        heapDump: HeapDump,
        output: Path,
    )

    fun exportConverted(
        heapDump: HeapDump,
        output: Path,
    )

    fun exportHistogram(
        histogram: HeapHistogram,
        output: Path,
    )

    fun exportClassInstances(
        heapDump: HeapDump,
        className: String,
        output: Path,
    ) = Unit

    fun exportHeapDiff(
        diff: com.androidperformancestudio.memory.model.HeapDiff,
        output: Path,
    ) = Unit

    fun exportHeapSnapshotJson(
        heapDump: HeapDump,
        histogram: HeapHistogram,
        output: Path,
        snapshotSummary: HeapSnapshotSummary? = null,
        exportContext: HeapExportContext? = null,
    ) = Unit

    @Suppress("LongParameterList")
    fun exportInvestigationReport(
        heapDump: HeapDump,
        histogram: HeapHistogram,
        diff: com.androidperformancestudio.memory.model.HeapDiff?,
        output: Path,
        snapshotSummary: HeapSnapshotSummary? = null,
        exportContext: HeapExportContext? = null,
    ) = Unit

    fun exportObjectInvestigation(
        investigation: HeapObjectInvestigation,
        output: Path,
    ) = Unit

    fun exportBitmapSession(
        session: BitmapDumpSession,
        output: Path,
    ) = Unit

    fun exportBitmapComparison(
        comparison: BitmapDumpComparison,
        output: Path,
    ) = Unit

    suspend fun captureNativeHeap(
        serial: String,
        process: MemoryProcessOption,
    ): MemoryBackendResult<LoadedNativeHeap> =
        MemoryBackendResult.Failure(
            title = "Native heap capture unavailable",
            detail = "The selected backend does not support heapprofd captures.",
        )

    suspend fun importNativeHeap(file: Path): MemoryBackendResult<LoadedNativeHeap> =
        MemoryBackendResult.Failure(
            title = "Native heap import unavailable",
            detail = "The selected backend does not support importing native heap traces.",
        )

    suspend fun importJavaHeap(file: Path): MemoryBackendResult<LoadedHeap> =
        MemoryBackendResult.Failure(
            title = "Java heap import unavailable",
            detail = "The selected backend does not support importing java_hprof traces.",
        )

    fun exportNativeHeap(
        trace: NativeHeapTrace,
        output: Path,
    ) = Unit
}

private const val MAX_INSTANCE_HISTORY = 100
private const val MAX_SNAPSHOT_HISTORY = 8

@Suppress("TooManyFunctions", "LargeClass")
internal class MemoryProfilerController(
    private val backend: MemoryProfilerBackend,
    private val language: UiLanguage = UiLanguage.ENGLISH,
) {
    private val mutableState = MutableStateFlow(MemoryProfilerState())
    private var activeOperationJob: Job? = null

    val state: StateFlow<MemoryProfilerState> = mutableState.asStateFlow()

    var loadedHeap: LoadedHeap? = null
        private set

    /** Reachability/instance query rebuilt whenever a new heap dump is loaded. */
    var instanceQuery: InstanceReferenceQuery? = null
        private set

    var loadedBitmapDump: LoadedBitmapDump? = null
        private set

    var recentSessions: List<MemorySessionMetadata> = emptyList()
        private set

    private val loadedHeapsBySnapshotId = linkedMapOf<String, LoadedHeap>()
    private var previousBitmapDump: LoadedBitmapDump? = null
    private val processSelection = AndroidProcessSelection(MemoryProcessOption::pid)
    private val processSelectionSubscription =
        processSelection.register { choice ->
            mutableState.value =
                mutableState.value.copy(
                    selectedDeviceSerial = choice.serial,
                    processes = choice.processes,
                    selectedProcessId = choice.selectedPid,
                )
        }
    private val targetSubscription =
        backend.registerTargetListener(
            object : AndroidTargetListener {
                override fun onDevices(result: AndroidDeviceInfosResult) {
                    when (result) {
                        is com.androidperformancestudio.model.StudioResult.Failure ->
                            mutableState.value =
                                mutableState.value.copy(
                                    error =
                                        MemoryProfilerError(
                                            title = "Unable to list Android devices",
                                            detail = result.error.message,
                                        ),
                                )
                        is com.androidperformancestudio.model.StudioResult.Success ->
                            mutableState.value =
                                mutableState.value.copy(
                                    devices =
                                        result.value.map { device ->
                                            MemoryDeviceOption(
                                                serial = device.serial,
                                                name = device.displayName,
                                                online = device.online,
                                            )
                                        },
                                    error = null,
                                )
                    }
                }

                override fun onTargetSnapshot(
                    serial: String,
                    result: com.androidperformancestudio.model.StudioResult<AdbTargetSnapshot>,
                ) {
                    if (serial != mutableState.value.selectedDeviceSerial) return
                    when (result) {
                        is com.androidperformancestudio.model.StudioResult.Failure ->
                            mutableState.value =
                                mutableState.value.copy(
                                    error =
                                        MemoryProfilerError(
                                            title = "Unable to list device processes",
                                            detail = result.error.message,
                                        ),
                                )
                        is com.androidperformancestudio.model.StudioResult.Success -> {
                            processSelection.updateProcesses(serial, result.value.heapDumpableProcesses())
                            mutableState.value = mutableState.value.copy(error = null)
                        }
                    }
                }
            },
        )

    fun close() {
        processSelectionSubscription.close()
        targetSubscription.close()
    }

    suspend fun refreshDevices() {
        when (val result = backend.listDevices()) {
            is MemoryBackendResult.Failure -> showFailure(result)
            is MemoryBackendResult.Success -> {
                val currentSerial = mutableState.value.selectedDeviceSerial
                val retainedSerial =
                    currentSerial?.takeIf { serial -> result.value.any { it.serial == serial && it.online } }
                val automaticSerial =
                    retainedSerial ?: result.value
                        .filter(MemoryDeviceOption::online)
                        .singleOrNull()
                        ?.serial
                if (retainedSerial == null) processSelection.selectDevice(null)
                mutableState.value = mutableState.value.copy(devices = result.value, error = null)
                if (retainedSerial == null && automaticSerial != null) {
                    selectDevice(automaticSerial)
                }
            }
        }
    }

    private fun markOperationCancelled() {
        activeOperationJob = null
        if (mutableState.value.isDumping) {
            mutableState.value =
                mutableState.value.copy(
                    isDumping = false,
                    operationMessage = null,
                    loadPhase = HeapLoadPhase.CANCELLED,
                    loadProgress = null,
                )
        }
    }

    fun cancelActiveOperation() {
        if (!mutableState.value.isDumping) return
        activeOperationJob?.cancel()
        mutableState.value =
            mutableState.value.copy(
                isDumping = false,
                operationMessage = null,
                loadPhase = HeapLoadPhase.CANCELLED,
                loadProgress = null,
            )
    }

    suspend fun selectDevice(serial: String) {
        processSelection.selectDevice(serial)
        mutableState.value = mutableState.value.copy(error = null)
        val result = backend.listProcesses(serial)
        if (processSelection.snapshot.serial != serial) return
        when (result) {
            is MemoryBackendResult.Failure -> showFailure(result)
            is MemoryBackendResult.Success -> {
                processSelection.updateProcesses(serial, result.value)
                mutableState.value = mutableState.value.copy(error = null)
            }
        }
    }

    fun selectProcess(pid: Int) {
        if (processSelection.selectProcess(pid)) {
            mutableState.value = mutableState.value.copy(error = null)
        }
    }

    fun selectSnapshot(snapshotId: String) {
        val heap = loadedHeapsBySnapshotId[snapshotId] ?: return
        applyLoadedResult(MemoryBackendResult.Success(heap), compareWithPrevious = false, appendSnapshot = false)
    }

    fun closeSnapshot(snapshotId: String) {
        val current = mutableState.value
        val remaining = current.snapshotSummaries.filterNot { it.id == snapshotId }
        loadedHeapsBySnapshotId.remove(snapshotId)
        if (current.activeSnapshotId != snapshotId) {
            mutableState.value = current.copy(snapshotSummaries = remaining)
            return
        }
        val nextSnapshot = remaining.lastOrNull()
        if (nextSnapshot == null) {
            loadedHeap = null
            instanceQuery = null
            mutableState.value =
                current.copy(
                    snapshotSummary = null,
                    snapshotSummaries = emptyList(),
                    activeSnapshotId = null,
                    selectedClassName = null,
                    selectedClassifierId = null,
                    selectedClassifierLabel = null,
                    selectedClassInstances = emptyList(),
                    selectedInstanceDetail = null,
                    dominatorRows = emptyList(),
                    instanceHistory = emptyList(),
                    instanceHistoryIndex = -1,
                    pinnedInstanceIds = emptySet(),
                )
        } else {
            mutableState.value = current.copy(snapshotSummaries = remaining)
            selectSnapshot(nextSnapshot.id)
        }
    }

    fun sort(sort: MemoryHistogramSort) {
        mutableState.value = mutableState.value.copy(sort = sort)
    }

    fun highlightClass(className: String) {
        mutableState.value = mutableState.value.copy(highlightedClassName = className)
    }

    fun changeViewMode(mode: MemoryProfilerViewMode) {
        mutableState.value = mutableState.value.copy(viewMode = mode)
    }

    fun selectClass(className: String) {
        val instances =
            instanceQuery
                ?.instancesOf(className, heapName = mutableState.value.heapFilter)
                .orEmpty()
                .map { it.toPresentation() }
        mutableState.value =
            mutableState.value.copy(
                selectedClassName = className,
                selectedClassifierId = "class:$className",
                selectedClassifierLabel = className,
                selectedClassInstances = instances,
                selectedInstanceDetail = null,
                instanceHistory = emptyList(),
                instanceHistoryIndex = -1,
            )
    }

    fun selectClassifier(row: MemoryClassifierRow) {
        val classNames = row.classNames()
        val instances =
            classNames
                .flatMap { className ->
                    instanceQuery
                        ?.instancesOf(className, heapName = mutableState.value.heapFilter)
                        .orEmpty()
                }.sortedBy { it.objectId }
                .map { it.toPresentation() }
        mutableState.value =
            mutableState.value.copy(
                selectedClassName = row.className,
                selectedClassifierId = row.id,
                selectedClassifierLabel = row.label,
                selectedClassInstances = instances,
                selectedInstanceDetail = null,
                instanceHistory = emptyList(),
                instanceHistoryIndex = -1,
            )
    }

    fun selectInstance(objectId: Long) {
        selectInstance(objectId, appendToHistory = true)
    }

    fun loadArrayRange(
        objectId: Long,
        arrayStart: Int,
    ) {
        selectInstance(
            objectId,
            appendToHistory = false,
            arrayStart = arrayStart,
        )
    }

    fun navigateInstanceBack() {
        val current = mutableState.value
        if (current.instanceHistoryIndex <= 0) return
        selectInstance(
            current.instanceHistory[current.instanceHistoryIndex - 1],
            appendToHistory = false,
            historyIndex = current.instanceHistoryIndex - 1,
        )
    }

    fun navigateInstanceForward() {
        val current = mutableState.value
        val nextIndex = current.instanceHistoryIndex + 1
        if (nextIndex !in current.instanceHistory.indices) return
        selectInstance(current.instanceHistory[nextIndex], appendToHistory = false, historyIndex = nextIndex)
    }

    fun togglePinnedInstance(objectId: Long) {
        val pinned = mutableState.value.pinnedInstanceIds.toMutableSet()
        if (!pinned.add(objectId)) pinned.remove(objectId)
        mutableState.value = mutableState.value.copy(pinnedInstanceIds = pinned)
    }

    private fun selectInstance(
        objectId: Long,
        appendToHistory: Boolean,
        historyIndex: Int? = null,
        arrayStart: Int = 0,
    ) {
        val boundedStart = arrayStart.coerceAtLeast(0)
        val detail = instanceQuery?.detailOf(objectId, arrayStart = boundedStart)?.toPresentation(arrayStart = boundedStart) ?: return
        val current = mutableState.value
        val instances =
            instanceQuery
                ?.instancesOf(detail.className, heapName = current.heapFilter)
                .orEmpty()
                .map { it.toPresentation() }
        val nextHistory =
            if (appendToHistory) {
                (current.instanceHistory.take(current.instanceHistoryIndex + 1) + objectId).takeLast(MAX_INSTANCE_HISTORY)
            } else {
                current.instanceHistory
            }
        mutableState.value =
            current.copy(
                selectedClassName = detail.className,
                selectedClassifierId = "class:${detail.className}",
                selectedClassifierLabel = detail.className,
                selectedClassInstances = instances,
                selectedInstanceDetail = detail,
                instanceHistory = nextHistory,
                instanceHistoryIndex = historyIndex ?: nextHistory.lastIndex,
            )
    }

    /** Switches the base class table between all heaps and a single [heap] (null = all heaps). */
    fun changeHeapFilter(heap: String?) {
        val base =
            when (heap) {
                null -> loadedHeap?.histogram?.classes.orEmpty()
                else -> loadedHeap?.perHeapClasses?.get(heap).orEmpty()
            }
        mutableState.value =
            mutableState.value.copy(
                heapFilter = heap,
                heapBaseClasses = base,
                selectedClassName = null,
                selectedClassifierId = null,
                selectedClassifierLabel = null,
                selectedClassInstances = emptyList(),
                selectedInstanceDetail = null,
                instanceHistory = emptyList(),
                instanceHistoryIndex = -1,
            )
        persistWorkspaceSettings()
    }

    fun changeClassScope(scope: MemoryClassScope) {
        mutableState.value = mutableState.value.copy(classScope = scope)
        persistWorkspaceSettings()
    }

    fun changeLeakFilter(filter: MemoryLeakFilter) {
        mutableState.value = mutableState.value.copy(leakFilter = filter)
        persistWorkspaceSettings()
    }

    fun changeArrangeBy(arrangeBy: MemoryArrangeBy) {
        mutableState.value = mutableState.value.copy(arrangeBy = arrangeBy)
        persistWorkspaceSettings()
    }

    fun changeSearchText(text: String) {
        mutableState.value = mutableState.value.copy(searchText = text)
        persistWorkspaceSettings()
    }

    fun changeMatchCase(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(matchCase = enabled)
        persistWorkspaceSettings()
    }

    fun changeUseRegex(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(useRegex = enabled)
        persistWorkspaceSettings()
    }

    fun saveFilterPreset() {
        val current = mutableState.value
        val nextIndex = current.savedFilterPresets.size + 1
        val preset =
            MemoryFilterPreset(
                id = "filter-$nextIndex",
                label = "Filter $nextIndex",
                heapFilter = current.heapFilter,
                classScope = current.classScope,
                leakFilter = current.leakFilter,
                arrangeBy = current.arrangeBy,
                searchText = current.searchText,
                matchCase = current.matchCase,
                useRegex = current.useRegex,
            )
        mutableState.value =
            current.copy(
                savedFilterPresets = current.savedFilterPresets + preset,
                activeFilterPresetId = preset.id,
            )
        persistWorkspaceSettings()
    }

    fun applyFilterPreset(preset: MemoryFilterPreset) {
        if (preset !in mutableState.value.savedFilterPresets) return
        val base =
            when (preset.heapFilter) {
                null -> loadedHeap?.histogram?.classes.orEmpty()
                else -> loadedHeap?.perHeapClasses?.get(preset.heapFilter).orEmpty()
            }
        mutableState.value =
            mutableState.value.copy(
                heapFilter = preset.heapFilter,
                classScope = preset.classScope,
                leakFilter = preset.leakFilter,
                arrangeBy = preset.arrangeBy,
                searchText = preset.searchText,
                matchCase = preset.matchCase,
                useRegex = preset.useRegex,
                activeFilterPresetId = preset.id,
                heapBaseClasses = base,
                selectedClassName = null,
                selectedClassifierId = null,
                selectedClassifierLabel = null,
                selectedClassInstances = emptyList(),
                selectedInstanceDetail = null,
                instanceHistory = emptyList(),
                instanceHistoryIndex = -1,
            )
        persistWorkspaceSettings()
    }

    fun deleteActiveFilterPreset() {
        val current = mutableState.value
        val activeId = current.activeFilterPresetId ?: return
        mutableState.value =
            current.copy(
                savedFilterPresets = current.savedFilterPresets.filterNot { it.id == activeId },
                activeFilterPresetId = null,
            )
        persistWorkspaceSettings()
    }

    fun toggleClassifierColumn(column: MemoryClassifierColumn) {
        if (column == MemoryClassifierColumn.NAME) return
        val current = mutableState.value.visibleClassifierColumns.toMutableSet()
        if (!current.add(column)) current.remove(column)
        mutableState.value = mutableState.value.copy(visibleClassifierColumns = current)
        persistWorkspaceSettings()
    }

    fun sortClassifier(column: MemoryClassifierColumn) {
        val current = mutableState.value
        val direction =
            if (current.classifierSortColumn == column) {
                if (current.classifierSortDirection == MemorySortDirection.ASCENDING) {
                    MemorySortDirection.DESCENDING
                } else {
                    MemorySortDirection.ASCENDING
                }
            } else if (column == MemoryClassifierColumn.NAME || column == MemoryClassifierColumn.MODULE_NAME) {
                MemorySortDirection.ASCENDING
            } else {
                MemorySortDirection.DESCENDING
            }
        mutableState.value = current.copy(classifierSortColumn = column, classifierSortDirection = direction)
    }

    suspend fun dumpHeap() {
        activeOperationJob = currentCoroutineContext()[Job]
        val snapshot = mutableState.value
        val serial = snapshot.selectedDeviceSerial ?: return
        val process = snapshot.processes.firstOrNull { it.pid == snapshot.selectedProcessId } ?: return
        mutableState.value =
            snapshot.copy(
                isDumping = true,
                operationMessage = localizedStringResource(Res.string.dumping_heap_for, language, process.name),
                loadPhase = HeapLoadPhase.ANALYZE,
                loadProgress = null,
                error = null,
                warning = null,
                cleanupWarning = null,
            )
        val result =
            try {
                backend.capture(serial, process)
            } catch (exception: CancellationException) {
                markOperationCancelled()
                throw exception
            }
        applyLoadedResult(result)
    }

    suspend fun injectLeakCanaryAgent(projectRoot: Path) {
        when (val result = backend.injectLeakCanaryAgent(projectRoot)) {
            is MemoryBackendResult.Failure ->
                mutableState.value =
                    mutableState.value.copy(
                        error = MemoryProfilerError(result.title, result.detail),
                    )
            is MemoryBackendResult.Success ->
                mutableState.value =
                    mutableState.value.copy(
                        operationMessage = result.value,
                        error = null,
                    )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun monitorLiveLeakCanary() {
        activeOperationJob = currentCoroutineContext()[Job]
        val snapshot = mutableState.value
        val serial = snapshot.selectedDeviceSerial ?: return
        val process = snapshot.processes.firstOrNull { it.pid == snapshot.selectedProcessId } ?: return
        mutableState.value =
            snapshot.copy(
                isDumping = true,
                operationMessage = localizedStringResource(Res.string.connecting_leak_canary_agent, language),
                loadPhase = HeapLoadPhase.ANALYZE,
                loadProgress = null,
                error = null,
                warning = null,
                leakCanaryLiveSession = snapshot.leakCanaryLiveSession.copy(status = LeakCanaryLiveStatus.CONNECTING),
            )
        when (val opened = backend.openLeakCanaryAgent(serial, process)) {
            is MemoryBackendResult.Failure -> {
                showFailure(opened)
                mutableState.value =
                    mutableState.value.copy(
                        leakCanaryLiveSession = LeakCanaryLiveSession(status = LeakCanaryLiveStatus.FAILED, message = opened.detail),
                    )
                return
            }
            is MemoryBackendResult.Success ->
                mutableState.value =
                    mutableState.value.copy(
                        leakCanaryLiveSession = opened.value.copy(status = LeakCanaryLiveStatus.RUNNING),
                        operationMessage = localizedStringResource(Res.string.monitoring_leak_canary, language),
                        error = null,
                    )
        }
        try {
            while (currentCoroutineContext().isActive) {
                kotlinx.coroutines.delay(500L)
                when (val polled = backend.pollLeakCanaryAgent()) {
                    is MemoryBackendResult.Failure -> {
                        mutableState.value =
                            mutableState.value.copy(
                                isDumping = false,
                                operationMessage = null,
                                loadPhase = HeapLoadPhase.FAILED,
                                leakCanaryLiveSession =
                                    mutableState.value.leakCanaryLiveSession.copy(
                                        status = LeakCanaryLiveStatus.FAILED,
                                        message = polled.detail,
                                    ),
                                error = MemoryProfilerError(polled.title, polled.detail),
                            )
                        return
                    }
                    is MemoryBackendResult.Success ->
                        mutableState.value =
                            mutableState.value.copy(
                                leakCanaryLiveSession = polled.value.copy(status = LeakCanaryLiveStatus.RUNNING),
                                error = null,
                            )
                }
            }
        } finally {
            backend.closeLeakCanaryAgent()
            activeOperationJob = null
            mutableState.value =
                mutableState.value.copy(
                    isDumping = false,
                    operationMessage = null,
                    loadPhase = HeapLoadPhase.IDLE,
                    leakCanaryLiveSession = mutableState.value.leakCanaryLiveSession.copy(status = LeakCanaryLiveStatus.STOPPED),
                )
        }
    }

    fun stopLiveLeakCanary() {
        backend.closeLeakCanaryAgent()
        activeOperationJob?.cancel()
        mutableState.value =
            mutableState.value.copy(
                isDumping = false,
                operationMessage = null,
                loadPhase = HeapLoadPhase.IDLE,
                leakCanaryLiveSession = mutableState.value.leakCanaryLiveSession.copy(status = LeakCanaryLiveStatus.STOPPED),
            )
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun analyzeLeaks() {
        activeOperationJob = currentCoroutineContext()[Job]
        val snapshot = mutableState.value
        if (snapshot.snapshotSummary == null) return
        mutableState.value =
            snapshot.copy(
                isDumping = true,
                operationMessage = localizedStringResource(Res.string.analyzing_memory_leaks, language),
                loadPhase = HeapLoadPhase.ANALYZE,
                loadProgress = null,
                error = null,
                warning = null,
            )
        val result =
            try {
                backend.analyzeLeaks()
            } catch (exception: CancellationException) {
                markOperationCancelled()
                throw exception
            } catch (exception: Exception) {
                MemoryBackendResult.Failure(
                    title = localizedStringResource(Res.string.memory_leaks_analysis_failed, language),
                    detail = exception.message ?: exception::class.simpleName.orEmpty(),
                )
            }
        when (result) {
            is MemoryBackendResult.Failure -> showFailure(result)
            is MemoryBackendResult.Success -> {
                activeOperationJob = null
                mutableState.value =
                    mutableState.value.copy(
                        isDumping = false,
                        operationMessage = null,
                        loadPhase = HeapLoadPhase.IDLE,
                        loadProgress = 100,
                        leakCanaryReport = result.value,
                        error = null,
                    )
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun dumpBitmaps() {
        activeOperationJob = currentCoroutineContext()[Job]
        val snapshot = mutableState.value
        val serial = snapshot.selectedDeviceSerial ?: return
        val process = snapshot.processes.firstOrNull { it.pid == snapshot.selectedProcessId } ?: return
        mutableState.value =
            snapshot.copy(
                isDumping = true,
                operationMessage = localizedStringResource(Res.string.dumping_bitmaps_for, language, process.name, 0),
                error = null,
                warning = null,
                cleanupWarning = null,
            )
        val result =
            try {
                backend.captureBitmaps(serial, process) { progress ->
                    mutableState.value =
                        mutableState.value.copy(
                            operationMessage = localizedStringResource(Res.string.dumping_bitmaps_for, language, process.name, progress),
                        )
                }
            } catch (exception: CancellationException) {
                markOperationCancelled()
                throw exception
            } catch (exception: Exception) {
                MemoryBackendResult.Failure(
                    title = localizedStringResource(Res.string.bitmap_dump_failed, language),
                    detail = exception.message ?: exception::class.simpleName.orEmpty(),
                )
            }
        applyBitmapResult(result)
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun captureNativeHeap() {
        mutableState.value = mutableState.value.copy(viewMode = MemoryProfilerViewMode.NativeHeap)
        activeOperationJob = currentCoroutineContext()[Job]
        val snapshot = mutableState.value
        val serial = snapshot.selectedDeviceSerial ?: return
        val process = snapshot.processes.firstOrNull { it.pid == snapshot.selectedProcessId } ?: return
        mutableState.value =
            snapshot.copy(
                isDumping = true,
                operationMessage = localizedStringResource(Res.string.capturing_native_heap, language, process.name),
                error = null,
            )
        val result =
            try {
                backend.captureNativeHeap(serial, process)
            } catch (exception: CancellationException) {
                markOperationCancelled()
                throw exception
            } catch (exception: Exception) {
                MemoryBackendResult.Failure(
                    title = localizedStringResource(Res.string.unable_to_capture_native_heap, language),
                    detail = exception.message ?: exception::class.simpleName.orEmpty(),
                )
            }
        applyNativeHeapResult(result)
    }

    fun exportNativeHeap(output: Path) {
        mutableState.value.nativeHeapTrace?.let { backend.exportNativeHeap(it, output) }
    }

    private fun applyNativeHeapResult(result: MemoryBackendResult<LoadedNativeHeap>) {
        when (result) {
            is MemoryBackendResult.Failure -> showFailure(result)
            is MemoryBackendResult.Success -> {
                activeOperationJob = null
                mutableState.value =
                    mutableState.value.copy(
                        isDumping = false,
                        operationMessage = null,
                        loadPhase = HeapLoadPhase.IDLE,
                        loadProgress = 100,
                        error = null,
                        nativeHeapTrace = result.value.trace,
                        nativeHeapAnalysis = result.value.analysis,
                        artifact = result.value.trace.artifact,
                    )
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun importHprof(file: Path) {
        activeOperationJob = currentCoroutineContext()[Job]
        mutableState.value =
            mutableState.value.copy(
                isDumping = true,
                operationMessage = localizedStringResource(Res.string.importing, language, file.fileName),
                loadPhase = HeapLoadPhase.PROBE,
                loadProgress = 0,
                error = null,
                warning = null,
                cleanupWarning = null,
            )
        val result =
            try {
                backend.importHprof(file) { progress ->
                    mutableState.value =
                        mutableState.value.copy(
                            operationMessage = localizedStringResource(Res.string.importing_ad13e4da, language, file.fileName, progress),
                        )
                }
            } catch (exception: CancellationException) {
                markOperationCancelled()
                throw exception
            } catch (_: OutOfMemoryError) {
                MemoryBackendResult.Failure(
                    title = localizedStringResource(Res.string.unable_to_analyze_hprof, language),
                    detail = localizedStringResource(Res.string.hprof_parser_out_of_memory, language),
                )
            } catch (exception: Exception) {
                MemoryBackendResult.Failure(
                    title = localizedStringResource(Res.string.unable_to_analyze_hprof, language),
                    detail = exception.message ?: exception::class.simpleName.orEmpty(),
                )
            }
        applyLoadedResult(result)
        activeOperationJob = null
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun importMapping(file: Path) {
        activeOperationJob = currentCoroutineContext()[Job]
        mutableState.value =
            mutableState.value.copy(
                isDumping = true,
                operationMessage = localizedStringResource(Res.string.importing_mapping, language, file.fileName),
                error = null,
            )
        val result =
            try {
                backend.importMapping(file)
            } catch (exception: CancellationException) {
                markOperationCancelled()
                throw exception
            } catch (exception: Exception) {
                MemoryBackendResult.Failure(
                    title = localizedStringResource(Res.string.unable_to_load_mapping, language),
                    detail = exception.message ?: exception::class.simpleName.orEmpty(),
                )
            }
        when (result) {
            is MemoryBackendResult.Failure -> showFailure(result)
            is MemoryBackendResult.Success ->
                when (val heap = result.value) {
                    null -> {
                        mutableState.value =
                            mutableState.value.copy(
                                isDumping = false,
                                operationMessage = null,
                                error = null,
                                mappingLoaded = true,
                                warning = localizedStringResource(Res.string.mapping_imported, language),
                            )
                        activeOperationJob = null
                    }
                    else -> applyLoadedResult(MemoryBackendResult.Success(heap), compareWithPrevious = false)
                }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun importNativeHeap(file: Path) {
        mutableState.value = mutableState.value.copy(viewMode = MemoryProfilerViewMode.NativeHeap)
        activeOperationJob = currentCoroutineContext()[Job]
        mutableState.value =
            mutableState.value.copy(
                isDumping = true,
                operationMessage = localizedStringResource(Res.string.importing, language, file.fileName),
                error = null,
            )
        val result =
            try {
                backend.importNativeHeap(file)
            } catch (exception: CancellationException) {
                markOperationCancelled()
                throw exception
            } catch (exception: Exception) {
                MemoryBackendResult.Failure(
                    title = localizedStringResource(Res.string.unable_to_import_native_heap, language),
                    detail = exception.message ?: exception::class.simpleName.orEmpty(),
                )
            }
        applyNativeHeapResult(result)
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun importJavaHeap(file: Path) {
        activeOperationJob = currentCoroutineContext()[Job]
        mutableState.value =
            mutableState.value.copy(
                isDumping = true,
                operationMessage = localizedStringResource(Res.string.importing, language, file.fileName),
                error = null,
            )
        val result =
            try {
                backend.importJavaHeap(file)
            } catch (exception: CancellationException) {
                markOperationCancelled()
                throw exception
            } catch (exception: Exception) {
                MemoryBackendResult.Failure(
                    title = localizedStringResource(Res.string.unable_to_import_java_heap, language),
                    detail = exception.message ?: exception::class.simpleName.orEmpty(),
                )
            }
        applyLoadedResult(result)
    }

    suspend fun refreshSessions() {
        when (val result = backend.listSessions()) {
            is MemoryBackendResult.Failure -> Unit
            is MemoryBackendResult.Success -> recentSessions = result.value
        }
    }

    suspend fun loadSession(metadata: MemorySessionMetadata) {
        activeOperationJob = currentCoroutineContext()[Job]
        mutableState.value =
            mutableState.value.copy(
                isDumping = true,
                operationMessage = localizedStringResource(Res.string.loading_session, language, metadata.packageName),
                loadPhase = HeapLoadPhase.PERSIST,
                loadProgress = null,
                error = null,
                warning = null,
                cleanupWarning = null,
            )
        val result =
            try {
                backend.loadSession(metadata)
            } catch (exception: CancellationException) {
                markOperationCancelled()
                throw exception
            }
        applyLoadedResult(result)
    }

    fun exportRaw(output: Path) {
        loadedHeap?.let { backend.exportRaw(it.heapDump, output) }
    }

    fun exportConverted(output: Path) {
        loadedHeap?.let { backend.exportConverted(it.heapDump, output) }
    }

    fun exportHistogram(output: Path) {
        loadedHeap?.let { backend.exportHistogram(it.histogram, output) }
    }

    fun exportSelectedClassInstances(output: Path) {
        val className = mutableState.value.selectedClassName ?: return
        loadedHeap?.let { backend.exportClassInstances(it.heapDump, className, output) }
    }

    fun exportHeapDiff(output: Path) {
        mutableState.value.heapDiff?.let { backend.exportHeapDiff(it, output) }
    }

    fun exportHeapSnapshotJson(output: Path) {
        loadedHeap?.let { backend.exportHeapSnapshotJson(it.heapDump, it.histogram, output, mutableState.value.snapshotSummary) }
    }

    fun exportInvestigationReport(output: Path) {
        loadedHeap?.let {
            backend.exportInvestigationReport(
                it.heapDump,
                it.histogram,
                mutableState.value.heapDiff,
                output,
                mutableState.value.snapshotSummary,
                currentExportContext(),
            )
        }
    }

    fun exportObjectInvestigation(output: Path) {
        val objectId = mutableState.value.selectedInstanceDetail?.objectId ?: return
        instanceQuery?.detailOf(objectId)?.toEvidence()?.let { backend.exportObjectInvestigation(it, output) }
    }

    fun exportBitmapSession(output: Path) {
        loadedBitmapDump?.let { backend.exportBitmapSession(it.session, output) }
    }

    fun exportBitmapComparison(output: Path) {
        mutableState.value.bitmapDumpComparison?.let { backend.exportBitmapComparison(it, output) }
    }

    @Suppress("LongMethod")
    private fun applyLoadedResult(
        result: MemoryBackendResult<LoadedHeap>,
        compareWithPrevious: Boolean = true,
        appendSnapshot: Boolean = true,
    ) {
        when (result) {
            is MemoryBackendResult.Failure -> showFailure(result)
            is MemoryBackendResult.Success -> {
                activeOperationJob = null
                val previous = loadedHeap
                loadedHeap = result.value
                instanceQuery = InstanceReferenceQuery(result.value.heapDump)
                val snapshotSummary =
                    result.value.heapDump.toSnapshotSummary(
                        result.value.histogram,
                        result.value.mapping != null,
                        result.value.warning != null,
                        result.value.sourceFileDigest,
                        result.value.mappingDigest,
                        result.value.indexFile,
                    )
                loadedHeapsBySnapshotId[snapshotSummary.id] = result.value
                val snapshotSummaries =
                    if (appendSnapshot) {
                        (
                            mutableState.value.snapshotSummaries.filterNot { it.id == snapshotSummary.id } + snapshotSummary
                        ).takeLast(MAX_SNAPSHOT_HISTORY)
                    } else {
                        mutableState.value.snapshotSummaries
                    }
                mutableState.value =
                    mutableState.value.copy(
                        summary = result.value.histogram.summary,
                        classes = result.value.histogram.classes,
                        activityCount =
                            result.value.histogram.classes
                                .filter { it.className.endsWith("Activity") }
                                .sumOf { it.instanceCount },
                        leakSuspects = result.value.heapDump.leakSuspects,
                        isDumping = false,
                        operationMessage = null,
                        loadPhase = HeapLoadPhase.IDLE,
                        loadProgress = 100,
                        snapshotSummary = snapshotSummary,
                        snapshotSummaries = snapshotSummaries,
                        activeSnapshotId = snapshotSummary.id,
                        savedFilterPresets =
                            result.value.uiSettings.filterPresets
                                .map { it.toPresentation() },
                        activeFilterPresetId = null,
                        visibleClassifierColumns =
                            result.value.uiSettings.visibleColumns
                                .mapNotNull { name -> runCatching { MemoryClassifierColumn.valueOf(name) }.getOrNull() }
                                .ifEmpty { MemoryClassifierColumn.entries.toSet() }
                                .toSet(),
                        error = null,
                        warning = result.value.warning,
                        cleanupWarning = result.value.cleanupWarning,
                        artifact = result.value.heapDump.artifact,
                        heapDiff =
                            previous?.takeIf { compareWithPrevious }?.let {
                                HeapDiffAnalyzer().diff(
                                    it.histogram.classes,
                                    result.value.histogram.classes,
                                    HeapDiffMatchMode.CLASS_NAME,
                                )
                            },
                        bitmapInstances = result.value.heapDump.bitmapInstances,
                        activityLeaks = result.value.heapDump.activityLeaks,
                        leakCanaryReport = result.value.heapDump.leakCanaryReport,
                        mappingLoaded = result.value.mapping != null,
                        selectedClassName = null,
                        selectedClassifierId = null,
                        selectedClassifierLabel = null,
                        selectedClassInstances = emptyList(),
                        selectedInstanceDetail = null,
                        dominatorRows = dominatorRows(result.value.heapDump),
                        instanceHistory = emptyList(),
                        instanceHistoryIndex = -1,
                        pinnedInstanceIds = emptySet(),
                        availableHeaps = result.value.availableHeaps,
                        heapFilter = null,
                        heapBaseClasses = result.value.histogram.classes,
                        classScope = MemoryClassScope.ALL,
                        leakFilter = MemoryLeakFilter.NONE,
                        arrangeBy = MemoryArrangeBy.CLASS,
                        availableArrangeBy = classifierGroupings(result.value.histogram.classes),
                        classifierSortColumn = MemoryClassifierColumn.NAME,
                        classifierSortDirection = MemorySortDirection.ASCENDING,
                        searchText = "",
                        matchCase = false,
                        useRegex = false,
                    )
            }
        }
    }

    private fun applyBitmapResult(result: MemoryBackendResult<LoadedBitmapDump>) {
        when (result) {
            is MemoryBackendResult.Failure -> showFailure(result)
            is MemoryBackendResult.Success -> {
                activeOperationJob = null
                previousBitmapDump = loadedBitmapDump
                loadedBitmapDump = result.value
                val comparison =
                    previousBitmapDump?.let { previous ->
                        com.androidperformancestudio.memory.analysis
                            .BitmapDumpAnalyzer()
                            .compare(previous.session, result.value.session)
                    }
                mutableState.value =
                    mutableState.value.copy(
                        isDumping = false,
                        operationMessage = null,
                        error = null,
                        warning = result.value.warning,
                        cleanupWarning = result.value.cleanupWarning,
                        bitmapDumpSession = result.value.session,
                        bitmapDumpComparison = comparison,
                    )
            }
        }
    }

    private fun currentExportContext(): HeapExportContext =
        HeapExportContext(
            snapshotId = mutableState.value.activeSnapshotId,
            sourceFileDigest = mutableState.value.snapshotSummary?.sourceFileDigest,
            mappingDigest = mutableState.value.snapshotSummary?.mappingDigest,
            filters =
                mapOf(
                    "heap" to mutableState.value.heapFilter.orEmpty(),
                    "scope" to mutableState.value.classScope.name,
                    "leak" to mutableState.value.leakFilter.name,
                    "arrangeBy" to mutableState.value.arrangeBy.name,
                    "search" to mutableState.value.searchText,
                    "matchCase" to mutableState.value.matchCase.toString(),
                    "regex" to mutableState.value.useRegex.toString(),
                ),
            visibleColumns =
                mutableState.value.visibleClassifierColumns
                    .map { it.name }
                    .toSet(),
            selectedObjectIds =
                mutableState.value.selectedInstanceDetail
                    ?.let { listOf(it.objectId) }
                    .orEmpty(),
            exportedAt = Instant.now(),
        )

    private fun persistWorkspaceSettings() {
        val snapshotId = mutableState.value.activeSnapshotId ?: return
        val current = mutableState.value
        backend.saveWorkspaceSettings(
            snapshotId,
            MemorySessionUiSettings(
                visibleColumns = current.visibleClassifierColumns.map { it.name }.toSet(),
                filterPresets = current.savedFilterPresets.map { it.toStorage() },
            ),
        )
    }

    private fun MemorySessionFilterPreset.toPresentation(): MemoryFilterPreset =
        MemoryFilterPreset(
            id = id,
            label = label,
            heapFilter = heapFilter,
            classScope = runCatching { MemoryClassScope.valueOf(classScope) }.getOrDefault(MemoryClassScope.ALL),
            leakFilter = runCatching { MemoryLeakFilter.valueOf(leakFilter) }.getOrDefault(MemoryLeakFilter.NONE),
            arrangeBy = runCatching { MemoryArrangeBy.valueOf(arrangeBy) }.getOrDefault(MemoryArrangeBy.CLASS),
            searchText = searchText,
            matchCase = matchCase,
            useRegex = useRegex,
        )

    private fun MemoryFilterPreset.toStorage(): MemorySessionFilterPreset =
        MemorySessionFilterPreset(
            id = id,
            label = label,
            heapFilter = heapFilter,
            classScope = classScope.name,
            leakFilter = leakFilter.name,
            arrangeBy = arrangeBy.name,
            searchText = searchText,
            matchCase = matchCase,
            useRegex = useRegex,
        )

    private fun dominatorRows(heapDump: HeapDump): List<MemoryDominatorRow> {
        val objects =
            buildMap<Long, Pair<String, Long>> {
                heapDump.instances.forEach { put(it.objectId, it.className to it.shallowSize) }
                heapDump.objectArrays.forEach { put(it.objectId, it.className to it.shallowSize) }
                heapDump.primitiveArrays.forEach { put(it.objectId, it.className to it.shallowSize) }
            }

        fun depthOf(objectId: Long): Int {
            var current = objectId
            var depth = 0
            val seen = mutableSetOf<Long>()
            while (seen.add(current)) {
                val parent = heapDump.objectImmediateDominators[current] ?: break
                depth++
                current = parent
            }
            return depth
        }
        return heapDump.objectRetainedSizes
            .mapNotNull { (objectId, retainedSize) ->
                val (className, shallowSize) = objects[objectId] ?: return@mapNotNull null
                MemoryDominatorRow(
                    objectId = objectId,
                    className = className,
                    shallowSize = shallowSize,
                    retainedSize = retainedSize,
                    depth = depthOf(objectId),
                    parentObjectId = heapDump.objectImmediateDominators[objectId],
                )
            }.sortedWith(compareBy<MemoryDominatorRow> { it.depth }.thenByDescending { it.retainedSize }.thenBy { it.className })
    }

    @Suppress("LongParameterList")
    private fun HeapDump.toSnapshotSummary(
        histogram: HeapHistogram,
        mappingLoaded: Boolean,
        hasAdditionalWarning: Boolean,
        sourceFileDigest: String?,
        mappingDigest: String?,
        indexFile: Path?,
    ): HeapSnapshotSummary {
        val capabilities =
            buildSet {
                add(HeapCapability.HEAP_GRAPH)
                if (gcRoots.isNotEmpty()) add(HeapCapability.GC_ROOTS)
                if (instances.any { it.nativeSizeBytes != null }) add(HeapCapability.NATIVE_SIZE)
                if (mappingLoaded) add(HeapCapability.MAPPING)
                if (bitmapInstances.isNotEmpty()) add(HeapCapability.BITMAP_PAYLOAD)
            }
        return HeapSnapshotSummary(
            id = id.ifBlank { rawHprofFile?.fileName?.toString().orEmpty() },
            sourceFile = rawHprofFile,
            fileSizeBytes = rawHprofFile?.let { runCatching { Files.size(it) }.getOrNull() },
            sourceFileDigest = sourceFileDigest,
            mappingDigest = mappingDigest,
            indexFile = indexFile,
            capturedAt = capturedAt,
            loadedAt = Instant.now(),
            format = format,
            idSize = idSize,
            classCount = histogram.summary.classCount,
            objectCount = histogram.summary.objectCount,
            warningCount = warnings.size + if (hasAdditionalWarning) 1 else 0,
            capabilities = capabilities,
        )
    }

    private fun classifierGroupings(classes: List<ClassStats>): List<MemoryArrangeBy> =
        buildList {
            add(MemoryArrangeBy.CLASS)
            add(MemoryArrangeBy.PACKAGE)
            if (classes.any { it.allocationCallstack.isNotEmpty() }) add(MemoryArrangeBy.CALLSTACK)
            if (classes.any { it.allocationMethod != null }) add(MemoryArrangeBy.ALLOCATION_METHOD)
        }

    private fun showFailure(failure: MemoryBackendResult.Failure) {
        activeOperationJob = null
        mutableState.value =
            mutableState.value.copy(
                isDumping = false,
                operationMessage = null,
                loadPhase = HeapLoadPhase.FAILED,
                loadProgress = null,
                error = MemoryProfilerError(title = failure.title, detail = failure.detail),
            )
    }

    private fun MemoryClassifierRow.classNames(): List<String> = className?.let(::listOf) ?: children.flatMap { it.classNames() }

    private fun InstanceQueryRow.toPresentation(): MemoryInstanceRow =
        MemoryInstanceRow(
            objectId = objectId,
            index = index,
            shallowSize = shallowSize,
            retainedSize = retainedSize,
            depth = depth,
            reachable = reachable,
            nativeSize = nativeSize,
            shallowSizeKnown = shallowSizeKnown,
        )

    private fun InstanceQueryDetail.toEvidence(): HeapObjectInvestigation =
        HeapObjectInvestigation(
            objectId = objectId,
            className = className,
            shallowSize = shallowSize,
            retainedSize = retainedSize,
            depth = depth,
            fields = fields.map { HeapObjectFieldEvidence(it.name, it.displayValue, it.targetObjectId, it.targetClassName) },
            references = references.map { HeapObjectFieldEvidence(it.name, it.displayValue, it.targetObjectId, it.targetClassName) },
            referenceChain = referenceChain,
            shallowSizeKnown = shallowSizeKnown,
            nativeSize = nativeSize,
        )

    private fun InstanceQueryDetail.toPresentation(arrayStart: Int = 0): MemoryInstanceDetail =
        MemoryInstanceDetail(
            objectId = objectId,
            className = className,
            shallowSize = shallowSize,
            retainedSize = retainedSize,
            depth = depth,
            isArray = isArray,
            elementCount = elementCount,
            arrayStart = arrayStart,
            arrayPageSize = arrayPageSize,
            fields =
                fields.map { field ->
                    MemoryInstanceField(
                        name = field.name,
                        displayValue = field.displayValue,
                        targetObjectId = field.targetObjectId,
                        targetClassName = field.targetClassName,
                    )
                },
            referenceChain = referenceChain,
            references =
                references.map { reference ->
                    MemoryInstanceField(
                        name = reference.name,
                        displayValue = reference.displayValue,
                        targetObjectId = reference.targetObjectId,
                        targetClassName = reference.targetClassName,
                    )
                },
            shallowSizeKnown = shallowSizeKnown,
            nativeSize = nativeSize,
        )
}
