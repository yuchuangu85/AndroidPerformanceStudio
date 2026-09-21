@file:Suppress("MagicNumber")

package com.androidperformancestudio.frame.analysis

import com.androidperformancestudio.frame.model.FrameSample

/** Groups frames by Activity/window/UI-state so platform jank can be attributed to app context. */
public class FrameAttributionAnalyzer {
    public fun analyze(samples: List<FrameSample>): List<FrameAttribution> =
        samples
            .groupBy(::keyOf)
            .map { (key, frames) ->
                val durations = frames.mapNotNull { it.resolvedDurationNs() }.sorted()
                FrameAttribution(
                    activityName = key.activityName,
                    windowId = key.windowId,
                    uiState = key.uiState,
                    fragmentName = key.fragmentName,
                    pageName = key.pageName,
                    interactionState = key.interactionState,
                    totalFrames = frames.size,
                    deadlineMissFrames =
                        frames.count {
                            it.resolvedDurationNs()?.let { duration ->
                                it.expectedDurationNs?.let { expected -> duration > expected } == true
                            } == true
                        },
                    platformJankFrames = frames.count { it.platformJank == true },
                    averageDurationNs = durations.takeIf(List<Long>::isNotEmpty)?.average()?.toLong(),
                    p95DurationNs = durations.percentile(0.95),
                    worstDurationNs = durations.lastOrNull(),
                    jankTypes = frames.flatMap { it.platformJankTypes }.map { it.name }.toSet(),
                    renderThreadNames = frames.mapNotNull { it.renderThreadName }.toSet(),
                    surfaceFlingerJankTypes = frames.mapNotNull { it.surfaceFlingerJankType }.toSet(),
                    jankStatsFrames = frames.count { it.jankStatsJank == true },
                )
            }.sortedWith(
                compareByDescending<FrameAttribution> { it.platformJankFrames }
                    .thenByDescending { it.worstDurationNs ?: 0L },
            )

    private fun keyOf(sample: FrameSample): AttributionKey =
        AttributionKey(
            activityName = sample.activityName ?: "<unknown activity>",
            windowId = sample.windowId ?: "<unknown window>",
            uiState =
                sample.interactionState
                    ?: sample.states["screen"]
                    ?: sample.states["screenName"]
                    ?: sample.states["uiState"]
                    ?: "<unknown state>",
            fragmentName = sample.fragmentName ?: sample.states["fragment"],
            pageName = sample.pageName ?: sample.states["page"],
            interactionState = sample.interactionState ?: sample.states["interaction"],
        )

    private data class AttributionKey(
        val activityName: String,
        val windowId: String,
        val uiState: String,
        val fragmentName: String?,
        val pageName: String?,
        val interactionState: String?,
    )

    private fun List<Long>.percentile(fraction: Double): Long? {
        if (isEmpty()) return null
        return this[(size * fraction).toInt().coerceAtMost(lastIndex)]
    }
}

public data class FrameAttribution(
    val activityName: String,
    val windowId: String,
    val uiState: String,
    val totalFrames: Int,
    val deadlineMissFrames: Int,
    val platformJankFrames: Int,
    val averageDurationNs: Long?,
    val p95DurationNs: Long?,
    val worstDurationNs: Long?,
    val jankTypes: Set<String>,
    val fragmentName: String? = null,
    val pageName: String? = null,
    val interactionState: String? = null,
    val renderThreadNames: Set<String> = emptySet(),
    val surfaceFlingerJankTypes: Set<String> = emptySet(),
    val jankStatsFrames: Int = 0,
)
