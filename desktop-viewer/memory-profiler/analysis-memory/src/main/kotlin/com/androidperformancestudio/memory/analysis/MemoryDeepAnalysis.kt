@file:Suppress("CyclomaticComplexMethod", "LongMethod", "MagicNumber", "MaxLineLength", "TooManyFunctions")

package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.model.ActivityLeakEntry
import com.androidperformancestudio.memory.model.BitmapInstanceStats
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapDiff
import com.androidperformancestudio.memory.model.HeapDiffEntry
import com.androidperformancestudio.memory.model.HeapDiffMatchMode
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapHistogram
import com.androidperformancestudio.memory.model.HeapInstance
import com.androidperformancestudio.memory.model.LeakSuspect
import com.androidperformancestudio.memory.model.ObjectReference
import java.util.ArrayDeque

data class MemoryDeepAnalysisResult(
    val histogram: HeapHistogram,
    val dominatorTree: DominatorTreeResult,
    val leakSuspects: List<LeakSuspect>,
    val bitmapInstances: List<BitmapInstanceStats>,
    val activityLeaks: List<ActivityLeakEntry>,
)

class MemoryDeepAnalyzer(
    bitmapThresholdBytes: Long = DEFAULT_BITMAP_THRESHOLD_BYTES,
) {
    init {
        require(bitmapThresholdBytes >= 0L) { "bitmapThresholdBytes must not be negative" }
    }

    fun analyze(
        heapDump: HeapDump,
        deobfuscator: ProguardMapping? = null,
        onProgress: (Int) -> Unit = {},
    ): MemoryDeepAnalysisResult {
        val graph = HeapGraph.from(heapDump)
        val dominators = DominatorTreeAnalyzer().analyze(graph, onProgress)
        val chainFinder = ReferenceChainFinder(heapDump, graph)
        val leaks = detectLeaks(heapDump, graph, dominators.retainedSizes, chainFinder)
        val bitmaps = bitmapInstances(heapDump, dominators.retainedSizes, chainFinder)
        return MemoryDeepAnalysisResult(
            histogram =
                MemoryHistogramAnalyzer().histogram(
                    heapDump,
                    retainedSizes = dominators.retainedSizes,
                    immediateDominators = dominators.immediateDominators,
                    deobfuscator = deobfuscator,
                ),
            dominatorTree = dominators,
            leakSuspects = leaks,
            bitmapInstances = bitmaps,
            activityLeaks = activityLeakReport(heapDump, dominators.retainedSizes, chainFinder),
        )
    }

    /**
     * Detects leak suspects from strong-reachability heuristics.
     *
     * These are reviewable retention signals, not proof of a leak. Confidence remains unset because
     * no calibrated model exists; every result requires manual verification.
     */
    private fun detectLeaks(
        heapDump: HeapDump,
        graph: HeapGraph,
        retainedSizes: Map<Long, Long>,
        chainFinder: ReferenceChainFinder,
    ): List<LeakSuspect> {
        val instancesByClass = heapDump.instances.groupBy { it.className }
        val activityClassIds = heapDump.classIdsAssignableTo("android.app.Activity")
        val fragmentClassIds =
            FRAGMENT_BASE_CLASSES.flatMapTo(hashSetOf()) { heapDump.classIdsAssignableTo(it) }

        fun isActivity(instance: HeapInstance): Boolean = instance.classObjectId in activityClassIds

        val candidates = mutableListOf<LeakCandidate>()
        heapDump.instances.filter { isActivity(it) && isDestroyed(it) }.forEach { activity ->
            val chain = chainFinder.chainTo(activity.objectId)
            if (chainFinder.depthOf(activity.objectId)?.let { it > 0 } == true) {
                candidates +=
                    LeakCandidate(
                        activity.className,
                        "Destroyed or finished Activity remains strongly reachable",
                        retainedSizes[activity.objectId] ?: activity.shallowSize,
                        instancesByClass[activity.className]?.size ?: 1,
                        chain,
                        activityOrFragmentLeak = true,
                    )
            }
        }
        heapDump.instances
            .filter { it.classObjectId in fragmentClassIds && it.hasNullReference("mFragmentManager") }
            .forEach { fragment ->
                val chain = chainFinder.chainTo(fragment.objectId)
                if (chainFinder.depthOf(fragment.objectId)?.let { it > 0 } == true) {
                    candidates +=
                        LeakCandidate(
                            fragment.className,
                            "Detached Fragment remains strongly reachable (mFragmentManager is null; may be a false positive)",
                            retainedSizes[fragment.objectId] ?: fragment.shallowSize,
                            instancesByClass[fragment.className]?.size ?: 1,
                            chain,
                            activityOrFragmentLeak = true,
                        )
                }
            }
        heapDump.classes.forEach { heapClass ->
            heapClass.staticReferences.forEach { reference ->
                val targetClass = graph.classNames[reference.targetObjectId].orEmpty()
                if (isContextClass(targetClass) && chainFinder.chainTo(reference.targetObjectId).isNotEmpty()) {
                    candidates +=
                        LeakCandidate(
                            targetClass,
                            "Static/singleton field ${heapClass.name}." +
                                "${reference.fieldName.removePrefix("static ")} retains a Context",
                            retainedSizes[reference.targetObjectId] ?: 0L,
                            instancesByClass[targetClass]?.size ?: 1,
                            chainFinder.chainTo(reference.targetObjectId),
                        )
                }
            }
        }
        heapDump.instances.filter { isHandlerOrThreadClass(it.className) }.forEach { holder ->
            holder.references.forEach { reference ->
                val targetClass = graph.classNames[reference.targetObjectId].orEmpty()
                val target = heapDump.instances.firstOrNull { it.objectId == reference.targetObjectId }
                val chain = chainFinder.chainTo(reference.targetObjectId)
                if (target != null && isActivity(target) && chain.isNotEmpty()) {
                    candidates +=
                        LeakCandidate(
                            targetClass,
                            "${holder.className} retains an Activity through ${reference.fieldName}",
                            retainedSizes[reference.targetObjectId] ?: 0L,
                            instancesByClass[targetClass]?.size ?: 1,
                            chain,
                        )
                }
            }
        }
        return candidates
            .groupBy { it.className to it.reason }
            .map { (_, grouped) -> grouped.maxBy { it.retainedSize }.toLeakSuspect() }
            .sortedWith(compareByDescending<LeakSuspect> { it.retainedSize ?: 0L }.thenBy { it.className })
    }

    /** Per-Activity-class leak report: live instance count plus destroyed-but-retained instances. */
    private fun activityLeakReport(
        heapDump: HeapDump,
        retainedSizes: Map<Long, Long>,
        chainFinder: ReferenceChainFinder,
    ): List<ActivityLeakEntry> {
        val activityClassIds = heapDump.classIdsAssignableTo("android.app.Activity")
        val instancesByClass =
            heapDump.instances
                .filter {
                    it.classObjectId in activityClassIds
                }.groupBy { it.className }
        return instancesByClass
            .mapNotNull { (className, instances) ->
                val reachable =
                    instances.filter {
                        it.objectId in retainedSizes && chainFinder.depthOf(it.objectId)?.let { depth -> depth > 0 } == true
                    }
                if (reachable.isEmpty()) return@mapNotNull null
                val destroyedInstances = reachable.filter(::isDestroyed)
                if (destroyedInstances.isEmpty()) return@mapNotNull null
                val representative = destroyedInstances.maxBy { retainedSizes[it.objectId] ?: it.shallowSize }
                ActivityLeakEntry(
                    className = className,
                    liveInstanceCount = reachable.size,
                    destroyedInstanceCount = destroyedInstances.size,
                    retainedSize = retainedSizes[representative.objectId] ?: representative.shallowSize,
                    referenceChain = chainFinder.chainTo(representative.objectId),
                )
            }.sortedWith(
                compareByDescending<ActivityLeakEntry> { it.destroyedInstanceCount }
                    .thenByDescending { it.liveInstanceCount }
                    .thenBy { it.className },
            )
    }

    /** An Activity is a strong leak signal when it was destroyed/finished yet still in the heap. */
    private fun isDestroyed(instance: HeapInstance): Boolean =
        instance.primitiveFields["mDestroyed"] == 1L || instance.primitiveFields["mFinished"] == 1L

    private fun HeapInstance.hasNullReference(fieldName: String): Boolean =
        references.any { it.fieldName == fieldName && it.targetObjectId == 0L }

    private fun bitmapInstances(
        heapDump: HeapDump,
        retainedSizes: Map<Long, Long>,
        chainFinder: ReferenceChainFinder,
    ): List<BitmapInstanceStats> =
        heapDump.instances
            .filter { isBitmapClass(it.className) }
            .map { bitmap ->
                val width = bitmap.dimension("mWidth", "width")
                val height = bitmap.dimension("mHeight", "height")
                val rowBytes = bitmap.dimension("mRowBytes", "rowBytes")
                val estimatedPixelBytes = estimatedPixelBytes(width, height, rowBytes)
                BitmapInstanceStats(
                    objectId = bitmap.objectId,
                    width = width,
                    height = height,
                    retainedSize = retainedSizes[bitmap.objectId] ?: bitmap.shallowSize,
                    referenceChain = chainFinder.chainTo(bitmap.objectId),
                    estimatedPixelBytes = estimatedPixelBytes,
                    javaSizeBytes = bitmap.shallowSize,
                    nativeSizeBytes = bitmap.nativeSizeBytes,
                    className = bitmap.className,
                    bitmapId = bitmap.primitiveFields["mId"],
                    bitmapSourceId = bitmap.primitiveFields["mSourceId"],
                )
            }.sortedWith(compareByDescending<BitmapInstanceStats> { it.retainedSize }.thenBy { it.objectId })

    private fun HeapInstance.dimension(vararg names: String): Int? =
        names.firstNotNullOfOrNull { primitiveFields[it]?.toInt()?.takeIf { value -> value >= 0 } }

    /** Uses rowBytes when present; otherwise assumes ARGB_8888 (width × height × 4 B). */
    private fun estimatedPixelBytes(
        width: Int?,
        height: Int?,
        rowBytes: Int?,
    ): Long? {
        val w = width?.takeIf { it > 0 }
        val h = height?.takeIf { it > 0 } ?: return null
        val bytesPerRow =
            rowBytes?.takeIf { it > 0 }?.toLong()
                ?: w?.let { Math.multiplyExact(it.toLong(), ARGB_8888_BYTES_PER_PIXEL) }
        return bytesPerRow?.let { runCatching { Math.multiplyExact(it, h.toLong()) }.getOrNull() }
    }

    private fun isLikelyActivityName(className: String): Boolean = className.endsWith("Activity") || className.contains(".Activity$")

    private fun isContextClass(className: String): Boolean =
        isLikelyActivityName(className) || className.endsWith("Context") || className.endsWith("ContextWrapper")

    private fun isHandlerOrThreadClass(className: String): Boolean =
        className.endsWith("Handler") || className.endsWith("Thread") || className.contains("Handler$")

    private fun isBitmapClass(className: String): Boolean = className == "android.graphics.Bitmap" || className.endsWith(".Bitmap")

    private data class LeakCandidate(
        val className: String,
        val reason: String,
        val retainedSize: Long,
        val instanceCount: Int,
        val referenceChain: List<ObjectReference>,
        val activityOrFragmentLeak: Boolean = false,
    ) {
        fun toLeakSuspect(): LeakSuspect =
            LeakSuspect(
                className = className,
                reason = reason,
                retainedSize = retainedSize,
                instanceCount = instanceCount,
                referenceChain = referenceChain,
                confidence = 0f,
                requiresManualVerification = true,
                activityOrFragmentLeak = activityOrFragmentLeak,
            )
    }

    companion object {
        private val FRAGMENT_BASE_CLASSES =
            listOf(
                "android.app.Fragment",
                "android.support.v4.app.Fragment",
                "androidx.fragment.app.Fragment",
            )
        private const val MEBIBYTE = 1024L * 1024L
        const val DEFAULT_BITMAP_THRESHOLD_BYTES: Long = 10L * MEBIBYTE
        const val MANUAL_VERIFICATION_THRESHOLD: Float = 0.7f
        private const val ARGB_8888_BYTES_PER_PIXEL: Long = 4L
    }
}

