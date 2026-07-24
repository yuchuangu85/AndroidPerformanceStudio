package dev.agentperf.memory.analysis

import dev.agentperf.memory.model.ClassStats
import dev.agentperf.memory.model.HeapDump
import dev.agentperf.memory.model.HeapHistogram
import dev.agentperf.memory.model.HeapObject
import dev.agentperf.memory.model.HeapSummary

enum class HistogramSort {
    COUNT,
    SHALLOW_SIZE,
}

class MemoryHistogramAnalyzer {
    fun histogram(
        heapDump: HeapDump,
        sort: HistogramSort = HistogramSort.COUNT,
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
                    .add(heapObject.shallowSize)
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
                        retainedSize = null,
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

        fun add(objectShallowSize: Long) {
            instanceCount += 1
            shallowSize += objectShallowSize
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
