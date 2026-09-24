package com.androidperformancestudio.startup.presentation

import com.androidperformancestudio.startup.model.StartupPhase
import com.androidperformancestudio.startup.model.StartupRun
import com.androidperformancestudio.startup.model.StartupSource

internal data class StartupWaterfallSegment(
    val phase: StartupPhase,
    val source: StartupSource,
    val startNs: Long,
    val endNs: Long,
)

internal data class StartupWaterfallGroup(
    val source: StartupSource,
    val originNs: Long,
    val extentNs: Long,
    val segments: List<StartupWaterfallSegment>,
)

internal data class StartupWaterfallModel(
    val groups: List<StartupWaterfallGroup>,
    val unplottedPhaseCount: Int,
)

internal fun StartupRun.waterfallModel(): StartupWaterfallModel {
    val segments =
        phases.mapNotNull { phase ->
            val start = milestones.firstOrNull { it.kind == phase.start && it.elapsedRealtimeNs != null }
            val end = milestones.firstOrNull { it.kind == phase.end && it.elapsedRealtimeNs != null }
            val startNs = start?.elapsedRealtimeNs
            val endNs = end?.elapsedRealtimeNs
            val observedDuration =
                if (startNs != null && endNs != null && endNs >= startNs) {
                    runCatching { Math.subtractExact(endNs, startNs) }.getOrNull()
                } else {
                    null
                }
            if (startNs == null || endNs == null || start.source != end.source || observedDuration != phase.durationNs) {
                null
            } else {
                StartupWaterfallSegment(phase, start.source, startNs, endNs)
            }
        }
    val groups =
        segments.groupBy(StartupWaterfallSegment::source).mapNotNull { (source, sourceSegments) ->
            val origin = sourceSegments.minOf(StartupWaterfallSegment::startNs)
            val extent =
                runCatching {
                    Math.subtractExact(sourceSegments.maxOf(StartupWaterfallSegment::endNs), origin)
                }.getOrNull()
            if (extent == null || extent < 0) {
                null
            } else {
                StartupWaterfallGroup(source, origin, extent, sourceSegments.sortedBy(StartupWaterfallSegment::startNs))
            }
        }
    return StartupWaterfallModel(groups, phases.size - groups.sumOf { it.segments.size })
}
