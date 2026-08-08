@file:Suppress("CyclomaticComplexMethod", "LongMethod", "MagicNumber")

package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapObject
import com.androidperformancestudio.memory.model.ObjectReference
import java.util.ArrayDeque

data class DominatorTreeResult(
    val immediateDominators: Map<Long, Long?>,
    val retainedSizes: Map<Long, Long>,
    val reachableObjectIds: Set<Long>,
)

/**
 * Reachability-first immediate dominators.
 *
 * A BFS from the GC roots first computes the reachable (retainable) object subset, then the
 * Lengauer-Tarjan dominator tree is built only over that subset using compact indices. Unreachable
 * objects have no dominator and keep their shallow size. For large dumps with lots of garbage this
 * avoids allocating dominator-tree arrays for unreachable objects, substantially cutting peak
 * memory and wall time (mirrors the reachability-first approach of LeakCanary / Android Studio).
 */
class DominatorTreeAnalyzer {
    fun analyze(
        heapDump: HeapDump,
        onProgress: (Int) -> Unit = {},
    ): DominatorTreeResult = analyze(HeapGraph.from(heapDump), onProgress)

    internal fun analyze(
        graph: HeapGraph,
        onProgress: (Int) -> Unit = {},
    ): DominatorTreeResult {
        if (graph.ids.isEmpty()) {
            onProgress(100)
            return DominatorTreeResult(emptyMap(), emptyMap(), emptySet())
        }
        onProgress(0)
        val reachableIds =
            if (graph.roots.isEmpty()) {
                graph.ids
            } else {
                reachabilityBfs(graph, graph.roots)
            }
        onProgress(30)

        val indexOf = reachableIds.withIndex().associate { (index, id) -> id to index + 1 }
        val count = reachableIds.size + 1
        val successors = Array(count) { mutableListOf<Int>() }
        graph.references.forEach { (source, refs) ->
            val sourceIndex = indexOf[source] ?: return@forEach
            refs.mapNotNullTo(successors[sourceIndex]) { indexOf[it.targetObjectId] }
            successors[sourceIndex].sort()
        }
        val rootIndices =
            if (graph.roots.isEmpty()) {
                (1 until count).toList()
            } else {
                graph.roots
                    .mapNotNull { indexOf[it] }
                    .distinct()
                    .sorted()
            }
        successors[SYNTHETIC_ROOT] += rootIndices

        val dfs = IntArray(count)
        val vertex = IntArray(count + 1)
        val parent = IntArray(count) { -1 }
        var dfsCount = 0
        val nextSuccessor = IntArray(count)
        val stack = ArrayDeque<Int>()
        dfs[SYNTHETIC_ROOT] = ++dfsCount
        vertex[dfsCount] = SYNTHETIC_ROOT
        stack.add(SYNTHETIC_ROOT)
        while (stack.isNotEmpty()) {
            val node = stack.last()
            val successorIndex = nextSuccessor[node]
            if (successorIndex >= successors[node].size) {
                stack.removeLast()
                continue
            }
            nextSuccessor[node] = successorIndex + 1
            val target = successors[node][successorIndex]
            if (dfs[target] == 0) {
                parent[target] = node
                dfs[target] = ++dfsCount
                vertex[dfsCount] = target
                stack.add(target)
            }
        }

        val predecessors = Array(count) { mutableListOf<Int>() }
        successors.forEachIndexed { source, targets ->
            if (dfs[source] != 0) {
                targets.filter { dfs[it] != 0 }.forEach { predecessors[it] += source }
            }
        }
        val semi = IntArray(count) { dfs[it] }
        val label = IntArray(count) { it }
        val ancestor = IntArray(count) { -1 }
        val immediate = IntArray(count) { -1 }
        val bucket = Array(count) { mutableListOf<Int>() }

        fun compress(node: Int) {
            val parentAncestor = ancestor[node].takeIf { it >= 0 }?.let(ancestor::get) ?: -1
            if (parentAncestor >= 0) {
                compress(ancestor[node])
                if (semi[label[ancestor[node]]] < semi[label[node]]) label[node] = label[ancestor[node]]
                ancestor[node] = ancestor[ancestor[node]]
            }
        }

        fun eval(node: Int): Int {
            if (ancestor[node] < 0) return label[node]
            compress(node)
            return label[node]
        }

        for (order in dfsCount downTo 2) {
            val node = vertex[order]
            predecessors[node].forEach { predecessor ->
                val candidate = eval(predecessor)
                if (semi[candidate] < semi[node]) semi[node] = semi[candidate]
            }
            bucket[vertex[semi[node]]] += node
            ancestor[node] = parent[node]
            val parentNode = parent[node]
            bucket[parentNode].forEach { candidate ->
                val evaluated = eval(candidate)
                immediate[candidate] = if (semi[evaluated] < semi[candidate]) evaluated else parentNode
            }
            bucket[parentNode].clear()
        }
        for (order in 2..dfsCount) {
            val node = vertex[order]
            if (immediate[node] != vertex[semi[node]]) immediate[node] = immediate[immediate[node]]
        }
        immediate[SYNTHETIC_ROOT] = SYNTHETIC_ROOT

        val retained = LongArray(count)
        reachableIds.forEachIndexed { index, id -> retained[index + 1] = graph.shallowSizes[id] ?: 0L }
        for (order in dfsCount downTo 2) {
            val node = vertex[order]
            val dominator = immediate[node]
            if (dominator > SYNTHETIC_ROOT) retained[dominator] += retained[node]
        }
        onProgress(70)

        val immediateById = linkedMapOf<Long, Long?>()
        val retainedById = linkedMapOf<Long, Long>()
        graph.ids.forEach { id ->
            val node = indexOf[id]
            if (node == null) {
                immediateById[id] = null
                retainedById[id] = graph.shallowSizes[id] ?: 0L
            } else {
                val dominator = immediate[node]
                immediateById[id] =
                    if (dfs[node] == 0 || dominator <= SYNTHETIC_ROOT) null else reachableIds[dominator - 1]
                retainedById[id] = if (dfs[node] == 0) graph.shallowSizes[id] ?: 0L else retained[node]
            }
        }
        onProgress(100)
        return DominatorTreeResult(
            immediateDominators = immediateById,
            retainedSizes = retainedById,
            reachableObjectIds = reachableIds.toSet(),
        )
    }

