@file:Suppress("MaxLineLength", "LongMethod", "ReturnCount", "MagicNumber")

package com.androidperformancestudio.memory.app

import com.androidperformancestudio.adb.AdbTargetSnapshot
import com.androidperformancestudio.adb.AndroidDeviceInfosResult
import com.androidperformancestudio.adb.AndroidProcessSelection
import com.androidperformancestudio.adb.AndroidTargetListener
import com.androidperformancestudio.adb.AndroidTargetSubscription
import com.androidperformancestudio.memory.analysis.FieldValue
import com.androidperformancestudio.memory.analysis.HeapDiffAnalyzer
import com.androidperformancestudio.memory.analysis.InstanceQueryDetail
import com.androidperformancestudio.memory.analysis.InstanceQueryRow
import com.androidperformancestudio.memory.analysis.ProguardMapping
import com.androidperformancestudio.memory.memory_app.generated.resources.Res
import com.androidperformancestudio.memory.memory_app.generated.resources.analyzing_memory_leaks
import com.androidperformancestudio.memory.memory_app.generated.resources.bitmap_dump_failed
import com.androidperformancestudio.memory.memory_app.generated.resources.capturing_native_heap
import com.androidperformancestudio.memory.memory_app.generated.resources.dumping_bitmaps_for
import com.androidperformancestudio.memory.memory_app.generated.resources.dumping_heap_for
import com.androidperformancestudio.memory.memory_app.generated.resources.hprof_parser_out_of_memory
import com.androidperformancestudio.memory.memory_app.generated.resources.importing
import com.androidperformancestudio.memory.memory_app.generated.resources.importing_ad13e4da
import com.androidperformancestudio.memory.memory_app.generated.resources.importing_mapping
import com.androidperformancestudio.memory.memory_app.generated.resources.loading_session
import com.androidperformancestudio.memory.memory_app.generated.resources.mapping_imported
import com.androidperformancestudio.memory.memory_app.generated.resources.memory_leaks_analysis_failed
import com.androidperformancestudio.memory.memory_app.generated.resources.recording_sampled_art_allocations
import com.androidperformancestudio.memory.memory_app.generated.resources.sampled_art_capture_failed
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
import com.androidperformancestudio.memory.model.LeakCanaryStatus
import com.androidperformancestudio.memory.model.NativeHeapAnalysis
import com.androidperformancestudio.memory.model.NativeHeapTrace
import com.androidperformancestudio.memory.presentation.DEFAULT_MEMORY_CLASSIFIER_COLUMNS
import com.androidperformancestudio.memory.presentation.MemoryArrangeBy
import com.androidperformancestudio.memory.presentation.MemoryClassScope
import com.androidperformancestudio.memory.presentation.MemoryClassifierColumn
import com.androidperformancestudio.memory.presentation.MemoryClassifierRow
import com.androidperformancestudio.memory.presentation.MemoryDeviceOption
import com.androidperformancestudio.memory.presentation.MemoryFilterPreset
import com.androidperformancestudio.memory.presentation.MemoryHistogramSort
import com.androidperformancestudio.memory.presentation.MemoryInspectorObjectKey
import com.androidperformancestudio.memory.presentation.MemoryInspectorObjectState
import com.androidperformancestudio.memory.presentation.MemoryInstanceDetail
import com.androidperformancestudio.memory.presentation.MemoryInstanceField
import com.androidperformancestudio.memory.presentation.MemoryInstanceRow
import com.androidperformancestudio.memory.presentation.MemoryLeakFilter
import com.androidperformancestudio.memory.presentation.MemoryProcessOption
import com.androidperformancestudio.memory.presentation.MemoryProfilerError
import com.androidperformancestudio.memory.presentation.MemoryProfilerState
import com.androidperformancestudio.memory.presentation.MemoryProfilerViewMode
import com.androidperformancestudio.memory.presentation.MemorySampledArtAnalysis
import com.androidperformancestudio.memory.presentation.MemorySampledArtCallSite
import com.androidperformancestudio.memory.presentation.MemorySampledArtTrace
import com.androidperformancestudio.memory.presentation.MemorySortDirection
import com.androidperformancestudio.memory.storage.MemorySessionFilterPreset
import com.androidperformancestudio.memory.storage.MemorySessionMetadata
import com.androidperformancestudio.memory.storage.MemorySessionUiSettings
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

internal data class LoadedNativeHeap(
    val trace: NativeHeapTrace,
    val analysis: NativeHeapAnalysis,
)

