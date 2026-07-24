@file:Suppress(
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "NestedBlockDepth",
    "TooManyFunctions",
)

package com.androidperformancestudio.frame.analysis

import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.JankType
import kotlin.math.ceil

public enum class JankVerdict {
    SMOOTH,
    JANK,
    UNKNOWN,
}

public enum class JankSeverity {
    SMOOTH,
    MINOR,
    MAJOR,
    SEVERE,
    FROZEN,
    UNKNOWN,
}

public data class AnalyzedFrame(
    val sample: FrameSample,
    val verdict: JankVerdict,
    val severity: JankSeverity,
    val missedVsyncCount: Int?,
    val jankTypes: Set<JankType>,
    val bottleneckStage: String?,
)

public data class FrameSummary(
    val totalFrames: Int = 0,
    val classifiedFrames: Int = 0,
    val jankFrames: Int = 0,
    val unknownFrames: Int = 0,
    val jankRate: Double = 0.0,
    val p50DurationNs: Long? = null,
    val p95DurationNs: Long? = null,
    val p99DurationNs: Long? = null,
    val worstDurationNs: Long? = null,
)

public data class JankCluster(
    val id: Int,
    val firstFrameId: Long,
    val lastFrameId: Long,
    val jankFrameIds: List<Long>,
    val durationNs: Long,
    val worstSeverity: JankSeverity,
    val windowId: String?,
    val activityName: String?,
    val dominantStage: String?,
)

public data class FrameAnalysisResult(
    val frames: List<AnalyzedFrame>,
    val summary: FrameSummary,
    val clusters: List<JankCluster>,
)

