package com.androidperformancestudio.arttrace

import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.CallStackTable
import com.androidperformancestudio.profileanalysis.CallTreeProjector
import com.androidperformancestudio.profileanalysis.FlameGraphEmptyReason
import com.androidperformancestudio.profileanalysis.FlameGraphRowProjector
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot

/**
 * Builds a [FlameGraphSnapshot] from a [CallStackTable] produced by [ArtTraceCallStackProjector],
 * reusing the exact `profile-analysis` projection pipeline (call tree, then flame rows) so the
 * existing flame-graph canvas renders method-trace durations as widths (weight = nanoseconds).
 */
object ArtTraceFlameGraphBuilder {
    fun build(
        table: CallStackTable,
        query: CallStackAnalysisQuery = CallStackAnalysisQuery(),
    ): FlameGraphSnapshot {
        val callNodes = CallTreeProjector.project(table, query.direction)
        val rows = FlameGraphRowProjector.project(callNodes, query.direction)
        val totalWeight = table.stacks.sumOf { it.weight }
        val emptyReason =
            if (callNodes.size == 0) {
                FlameGraphEmptyReason.THREAD_HAS_NO_SAMPLES
            } else {
                null
            }
        return FlameGraphSnapshot(
            query = query,
            callNodes = callNodes,
            rows = rows,
            totalWeight = totalWeight,
            emptyReason = emptyReason,
            invalidTransforms = emptyList(),
            diagnosticDetails = null,
        )
    }
}
