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
                sample.states["screen"]
                    ?: sample.states["screenName"]
                    ?: sample.states["uiState"]
                    ?: "<unknown state>",
        )

    private data class AttributionKey(
        val activityName: String,
        val windowId: String,
        val uiState: String,
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
)
