package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapHistogram
import com.androidperformancestudio.memory.model.HeapObject
import com.androidperformancestudio.memory.model.HeapSummary
import com.androidperformancestudio.memory.model.MemoryHeapNames

enum class HistogramSort {
    COUNT,
    SHALLOW_SIZE,
}

class MemoryHistogramAnalyzer {
    /**
     * Aggregates class statistics over all objects, or only those belonging to [heapName] when it
     * is non-null (one of [MemoryHeapNames]). Retained sizes are always the global dominator
     * values; only counts and shallow sizes are heap-scoped.
     */
    @Suppress("LongParameterList")
    fun histogram(
        heapDump: HeapDump,
        heapName: String? = null,
        sort: HistogramSort = HistogramSort.COUNT,
        retainedSizes: Map<Long, Long> = emptyMap(),
        immediateDominators: Map<Long, Long?> = emptyMap(),
        deobfuscator: ProguardMapping? = null,
    ): HeapHistogram {
        val totalsByClass = linkedMapOf<String, MutableClassTotals>()
        val depthByName = hierarchyDepthByName(heapDump)
        var objectCount = 0
        var shallowSize = 0L

        fun accumulate(objects: Iterable<HeapObject>) {
            objects.forEach { heapObject ->
                if (heapName != null && heapDump.heapByObjectId[heapObject.objectId] != heapName) {
                    return@forEach
                }
                objectCount += 1
                shallowSize += heapObject.shallowSize
                totalsByClass
                    .getOrPut(heapObject.className, ::MutableClassTotals)
                    .add(
                        heapObject.objectId,
                        heapObject.shallowSize,
                        retainedSizes[heapObject.objectId],
                        (heapObject as? com.androidperformancestudio.memory.model.HeapInstance)?.nativeSizeBytes,
                    )
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
                        obfuscatedClassName =
                            deobfuscator?.obfuscatedName(className)?.takeIf { it != className },
                        hierarchyDepth = depthByName[className],
                        nativeSize = totals.nativeSize.takeIf { it > 0L },
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

    /** Canonical heap labels actually present in [heapDump], in display order. */
    fun heapNamesOf(heapDump: HeapDump): List<String> {
        val present = heapDump.heapByObjectId.values.toHashSet()
        return MemoryHeapNames.ordered.filter { it in present }
    }

    private class MutableClassTotals {
        var instanceCount: Int = 0
            private set
        var shallowSize: Long = 0L
            private set
        var nativeSize: Long = 0L
            private set
        private val retainedSizesByObjectId = linkedMapOf<Long, Long>()

        fun add(
            objectId: Long,
            objectShallowSize: Long,
            objectRetainedSize: Long?,
            objectNativeSize: Long?,
        ) {
            instanceCount += 1
            shallowSize += objectShallowSize
            objectRetainedSize?.let { retainedSizesByObjectId[objectId] = it }
            nativeSize += objectNativeSize ?: 0L
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

/** Maps each class name to its superclass-hierarchy depth (java.lang.Object == 0). */
private fun hierarchyDepthByName(heapDump: HeapDump): Map<String, Int> {
    val superClassById = heapDump.classes.associate { it.objectId to it.superClassObjectId }

    fun depthOf(classObjectId: Long): Int {
        var depth = 0
        var current = classObjectId
        val visited = hashSetOf<Long>()
        while (current != 0L && visited.add(current)) {
            val superId = superClassById[current] ?: break
            depth += 1
            current = superId
        }
        return depth
    }
    return heapDump.classes
        .groupBy({ it.name }, { depthOf(it.objectId) })
        .mapValues { (_, depths) -> depths.max() }
}
