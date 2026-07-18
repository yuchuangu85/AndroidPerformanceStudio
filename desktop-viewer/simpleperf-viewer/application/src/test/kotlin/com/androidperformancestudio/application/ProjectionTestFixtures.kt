package com.androidperformancestudio.application

import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.FlameGraphRowProjector
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.profileanalysis.StackChartEmptyReason
import com.androidperformancestudio.profileanalysis.StackChartSnapshot
import com.androidperformancestudio.storage.MarkerAvailability
import com.androidperformancestudio.storage.MarkerProjectionSnapshot
import com.androidperformancestudio.storage.PanelProjection

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

internal fun emptyStackChart(): PanelProjection<StackChartSnapshot> =
    PanelProjection.Ready(
        StackChartSnapshot(
            framesById = emptyMap(),
            blocks = emptyList(),
            startNanos = null,
            endNanosExclusive = null,
            maxDepth = 0,
            emptyReason = StackChartEmptyReason.NO_SAMPLES,
        ),
    )

internal fun emptyMarkers(): PanelProjection<MarkerProjectionSnapshot> =
    PanelProjection.Ready(
        MarkerProjectionSnapshot(
            availability = MarkerAvailability.NOT_COLLECTED,
            emptyReason = null,
            markers = emptyList(),
            lanes = emptyList(),
        ),
    )
