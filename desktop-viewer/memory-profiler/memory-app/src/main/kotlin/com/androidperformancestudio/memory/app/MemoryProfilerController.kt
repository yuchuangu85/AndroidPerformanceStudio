package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.presentation.MemoryDeviceOption
import com.androidperformancestudio.memory.presentation.MemoryHistogramSort
import com.androidperformancestudio.memory.presentation.MemoryProcessOption
import com.androidperformancestudio.memory.presentation.MemoryProfilerError
import com.androidperformancestudio.memory.presentation.MemoryProfilerState
import dev.agentperf.memory.model.HeapDump
import dev.agentperf.memory.model.HeapHistogram
import dev.agentperf.memory.analysis.HeapDiffAnalyzer
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

internal interface MemoryProfilerBackend {
    suspend fun listDevices(): MemoryBackendResult<List<MemoryDeviceOption>>

    suspend fun listProcesses(serial: String): MemoryBackendResult<List<MemoryProcessOption>>

    suspend fun capture(
        serial: String,
        process: MemoryProcessOption,
    ): MemoryBackendResult<LoadedHeap>

    suspend fun importHprof(file: Path): MemoryBackendResult<LoadedHeap>

    suspend fun importHprof(
        file: Path,
        onProgress: (Int) -> Unit,
    ): MemoryBackendResult<LoadedHeap> = importHprof(file)

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
}

@Suppress("TooManyFunctions")
internal class MemoryProfilerController(
    private val backend: MemoryProfilerBackend,
) {
    private val mutableState = MutableStateFlow(MemoryProfilerState())

    val state: StateFlow<MemoryProfilerState> = mutableState.asStateFlow()

    var loadedHeap: LoadedHeap? = null
        private set

    suspend fun refreshDevices() {
        when (val result = backend.listDevices()) {
            is MemoryBackendResult.Failure -> showFailure(result)
            is MemoryBackendResult.Success -> {
                val currentSerial = mutableState.value.selectedDeviceSerial
                val selected = currentSerial?.takeIf { serial -> result.value.any { it.serial == serial && it.online } }
                mutableState.value =
                    mutableState.value.copy(
                        devices = result.value,
                        selectedDeviceSerial = selected,
                        processes = if (selected == null) emptyList() else mutableState.value.processes,
                        selectedProcessId = if (selected == null) null else mutableState.value.selectedProcessId,
                        error = null,
                    )
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

    suspend fun dumpHeap() {
        val snapshot = mutableState.value
        val serial = snapshot.selectedDeviceSerial ?: return
        val process = snapshot.processes.firstOrNull { it.pid == snapshot.selectedProcessId } ?: return
        mutableState.value =
            snapshot.copy(
                isDumping = true,
                operationMessage = "Dumping heap for ${process.name}…",
                error = null,
                warning = null,
                cleanupWarning = null,
            )
        applyLoadedResult(backend.capture(serial, process))
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun importHprof(file: Path) {
        mutableState.value =
            mutableState.value.copy(
                isDumping = true,
                operationMessage = "Importing ${file.fileName}…",
                error = null,
                warning = null,
                cleanupWarning = null,
            )
        val result =
            try {
                backend.importHprof(file) { progress ->
                    mutableState.value =
                        mutableState.value.copy(operationMessage = "Importing ${file.fileName}… $progress%")
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: OutOfMemoryError) {
                MemoryBackendResult.Failure(
                    title = "Unable to analyze HPROF",
                    detail =
                        "The parser ran out of memory while analyzing this file. " +
                            "Try closing other workspaces or increase the desktop application's maximum heap size.",
                )
            } catch (exception: Exception) {
                MemoryBackendResult.Failure(
                    title = "Unable to analyze HPROF",
                    detail = exception.message ?: exception::class.simpleName.orEmpty(),
                )
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

    private fun applyLoadedResult(result: MemoryBackendResult<LoadedHeap>) {
        when (result) {
            is MemoryBackendResult.Failure -> showFailure(result)
            is MemoryBackendResult.Success -> {
                val previous = loadedHeap
                loadedHeap = result.value
                mutableState.value =
                    mutableState.value.copy(
                        summary = result.value.histogram.summary,
                        classes = result.value.histogram.classes,
                        activityCount = result.value.histogram.classes
                            .filter { it.className.endsWith("Activity") }
                            .sumOf { it.instanceCount },
                        leakSuspects = result.value.heapDump.leakSuspects,
                        isDumping = false,
                        operationMessage = null,
                        error = null,
                        warning = result.value.warning,
                        cleanupWarning = result.value.cleanupWarning,
                        heapDiff = previous?.let { HeapDiffAnalyzer().diff(it.histogram.classes, result.value.histogram.classes) },
                        bitmapInstances = result.value.heapDump.bitmapInstances,
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
}