class HeapDiffAnalyzer {
    /**
     * Compares two histograms by [matchMode].
     *
     * Matching by class name only can spuriously merge two distinct classes that share a name
     * (multi-dex, plugin classloaders, or obfuscation renames). Matching by name + superclass
     * hierarchy depth is retained only for compatibility; it does not identify a ClassLoader and
     * must not be used as an exact cross-dump identity.
     */
    fun diff(
        before: List<ClassStats>,
        after: List<ClassStats>,
        matchMode: HeapDiffMatchMode = HeapDiffMatchMode.CLASS_NAME,
    ): HeapDiff {
        val key: (ClassStats) -> String = { stats ->
            if (matchMode == HeapDiffMatchMode.CLASS_NAME_AND_HIERARCHY && stats.hierarchyDepth != null) {
                "${stats.className}|depth=${stats.hierarchyDepth}"
            } else {
                stats.className
            }
        }
        val beforeByKey = before.associateBy(key)
        val afterByKey = after.associateBy(key)
        val entries =
            (beforeByKey.keys + afterByKey.keys)
                .sorted()
                .map { matchKey ->
                    val old = beforeByKey[matchKey]
                    val new = afterByKey[matchKey]
                    HeapDiffEntry(
                        className = new?.className ?: old?.className ?: matchKey,
                        beforeCount = old?.instanceCount ?: 0,
                        afterCount = new?.instanceCount ?: 0,
                        countDelta = (new?.instanceCount ?: 0) - (old?.instanceCount ?: 0),
                        beforeShallowSize = old?.shallowSize ?: 0L,
                        afterShallowSize = new?.shallowSize ?: 0L,
                        shallowSizeDelta = (new?.shallowSize ?: 0L) - (old?.shallowSize ?: 0L),
                        matchedBy = matchMode,
                        hierarchyDepth = new?.hierarchyDepth ?: old?.hierarchyDepth,
                    )
                }.filter { it.countDelta != 0 || it.shallowSizeDelta != 0L }
        return HeapDiff(
            entries.sortedWith(
                compareByDescending<HeapDiffEntry> { it.countDelta }.thenBy { it.className },
            ),
        )
    }
}

internal class ReferenceChainFinder(
    heapDump: HeapDump,
    private val graph: HeapGraph,
) {
    private val roots = heapDump.gcRoots.sortedWith(compareBy({ it.objectId }, { it.kind.name }))
    private val knownObjectIds = graph.ids.toHashSet()
    private val depthByObjectId = hashMapOf<Long, Int>()
    private val traversal = buildTraversal()
    private val chainCache = hashMapOf<Long, List<ObjectReference>>()

    /** Number of strong edges from a GC root to [targetObjectId]; null when the object is unreachable. */
    fun depthOf(targetObjectId: Long): Int? = depthByObjectId[targetObjectId]

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
                depthByObjectId[root.objectId] = 0
                queue += root.objectId
            }
        }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val currentDepth = depthByObjectId.getValue(current)
            graph.references[current].orEmpty().forEach { reference ->
                if (visited.add(reference.targetObjectId)) {
                    val resolved =
                        reference.copy(
                            targetClassName = graph.classNames[reference.targetObjectId].orEmpty(),
                        )
                    predecessors[reference.targetObjectId] = Predecessor(current, resolved)
                    depthByObjectId[reference.targetObjectId] = currentDepth + 1
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
