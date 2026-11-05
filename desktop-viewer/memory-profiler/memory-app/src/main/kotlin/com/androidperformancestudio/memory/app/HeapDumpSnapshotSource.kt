package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.analysis.InstanceReferenceQuery
import com.androidperformancestudio.memory.analysis.InstanceQueryDetail
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapHistogram
import com.androidperformancestudio.memory.model.HeapInstance
import com.androidperformancestudio.memory.model.HeapObjectArray
import com.androidperformancestudio.memory.presentation.MemoryDominatorRow

/** Adapts an already-built heap graph, such as a Java heap trace fallback, to snapshot APIs. */
internal class HeapDumpSnapshotSource(
    private val heap: HeapDump,
    private val loadedHistogram: HeapHistogram,
    private val loadedHeaps: List<String>,
    private val loadedPerHeapClasses: Map<String, List<com.androidperformancestudio.memory.model.ClassStats>>,
) : HeapSnapshotSource {
    private val delegate = InstanceReferenceQuery(heap)

    override val metadata: HeapSourceMetadata by lazy {
        HeapSourceMetadata(
            format = heap.format,
            idSize = heap.idSize,
            timestampMillis = heap.timestampMillis,
            classCount = heap.classes.size,
            instanceCount = heap.instances.size,
            objectArrayCount = heap.objectArrays.size,
            primitiveArrayCount = heap.primitiveArrays.size,
            rootCount = heap.gcRoots.size,
            warnings = heap.warnings,
            heaps = loadedHeaps,
        )
    }

    override val query: HeapSnapshotQuery =
        object : HeapSnapshotQuery {
            override fun identity(objectId: Long): HeapObjectIdentity? {
                val detail = delegate.detailOf(objectId) ?: return null
                val heapObject = delegate.objectOf(objectId)
                return HeapObjectIdentity(
                    className = detail.className,
                    groupingClassName = delegate.groupingClassNameOf(objectId) ?: detail.className,
                    heapName = delegate.heapNameOf(objectId),
                    shallowSize = detail.shallowSize,
                    kind =
                        when (heapObject) {
                            is HeapInstance -> "INSTANCE"
                            is HeapObjectArray -> "OBJECT_ARRAY"
                            null -> "CLASS"
                            else -> "PRIMITIVE_ARRAY"
                        },
                )
            }

            override fun groupingClassNameOf(objectId: Long): String? = delegate.groupingClassNameOf(objectId)

            override fun heapNameOf(objectId: Long): String? = delegate.heapNameOf(objectId)

            override fun instancesPage(
                classNames: List<String>,
                heapName: String?,
                offset: Int,
                limit: Int,
                ordinaryOnly: Boolean,
                anchorObjectId: Long?,
            ): HeapInstancePage {
                require(offset >= 0 && limit in 1..MAX_HEAP_PAGE_SIZE)
                val rows =
                    classNames
                        .distinct()
                        .flatMap { delegate.instancesOf(it, heapName) }
                        .filter { !ordinaryOnly || delegate.objectOf(it.objectId) is HeapInstance }
                        .sortedBy { it.objectId }
                val anchorIndex = anchorObjectId?.let { id -> rows.indexOfFirst { it.objectId == id } } ?: -1
                val pageOffset = if (anchorIndex >= 0) anchorIndex / limit * limit else offset
                return HeapInstancePage(rows.size, rows.drop(pageOffset).take(limit), pageOffset)
            }

            override fun detailOf(
                objectId: Long,
                arrayStart: Int,
                arrayLimit: Int,
            ): InstanceQueryDetail? = delegate.detailOf(objectId, arrayStart, arrayLimit)
        }

    private val dominatorRows by lazy {
        val ids = heap.objectRetainedSizes.keys.toLongArray().apply { sort() }
        val depths = IntArray(ids.size) { -1 }
        val path = IntArray(ids.size)

        fun depthOf(objectId: Long): Int {
            var current: Long? = objectId
            var pathSize = 0
            var knownDepth = -1
            while (current != null && pathSize < path.size) {
                val index = ids.binarySearch(current)
                if (index < 0) {
                    current = null
                } else if (depths[index] >= 0) {
                    knownDepth = depths[index]
                    current = null
                } else {
                    path[pathSize++] = index
                    current = heap.objectImmediateDominators[current]
                }
            }
            while (pathSize > 0) depths[path[--pathSize]] = ++knownDepth
            return depths[ids.binarySearch(objectId)]
        }

        heap.objectRetainedSizes
            .mapNotNull { (objectId, retainedSize) ->
                val heapObject = delegate.objectOf(objectId) ?: return@mapNotNull null
                MemoryDominatorRow(
                    objectId = objectId,
                    className = heapObject.className,
                    shallowSize = heapObject.shallowSize,
                    retainedSize = retainedSize,
                    depth = depthOf(objectId),
                    parentObjectId = heap.objectImmediateDominators[objectId],
                )
            }.sortedWith(
                compareBy<MemoryDominatorRow> { it.depth }
                    .thenByDescending { it.retainedSize }
                    .thenBy { it.className },
            )
    }

    override fun histogram(heapName: String?): HeapHistogram {
        if (heapName == null) return loadedHistogram
        val classes = loadedPerHeapClasses[heapName].orEmpty()
        return loadedHistogram.copy(
            summary =
                loadedHistogram.summary.copy(
                    objectCount = classes.sumOf { it.instanceCount },
                    shallowSize = classes.sumOf { it.shallowSize },
                    classCount = classes.size,
                    heapSegments = loadedHistogram.summary.heapSegments.filter { it.name == heapName },
                ),
            classes = classes,
        )
    }

    override fun dominatorsPage(
        offset: Int,
        limit: Int,
        parentObjectId: Long?,
    ): HeapDominatorPage {
        require(offset >= 0 && limit in 1..MAX_HEAP_PAGE_SIZE)
        val knownIds = dominatorRows.mapTo(hashSetOf()) { it.objectId }
        val rows =
            dominatorRows.filter {
                if (parentObjectId == null) it.parentObjectId !in knownIds else it.parentObjectId == parentObjectId
            }
        return HeapDominatorPage(rows.size, rows.drop(offset).take(limit))
    }

    override fun findings(): HeapFindings =
        HeapFindings(
            leakSuspects = heap.leakSuspects,
            bitmapInstances = heap.bitmapInstances,
            activityLeaks = heap.activityLeaks,
            lifecycleLeakObjects =
                heap.instances
                    .asSequence()
                    .filter { it.objectId in heap.activityFragmentLeakObjectIds }
                    .map { HeapLifecycleObject(it.objectId, it.className, heap.heapByObjectId[it.objectId]) }
                    .toList(),
        )

    override fun close() = Unit
}
