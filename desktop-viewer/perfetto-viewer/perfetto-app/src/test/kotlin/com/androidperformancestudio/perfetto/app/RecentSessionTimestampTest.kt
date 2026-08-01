package com.androidperformancestudio.perfetto.app

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class RecentSessionTimestampTest {
    @Test
    fun `recent session timestamp separates date and time with dash T`() {
        assertEquals(
            "2026-08-01-T08:07:59.643394Z",
            formatRecentSessionTimestamp(Instant.parse("2026-08-01T08:07:59.643394Z")),
        )
    }
}
