package com.androidperformancestudio.startup.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AmStartOutputParserTest {
    @Test
    fun `parses modern activity manager output`() {
        val result =
            AmStartOutputParser().parse(
                """
                Starting: Intent { cmp=dev.sample/.MainActivity }
                Status: ok
                LaunchState: COLD
                Activity: dev.sample/.MainActivity
                ThisTime: 320
                TotalTime: 410
                WaitTime: 432
                Complete
                """.trimIndent(),
            )

        assertEquals(410, result.metrics.totalTimeMs)
        assertEquals("COLD", result.metrics.launchState)
        assertTrue(result.metrics.complete)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `keeps command warnings and missing total time`() {
        val result = AmStartOutputParser().parse("Warning: Activity not started, intent delivered")

        assertEquals(null, result.metrics.totalTimeMs)
        assertEquals(2, result.warnings.size)
    }
}