    private fun reachabilityBfs(
        graph: HeapGraph,
        roots: List<Long>,
    ): List<Long> {
        val visited = linkedSetOf<Long>()
        val queue = ArrayDeque<Long>()
        roots.forEach { if (visited.add(it)) queue.add(it) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            graph.references[current].orEmpty().forEach { reference ->
                if (visited.add(reference.targetObjectId)) queue.add(reference.targetObjectId)
            }
        }
        return visited.toList()
    }

    companion object {
        private const val SYNTHETIC_ROOT = 0
    }
}

internal data class HeapGraph(
    val ids: List<Long>,
    val shallowSizes: Map<Long, Long>,
    val classNames: Map<Long, String>,
    val references: Map<Long, List<ObjectReference>>,
    val roots: List<Long>,
) {
    companion object {
        fun from(heapDump: HeapDump): HeapGraph {
            val objects =
                buildList<HeapObject> {
                    addAll(heapDump.instances)
                    addAll(heapDump.objectArrays)
                    addAll(heapDump.primitiveArrays)
                }
            val ids = (objects.map(HeapObject::objectId) + heapDump.classes.map { it.objectId }).distinct().sorted()
            val knownIds = ids.toHashSet()
            val referenceClassIds = heapDump.classIdsAssignableTo("java.lang.ref.Reference")
            val referenceHolderIds =
                heapDump.instances
                    .filterTo(hashSetOf()) {
                        it.classObjectId in referenceClassIds ||
                            it.className == "java.lang.ref.WeakReference" ||
                            it.className == "java.lang.ref.SoftReference" ||
                            it.className == "java.lang.ref.PhantomReference"
                    }.mapTo(hashSetOf(), HeapObject::objectId)
            val references = linkedMapOf<Long, List<ObjectReference>>()
            objects.forEach { heapObject ->
                references[heapObject.objectId] =
                    heapObject.references
                        .filterNot {
                            heapObject.objectId in referenceHolderIds && it.fieldName == "referent"
                        }.filter { it.targetObjectId in knownIds }
                        .sortedWith(compareBy<ObjectReference> { it.fieldName }.thenBy { it.targetObjectId })
            }
            heapDump.classes.forEach { heapClass ->
                references[heapClass.objectId] =
                    heapClass.staticReferences
                        .filter { it.targetObjectId in knownIds }
                        .sortedWith(compareBy<ObjectReference> { it.fieldName }.thenBy { it.targetObjectId })
            }
            return HeapGraph(
                ids = ids,
                shallowSizes = objects.associate { it.objectId to it.shallowSize },
                classNames =
                    buildMap {
                        objects.forEach { put(it.objectId, it.className) }
                        heapDump.classes.forEach { put(it.objectId, it.name) }
                    },
                references = references,
                roots =
                    heapDump.gcRoots
                        .map { it.objectId }
                        .filter { it in knownIds }
                        .distinct()
                        .sorted(),
            )
        }
    }
}

/** HPROF class ids assignable to [baseClassName], including indirect subclasses. */
internal fun HeapDump.classIdsAssignableTo(baseClassName: String): Set<Long> {
    val classesById = classes.associateBy { it.objectId }
    val baseIds = classes.filterTo(hashSetOf()) { it.name == baseClassName }.mapTo(hashSetOf()) { it.objectId }
    if (baseIds.isEmpty()) return emptySet()
    return classes
        .filterTo(hashSetOf()) { candidate ->
            val visited = hashSetOf<Long>()
            var current: Long? = candidate.objectId
            while (current != null && current != 0L && visited.add(current)) {
                if (current in baseIds) return@filterTo true
                current = classesById[current]?.superClassObjectId
            }
            false
        }.mapTo(hashSetOf()) { it.objectId }
}
