package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.analysis.InstanceReferenceQuery
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.presentation.MemoryDominatorRow

/** Built with LoadedHeap on the backend dispatcher, reused when switching snapshots. */
internal class MemoryHeapInvestigationIndex(
    heapDump: HeapDump,
) {
    val query = InstanceReferenceQuery(heapDump)
    private val dominatorRows =
        lazy {
            // A large dump can have millions of objects. Reuse the query's object lookup and keep
            // depth memoization in primitive arrays instead of another boxed map per object.
            val ids =
                heapDump.objectRetainedSizes.keys
                    .toLongArray()
                    .apply { sort() }
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
                        current = heapDump.objectImmediateDominators[current]
                    }
                }
                while (pathSize > 0) depths[path[--pathSize]] = ++knownDepth
                return depths[ids.binarySearch(objectId)]
            }
            heapDump.objectRetainedSizes
                .mapNotNull { (objectId, retainedSize) ->
                    val obj = query.objectOf(objectId) ?: return@mapNotNull null
                    MemoryDominatorRow(
                        objectId,
                        obj.className,
                        obj.shallowSize,
                        retainedSize,
                        depthOf(objectId),
                        heapDump.objectImmediateDominators[objectId],
                    )
                }.sortedWith(
                    compareBy<MemoryDominatorRow> { it.depth }
                        .thenByDescending { it.retainedSize }
                        .thenBy { it.className },
                )
        }

    val dominators: List<MemoryDominatorRow> get() = dominatorRows.value

    internal val dominatorsInitialized: Boolean get() = dominatorRows.isInitialized()
}
