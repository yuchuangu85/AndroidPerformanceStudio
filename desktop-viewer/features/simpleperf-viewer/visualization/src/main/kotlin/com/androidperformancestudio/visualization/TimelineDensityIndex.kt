package com.androidperformancestudio.visualization

import com.androidperformancestudio.model.ProfileSample
import kotlin.math.ceil
import kotlin.math.floor

data class TimeViewport(
    val startNanos: Long,
    val endNanosExclusive: Long,
) {
    init {
        require(endNanosExclusive > startNanos) { "viewport must have a positive duration" }
    }
}

data class TimelineColumn(
    val weight: Long,
)

data class TimelineFrame(
    val columns: List<TimelineColumn>,
) {
    val totalWeight: Long = columns.sumOf(TimelineColumn::weight)
    val maximumWeight: Long = columns.maxOfOrNull(TimelineColumn::weight) ?: 0
}

class TimelineDensityIndex private constructor(
    private val startNanos: Long,
    private val endNanosExclusive: Long,
    private val buckets: LongArray,
) {
    fun project(
        viewport: TimeViewport,
        widthPixels: Int,
    ): TimelineFrame {
        require(widthPixels > 0) { "widthPixels must be positive" }
        val visibleStart = viewport.startNanos.coerceAtLeast(startNanos)
        val visibleEnd = viewport.endNanosExclusive.coerceAtMost(endNanosExclusive)
        if (visibleEnd <= visibleStart) {
            return TimelineFrame(List(widthPixels) { TimelineColumn(0) })
        }

        val firstBucket = bucketPosition(visibleStart)
        val lastBucketExclusive = bucketPosition(visibleEnd)
        val visibleBucketCount = lastBucketExclusive - firstBucket
        val columns = LongArray(widthPixels)
        val firstSourceBucket = floor(firstBucket).toInt().coerceAtLeast(0)
        val lastSourceBucketExclusive = ceil(lastBucketExclusive).toInt().coerceAtMost(buckets.size)
        for (bucket in firstSourceBucket until lastSourceBucketExclusive) {
            val bucketCenter = bucket + BUCKET_CENTER_OFFSET
            if (bucketCenter >= firstBucket && bucketCenter < lastBucketExclusive) {
                val pixel =
                    ((bucketCenter - firstBucket) / visibleBucketCount * widthPixels)
                        .toInt()
                        .coerceIn(0, widthPixels - 1)
                columns[pixel] += buckets[bucket]
            }
        }
        return TimelineFrame(columns.map(::TimelineColumn))
    }

    private fun bucketPosition(timestampNanos: Long): Double =
        (timestampNanos - startNanos).toDouble() /
            (endNanosExclusive - startNanos) * buckets.size

    companion object {
        private const val BUCKET_CENTER_OFFSET = 0.5

        fun build(
            samples: Sequence<ProfileSample>,
            startNanos: Long,
            endNanosExclusive: Long,
            bucketCount: Int,
        ): TimelineDensityIndex {
            require(endNanosExclusive > startNanos) { "index must have a positive duration" }
            require(bucketCount > 0) { "bucketCount must be positive" }
            val buckets = LongArray(bucketCount)
            val duration = endNanosExclusive - startNanos
            samples.forEach { sample ->
                if (sample.timestampNanos in startNanos until endNanosExclusive) {
                    val bucket =
                        ((sample.timestampNanos - startNanos).toDouble() / duration * bucketCount)
                            .toInt()
                            .coerceAtMost(bucketCount - 1)
                    buckets[bucket] += sample.eventCount
                }
            }
            return TimelineDensityIndex(startNanos, endNanosExclusive, buckets)
        }
    }
}