public class FrameJankAnalyzer(
    private val maxSmoothGap: Int = DEFAULT_MAX_SMOOTH_GAP,
) {
    init {
        require(maxSmoothGap >= 0) { "maxSmoothGap must not be negative" }
    }

    public fun analyze(samples: List<FrameSample>): FrameAnalysisResult {
        val analyzed = samples.sortedBy { it.intendedVsyncNs ?: it.frameId }.map(::analyzeFrame)
        return FrameAnalysisResult(
            frames = analyzed,
            summary = summarize(analyzed),
            clusters = cluster(analyzed),
        )
    }

    public fun analyzeFrame(sample: FrameSample): AnalyzedFrame {
        if (!sample.eligibleForJank) return unknown(sample)

        val duration = sample.resolvedDurationNs()
        val expected = sample.expectedDurationNs?.takeIf { it > 0L }
        val verdict =
            when (sample.platformJank) {
                true -> JankVerdict.JANK
                false -> JankVerdict.SMOOTH
                null ->
                    if (duration != null && expected != null) {
                        if (duration > expected) JankVerdict.JANK else JankVerdict.SMOOTH
                    } else {
                        JankVerdict.UNKNOWN
                    }
            }
        val missedVsyncCount =
            if (duration != null && expected != null) {
                (ceil(duration.toDouble() / expected).toInt() - 1).coerceAtLeast(0)
            } else {
                null
            }
        val bottleneck =
            sample.stages
                .values()
                .maxByOrNull { it.second }
                ?.first
        val types =
            buildSet {
                addAll(sample.platformJankTypes)
                if (sample.platformJank == true) add(JankType.PLATFORM_REPORTED)
                if (verdict == JankVerdict.JANK && duration != null && expected != null && duration > expected) {
                    add(JankType.DEADLINE_MISSED)
                }
                if (verdict == JankVerdict.JANK) bottleneck?.toJankType()?.let(::add)
            }
        return AnalyzedFrame(
            sample = sample,
            verdict = verdict,
            severity = severity(verdict, duration, expected, missedVsyncCount),
            missedVsyncCount = missedVsyncCount,
            jankTypes = types,
            bottleneckStage = bottleneck,
        )
    }

    private fun summarize(frames: List<AnalyzedFrame>): FrameSummary {
        val durations = frames.mapNotNull { it.sample.resolvedDurationNs() }.sorted()
        val classified = frames.count { it.verdict != JankVerdict.UNKNOWN }
        val jank = frames.count { it.verdict == JankVerdict.JANK }
        return FrameSummary(
            totalFrames = frames.size,
            classifiedFrames = classified,
            jankFrames = jank,
            unknownFrames = frames.size - classified,
            jankRate = if (classified == 0) 0.0 else jank.toDouble() / classified,
            p50DurationNs = durations.percentile(0.50),
            p95DurationNs = durations.percentile(0.95),
            p99DurationNs = durations.percentile(0.99),
            worstDurationNs = durations.lastOrNull(),
        )
    }

    private fun cluster(frames: List<AnalyzedFrame>): List<JankCluster> {
        val result = mutableListOf<JankCluster>()
        var cursor = 0
        while (cursor < frames.size) {
            val firstIndex = frames.indexOfFirstFrom(cursor) { it.verdict == JankVerdict.JANK }
            if (firstIndex < 0) break
            val first = frames[firstIndex]
            val jankFrames = mutableListOf(first)
            var scan = firstIndex + 1
            var smoothGap = 0
            while (scan < frames.size) {
                val candidate = frames[scan]
                if (!candidate.sameContext(first) || candidate.verdict == JankVerdict.UNKNOWN) break
                if (candidate.verdict == JankVerdict.JANK) {
                    jankFrames += candidate
                    smoothGap = 0
                } else {
                    smoothGap += 1
                    if (smoothGap > maxSmoothGap) break
                }
                scan += 1
            }
            val last = jankFrames.last()
            result +=
                JankCluster(
                    id = result.size,
                    firstFrameId = first.sample.frameId,
                    lastFrameId = last.sample.frameId,
                    jankFrameIds = jankFrames.map { it.sample.frameId },
                    durationNs = clusterDuration(first.sample, last.sample),
                    worstSeverity = jankFrames.maxBy { it.severity.ordinal }.severity,
                    windowId = first.sample.windowId,
                    activityName = first.sample.activityName,
                    dominantStage =
                        jankFrames
                            .mapNotNull(AnalyzedFrame::bottleneckStage)
                            .groupingBy { it }
                            .eachCount()
                            .maxByOrNull { it.value }
                            ?.key,
                )
            cursor = lastIndexOf(frames, last) + 1
        }
        return result
    }

    private fun severity(
        verdict: JankVerdict,
        durationNs: Long?,
        expectedNs: Long?,
        missedVsyncCount: Int?,
    ): JankSeverity =
        when {
            verdict == JankVerdict.UNKNOWN -> JankSeverity.UNKNOWN
            verdict == JankVerdict.SMOOTH -> JankSeverity.SMOOTH
            durationNs != null && durationNs >= FROZEN_FRAME_NS -> JankSeverity.FROZEN
            expectedNs == null || missedVsyncCount == null || missedVsyncCount <= 1 -> JankSeverity.MINOR
            missedVsyncCount <= 3 -> JankSeverity.MAJOR
            else -> JankSeverity.SEVERE
        }

    private fun unknown(sample: FrameSample): AnalyzedFrame =
        AnalyzedFrame(
            sample = sample,
            verdict = JankVerdict.UNKNOWN,
            severity = JankSeverity.UNKNOWN,
            missedVsyncCount = null,
            jankTypes = sample.platformJankTypes,
            bottleneckStage = null,
        )

    private fun List<Long>.percentile(fraction: Double): Long? {
        if (isEmpty()) return null
        val index = (ceil(size * fraction).toInt() - 1).coerceIn(indices)
        return this[index]
    }

    private fun String.toJankType(): JankType? =
        when (this) {
            "Input" -> JankType.SLOW_INPUT
            "Animation" -> JankType.SLOW_ANIMATION
            "Layout/Measure" -> JankType.SLOW_LAYOUT
            "Draw" -> JankType.SLOW_DRAW
            "Sync" -> JankType.SLOW_SYNC
            "Command" -> JankType.SLOW_COMMAND
            "Swap" -> JankType.SLOW_SWAP
            else -> null
        }

    private fun AnalyzedFrame.sameContext(other: AnalyzedFrame): Boolean =
        sample.windowId == other.sample.windowId && sample.activityName == other.sample.activityName

    private fun clusterDuration(
        first: FrameSample,
        last: FrameSample,
    ): Long {
        val start = first.intendedVsyncNs
        val end = last.presentNs ?: last.frameCompletedNs
        return if (start != null && end != null && end >= start) {
            end - start
        } else {
            last.resolvedDurationNs() ?: 0L
        }
    }

    private fun <T> List<T>.indexOfFirstFrom(
        start: Int,
        predicate: (T) -> Boolean,
    ): Int {
        for (index in start..lastIndex) if (predicate(this[index])) return index
        return -1
    }

    private fun <T> lastIndexOf(
        items: List<T>,
        item: T,
    ): Int = items.lastIndexOf(item)

    private companion object {
        const val DEFAULT_MAX_SMOOTH_GAP = 2
        const val FROZEN_FRAME_NS = 700_000_000L
    }
}
