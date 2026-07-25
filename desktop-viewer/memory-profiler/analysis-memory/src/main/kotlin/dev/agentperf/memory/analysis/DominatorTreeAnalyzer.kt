@file:Suppress("CyclomaticComplexMethod", "LongMethod")

package dev.agentperf.memory.analysis

import dev.agentperf.memory.model.HeapDump
import dev.agentperf.memory.model.HeapObject
import dev.agentperf.memory.model.ObjectReference
import java.util.ArrayDeque

data class DominatorTreeResult(
    val immediateDominators: Map<Long, Long?>,
    val retainedSizes: Map<Long, Long>,
    val reachableObjectIds: Set<Long>,
)

/** Lengauer-Tarjan immediate dominators over the heap graph with a synthetic super-root. */
class DominatorTreeAnalyzer {
    fun analyze(heapDump: HeapDump): DominatorTreeResult {
        val graph = HeapGraph.from(heapDump)
        if (graph.ids.isEmpty()) return DominatorTreeResult(emptyMap(), emptyMap(), emptySet())

        val count = graph.ids.size + 1
        val successors = Array(count) { mutableListOf<Int>() }
        val idToIndex = graph.ids.withIndex().associate { (index, id) -> id to index + 1 }
        graph.references.forEach { (source, refs) ->
            val sourceIndex = idToIndex[source] ?: return@forEach
            refs.mapNotNullTo(successors[sourceIndex]) { idToIndex[it.targetObjectId] }
            successors[sourceIndex].sort()
        }
        val configuredRoots =
            heapDump.gcRoots
                .mapNotNull { idToIndex[it.objectId] }
                .distinct()
                .sorted()
        val roots = configuredRoots.ifEmpty { (1 until count).toList() }
        successors[SYNTHETIC_ROOT] += roots

        val dfs = IntArray(count)
        val vertex = IntArray(count + 1)
        val parent = IntArray(count) { -1 }
        var dfsCount = 0
        val discovered = BooleanArray(count)
        discovered[SYNTHETIC_ROOT] = true
        val stack = ArrayDeque<Int>()
        stack.add(SYNTHETIC_ROOT)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            dfs[node] = ++dfsCount
            vertex[dfsCount] = node
            successors[node].asReversed().forEach { target ->
                if (!discovered[target]) {
                    discovered[target] = true
                    parent[target] = node
                    stack.add(target)
                }
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
        graph.ids.forEachIndexed { index, id -> retained[index + 1] = graph.shallowSizes[id] ?: 0L }
        for (order in dfsCount downTo 2) {
            val node = vertex[order]
            val dominator = immediate[node]
            if (dominator > SYNTHETIC_ROOT) retained[dominator] += retained[node]
        }

        val immediateById = linkedMapOf<Long, Long?>()
        val retainedById = linkedMapOf<Long, Long>()
        graph.ids.forEachIndexed { index, id ->
            val node = index + 1
            val dominator = immediate[node]
            immediateById[id] =
                if (dfs[node] == 0 || dominator <= SYNTHETIC_ROOT) null else graph.ids[dominator - 1]
            retainedById[id] = if (dfs[node] == 0) graph.shallowSizes[id] ?: 0L else retained[node]
        }
        return DominatorTreeResult(
            immediateDominators = immediateById,
            retainedSizes = retainedById,
            reachableObjectIds = graph.ids.filterIndexedTo(linkedSetOf()) { index, _ -> dfs[index + 1] != 0 },
        )
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
            val references = linkedMapOf<Long, List<ObjectReference>>()
            objects.forEach { heapObject ->
                references[heapObject.objectId] =
                    heapObject.references
                        .filter { it.targetObjectId in knownIds }
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
            )
        }
    }
}
