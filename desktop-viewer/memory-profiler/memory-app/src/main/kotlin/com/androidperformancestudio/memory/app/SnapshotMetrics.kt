package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.analysis.MemoryHistogramAnalyzer
import com.androidperformancestudio.memory.analysis.ProguardMapping
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapDump

/** TP IDs are not HPROF IDs: validate raw class/heap counts and sizes before enriching metrics. */
@Suppress("ReturnCount", "ComplexCondition")
internal fun enrichSnapshotMetrics(
    snapshot: HeapDumpLoadedSnapshot,
    rawHeap: HeapDump,
    metrics: List<HprofClassMetric>,
    mapping: ProguardMapping?,
): HeapDumpLoadedSnapshot {
    val byHeap = metrics.groupBy { it.heapName.normalizedHeapName() }
    if (metrics.isEmpty() || byHeap.keys != snapshot.availableHeaps.toSet()) return snapshot
    return try {
        val enriched =
            snapshot.availableHeaps.associateWith { heap ->
                val rows = byHeap.getValue(heap)
                val raw =
                    MemoryHistogramAnalyzer()
                        .histogram(rawHeap, heapName = heap)
                        .classes
                        .associateBy { it.className }
                if (rows.size != raw.size ||
                    rows.mapTo(hashSetOf()) { it.className } != raw.keys ||
                    rows.any { row ->
                        val stats = raw.getValue(row.className)
                        row.objectCount != stats.instanceCount.toLong() || row.shallowSize != stats.shallowSize
                    }
                ) {
                    return snapshot
                }
                val mapped = rows.groupBy { mapping?.originalClassName(it.className) ?: it.className }
                val parsed = snapshot.perHeapClasses[heap].orEmpty()
                if (mapped.keys != parsed.mapTo(hashSetOf()) { it.className }) return snapshot
                parsed.map { stats ->
                    val group = mapped.getValue(stats.className)
                    if (group.sumMetric { it.objectCount } != stats.instanceCount.toLong() ||
                        group.sumMetric { it.shallowSize } != stats.shallowSize
                    ) {
                        return snapshot
                    }
                    stats.copy(
                        nativeSize = group.sumMetric { it.nativeSize },
                        reachableShallowSize = group.sumMetric { it.reachableShallowSize },
                        reachableNativeSize = group.sumMetric { it.reachableNativeSize },
                    )
                }
            }
        val totals = enriched.values.flatten().groupBy { it.className }
        val classes =
            snapshot.histogram.classes.map { stats ->
                val rows = totals[stats.className].orEmpty()
                stats.copy(
                    nativeSize = rows.sumSize { it.nativeSize },
                    reachableShallowSize = rows.sumSize { it.reachableShallowSize },
                    reachableNativeSize = rows.sumSize { it.reachableNativeSize },
                )
            }
        snapshot.copy(
            perHeapClasses = enriched,
            histogram = snapshot.histogram.copy(classes = classes),
            heapDump = snapshot.heapDump.copy(topClasses = classes),
        )
    } catch (_: ArithmeticException) {
        snapshot
    }
}

private fun String.normalizedHeapName(): String =
    when (lowercase()) {
        "app" -> "App"
        "image" -> "Image"
        "zygote" -> "Zygote"
        else -> replaceFirstChar(Char::uppercase)
    }

private fun List<HprofClassMetric>.sumMetric(value: (HprofClassMetric) -> Long): Long =
    fold(0L) { total, row -> Math.addExact(total, value(row)) }

private fun List<ClassStats>.sumSize(value: (ClassStats) -> Long?): Long? =
    if (isEmpty()) null else fold(0L) { total, row -> Math.addExact(total, requireNotNull(value(row))) }
