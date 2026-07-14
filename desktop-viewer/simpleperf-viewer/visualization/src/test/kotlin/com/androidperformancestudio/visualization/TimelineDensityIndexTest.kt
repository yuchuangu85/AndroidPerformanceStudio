package com.androidperformancestudio.visualization

import com.androidperformancestudio.model.ProfileSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimelineDensityIndexTest {
    @Test
    fun `projects a million-scale stream into a viewport-bounded frame`() {
        val index =
            TimelineDensityIndex.build(
                samples =
                    (0 until 10_000).asSequence().map { index ->
                        ProfileSample(
                            timestampNanos = index.toLong(),
                            processId = 1,
                            threadId = 1,
                            eventType = "cpu-cycles",
                            symbolName = "work",
                            eventCount = 1,
                        )
                    },
                startNanos = 0,
                endNanosExclusive = 10_000,
                bucketCount = 1_000,
            )

        val frame =
            index.project(
                viewport = TimeViewport(2_000, 4_000),
                widthPixels = 100,
            )

        assertEquals(100, frame.columns.size)
        assertEquals(2_000L, frame.totalWeight)
        assertTrue(frame.columns.all { it.weight == 20L })
    }

    @Test
    fun `zooming beyond index resolution does not duplicate sample weight`() {
        val index =
            TimelineDensityIndex.build(
                samples =
                    (0 until 10).asSequence().map { index ->
                        ProfileSample(
                            timestampNanos = index.toLong(),
                            processId = 1,
                            threadId = 1,
                            eventType = "cpu-cycles",
                            symbolName = "work",
                            eventCount = 1,
                        )
                    },
                startNanos = 0,
                endNanosExclusive = 10,
                bucketCount = 10,
            )

        val frame = index.project(TimeViewport(0, 10), widthPixels = 20)

        assertEquals(10L, frame.totalWeight)
    }
}
