package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.analysis.InstanceQueryDetail
import com.androidperformancestudio.memory.analysis.InstanceQueryRow
import com.androidperformancestudio.memory.model.ActivityLeakEntry
import com.androidperformancestudio.memory.model.BitmapInstanceStats
import com.androidperformancestudio.memory.model.HeapHistogram
import com.androidperformancestudio.memory.model.LeakSuspect
import com.androidperformancestudio.memory.model.MemoryWarning
import com.androidperformancestudio.memory.presentation.MemoryDominatorRow

internal const val MAX_HEAP_PAGE_SIZE = 4096

internal data class HeapSourceMetadata(
    val format: String,
    val idSize: Int,
    val timestampMillis: Long,
    val classCount: Int,
    val instanceCount: Int,
    val objectArrayCount: Int,
    val primitiveArrayCount: Int,
    val rootCount: Int,
    val warnings: List<MemoryWarning>,
    val heaps: List<String>,
)

internal data class HeapInstancePage(
    val total: Int,
    val rows: List<InstanceQueryRow>,
    val offset: Int = 0,
)

internal data class HeapDominatorPage(
    val total: Int,
    val rows: List<MemoryDominatorRow>,
)

internal data class HeapObjectIdentity(
    val className: String,
    val groupingClassName: String,
    val heapName: String?,
    val shallowSize: Long,
    val kind: String,
)

internal data class HeapLifecycleObject(
    val objectId: Long,
    val className: String,
    val heapName: String?,
)

internal data class HeapFindings(
    val leakSuspects: List<LeakSuspect>,
    val bitmapInstances: List<BitmapInstanceStats>,
    val activityLeaks: List<ActivityLeakEntry>,
    val lifecycleLeakObjects: List<HeapLifecycleObject>,
)

internal interface HeapSnapshotQuery {
    fun identity(objectId: Long): HeapObjectIdentity?

    fun groupingClassNameOf(objectId: Long): String? = identity(objectId)?.groupingClassName

    fun heapNameOf(objectId: Long): String? = identity(objectId)?.heapName

    @Suppress("LongParameterList")
    fun instancesPage(
        classNames: List<String>,
        heapName: String?,
        offset: Int,
        limit: Int,
        ordinaryOnly: Boolean = false,
        anchorObjectId: Long? = null,
    ): HeapInstancePage

    fun detailOf(
        objectId: Long,
        arrayStart: Int = 0,
        arrayLimit: Int = 200,
    ): InstanceQueryDetail?
}

/** Owns the graph independently of the UI. */
internal interface HeapSnapshotSource : AutoCloseable {
    val metadata: HeapSourceMetadata
    val query: HeapSnapshotQuery

    fun histogram(heapName: String? = null): HeapHistogram

    fun dominatorsPage(
        offset: Int,
        limit: Int,
        parentObjectId: Long? = null,
    ): HeapDominatorPage

    fun findings(): HeapFindings
}
