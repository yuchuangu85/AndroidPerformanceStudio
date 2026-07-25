@file:Suppress("CyclomaticComplexMethod", "LongMethod", "MagicNumber", "MaxLineLength")

package dev.agentperf.memory.analysis

import dev.agentperf.memory.model.BitmapInstanceStats
import dev.agentperf.memory.model.ClassStats
import dev.agentperf.memory.model.HeapDiff
import dev.agentperf.memory.model.HeapDiffEntry
import dev.agentperf.memory.model.HeapDump
import dev.agentperf.memory.model.HeapHistogram
import dev.agentperf.memory.model.HeapInstance
import dev.agentperf.memory.model.LeakSuspect
import dev.agentperf.memory.model.ObjectReference
import java.util.ArrayDeque

data class MemoryDeepAnalysisResult(
    val histogram: HeapHistogram,
    val dominatorTree: DominatorTreeResult,
    val leakSuspects: List<LeakSuspect>,
    val bitmapInstances: List<BitmapInstanceStats>,
)

class MemoryDeepAnalyzer(
    private val bitmapThresholdBytes: Long = DEFAULT_BITMAP_THRESHOLD_BYTES,
) {
    fun analyze(heapDump: HeapDump): MemoryDeepAnalysisResult {
        val graph = HeapGraph.from(heapDump)
        val dominators = DominatorTreeAnalyzer().analyze(heapDump)
        val chainFinder = ReferenceChainFinder(heapDump, graph)
        val leaks = detectLeaks(heapDump, graph, dominators.retainedSizes, chainFinder)
        val bitmaps = bitmapInstances(heapDump, dominators.retainedSizes, chainFinder)
        return MemoryDeepAnalysisResult(
            histogram =
                MemoryHistogramAnalyzer().histogram(
                    heapDump,
                    retainedSizes = dominators.retainedSizes,
                    immediateDominators = dominators.immediateDominators,
                ),
            dominatorTree = dominators,
            leakSuspects = leaks,
            bitmapInstances = bitmaps,
        )
    }

    private fun detectLeaks(
        heapDump: HeapDump,
        graph: HeapGraph,
        retainedSizes: Map<Long, Long>,
        chainFinder: ReferenceChainFinder,
    ): List<LeakSuspect> {
        val instancesByClass = heapDump.instances.groupBy { it.className }
        val candidates = mutableListOf<LeakCandidate>()
        instancesByClass.filterKeys(::isActivityClass).forEach { (className, instances) ->
            val reachableInstances =
                instances.filter {
                    it.objectId in retainedSizes && chainFinder.chainTo(it.objectId).isNotEmpty()
                }
            if (reachableInstances.size > 1) {
                val retained = reachableInstances.maxOfOrNull { retainedSizes[it.objectId] ?: it.shallowSize } ?: 0L
                val representative = reachableInstances.maxBy { retainedSizes[it.objectId] ?: it.shallowSize }
                candidates +=
                    LeakCandidate(
                        className,
                        "Multiple Activity instances remain reachable",
                        retained,
                        reachableInstances.size,
                        chainFinder.chainTo(representative.objectId),
                        0.85f,
                    )
            }
        }
        heapDump.classes.forEach { heapClass ->
            heapClass.staticReferences.forEach { reference ->
                val targetClass = graph.classNames[reference.targetObjectId].orEmpty()
                if (isContextClass(targetClass)) {
                    candidates +=
                        LeakCandidate(
                            targetClass,
                            "Static/singleton field ${heapClass.name}." +
                                "${reference.fieldName.removePrefix("static ")} retains a Context",
                            retainedSizes[reference.targetObjectId] ?: 0L,
                            instancesByClass[targetClass]?.size ?: 1,
                            chainFinder.chainTo(reference.targetObjectId),
                            0.95f,
                        )
                }
            }
        }
        heapDump.instances.filter { isHandlerOrThreadClass(it.className) }.forEach { holder ->
            holder.references.forEach { reference ->
                val targetClass = graph.classNames[reference.targetObjectId].orEmpty()
                if (isActivityClass(targetClass)) {
                    candidates +=
                        LeakCandidate(
                            targetClass,
                            "${holder.className} retains an Activity through ${reference.fieldName}",
                            retainedSizes[reference.targetObjectId] ?: 0L,
                            instancesByClass[targetClass]?.size ?: 1,
                            chainFinder.chainTo(reference.targetObjectId),
                            0.9f,
                        )
                }
            }
        }
        heapDump.instances.filter { isBitmapClass(it.className) }.forEach { bitmap ->
            val retained = retainedSizes[bitmap.objectId] ?: bitmap.shallowSize
            if (retained >= bitmapThresholdBytes) {
                candidates +=
                    LeakCandidate(
                        bitmap.className,
                        "Bitmap retains more than ${bitmapThresholdBytes / MEBIBYTE} MiB",
                        retained,
                        instancesByClass[bitmap.className]?.size ?: 1,
                        chainFinder.chainTo(bitmap.objectId),
                        0.8f,
                    )
            }
        }
        return candidates
            .groupBy { it.className to it.reason }
            .map { (_, grouped) -> grouped.maxBy { it.retainedSize }.toLeakSuspect() }
            .sortedWith(compareByDescending<LeakSuspect> { it.retainedSize ?: 0L }.thenBy { it.className })
    }

    private fun bitmapInstances(
        heapDump: HeapDump,
        retainedSizes: Map<Long, Long>,
        chainFinder: ReferenceChainFinder,
    ): List<BitmapInstanceStats> =
        heapDump.instances
            .filter { isBitmapClass(it.className) }
            .map { bitmap ->
                BitmapInstanceStats(
                    objectId = bitmap.objectId,
                    width = bitmap.dimension("mWidth", "width"),
                    height = bitmap.dimension("mHeight", "height"),
                    retainedSize = retainedSizes[bitmap.objectId] ?: bitmap.shallowSize,
                    referenceChain = chainFinder.chainTo(bitmap.objectId),
                )
            }.sortedWith(compareByDescending<BitmapInstanceStats> { it.retainedSize }.thenBy { it.objectId })

    private fun HeapInstance.dimension(vararg names: String): Int? =
        names.firstNotNullOfOrNull { primitiveFields[it]?.toInt()?.takeIf { value -> value >= 0 } }

    private fun isActivityClass(className: String): Boolean = className.endsWith("Activity") || className.contains(".Activity$")

    private fun isContextClass(className: String): Boolean =
        isActivityClass(className) || className.endsWith("Context") || className.endsWith("ContextWrapper")

    private fun isHandlerOrThreadClass(className: String): Boolean =
        className.endsWith("Handler") || className.endsWith("Thread") || className.contains("Handler$")

    private fun isBitmapClass(className: String): Boolean = className == "android.graphics.Bitmap" || className.endsWith(".Bitmap")

    private data class LeakCandidate(
        val className: String,
        val reason: String,
        val retainedSize: Long,
        val instanceCount: Int,
        val referenceChain: List<ObjectReference>,
        val confidence: Float,
    ) {
        fun toLeakSuspect(): LeakSuspect = LeakSuspect(className, reason, retainedSize, instanceCount, referenceChain, confidence)
    }

    companion object {
        private const val MEBIBYTE = 1024L * 1024L
        const val DEFAULT_BITMAP_THRESHOLD_BYTES: Long = 10L * MEBIBYTE
    }
}

