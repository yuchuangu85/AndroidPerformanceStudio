package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapHistogram
import com.androidperformancestudio.memory.model.HeapObject
import com.androidperformancestudio.memory.model.HeapSummary

enum class HistogramSort {
    COUNT,
    SHALLOW_SIZE,
}

class MemoryHistogramAnalyzer {
    fun histogram(
        heapDump: HeapDump,
        sort: HistogramSort = HistogramSort.COUNT,
        retainedSizes: Map<Long, Long> = emptyMap(),
        immediateDominators: Map<Long, Long?> = emptyMap(),
    ): HeapHistogram {
        val totalsByClass = linkedMapOf<String, MutableClassTotals>()
        var objectCount = 0
        var shallowSize = 0L

        fun accumulate(objects: Iterable<HeapObject>) {
            objects.forEach { heapObject ->
                objectCount += 1
                shallowSize += heapObject.shallowSize
                totalsByClass
                    .getOrPut(heapObject.className, ::MutableClassTotals)
                    .add(heapObject.objectId, heapObject.shallowSize, retainedSizes[heapObject.objectId])
            }
        }

        accumulate(heapDump.instances)
        accumulate(heapDump.objectArrays)
        accumulate(heapDump.primitiveArrays)

        val grouped =
            totalsByClass
                .map { (className, totals) ->
                    ClassStats(
                        className = className,
                        instanceCount = totals.instanceCount,
                        shallowSize = totals.shallowSize,
                        retainedSize = totals.combinedRetainedSize(immediateDominators),
                    )
                }.sortedWith(sort.comparator)

        return HeapHistogram(
            summary =
                HeapSummary(
                    objectCount = objectCount,
                    shallowSize = shallowSize,
                    classCount = grouped.size,
                ),
            classes = grouped,
        )
    }

    private class MutableClassTotals {
        var instanceCount: Int = 0
            private set
        var shallowSize: Long = 0L
            private set
        private val retainedSizesByObjectId = linkedMapOf<Long, Long>()

        fun add(
            objectId: Long,
            objectShallowSize: Long,
            objectRetainedSize: Long?,
        ) {
            instanceCount += 1
            shallowSize += objectShallowSize
            objectRetainedSize?.let { retainedSizesByObjectId[objectId] = it }
        }

        fun combinedRetainedSize(immediateDominators: Map<Long, Long?>): Long? {
            val classObjectIds = retainedSizesByObjectId.keys
            return when {
                retainedSizesByObjectId.isEmpty() -> null
                immediateDominators.isEmpty() -> retainedSizesByObjectId.values.sum()
                else ->
                    retainedSizesByObjectId
                        .filterKeys { objectId ->
                            !isDominatedByClassInstance(objectId, classObjectIds, immediateDominators)
                        }.values
                        .sum()
            }
        }

        private fun isDominatedByClassInstance(
            objectId: Long,
            classObjectIds: Set<Long>,
            immediateDominators: Map<Long, Long?>,
        ): Boolean {
            var ancestor = immediateDominators[objectId]
            while (ancestor != null && ancestor !in classObjectIds) {
                ancestor = immediateDominators[ancestor]
            }
            return ancestor != null
        }
    }

    private val HistogramSort.comparator: Comparator<ClassStats>
        get() =
            when (this) {
                HistogramSort.COUNT ->
                    compareByDescending<ClassStats> { it.instanceCount }
                        .thenBy { it.className }
                HistogramSort.SHALLOW_SIZE ->
                    compareByDescending<ClassStats> { it.shallowSize }
                        .thenBy { it.className }
            }
}
