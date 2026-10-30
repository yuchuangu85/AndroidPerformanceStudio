package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.analysis.ProguardMapping
import com.androidperformancestudio.memory.model.ClassStats

/** TP's internal IDs are unrelated to HPROF IDs; join only after raw class/heap validation. */
internal fun enrichRustSnapshotMetrics(
    snapshot: RustLoadedSnapshot,
    source: RustHeapSnapshotSource,
    metrics: List<HprofClassMetric>,
    mapping: ProguardMapping?,
): RustLoadedSnapshot {
    val byHeap =
        metrics.groupBy { row ->
            when (row.heapName.lowercase()) {
                "app" -> "App"
                "image" -> "Image"
                "zygote" -> "Zygote"
                else -> row.heapName.replaceFirstChar(Char::uppercase)
            }
        }
    return try {
        val verified =
            snapshot.availableHeaps.associateWith { heap ->
                val rows = byHeap[heap].orEmpty()
                val raw = source.rawHistogram(heap).classes
                if (rawMetricsMatch(raw, rows)) {
                    enrichHeapClasses(snapshot.perHeapClasses[heap].orEmpty(), rows, mapping)
                } else {
                    null
                }
            }
        val complete =
            metrics.isNotEmpty() &&
                byHeap.keys == snapshot.availableHeaps.toSet() &&
                verified.values.all { it != null }
        if (complete) applyVerifiedMetrics(snapshot, verified.mapValues { requireNotNull(it.value) }) else snapshot
    } catch (_: ArithmeticException) {
        snapshot
    }
}

private fun rawMetricsMatch(
    parsed: List<ClassStats>,
    rows: List<HprofClassMetric>,
): Boolean {
    val raw = parsed.associateBy { it.className }
    val names = rows.mapTo(hashSetOf()) { it.className }
    return rows.size == raw.size &&
        names == raw.keys &&
        rows.all { row ->
            val stats = raw.getValue(row.className)
            row.objectCount == stats.instanceCount.toLong() && row.shallowSize == stats.shallowSize
        }
}

private fun List<HprofClassMetric>.sumMetric(value: (HprofClassMetric) -> Long): Long =
    fold(0L) { total, row -> Math.addExact(total, value(row)) }

private fun enrichHeapClasses(
    parsed: List<ClassStats>,
    rows: List<HprofClassMetric>,
    mapping: ProguardMapping?,
): List<ClassStats>? {
    val mapped = rows.groupBy { mapping?.originalClassName(it.className) ?: it.className }
    val matches =
        mapped.keys == parsed.mapTo(hashSetOf()) { it.className } &&
            parsed.all { stats ->
                val group = mapped.getValue(stats.className)
                group.sumMetric { it.objectCount } == stats.instanceCount.toLong() &&
                    group.sumMetric { it.shallowSize } == stats.shallowSize
            }
    return if (matches) {
        parsed.map { stats ->
            val group = mapped.getValue(stats.className)
            stats.copy(
                nativeSize = group.sumMetric { it.nativeSize },
                reachableShallowSize = group.sumMetric { it.reachableShallowSize },
                reachableNativeSize = group.sumMetric { it.reachableNativeSize },
            )
        }
    } else {
        null
    }
}

private fun applyVerifiedMetrics(
    snapshot: RustLoadedSnapshot,
    enriched: Map<String, List<ClassStats>>,
): RustLoadedSnapshot {
    val totals = enriched.values.flatten().groupBy { it.className }

    fun sum(
        rows: List<ClassStats>,
        value: (ClassStats) -> Long?,
    ): Long = rows.fold(0L) { total, row -> Math.addExact(total, requireNotNull(value(row))) }
    val classes =
        snapshot.histogram.classes.map { stats ->
            val rows = totals[stats.className]
            if (rows == null) {
                stats.copy(nativeSize = null, reachableShallowSize = null, reachableNativeSize = null)
            } else {
                stats.copy(
                    nativeSize = sum(rows) { it.nativeSize },
                    reachableShallowSize = sum(rows) { it.reachableShallowSize },
                    reachableNativeSize = sum(rows) { it.reachableNativeSize },
                )
            }
        }
    return snapshot.copy(perHeapClasses = enriched, histogram = snapshot.histogram.copy(classes = classes))
}