class HeapDiffAnalyzer {
    fun diff(
        before: List<ClassStats>,
        after: List<ClassStats>,
    ): HeapDiff {
        val beforeByName = before.associateBy(ClassStats::className)
        val afterByName = after.associateBy(ClassStats::className)
        val entries =
            (beforeByName.keys + afterByName.keys)
                .sorted()
                .map { className ->
                    val old = beforeByName[className]
                    val new = afterByName[className]
                    HeapDiffEntry(
                        className = className,
                        beforeCount = old?.instanceCount ?: 0,
                        afterCount = new?.instanceCount ?: 0,
                        countDelta = (new?.instanceCount ?: 0) - (old?.instanceCount ?: 0),
                        beforeShallowSize = old?.shallowSize ?: 0L,
                        afterShallowSize = new?.shallowSize ?: 0L,
                        shallowSizeDelta = (new?.shallowSize ?: 0L) - (old?.shallowSize ?: 0L),
                    )
                }.filter { it.countDelta != 0 || it.shallowSizeDelta != 0L }
        return HeapDiff(
            entries.sortedWith(
                compareByDescending<HeapDiffEntry> { it.countDelta }.thenBy { it.className },
            ),
        )
    }
}

private class ReferenceChainFinder(
    heapDump: HeapDump,
    private val graph: HeapGraph,
) {
    private val roots = heapDump.gcRoots.sortedWith(compareBy({ it.objectId }, { it.kind.name }))
    private val knownObjectIds = graph.ids.toHashSet()
    private val traversal = buildTraversal()
    private val chainCache = hashMapOf<Long, List<ObjectReference>>()

    fun chainTo(targetObjectId: Long): List<ObjectReference> =
        chainCache.getOrPut(targetObjectId) {
            val rootReference = traversal.rootReferences[targetObjectId]
            if (rootReference != null) return@getOrPut listOf(rootReference)
            if (targetObjectId !in traversal.predecessors) return@getOrPut emptyList()

            val reversed = mutableListOf<ObjectReference>()
            var current = targetObjectId
            while (true) {
                val predecessor = traversal.predecessors[current] ?: break
                reversed += predecessor.reference
                current = predecessor.sourceObjectId
            }
            val root = traversal.rootReferences[current] ?: return@getOrPut emptyList()
            buildList(reversed.size + 1) {
                add(root)
                reversed.asReversed().forEach(::add)
            }
        }

    private fun buildTraversal(): Traversal {
        val queue = ArrayDeque<Long>()
        val visited = hashSetOf<Long>()
        val rootReferences = linkedMapOf<Long, ObjectReference>()
        val predecessors = hashMapOf<Long, Predecessor>()
        roots.forEach { root ->
            if (root.objectId in knownObjectIds && visited.add(root.objectId)) {
                rootReferences[root.objectId] =
                    ObjectReference(
                        fieldName = "GC Root (${root.kind.name})",
                        targetObjectId = root.objectId,
                        targetClassName = graph.classNames[root.objectId].orEmpty(),
                    )
                queue += root.objectId
            }
        }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            graph.references[current].orEmpty().forEach { reference ->
                if (visited.add(reference.targetObjectId)) {
                    val resolved =
                        reference.copy(
                            targetClassName = graph.classNames[reference.targetObjectId].orEmpty(),
                        )
                    predecessors[reference.targetObjectId] = Predecessor(current, resolved)
                    queue += reference.targetObjectId
                }
            }
        }
        return Traversal(rootReferences, predecessors)
    }

    private data class Traversal(
        val rootReferences: Map<Long, ObjectReference>,
        val predecessors: Map<Long, Predecessor>,
    )

    private data class Predecessor(
        val sourceObjectId: Long,
        val reference: ObjectReference,
    )
}
