package com.androidperformancestudio.memory.app

import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.memory.analysis.ProguardMapping
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapHistogram
import com.androidperformancestudio.memory.model.HprofEngine
import com.androidperformancestudio.memory.model.LeakCanaryReport
import com.androidperformancestudio.memory.storage.MemorySessionUiSettings
import java.nio.file.Path
import java.time.Instant

internal sealed interface LoadedSnapshot : AutoCloseable {
    val engine: HprofEngine
    val id: String
    val rawHprofFile: Path?
    val convertedHprofFile: Path?
    val capturedAt: Instant
    val artifact: CaptureArtifact?
    val source: HeapSnapshotSource
    val histogram: HeapHistogram
    val warning: String?
    val cleanupWarning: String?
    val mapping: ProguardMapping?
    val availableHeaps: List<String>
    val perHeapClasses: Map<String, List<ClassStats>>
    val uiSettings: MemorySessionUiSettings
    val sourceFileDigest: String?
    val analysisSourceDigest: String?
    val mappingDigest: String?
    val indexFile: Path?
    val leakCanaryReport: LeakCanaryReport
    val findings: HeapFindings get() = source.findings()

    fun withLeakReport(report: LeakCanaryReport): LoadedSnapshot

    override fun close() = source.close()
}

internal data class RustLoadedSnapshot(
    override val id: String,
    override val rawHprofFile: Path?,
    override val convertedHprofFile: Path?,
    override val capturedAt: Instant,
    override val source: HeapSnapshotSource,
    override val findings: HeapFindings,
    override val histogram: HeapHistogram,
    override val warning: String? = null,
    override val cleanupWarning: String? = null,
    override val mapping: ProguardMapping? = null,
    override val availableHeaps: List<String> = emptyList(),
    override val perHeapClasses: Map<String, List<ClassStats>> = emptyMap(),
    override val uiSettings: MemorySessionUiSettings = MemorySessionUiSettings(),
    override val sourceFileDigest: String? = null,
    override val analysisSourceDigest: String? = null,
    override val mappingDigest: String? = null,
    override val indexFile: Path? = null,
    override val leakCanaryReport: LeakCanaryReport = LeakCanaryReport(),
    override val artifact: CaptureArtifact? = null,
    val sourceLeases: List<AutoCloseable> = emptyList(),
) : LoadedSnapshot {
    override val engine: HprofEngine get() = HprofEngine.RUST

    override fun withLeakReport(report: LeakCanaryReport): LoadedSnapshot = copy(leakCanaryReport = report)

    override fun close() {
        closeHeapResources(listOf(source) + sourceLeases)
    }
}

internal data class HeapDumpLoadedSnapshot(
    val heapDump: HeapDump,
    override val histogram: HeapHistogram,
    override val source: HeapSnapshotSource,
    override val warning: String? = null,
    override val cleanupWarning: String? = null,
    override val mapping: ProguardMapping? = null,
    override val availableHeaps: List<String> = emptyList(),
    override val perHeapClasses: Map<String, List<ClassStats>> = emptyMap(),
    override val uiSettings: MemorySessionUiSettings = MemorySessionUiSettings(),
    override val sourceFileDigest: String? = null,
    override val analysisSourceDigest: String? = null,
    override val mappingDigest: String? = null,
    override val indexFile: Path? = null,
    override val leakCanaryReport: LeakCanaryReport = LeakCanaryReport(),
) : LoadedSnapshot {
    override val engine: HprofEngine get() = HprofEngine.RUST
    override val id: String get() = heapDump.id
    override val rawHprofFile: Path? get() = heapDump.rawHprofFile
    override val convertedHprofFile: Path? get() = heapDump.convertedHprofFile
    override val capturedAt: Instant get() = heapDump.capturedAt
    override val artifact get() = heapDump.artifact

    override fun withLeakReport(report: LeakCanaryReport): LoadedSnapshot = copy(leakCanaryReport = report)
}

/** Release every owned resource even if an earlier close fails. */
@Suppress("TooGenericExceptionCaught")
internal fun closeHeapResources(
    resources: List<AutoCloseable>,
    cause: Throwable? = null,
) {
    var firstFailure: Exception? = null
    resources.forEach { resource ->
        try {
            resource.close()
        } catch (exception: Exception) {
            val previous = firstFailure
            if (previous == null) {
                firstFailure = exception
            } else if (previous !== exception) {
                previous.addSuppressed(exception)
            }
        }
    }
    firstFailure?.let { failure ->
        if (cause == null) {
            throw failure
        } else if (cause !== failure) {
            cause.addSuppressed(failure)
        }
    }
}
