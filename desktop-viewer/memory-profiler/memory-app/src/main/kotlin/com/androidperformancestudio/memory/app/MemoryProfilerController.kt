@file:Suppress("MaxLineLength")

package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.analysis.HeapDiffAnalyzer
import com.androidperformancestudio.memory.analysis.InstanceQueryDetail
import com.androidperformancestudio.memory.analysis.InstanceQueryRow
import com.androidperformancestudio.memory.analysis.InstanceReferenceQuery
import com.androidperformancestudio.memory.analysis.ProguardMapping
import com.androidperformancestudio.memory.memory_app.generated.resources.Res
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
import com.androidperformancestudio.memory.memory_app.generated.resources.unable_to_analyze_hprof
import com.androidperformancestudio.memory.memory_app.generated.resources.unable_to_capture_native_heap
import com.androidperformancestudio.memory.memory_app.generated.resources.unable_to_import_java_heap
import com.androidperformancestudio.memory.memory_app.generated.resources.unable_to_import_native_heap
import com.androidperformancestudio.memory.memory_app.generated.resources.unable_to_load_mapping
import com.androidperformancestudio.memory.model.BitmapDumpComparison
import com.androidperformancestudio.memory.model.BitmapDumpSession
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapDiffMatchMode
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapHistogram
import com.androidperformancestudio.memory.model.NativeHeapAnalysis
import com.androidperformancestudio.memory.model.NativeHeapTrace
import com.androidperformancestudio.memory.presentation.MemoryArrangeBy
import com.androidperformancestudio.memory.presentation.MemoryClassScope
import com.androidperformancestudio.memory.presentation.MemoryDeviceOption
import com.androidperformancestudio.memory.presentation.MemoryHistogramSort
import com.androidperformancestudio.memory.presentation.MemoryInstanceDetail
import com.androidperformancestudio.memory.presentation.MemoryInstanceField
import com.androidperformancestudio.memory.presentation.MemoryInstanceRow
import com.androidperformancestudio.memory.presentation.MemoryLeakFilter
import com.androidperformancestudio.memory.presentation.MemoryProcessOption
import com.androidperformancestudio.memory.presentation.MemoryProfilerError
import com.androidperformancestudio.memory.presentation.MemoryProfilerState
import com.androidperformancestudio.memory.presentation.MemoryProfilerViewMode
import com.androidperformancestudio.memory.storage.MemorySessionMetadata
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path

