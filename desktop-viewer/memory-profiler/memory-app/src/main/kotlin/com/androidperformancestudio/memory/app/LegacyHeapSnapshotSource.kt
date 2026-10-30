package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.analysis.InstanceQueryDetail
import com.androidperformancestudio.memory.model.HeapHistogram
import com.androidperformancestudio.memory.model.HeapInstance
import com.androidperformancestudio.memory.model.HeapObjectArray

internal class LegacyHeapSnapshotSource(
    private val loaded: LoadedHeap,
) : HeapSnapshotSource {
    private val heap get() = loaded.heapDump

    override val metadata: HeapSourceMetadata by lazy {
        HeapSourceMetadata(
            heap.format,
            heap.idSize,
            heap.timestampMillis,
            heap.classes.size,
            heap.instances.size,
            heap.objectArrays.size,
            heap.primitiveArrays.size,
            heap.gcRoots.size,
            heap.warnings,
            loaded.availableHeaps,
        )
    }

    override val query: HeapSnapshotQuery =
        loaded.investigationIndex.snapshotQuery ?: object : HeapSnapshotQuery {
            private val delegate get() = loaded.investigationIndex.query

            override fun identity(objectId: Long): HeapObjectIdentity? {
                val detail = delegate.detailOf(objectId) ?: return null
                val obj = delegate.objectOf(objectId)
                return HeapObjectIdentity(
                    detail.className,
                    delegate.groupingClassNameOf(objectId) ?: detail.className,
                    delegate.heapNameOf(objectId),
                    detail.shallowSize,
                    when (obj) {
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
        }.also { loaded.investigationIndex.snapshotQuery = it }

    override fun histogram(heapName: String?): HeapHistogram {
        if (heapName == null) return loaded.histogram
        val classes = loaded.perHeapClasses[heapName].orEmpty()
        return loaded.histogram.copy(
            summary =
                loaded.histogram.summary.copy(
                    objectCount = classes.sumOf { it.instanceCount },
                    shallowSize = classes.sumOf { it.shallowSize },
                    classCount = classes.size,
                    heapSegments =
                        loaded.histogram.summary.heapSegments
                            .filter { it.name == heapName },
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
        val all = loaded.investigationIndex.dominators
        val knownIds = all.mapTo(hashSetOf()) { it.objectId }
        val rows =
            all.filter {
                if (parentObjectId == null) it.parentObjectId !in knownIds else it.parentObjectId == parentObjectId
            }
        return HeapDominatorPage(rows.size, rows.drop(offset).take(limit))
    }

    override fun findings(): HeapFindings =
        HeapFindings(
            heap.leakSuspects,
            heap.bitmapInstances,
            heap.activityLeaks,
            heap.instances
                .asSequence()
                .filter { it.objectId in heap.activityFragmentLeakObjectIds }
                .map { HeapLifecycleObject(it.objectId, it.className, heap.heapByObjectId[it.objectId]) }
                .toList(),
        )

    override fun close() = Unit
}
