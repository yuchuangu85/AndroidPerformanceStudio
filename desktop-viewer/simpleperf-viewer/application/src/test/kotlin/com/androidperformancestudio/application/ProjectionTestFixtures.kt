package com.androidperformancestudio.application

import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.FlameGraphRowProjector
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot

internal fun emptyFlameGraph(query: CallStackAnalysisQuery = CallStackAnalysisQuery()): FlameGraphSnapshot {
    val nodes =
        CallNodeTable(
            ids = longArrayOf(),
            parentIndexes = intArrayOf(),
            frameIds = longArrayOf(),
            depths = intArrayOf(),
            inclusiveWeights = longArrayOf(),
            selfWeights = longArrayOf(),
            sampleCounts = longArrayOf(),
            threadCounts = intArrayOf(),
            categories = emptyList(),
            framesById = emptyMap(),
        )
    return FlameGraphSnapshot(
        query,
        nodes,
        FlameGraphRowProjector.project(nodes, query.direction),
        0,
        null,
        emptyList(),
    )
}