internal data class LoadedHeap(
    val heapDump: HeapDump,
    val histogram: HeapHistogram,
    val warning: String? = null,
    val cleanupWarning: String? = null,
    val mapping: ProguardMapping? = null,
    val availableHeaps: List<String> = emptyList(),
    val perHeapClasses: Map<String, List<ClassStats>> = emptyMap(),
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

    suspend fun listSessions(): MemoryBackendResult<List<MemorySessionMetadata>> = MemoryBackendResult.Success(emptyList())

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

@Suppress("TooManyFunctions")
internal class MemoryProfilerController(
    private val backend: MemoryProfilerBackend,
    private val language: UiLanguage = UiLanguage.ENGLISH,
) {
    private val mutableState = MutableStateFlow(MemoryProfilerState())

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

    private var previousBitmapDump: LoadedBitmapDump? = null

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
                mutableState.value =
                    mutableState.value.copy(
                        devices = result.value,
                        selectedDeviceSerial = retainedSerial,
                        processes = if (retainedSerial == null) emptyList() else mutableState.value.processes,
                        selectedProcessId = if (retainedSerial == null) null else mutableState.value.selectedProcessId,
                        error = null,
                    )
                if (retainedSerial == null && automaticSerial != null) {
                    selectDevice(automaticSerial)
                }
            }
        }
    }

    suspend fun selectDevice(serial: String) {
        mutableState.value =
            mutableState.value.copy(
                selectedDeviceSerial = serial,
                selectedProcessId = null,
                processes = emptyList(),
                error = null,
            )
        when (val result = backend.listProcesses(serial)) {
            is MemoryBackendResult.Failure -> showFailure(result)
            is MemoryBackendResult.Success ->
                mutableState.value = mutableState.value.copy(processes = result.value, error = null)
        }
    }

    fun selectProcess(pid: Int) {
        if (mutableState.value.processes.any { it.pid == pid }) {
            mutableState.value = mutableState.value.copy(selectedProcessId = pid, error = null)
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
                selectedClassInstances = instances,
                selectedInstanceDetail = null,
            )
    }

    fun selectInstance(objectId: Long) {
        val detail = instanceQuery?.detailOf(objectId)?.toPresentation()
        mutableState.value = mutableState.value.copy(selectedInstanceDetail = detail)
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
                selectedClassInstances = emptyList(),
                selectedInstanceDetail = null,
            )
    }

    fun changeClassScope(scope: MemoryClassScope) {
        mutableState.value = mutableState.value.copy(classScope = scope)
    }

    fun changeLeakFilter(filter: MemoryLeakFilter) {
        mutableState.value = mutableState.value.copy(leakFilter = filter)
    }

    fun changeArrangeBy(arrangeBy: MemoryArrangeBy) {
        mutableState.value = mutableState.value.copy(arrangeBy = arrangeBy)
    }

    fun changeSearchText(text: String) {
        mutableState.value = mutableState.value.copy(searchText = text)
    }

    fun changeMatchCase(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(matchCase = enabled)
    }

    fun changeUseRegex(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(useRegex = enabled)
    }

    suspend fun dumpHeap() {
        val snapshot = mutableState.value
        val serial = snapshot.selectedDeviceSerial ?: return
        val process = snapshot.processes.firstOrNull { it.pid == snapshot.selectedProcessId } ?: return
        mutableState.value =
            snapshot.copy(
                isDumping = true,
                operationMessage = localizedStringResource(Res.string.dumping_heap_for, language, process.name),
                error = null,
                warning = null,
                cleanupWarning = null,
            )
        applyLoadedResult(backend.capture(serial, process))
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun dumpBitmaps() {
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
            is MemoryBackendResult.Success ->
                mutableState.value =
                    mutableState.value.copy(
                        isDumping = false,
                        operationMessage = null,
                        error = null,
                        nativeHeapTrace = result.value.trace,
                        nativeHeapAnalysis = result.value.analysis,
                    )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun importHprof(file: Path) {
        mutableState.value =
            mutableState.value.copy(
                isDumping = true,
                operationMessage = localizedStringResource(Res.string.importing, language, file.fileName),
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
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun importMapping(file: Path) {
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
                    null ->
                        mutableState.value =
                            mutableState.value.copy(
                                isDumping = false,
                                operationMessage = null,
                                error = null,
                                mappingLoaded = true,
                                warning = localizedStringResource(Res.string.mapping_imported, language),
                            )
                    else -> applyLoadedResult(MemoryBackendResult.Success(heap), compareWithPrevious = false)
                }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun importNativeHeap(file: Path) {
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
        mutableState.value =
            mutableState.value.copy(
                isDumping = true,
                operationMessage = localizedStringResource(Res.string.loading_session, language, metadata.packageName),
                error = null,
                warning = null,
                cleanupWarning = null,
            )
        applyLoadedResult(backend.loadSession(metadata))
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

    fun exportBitmapSession(output: Path) {
        loadedBitmapDump?.let { backend.exportBitmapSession(it.session, output) }
    }

    fun exportBitmapComparison(output: Path) {
        mutableState.value.bitmapDumpComparison?.let { backend.exportBitmapComparison(it, output) }
    }

    private fun applyLoadedResult(
        result: MemoryBackendResult<LoadedHeap>,
        compareWithPrevious: Boolean = true,
    ) {
        when (result) {
            is MemoryBackendResult.Failure -> showFailure(result)
            is MemoryBackendResult.Success -> {
                val previous = loadedHeap
                loadedHeap = result.value
                instanceQuery = InstanceReferenceQuery(result.value.heapDump)
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
                        error = null,
                        warning = result.value.warning,
                        cleanupWarning = result.value.cleanupWarning,
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
                        mappingLoaded = result.value.mapping != null,
                        selectedClassName = null,
                        selectedClassInstances = emptyList(),
                        selectedInstanceDetail = null,
                        availableHeaps = result.value.availableHeaps,
                        heapFilter = null,
                        heapBaseClasses = result.value.histogram.classes,
                        classScope = MemoryClassScope.ALL,
                        leakFilter = MemoryLeakFilter.NONE,
                        arrangeBy = MemoryArrangeBy.CLASS,
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

    private fun showFailure(failure: MemoryBackendResult.Failure) {
        mutableState.value =
            mutableState.value.copy(
                isDumping = false,
                operationMessage = null,
                error = MemoryProfilerError(title = failure.title, detail = failure.detail),
            )
    }

    private fun InstanceQueryRow.toPresentation(): MemoryInstanceRow =
        MemoryInstanceRow(
            objectId = objectId,
            index = index,
            shallowSize = shallowSize,
            retainedSize = retainedSize,
            depth = depth,
            reachable = reachable,
            nativeSize = nativeSize,
        )

    private fun InstanceQueryDetail.toPresentation(): MemoryInstanceDetail =
        MemoryInstanceDetail(
            objectId = objectId,
            className = className,
            shallowSize = shallowSize,
            retainedSize = retainedSize,
            depth = depth,
            isArray = isArray,
            elementCount = elementCount,
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
        )
}
