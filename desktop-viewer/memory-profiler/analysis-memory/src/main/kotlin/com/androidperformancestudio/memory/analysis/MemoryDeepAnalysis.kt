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
    private val bitmapThresholdBytes: Long = DEFAULT_BITMAP_THRESHOLD_BYTES,
) {
    fun analyze(
        heapDump: HeapDump,
        deobfuscator: ProguardMapping? = null,
        onProgress: (Int) -> Unit = {},
    ): MemoryDeepAnalysisResult {
        val graph = HeapGraph.from(heapDump)
        val dominators = DominatorTreeAnalyzer().analyze(heapDump, onProgress)
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
     * Confidence basis (documented in docs/modules/memory-profiler.md):
     * - Activity multi-instance: 0.85, raised by destroyed-but-retained instances (0.9)
     * - Static/singleton retaining a Context: 0.95, but only when the target is NOT a long-lived
     *   application object (whitelisted) and is strongly reachable.
     * - Handler/Thread retaining an Activity: 0.9
     * - Bitmap above the retained-size threshold: 0.8
     * Suspects below [MANUAL_VERIFICATION_THRESHOLD] confidence are tagged
     * `requiresManualVerification`.
     */
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
                val destroyed = reachableInstances.count(::isDestroyed)
                candidates +=
                    LeakCandidate(
                        className,
                        "Multiple Activity instances remain reachable",
                        retained,
                        reachableInstances.size,
                        chainFinder.chainTo(representative.objectId),
                        if (destroyed > 0) 0.9f else 0.85f,
                    )
            }
        }
        heapDump.classes.forEach { heapClass ->
            heapClass.staticReferences.forEach { reference ->
                val targetClass = graph.classNames[reference.targetObjectId].orEmpty()
                // Whitelist long-lived application objects (Application, framework singletons) held
                // by static fields: they are expected to live for the process lifetime.
                val eligibleContext =
                    isContextClass(targetClass) &&
                        !LeakWhitelist.isLongLived(targetClass) &&
                        !LeakWhitelist.isLongLived(heapClass.name)
                if (eligibleContext && chainFinder.chainTo(reference.targetObjectId).isNotEmpty()) {
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
                val chain = chainFinder.chainTo(reference.targetObjectId)
                if (isActivityClass(targetClass) && chain.isNotEmpty()) {
                    candidates +=
                        LeakCandidate(
                            targetClass,
                            "${holder.className} retains an Activity through ${reference.fieldName}",
                            retainedSizes[reference.targetObjectId] ?: 0L,
                            instancesByClass[targetClass]?.size ?: 1,
                            chain,
                            0.9f,
                        )
                }
            }
        }
        heapDump.instances.filter { isBitmapClass(it.className) }.forEach { bitmap ->
            val retained = retainedSizes[bitmap.objectId] ?: bitmap.shallowSize
            val chain = chainFinder.chainTo(bitmap.objectId)
            if (retained >= bitmapThresholdBytes && chain.isNotEmpty()) {
                candidates +=
                    LeakCandidate(
                        bitmap.className,
                        "Bitmap retains more than ${bitmapThresholdBytes / MEBIBYTE} MiB",
                        retained,
                        instancesByClass[bitmap.className]?.size ?: 1,
                        chain,
                        0.8f,
                    )
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
        val instancesByClass =
            heapDump.instances
                .filter { isActivityClass(it.className) }
                .groupBy { it.className }
        return instancesByClass
            .mapNotNull { (className, instances) ->
                val reachable =
                    instances.filter {
                        it.objectId in retainedSizes && chainFinder.chainTo(it.objectId).isNotEmpty()
                    }
                if (reachable.isEmpty()) return@mapNotNull null
                val destroyed = reachable.count(::isDestroyed)
                val representative = reachable.maxBy { retainedSizes[it.objectId] ?: it.shallowSize }
                ActivityLeakEntry(
                    className = className,
                    liveInstanceCount = reachable.size,
                    destroyedInstanceCount = destroyed,
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
                val estimatedPixelBytes = estimatedPixelBytes(width, height)
                BitmapInstanceStats(
                    objectId = bitmap.objectId,
                    width = width,
                    height = height,
                    retainedSize = retainedSizes[bitmap.objectId] ?: bitmap.shallowSize,
                    referenceChain = chainFinder.chainTo(bitmap.objectId),
                    estimatedPixelBytes = estimatedPixelBytes,
                    javaSizeBytes = bitmap.shallowSize,
                    nativeSizeBytes = estimatedPixelBytes,
                )
            }.sortedWith(compareByDescending<BitmapInstanceStats> { it.retainedSize }.thenBy { it.objectId })

    private fun HeapInstance.dimension(vararg names: String): Int? =
        names.firstNotNullOfOrNull { primitiveFields[it]?.toInt()?.takeIf { value -> value >= 0 } }

    /** ARGB_8888 pixel-buffer footprint: width × height × 4 B. */
    private fun estimatedPixelBytes(
        width: Int?,
        height: Int?,
    ): Long? {
        val w = width?.takeIf { it > 0 }
        val h = height?.takeIf { it > 0 }
        return if (w == null || h == null) null else w.toLong() * h.toLong() * ARGB_8888_BYTES_PER_PIXEL
    }

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
        fun toLeakSuspect(): LeakSuspect =
            LeakSuspect(
                className = className,
                reason = reason,
                retainedSize = retainedSize,
                instanceCount = instanceCount,
                referenceChain = referenceChain,
                confidence = confidence,
                requiresManualVerification = confidence < MANUAL_VERIFICATION_THRESHOLD,
            )
    }

    companion object {
        private const val MEBIBYTE = 1024L * 1024L
        const val DEFAULT_BITMAP_THRESHOLD_BYTES: Long = 10L * MEBIBYTE
        const val MANUAL_VERIFICATION_THRESHOLD: Float = 0.7f
        private const val ARGB_8888_BYTES_PER_PIXEL: Long = 4L
    }
}

/**
 * Long-lived objects that legitimately outlive a single screen and are therefore excluded from
 * Context/Application leak heuristics to reduce false positives.
 */
object LeakWhitelist {
    val longLivedClassNames =
        setOf(
            "android.app.Application",
            "android.app.ActivityThread",
            "android.app.Instrumentation",
            "android.app.LoadedApk",
            "android.app.ResourcesManager",
            "android.content.res.Resources",
            "android.view.WindowManagerGlobal",
            "java.lang.Runtime",
            "dalvik.system.PathClassLoader",
            "java.lang.Class",
        )

    fun isLongLived(className: String): Boolean = className in longLivedClassNames || className.endsWith("Application")
}

class HeapDiffAnalyzer {
    /**
     * Compares two histograms by [matchMode].
     *
     * Matching by class name only can spuriously merge two distinct classes that share a name
     * (multi-dex, plugin classloaders, or obfuscation renames). Matching by name + superclass
     * hierarchy depth keeps such entries separate.
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
            val sourceIsReferenceHolder = isReferenceHolder(graph.classNames[current].orEmpty())
            graph.references[current].orEmpty().forEach { reference ->
                // A java.lang.ref.* referent is not a strong edge; objects reachable only through
                // WeakReference/SoftReference/PhantomReference are collectible and not leaks.
                if (sourceIsReferenceHolder && reference.fieldName == "referent") return@forEach
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

    private fun isReferenceHolder(className: String): Boolean =
        className == "java.lang.ref.WeakReference" ||
            className == "java.lang.ref.SoftReference" ||
            className == "java.lang.ref.PhantomReference"

    private data class Traversal(
        val rootReferences: Map<Long, ObjectReference>,
        val predecessors: Map<Long, Predecessor>,
    )

    private data class Predecessor(
        val sourceObjectId: Long,
        val reference: ObjectReference,
    )
}