internal data class LoadedSampledArt(
    val trace: MemorySampledArtTrace,
    val processing: SampledArtProcessingResult,
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
    fun changeLanguage(language: UiLanguage) = Unit

    fun registerTargetListener(listener: AndroidTargetListener): AndroidTargetSubscription

    suspend fun listDevices(): MemoryBackendResult<List<MemoryDeviceOption>>

    suspend fun listProcesses(serial: String): MemoryBackendResult<List<MemoryProcessOption>>

    suspend fun capture(
        serial: String,
        process: MemoryProcessOption,
    ): MemoryBackendResult<LoadedSnapshot>

    suspend fun captureBitmaps(
        serial: String,
        process: MemoryProcessOption,
        onProgress: (Int) -> Unit = {},
    ): MemoryBackendResult<LoadedBitmapDump> =
        MemoryBackendResult.Failure("Bitmap dump unavailable", "The selected backend does not support Bitmap dumps.")

    suspend fun importHprof(file: Path): MemoryBackendResult<LoadedSnapshot>

    suspend fun importHprof(
        file: Path,
        onProgress: (Int) -> Unit,
    ): MemoryBackendResult<LoadedSnapshot> = importHprof(file)

    /**
     * Imports an R8/ProGuard mapping.txt and returns the re-analyzed heap when one was already
     * loaded, or null when only the mapping was stored.
     */
    suspend fun importMapping(file: Path): MemoryBackendResult<LoadedSnapshot?> =
        MemoryBackendResult.Failure("Mapping import unavailable", "The selected backend does not support mapping.txt.")

    suspend fun analyzeLeaks(): MemoryBackendResult<LeakCanaryReport> =
        MemoryBackendResult.Failure(
            "Leak analysis unavailable",
            "The selected backend does not support LeakCanary analysis.",
        )

    suspend fun analyzeLeaks(
        snapshot: LoadedSnapshot,
        onProgress: (Int) -> Unit,
    ): MemoryBackendResult<LeakCanaryReport> = analyzeLeaks()

    fun exportSnapshotRaw(
        snapshot: LoadedSnapshot,
        output: Path,
    ) {
        SnapshotSourceExports.copyRaw(snapshot, output)
    }

    fun exportSnapshotConverted(
        snapshot: LoadedSnapshot,
        output: Path,
    ) {
        SnapshotSourceExports.copyConverted(snapshot, output)
    }

    fun exportSnapshotInstances(
        snapshot: LoadedSnapshot,
        className: String,
        output: Path,
    ) {
        SnapshotSourceExports.instances(snapshot, className, output)
    }

    fun exportSnapshotJson(
        snapshot: LoadedSnapshot,
        output: Path,
        summary: HeapSnapshotSummary?,
    ) {
        SnapshotSourceExports.json(snapshot, output, summary)
    }

    fun exportSnapshotReport(
        snapshot: LoadedSnapshot,
        diff: com.androidperformancestudio.memory.model.HeapDiff?,
        output: Path,
        summary: HeapSnapshotSummary?,
        context: HeapExportContext?,
    ) {
        SnapshotSourceExports.report(snapshot, diff, output, summary, context)
    }

    suspend fun injectLeakCanaryAgent(projectRoot: Path): MemoryBackendResult<String> =
        MemoryBackendResult.Failure(
            "LeakCanary Agent unavailable",
            "The selected backend does not support source-project injection.",
        )

    suspend fun openLeakCanaryAgent(
        serial: String,
        process: MemoryProcessOption,
    ): MemoryBackendResult<LeakCanaryLiveSession> =
        MemoryBackendResult.Failure(
            "LeakCanary Agent unavailable",
            "The selected backend does not support live LeakCanary monitoring.",
        )

    suspend fun pollLeakCanaryAgent(): MemoryBackendResult<LeakCanaryLiveSession> =
        MemoryBackendResult.Failure(
            "LeakCanary Agent unavailable",
            "The selected backend does not support live LeakCanary monitoring.",
        )

    fun closeLeakCanaryAgent() = Unit

    suspend fun listSessions(): MemoryBackendResult<List<MemorySessionMetadata>> = MemoryBackendResult.Success(emptyList())

    fun loadWorkspaceSettings(sessionId: String): MemorySessionUiSettings = MemorySessionUiSettings()

    fun saveWorkspaceSettings(
        sessionId: String,
        settings: MemorySessionUiSettings,
    ) = Unit

    suspend fun loadSession(metadata: MemorySessionMetadata): MemoryBackendResult<LoadedSnapshot> =
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

    suspend fun captureSampledArtAllocations(
        serial: String,
        process: MemoryProcessOption,
    ): MemoryBackendResult<LoadedSampledArt> =
        MemoryBackendResult.Failure(
            title = "ART sampling unavailable",
            detail = "The selected backend does not support sampled ART allocation recording.",
        )

    suspend fun importSampledArtAllocations(file: Path): MemoryBackendResult<LoadedSampledArt> =
        MemoryBackendResult.Failure(
            title = "ART sample import unavailable",
            detail = "The selected backend does not support importing ART sampling traces.",
        )

    fun exportSampledArtAllocations(
        trace: MemorySampledArtTrace,
        output: Path,
    ) = Unit

    suspend fun importNativeHeap(file: Path): MemoryBackendResult<LoadedNativeHeap> =
        MemoryBackendResult.Failure(
            title = "Native heap import unavailable",
            detail = "The selected backend does not support importing native heap traces.",
        )

    suspend fun importJavaHeap(file: Path): MemoryBackendResult<LoadedSnapshot> =
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
    @Volatile private var language: UiLanguage = UiLanguage.ENGLISH,
    private val selectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutableState = MutableStateFlow(MemoryProfilerState())
    private var activeOperationJob: Job? = null
    private var liveLeakMonitoringJob: Job? = null
    private var previousLeakReport: LeakCanaryReport? = null
    private val selectionMutex = Mutex()
    private var selectionGeneration = 0L
    private var inspectorGeneration = 0L

    val state: StateFlow<MemoryProfilerState> = mutableState.asStateFlow()

    var loadedHeap: LoadedSnapshot? = null
        private set

    /** Reachability/instance query rebuilt whenever a new heap dump is loaded. */
    var instanceQuery: HeapSnapshotQuery? = null
        private set

    var loadedBitmapDump: LoadedBitmapDump? = null
        private set

    private val mutableRecentSessions = MutableStateFlow<List<MemorySessionMetadata>>(emptyList())
    val recentSessions: StateFlow<List<MemorySessionMetadata>> = mutableRecentSessions.asStateFlow()

    private val loadedHeapsBySnapshotId = linkedMapOf<String, LoadedSnapshot>()
    private var selectedQueryClasses: List<String> = emptyList()
    private var selectedQueryHeap: String? = null
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

    fun changeLanguage(language: UiLanguage) {
        if (this.language == language) return
        backend.changeLanguage(language)
        this.language = language
    }

    fun close() {
        invalidateSelection()
        resetInspectorObjects()
        activeOperationJob?.cancel()
        stopLiveLeakCanary()
        processSelectionSubscription.close()
        targetSubscription.close()
        loadedHeapsBySnapshotId.values.forEach { it.close() }
        loadedHeapsBySnapshotId.clear()
    }

    suspend fun refreshDevices() {
        when (val result = backend.listDevices()) {
            is MemoryBackendResult.Failure -> showTargetFailure(result)
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
                leakCanaryReport = previousLeakReport ?: mutableState.value.leakCanaryReport,
            )
        previousLeakReport = null
    }

    suspend fun selectDevice(serial: String) {
        processSelection.selectDevice(serial)
        mutableState.value = mutableState.value.copy(error = null)
        val result = backend.listProcesses(serial)
        if (processSelection.snapshot.serial != serial) return
        when (result) {
            is MemoryBackendResult.Failure -> showTargetFailure(result)
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
        if (mutableState.value.isDumping) return
        val heap = loadedHeapsBySnapshotId[snapshotId] ?: return
        applyLoadedResult(MemoryBackendResult.Success(heap), compareWithPrevious = false, appendSnapshot = false)
    }

    fun compareSnapshots(
        beforeId: String,
        afterId: String,
    ) {
        if (mutableState.value.isDumping || beforeId == afterId) return
        val before = loadedHeapsBySnapshotId[beforeId] ?: return
        val after = loadedHeapsBySnapshotId[afterId] ?: return
        val snapshots = mutableState.value.snapshotSummaries.associateBy { it.id }
        val beforeSummary = snapshots[beforeId] ?: return
        val afterSummary = snapshots[afterId] ?: return
        mutableState.value =
            mutableState.value.copy(
                heapDiff =
                    HeapDiffAnalyzer()
                        .diff(before.histogram.classes, after.histogram.classes, HeapDiffMatchMode.CLASS_NAME)
                        .copy(beforeSnapshot = beforeSummary, afterSnapshot = afterSummary),
            )
    }

    fun closeSnapshot(snapshotId: String) {
        if (mutableState.value.isDumping) return
        invalidateSelection()
        val current = mutableState.value
        val remaining = current.snapshotSummaries.filterNot { it.id == snapshotId }
        loadedHeapsBySnapshotId.remove(snapshotId)?.close()
        if (current.activeSnapshotId != snapshotId) {
            val diff =
                current.heapDiff?.takeUnless {
                    it.beforeSnapshot?.id == snapshotId || it.afterSnapshot?.id == snapshotId
                }
            mutableState.value = current.copy(snapshotSummaries = remaining, heapDiff = diff)
            return
        }
        resetInspectorObjects()
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
                    inspectorObjects = emptyMap(),
                    dominatorRows = emptyList(),
                    instanceHistory = emptyList(),
                    instanceHistoryIndex = -1,
                    pinnedInstanceIds = emptySet(),
                    summary =
                        com.androidperformancestudio.memory.model
                            .HeapSummary(),
                    classes = emptyList(),
                    heapBaseClasses = emptyList(),
                    perHeapClasses = emptyMap(),
                    displayedClasses = emptyList(),
                    classifierRows = emptyList(),
                    heapDiff = null,
                    leakSuspects = emptyList(),
                    lifecycleLeakObjects = emptyList(),
                    bitmapInstances = emptyList(),
                    activityLeaks = emptyList(),
                    activityCount = 0,
                    artifact = null,
                    mappingLoaded = false,
                    availableHeaps = emptyList(),
                    heapFilter = null,
                    warning = null,
                    cleanupWarning = null,
                    leakCanaryReport = LeakCanaryReport(),
                )
        } else {
            mutableState.value = mutableState.value.copy(snapshotSummaries = remaining)
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
        invalidateSelection()
        val loaded = loadedHeap
        val remote = loaded != null
        val page =
            if (mode == MemoryProfilerViewMode.Dominators && remote) {
                loaded.source.dominatorsPage(0, 500)
            } else {
                null
            }
        val dominators =
            if (mode == MemoryProfilerViewMode.Dominators) {
            page?.rows ?: loaded?.source?.dominatorsPage(0, 500)?.rows.orEmpty()
            } else {
                emptyList()
            }
        mutableState.value =
            mutableState.value.copy(
                viewMode = mode,
                dominatorRows = dominators,
                remoteDominators = remote,
                dominatorPageOffset = 0,
                dominatorTotalCount = page?.total ?: dominators.size,
                dominatorChildren = emptyMap(),
                dominatorChildTotals = emptyMap(),
                dominatorChildOffsets = emptyMap(),
                failedDominatorChildren = emptySet(),
            )
    }

    suspend fun changeViewModeAsync(mode: MemoryProfilerViewMode) {
        val loaded = loadedHeap
        if (mode != MemoryProfilerViewMode.Dominators || loaded == null) {
            changeViewMode(mode)
            return
        }
        querySelection(
            compute = { _, _ -> loaded.source.dominatorsPage(0, 500) },
            publish = { page ->
                mutableState.value =
                    mutableState.value.copy(
                        viewMode = mode,
                        remoteDominators = true,
                        dominatorRows = page.rows,
                        dominatorTotalCount = page.total,
                        dominatorPageOffset = 0,
                        dominatorChildren = emptyMap(),
                        dominatorChildTotals = emptyMap(),
                        dominatorChildOffsets = emptyMap(),
                        failedDominatorChildren = emptySet(),
                    )
            },
        )
    }

    fun selectClass(className: String) {
        invalidateSelection()
        val page =
            instanceQuery
                ?.let { selectionPage(it, listOf(className), mutableState.value.heapFilter) }
                ?: HeapInstancePage(0, emptyList())
        publishClassSelection(className, page)
    }

    suspend fun selectClassAsync(className: String) {
        querySelection(
            compute = { query, heapFilter -> selectionPage(query, listOf(className), heapFilter) },
            publish = { page -> publishClassSelection(className, page) },
        )
    }

    private fun publishClassSelection(
        className: String,
        page: HeapInstancePage,
    ) {
        resetInspectorObjects()
        selectedQueryClasses = listOf(className)
        selectedQueryHeap = mutableState.value.heapFilter
        mutableState.value =
            mutableState.value.copy(
                selectedClassName = className,
                selectedClassifierId = classifierClassId(className, mutableState.value.heapFilter),
                selectedClassifierLabel = className,
                selectedClassInstances = page.rows.map { it.toPresentation() },
                instanceTotalCount = page.total,
                instancePageOffset = page.offset,
                selectedInstanceDetail = null,
                instanceHistory = emptyList(),
                instanceHistoryIndex = -1,
            )
    }

    fun selectClassifier(row: MemoryClassifierRow) {
        invalidateSelection()
        val classNames = row.classNames()
        val page =
            instanceQuery?.let { selectionPage(it, classNames, row.heapName ?: mutableState.value.heapFilter) }
                ?: HeapInstancePage(0, emptyList())
        publishClassifierSelection(row, page)
    }

    suspend fun selectClassifierAsync(row: MemoryClassifierRow) {
        val classNames = row.classNames()
        querySelection(
            compute = { query, heapFilter ->
                selectionPage(query, classNames, row.heapName ?: heapFilter)
            },
            publish = { page -> publishClassifierSelection(row, page) },
        )
    }

    private fun publishClassifierSelection(
        row: MemoryClassifierRow,
        page: HeapInstancePage,
    ) {
        resetInspectorObjects()
        selectedQueryClasses = row.classNames()
        selectedQueryHeap = row.heapName ?: mutableState.value.heapFilter
        mutableState.value =
            mutableState.value.copy(
                selectedClassName = row.className,
                selectedClassifierId = row.id,
                selectedClassifierLabel = row.label,
                selectedClassInstances = page.rows.map { it.toPresentation() },
                instanceTotalCount = page.total,
                instancePageOffset = page.offset,
                selectedInstanceDetail = null,
                instanceHistory = emptyList(),
                instanceHistoryIndex = -1,
            )
    }

    suspend fun loadInstancePageAsync(offset: Int) {
        val classes = selectedQueryClasses
        val heapName = selectedQueryHeap
        if (classes.isEmpty() || offset < 0) return
        querySelection(
            compute = { query, _ -> query.instancesPage(classes, heapName, offset, 500) },
            publish = { page ->
                mutableState.value =
                    mutableState.value.copy(
                        selectedClassInstances = page.rows.map { it.toPresentation() },
                        instanceTotalCount = page.total,
                        instancePageOffset = page.offset,
                    )
            },
        )
    }

    private fun selectionPage(
        query: HeapSnapshotQuery,
        classNames: List<String>,
        heapName: String?,
        anchorObjectId: Long? = null,
    ): HeapInstancePage {
        return query.instancesPage(classNames, heapName, 0, 500, anchorObjectId = anchorObjectId)
    }

    suspend fun loadDominatorPageAsync(offset: Int) {
        val loaded = loadedHeap ?: return
        if (offset < 0) return
        querySelection(
            compute = { _, _ -> loaded.source.dominatorsPage(offset, 500) },
            publish = { page ->
                mutableState.value =
                    mutableState.value.copy(
                        dominatorRows = page.rows,
                        dominatorTotalCount = page.total,
                        dominatorPageOffset = offset,
                        dominatorChildren = emptyMap(),
                        dominatorChildTotals = emptyMap(),
                        dominatorChildOffsets = emptyMap(),
                        failedDominatorChildren = emptySet(),
                    )
            },
        )
    }

    suspend fun loadDominatorChildrenAsync(
        parentObjectId: Long,
        offset: Int,
    ) {
        val loaded = loadedHeap ?: return
        if (offset < 0) return
        querySelection(
            compute = { _, _ -> loaded.source.dominatorsPage(offset, 500, parentObjectId) },
            publish = { page ->
                val current = mutableState.value
                mutableState.value =
                    current.copy(
                        dominatorChildren = current.dominatorChildren + (parentObjectId to page.rows),
                        dominatorChildTotals = current.dominatorChildTotals + (parentObjectId to page.total),
                        dominatorChildOffsets = current.dominatorChildOffsets + (parentObjectId to offset),
                        failedDominatorChildren = current.failedDominatorChildren - parentObjectId,
                    )
            },
            onFailure = {
                val current = mutableState.value
                mutableState.value = current.copy(failedDominatorChildren = current.failedDominatorChildren + parentObjectId)
            },
        )
    }

    fun selectInstance(objectId: Long) {
        selectInstance(objectId, appendToHistory = true)
    }

    suspend fun selectInstanceAsync(objectId: Long) {
        selectInstanceAsync(objectId, appendToHistory = true)
    }

    fun clearInstanceSelection() {
        invalidateSelection()
        resetInspectorObjects()
        mutableState.value = mutableState.value.copy(selectedInstanceDetail = null)
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

    suspend fun loadArrayRangeAsync(
        objectId: Long,
        arrayStart: Int,
    ) {
        selectInstanceAsync(objectId, appendToHistory = false, arrayStart = arrayStart)
    }

    @Suppress("TooGenericExceptionCaught")
    fun loadInspectorObject(
        objectId: Long,
        arrayStart: Int = 0,
    ) {
        val request = beginInspectorObjectLoad(objectId, arrayStart) ?: return
        try {
            publishInspectorObject(request, inspectorObjectDetail(request))
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            publishInspectorObject(request, null)
        } finally {
            clearInspectorObjectLoading(request)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun loadInspectorObjectAsync(
        objectId: Long,
        arrayStart: Int = 0,
    ) {
        val request = beginInspectorObjectLoad(objectId, arrayStart) ?: return
        try {
            val detail =
                selectionMutex.withLock {
                    if (!inspectorRequestIsCurrent(request)) return@withLock null
                    withContext(selectionDispatcher) { inspectorObjectDetail(request) }
                }
            publishInspectorObject(request, detail)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            publishInspectorObject(request, null)
        } finally {
            clearInspectorObjectLoading(request)
        }
    }

    private data class InspectorObjectRequest(
        val key: MemoryInspectorObjectKey,
        val query: HeapSnapshotQuery,
        val snapshotId: String?,
        val rootObjectId: Long,
        val generation: Long,
    )

    private fun beginInspectorObjectLoad(
        objectId: Long,
        arrayStart: Int,
    ): InspectorObjectRequest? {
        val current = mutableState.value
        val query = instanceQuery ?: return null
        val rootObjectId = current.selectedInstanceDetail?.objectId ?: return null
        val key = MemoryInspectorObjectKey(objectId, arrayStart.coerceAtLeast(0))
        when (current.inspectorObjects[key]) {
            MemoryInspectorObjectState.Loading, is MemoryInspectorObjectState.Loaded -> return null
            else -> Unit
        }
        val request = InspectorObjectRequest(key, query, current.activeSnapshotId, rootObjectId, inspectorGeneration)
        mutableState.value =
            current.copy(inspectorObjects = current.inspectorObjects + (key to MemoryInspectorObjectState.Loading))
        return request
    }

    private fun inspectorObjectDetail(request: InspectorObjectRequest): MemoryInstanceDetail? =
        request.query
            .detailOf(request.key.objectId, arrayStart = request.key.arrayStart)
            ?.toPresentation(arrayStart = request.key.arrayStart)

    private fun inspectorRequestIsCurrent(request: InspectorObjectRequest): Boolean =
        inspectorGeneration == request.generation &&
            instanceQuery === request.query &&
            mutableState.value.activeSnapshotId == request.snapshotId &&
            mutableState.value.selectedInstanceDetail?.objectId == request.rootObjectId

    private fun publishInspectorObject(
        request: InspectorObjectRequest,
        detail: MemoryInstanceDetail?,
    ) {
        if (!inspectorRequestIsCurrent(request)) return
        val result = detail?.let { MemoryInspectorObjectState.Loaded(it) } ?: MemoryInspectorObjectState.Unavailable
        val current = mutableState.value
        mutableState.value = current.copy(inspectorObjects = current.inspectorObjects + (request.key to result))
    }

    private fun clearInspectorObjectLoading(request: InspectorObjectRequest) {
        if (!inspectorRequestIsCurrent(request)) return
        val current = mutableState.value
        if (current.inspectorObjects[request.key] == MemoryInspectorObjectState.Loading) {
            mutableState.value = current.copy(inspectorObjects = current.inspectorObjects - request.key)
        }
    }

    private fun resetInspectorObjects() {
        inspectorGeneration++
        if (mutableState.value.inspectorObjects.isNotEmpty()) {
            mutableState.value = mutableState.value.copy(inspectorObjects = emptyMap())
        }
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

    suspend fun navigateInstanceBackAsync() {
        val current = mutableState.value
        if (current.instanceHistoryIndex <= 0) return
        selectInstanceAsync(
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

    suspend fun navigateInstanceForwardAsync() {
        val current = mutableState.value
        val nextIndex = current.instanceHistoryIndex + 1
        if (nextIndex !in current.instanceHistory.indices) return
        selectInstanceAsync(current.instanceHistory[nextIndex], appendToHistory = false, historyIndex = nextIndex)
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
        invalidateSelection()
        val boundedStart = arrayStart.coerceAtLeast(0)
        val detail =
            instanceQuery?.detailOf(objectId, arrayStart = boundedStart)?.toPresentation(arrayStart = boundedStart)
                ?: return
        val groupingClassName = instanceQuery?.groupingClassNameOf(objectId) ?: detail.className
        val current = mutableState.value
        val heapName = instanceQuery?.heapNameOf(objectId) ?: current.heapFilter
        val page =
            instanceQuery
                ?.let { selectionPage(it, listOf(groupingClassName), heapName, objectId) }
                ?: HeapInstancePage(0, emptyList())
        publishInstanceSelection(
            objectId,
            InstanceSelection(detail, groupingClassName, page, heapName),
            appendToHistory,
            historyIndex,
        )
    }

    private suspend fun selectInstanceAsync(
        objectId: Long,
        appendToHistory: Boolean,
        historyIndex: Int? = null,
        arrayStart: Int = 0,
    ) {
        val boundedStart = arrayStart.coerceAtLeast(0)
        querySelection(
            compute = { query, heapFilter ->
                query
                    .detailOf(objectId, arrayStart = boundedStart)
                    ?.toPresentation(arrayStart = boundedStart)
                    ?.let { detail ->
                        val groupingClassName = query.groupingClassNameOf(objectId) ?: detail.className
                        val heapName = query.heapNameOf(objectId) ?: heapFilter
                        InstanceSelection(
                            detail,
                            groupingClassName,
                            selectionPage(query, listOf(groupingClassName), heapName, objectId),
                            heapName,
                        )
                    }
            },
            publish = { selection ->
                selection?.let {
                    publishInstanceSelection(objectId, it, appendToHistory, historyIndex)
                }
            },
        )
    }

    private fun publishInstanceSelection(
        objectId: Long,
        selection: InstanceSelection,
        appendToHistory: Boolean,
        historyIndex: Int?,
    ) {
        if (mutableState.value.selectedInstanceDetail?.objectId != objectId) resetInspectorObjects()
        val current = mutableState.value
        selectedQueryClasses = listOf(selection.groupingClassName)
        selectedQueryHeap = selection.heapName
        val nextHistory =
            if (appendToHistory) {
                (
                    current.instanceHistory.take(
                        current.instanceHistoryIndex + 1,
                    ) + objectId
                ).takeLast(MAX_INSTANCE_HISTORY)
            } else {
                current.instanceHistory
            }
        mutableState.value =
            current.copy(
                selectedClassName = selection.groupingClassName,
                selectedClassifierId = classifierClassId(selection.groupingClassName, selection.heapName),
                selectedClassifierLabel = selection.groupingClassName,
                selectedClassInstances = selection.page.rows.map { it.toPresentation() },
                instanceTotalCount = selection.page.total,
                instancePageOffset = selection.page.offset,
                selectedInstanceDetail = selection.detail,
                instanceHistory = nextHistory,
                instanceHistoryIndex = historyIndex ?: nextHistory.lastIndex,
            )
    }

    private data class InstanceSelection(
        val detail: MemoryInstanceDetail,
        val groupingClassName: String,
        val page: HeapInstancePage,
        val heapName: String?,
    )

    private data class SelectionRequest(
        val query: HeapSnapshotQuery,
        val snapshotId: String?,
        val heapFilter: String?,
        val generation: Long,
    )

    private data class SelectionValue<T>(
        val value: T,
    )

    private fun invalidateSelection() {
        selectionGeneration++
        if (mutableState.value.isSelectionLoading) {
            mutableState.value = mutableState.value.copy(isSelectionLoading = false)
        }
    }

    private fun selectionIsCurrent(request: SelectionRequest): Boolean =
        selectionGeneration == request.generation &&
            instanceQuery === request.query &&
            mutableState.value.activeSnapshotId == request.snapshotId &&
            mutableState.value.heapFilter == request.heapFilter &&
            !mutableState.value.isDumping

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> querySelection(
        compute: (HeapSnapshotQuery, String?) -> T,
        publish: (T) -> Unit,
        onFailure: (Exception) -> Unit = {},
    ) {
        invalidateSelection()
        val request =
            SelectionRequest(
                query = instanceQuery ?: return,
                snapshotId = mutableState.value.activeSnapshotId,
                heapFilter = mutableState.value.heapFilter,
                generation = selectionGeneration,
            )
        mutableState.value = mutableState.value.copy(isSelectionLoading = true)
        // Serialize large graph walks: cancelled/stale UI requests must not build multiple
        // predecessor maps or inbound scans concurrently near the desktop heap cap.
        try {
            val result =
                selectionMutex.withLock {
                    if (!selectionIsCurrent(request)) return@withLock null
                    SelectionValue(runInterruptible(selectionDispatcher) { compute(request.query, request.heapFilter) })
                }
            if (result != null && selectionIsCurrent(request)) publish(result.value)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (selectionIsCurrent(request)) {
                onFailure(exception)
                showTargetFailure(MemoryBackendResult.Failure("Heap query failed", exception.message.orEmpty()))
            }
        } finally {
            if (selectionGeneration == request.generation) {
                mutableState.value = mutableState.value.copy(isSelectionLoading = false)
            }
        }
    }

    /** Switches the base class table between all heaps and a single [heap] (null = all heaps). */
    fun changeHeapFilter(heap: String?) {
        invalidateSelection()
        resetInspectorObjects()
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
        invalidateSelection()
        resetInspectorObjects()
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
        val snapshot = mutableState.value
        val serial = snapshot.selectedDeviceSerial ?: return
        val process = snapshot.processes.firstOrNull { it.pid == snapshot.selectedProcessId } ?: return
        if (liveLeakMonitoringJob != null) return
        liveLeakMonitoringJob = currentCoroutineContext()[Job]
        mutableState.value =
            snapshot.copy(
                leakCanaryLiveSession = snapshot.leakCanaryLiveSession.copy(status = LeakCanaryLiveStatus.CONNECTING),
            )
        try {
            when (val opened = backend.openLeakCanaryAgent(serial, process)) {
                is MemoryBackendResult.Failure -> {
                    mutableState.update {
                        it.copy(leakCanaryLiveSession = LeakCanaryLiveSession(LeakCanaryLiveStatus.FAILED, message = opened.detail))
                    }
                    return
                }
                is MemoryBackendResult.Success ->
                    mutableState.update {
                        it.copy(leakCanaryLiveSession = opened.value.copy(status = LeakCanaryLiveStatus.RUNNING))
                    }
            }
            while (currentCoroutineContext().isActive) {
                kotlinx.coroutines.delay(500L)
                when (val polled = backend.pollLeakCanaryAgent()) {
                    is MemoryBackendResult.Failure -> {
                        mutableState.update {
                            it.copy(
                                leakCanaryLiveSession =
                                    it.leakCanaryLiveSession.copy(
                                        status = LeakCanaryLiveStatus.FAILED,
                                        message = polled.detail,
                                    ),
                            )
                        }
                        return
                    }
                    is MemoryBackendResult.Success ->
                        mutableState.update {
                            it.copy(leakCanaryLiveSession = polled.value.copy(status = LeakCanaryLiveStatus.RUNNING))
                        }
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            mutableState.update {
                it.copy(
                    leakCanaryLiveSession =
                        it.leakCanaryLiveSession.copy(status = LeakCanaryLiveStatus.FAILED, message = exception.message),
                )
            }
        } finally {
            backend.closeLeakCanaryAgent()
            liveLeakMonitoringJob = null
            mutableState.update {
                if (it.leakCanaryLiveSession.status == LeakCanaryLiveStatus.FAILED) {
                    it
                } else {
                    it.copy(leakCanaryLiveSession = it.leakCanaryLiveSession.copy(status = LeakCanaryLiveStatus.STOPPED))
                }
            }
        }
    }

    fun stopLiveLeakCanary() {
        liveLeakMonitoringJob?.cancel()
        backend.closeLeakCanaryAgent()
        mutableState.value =
            mutableState.value.copy(
                leakCanaryLiveSession =
                    mutableState.value.leakCanaryLiveSession.copy(
                        status = LeakCanaryLiveStatus.STOPPED,
                    ),
            )
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun analyzeLeaks() {
        val snapshot = mutableState.value
        val heap = loadedHeap ?: return
        if (snapshot.snapshotSummary == null || snapshot.isDumping) return
        val context = currentCoroutineContext()
        val operationJob = context[Job]
        activeOperationJob = operationJob
        previousLeakReport = snapshot.leakCanaryReport
        mutableState.value =
            snapshot.copy(
                isDumping = true,
                operationMessage = localizedStringResource(Res.string.analyzing_memory_leaks, language),
                loadPhase = HeapLoadPhase.ANALYZE,
                loadProgress = null,
                error = null,
                leakCanaryReport = snapshot.leakCanaryReport.copy(status = LeakCanaryStatus.ANALYZING),
            )
        val result =
            try {
                val report =
                    backend.analyzeLeaks(heap) { progress ->
                        context.ensureActive()
                        updateLeakAnalysisProgress(operationJob, snapshot.activeSnapshotId, progress)
                    }
                context.ensureActive()
                report
            } catch (exception: CancellationException) {
                finishCancelledLeakAnalysis(operationJob, heap, snapshot)
                throw exception
            } catch (exception: Exception) {
                MemoryBackendResult.Failure(
                    title = localizedStringResource(Res.string.memory_leaks_analysis_failed, language),
                    detail = exception.message ?: exception::class.simpleName.orEmpty(),
                )
            }
        if (activeOperationJob !== operationJob || loadedHeap !== heap) return
        previousLeakReport = null
        when (result) {
            is MemoryBackendResult.Failure -> {
                val report = snapshot.leakCanaryReport.copy(status = LeakCanaryStatus.FAILED, message = result.detail)
                cacheLeakReport(heap, report, snapshot.activeSnapshotId)
                showFailure(result)
                mutableState.value = mutableState.value.copy(leakCanaryReport = report)
            }
            is MemoryBackendResult.Success -> {
                invalidateSelection()
                activeOperationJob = null
                cacheLeakReport(heap, result.value, snapshot.activeSnapshotId)
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

    private fun finishCancelledLeakAnalysis(
        operationJob: Job?,
        heap: LoadedSnapshot,
        snapshot: MemoryProfilerState,
    ) {
        if (activeOperationJob !== operationJob) return
        previousLeakReport = null
        if (loadedHeap === heap && mutableState.value.activeSnapshotId == snapshot.activeSnapshotId) {
            markOperationCancelled()
            mutableState.value = mutableState.value.copy(leakCanaryReport = snapshot.leakCanaryReport)
        } else {
            activeOperationJob = null
        }
    }

    private fun cacheLeakReport(
        heap: LoadedSnapshot,
        report: LeakCanaryReport,
        snapshotId: String?,
    ) {
        val analyzedHeap = heap.withLeakReport(report)
        loadedHeap = analyzedHeap
        snapshotId?.let { retainSnapshot(it, analyzedHeap) }
    }

    private fun updateLeakAnalysisProgress(
        operationJob: Job?,
        snapshotId: String?,
        progress: Int,
    ) {
        mutableState.update { current ->
            if (activeOperationJob === operationJob && current.activeSnapshotId == snapshotId) {
                current.copy(loadProgress = progress.coerceIn(0, 100))
            } else {
                current
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
                            operationMessage =
                                localizedStringResource(
                                    Res.string.dumping_bitmaps_for,
                                    language,
                                    process.name,
                                    progress,
                                ),
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

    @Suppress("TooGenericExceptionCaught")
    suspend fun captureSampledArtAllocations() {
        val snapshot = mutableState.value
        if (snapshot.isDumping) return
        val serial = snapshot.selectedDeviceSerial ?: return
        val process = snapshot.processes.firstOrNull { it.pid == snapshot.selectedProcessId } ?: return
        activeOperationJob = currentCoroutineContext()[Job]
        mutableState.value =
            snapshot.copy(
                viewMode = MemoryProfilerViewMode.SampledArtAllocations,
                isDumping = true,
                operationMessage =
                    localizedStringResource(Res.string.recording_sampled_art_allocations, language, process.name),
                error = null,
            )
        val result =
            try {
                backend.captureSampledArtAllocations(serial, process)
            } catch (exception: CancellationException) {
                markOperationCancelled()
                throw exception
            } catch (exception: Exception) {
                MemoryBackendResult.Failure(
                    title = localizedStringResource(Res.string.sampled_art_capture_failed, language),
                    detail = exception.message ?: exception::class.simpleName.orEmpty(),
                )
            }
        applySampledArtResult(result)
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun importSampledArtAllocations(file: Path) {
        if (mutableState.value.isDumping) return
        activeOperationJob = currentCoroutineContext()[Job]
        mutableState.value =
            mutableState.value.copy(
                viewMode = MemoryProfilerViewMode.SampledArtAllocations,
                isDumping = true,
                operationMessage = localizedStringResource(Res.string.importing, language, file.fileName),
                error = null,
            )
        val result =
            try {
                backend.importSampledArtAllocations(file)
            } catch (exception: CancellationException) {
                markOperationCancelled()
                throw exception
            } catch (exception: Exception) {
                MemoryBackendResult.Failure(
                    title = localizedStringResource(Res.string.sampled_art_capture_failed, language),
                    detail = exception.message ?: exception::class.simpleName.orEmpty(),
                )
            }
        applySampledArtResult(result)
    }

    fun exportSampledArtAllocations(output: Path) {
        mutableState.value.sampledArtTrace?.let { backend.exportSampledArtAllocations(it, output) }
    }

    private fun applySampledArtResult(result: MemoryBackendResult<LoadedSampledArt>) {
        when (result) {
            is MemoryBackendResult.Failure -> showFailure(result)
            is MemoryBackendResult.Success -> {
                val trace = result.value.trace
                val analysis =
                    when (val processing = result.value.processing) {
                        is SampledArtProcessingResult.Success ->
                            MemorySampledArtAnalysis.Analyzed(
                                sampleRows = processing.analysis.sampleRows,
                                sampledObjectCount = processing.analysis.sampledObjectCount,
                                sampledBytes = processing.analysis.sampledBytes,
                                callSites =
                                    processing.analysis.callSites.map { site ->
                                        MemorySampledArtCallSite(
                                            callsiteId = site.callsiteId,
                                            sampledObjectCount = site.sampledObjectCount,
                                            sampledBytes = site.sampledBytes,
                                            callStack = site.callStack,
                                        )
                                    },
                            )
                        is SampledArtProcessingResult.Unavailable ->
                            MemorySampledArtAnalysis.Unavailable(processing.reason)
                        is SampledArtProcessingResult.Failure ->
                            MemorySampledArtAnalysis.Failed(processing.reason)
                    }
                activeOperationJob = null
                mutableState.value =
                    mutableState.value.copy(
                        isDumping = false,
                        operationMessage = null,
                        loadPhase = HeapLoadPhase.IDLE,
                        loadProgress = 100,
                        error = null,
                        sampledArtAnalysis = analysis,
                        sampledArtTrace = trace,
                    )
            }
        }
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
        val importStarted = System.nanoTime()
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
                            operationMessage =
                                localizedStringResource(
                                    Res.string.importing_ad13e4da,
                                    language,
                                    file.fileName,
                                    progress,
                                ),
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
        if (result is MemoryBackendResult.Success) {
            mutableState.value = mutableState.value.copy(importDurationMillis = (System.nanoTime() - importStarted) / 1_000_000L, importEngine = result.value.engine.name)
        }
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
            is MemoryBackendResult.Success -> mutableRecentSessions.value = result.value
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
        loadedHeap?.let { backend.exportSnapshotRaw(it, output) }
    }

    fun exportConverted(output: Path) {
        loadedHeap?.let { backend.exportSnapshotConverted(it, output) }
    }

    fun exportHistogram(output: Path) {
        loadedHeap?.let { backend.exportHistogram(it.histogram, output) }
    }

    fun exportSelectedClassInstances(output: Path) {
        val className = mutableState.value.selectedClassName ?: return
        loadedHeap?.let { backend.exportSnapshotInstances(it, className, output) }
    }

    fun exportHeapDiff(output: Path) {
        mutableState.value.heapDiff?.let { backend.exportHeapDiff(it, output) }
    }

    fun exportHeapSnapshotJson(output: Path) {
        loadedHeap?.let {
            backend.exportSnapshotJson(it, output, mutableState.value.snapshotSummary)
        }
    }

    fun exportInvestigationReport(output: Path) {
        loadedHeap?.let {
            backend.exportSnapshotReport(
                it,
                mutableState.value.heapDiff,
                output,
                mutableState.value.snapshotSummary,
                currentExportContext(),
            )
        }
    }

    fun exportObjectInvestigation(output: Path) {
        val objectId = mutableState.value.selectedInstanceDetail?.objectId ?: return
        instanceQuery
            ?.detailOf(objectId)
            ?.toEvidence()
            ?.copy(snapshotSummary = mutableState.value.snapshotSummary)
            ?.let { backend.exportObjectInvestigation(it, output) }
    }

    fun exportBitmapSession(output: Path) {
        loadedBitmapDump?.let { backend.exportBitmapSession(it.session, output) }
    }

    fun exportBitmapComparison(output: Path) {
        mutableState.value.bitmapDumpComparison?.let { backend.exportBitmapComparison(it, output) }
    }

    @Suppress("LongMethod")
    private fun applyLoadedResult(
        result: MemoryBackendResult<LoadedSnapshot>,
        compareWithPrevious: Boolean = true,
        appendSnapshot: Boolean = true,
    ) {
        when (result) {
            is MemoryBackendResult.Failure -> showFailure(result)
            is MemoryBackendResult.Success -> {
                activeOperationJob = null
                resetInspectorObjects()
                val previous = loadedHeap
                loadedHeap = result.value
                instanceQuery = result.value.source.query
                val findings = result.value.findings
                val snapshotSummary =
                    result.value.toSnapshotSummary()
                retainSnapshot(snapshotSummary.id, result.value)
                val snapshotSummaries =
                    if (appendSnapshot) {
                        (
                            mutableState.value.snapshotSummaries.filterNot { it.id == snapshotSummary.id } +
                                snapshotSummary
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
                        leakSuspects = findings.leakSuspects,
                        lifecycleLeakObjects =
                            findings.lifecycleLeakObjects
                                .map { instance ->
                                    com.androidperformancestudio.memory.presentation.MemoryLifecycleLeakObject(
                                        objectId = instance.objectId,
                                        className = instance.className,
                                        heapName = instance.heapName,
                                    )
                                }.toList(),
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
                                .ifEmpty { DEFAULT_MEMORY_CLASSIFIER_COLUMNS }
                                .toSet(),
                        error = null,
                        warning = result.value.warning,
                        cleanupWarning = result.value.cleanupWarning,
                        artifact = result.value.artifact,
                        heapDiff =
                            if (!compareWithPrevious) {
                                mutableState.value.heapDiff?.takeIf { diff ->
                                    snapshotSummaries.any { it.id == diff.beforeSnapshot?.id } &&
                                        snapshotSummaries.any { it.id == diff.afterSnapshot?.id }
                                }
                            } else {
                                previous?.takeIf { it.id != result.value.id }?.let {
                                    HeapDiffAnalyzer()
                                        .diff(
                                            it.histogram.classes,
                                            result.value.histogram.classes,
                                            HeapDiffMatchMode.CLASS_NAME,
                                        ).copy(
                                            beforeSnapshot = mutableState.value.snapshotSummary,
                                            afterSnapshot = snapshotSummary,
                                        )
                                }
                            },
                        bitmapInstances = findings.bitmapInstances,
                        activityLeaks = findings.activityLeaks,
                        leakCanaryReport = result.value.leakCanaryReport,
                        mappingLoaded = result.value.mapping != null,
                        selectedClassName = null,
                        selectedClassifierId = null,
                        selectedClassifierLabel = null,
                        selectedClassInstances = emptyList(),
                        instanceTotalCount = 0,
                        instancePageOffset = 0,
                        remoteDominators = true,
                        dominatorChildren = emptyMap(),
                        dominatorChildTotals = emptyMap(),
                        dominatorChildOffsets = emptyMap(),
                        failedDominatorChildren = emptySet(),
                        selectedInstanceDetail = null,
                        dominatorRows =
                            if (mutableState.value.viewMode == MemoryProfilerViewMode.Dominators) {
                                visibleDominatorRows(result.value)
                            } else {
                                emptyList()
                            },
                        instanceHistory = emptyList(),
                        instanceHistoryIndex = -1,
                        pinnedInstanceIds = emptySet(),
                        availableHeaps = result.value.availableHeaps,
                        heapFilter = null,
                        heapBaseClasses = result.value.histogram.classes,
                        perHeapClasses = result.value.perHeapClasses,
                        classScope = MemoryClassScope.ALL,
                        leakFilter = MemoryLeakFilter.NONE,
                        arrangeBy = MemoryArrangeBy.CLASS,
                        availableArrangeBy = classifierGroupings(result.value.histogram.classes),
                        classifierSortColumn = MemoryClassifierColumn.RETAINED_SIZE,
                        classifierSortDirection = MemorySortDirection.DESCENDING,
                        searchText = "",
                        matchCase = false,
                        useRegex = false,
                    )
                val retainedIds = snapshotSummaries.mapTo(hashSetOf()) { it.id }
                loadedHeapsBySnapshotId.keys.filterNot { it in retainedIds }.forEach { id ->
                    loadedHeapsBySnapshotId.remove(id)?.close()
                }
                selectedQueryClasses = emptyList()
                selectedQueryHeap = null
                if (mutableState.value.viewMode == MemoryProfilerViewMode.Dominators) {
                    changeViewMode(MemoryProfilerViewMode.Dominators)
                }
                if (!compareWithPrevious) {
                    val comparison = mutableState.value.heapDiff
                    val beforeId = comparison?.beforeSnapshot?.id
                    val afterId = comparison?.afterSnapshot?.id
                    if (beforeId != null && afterId != null) compareSnapshots(beforeId, afterId)
                }
            }
        }
    }

    private fun visibleDominatorRows(snapshot: LoadedSnapshot): List<com.androidperformancestudio.memory.presentation.MemoryDominatorRow> =
        snapshot.source.dominatorsPage(0, 500).rows

    private fun retainSnapshot(
        id: String,
        snapshot: LoadedSnapshot,
    ) {
        val replaced = loadedHeapsBySnapshotId.put(id, snapshot)
        if (replaced != null && replaced.source !== snapshot.source) replaced.close()
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

    @Suppress("LongParameterList")
    private fun LoadedSnapshot.toSnapshotSummary(): HeapSnapshotSummary {
        val metadata = source.metadata
        val capabilities =
            buildSet {
                add(HeapCapability.HEAP_GRAPH)
                if (metadata.rootCount > 0) add(HeapCapability.GC_ROOTS)
                if (histogram.classes.any { it.nativeSize != null }) add(HeapCapability.NATIVE_SIZE)
                if (mapping != null) add(HeapCapability.MAPPING)
                if (findings.bitmapInstances.isNotEmpty()) add(HeapCapability.BITMAP_PAYLOAD)
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
            format = metadata.format,
            idSize = metadata.idSize,
            classCount = histogram.summary.classCount,
            objectCount = histogram.summary.objectCount,
            warningCount = metadata.warnings.size + if (warning != null) 1 else 0,
            capabilities = capabilities,
            engine = engine,
        )
    }

    private fun classifierGroupings(classes: List<ClassStats>): List<MemoryArrangeBy> =
        buildList {
            add(MemoryArrangeBy.CLASS)
            add(MemoryArrangeBy.PACKAGE)
            if (classes.any { it.allocationCallstack.isNotEmpty() }) add(MemoryArrangeBy.CALLSTACK)
            if (classes.any { it.allocationMethod != null }) add(MemoryArrangeBy.ALLOCATION_METHOD)
        }

    private fun showTargetFailure(failure: MemoryBackendResult.Failure) {
        mutableState.value = mutableState.value.copy(error = MemoryProfilerError(failure.title, failure.detail))
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

    private fun classifierClassId(
        className: String,
        heapName: String?,
    ): String = if (heapName == null) "class:$className" else "heap:$heapName/class:$className"

    private fun MemoryClassifierRow.classNames(): List<String> = className?.let(::listOf) ?: children.flatMap { it.classNames() }.distinct()

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
            className = className,
            representedClassName = representedClassName,
        )

    private fun InstanceQueryDetail.toEvidence(): HeapObjectInvestigation =
        HeapObjectInvestigation(
            objectId = objectId,
            className = className,
            shallowSize = shallowSize,
            retainedSize = retainedSize,
            depth = depth,
            fields =
                fields.map {
                    HeapObjectFieldEvidence(
                        it.name,
                        it.displayValue,
                        it.targetObjectId,
                        it.targetClassName,
                    )
                },
            references =
                references.map {
                    HeapObjectFieldEvidence(it.name, it.displayValue, it.targetObjectId, it.targetClassName)
                },
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
            fields = fields.map { it.toPresentation() },
            referenceChain = referenceChain,
            references = references.map { it.toPresentation() },
            shallowSizeKnown = shallowSizeKnown,
            nativeSize = nativeSize,
            valueKind = valueKind,
            representedClassName = representedClassName,
            stringPreview = stringPreview,
            retainedNativeSize = retainedNativeSize,
        )

    private fun FieldValue.toPresentation(): MemoryInstanceField =
        MemoryInstanceField(
            name = name,
            displayValue = displayValue,
            targetObjectId = targetObjectId,
            targetClassName = targetClassName,
            depth = depth,
            valueKind = valueKind,
            shallowSize = shallowSize,
            retainedSize = retainedSize,
            nativeSize = nativeSize,
            retainedNativeSize = retainedNativeSize,
            representedClassName = representedClassName,
            stringPreview = stringPreview,
            childCount = childCount,
        )
}
