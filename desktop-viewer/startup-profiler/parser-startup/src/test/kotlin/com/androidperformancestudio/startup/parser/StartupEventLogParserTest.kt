package com.androidperformancestudio.startup.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class StartupEventLogParserTest {
    @Test
    fun `extracts displayed and fully drawn durations for target package`() {
        val output =
            """
            I/wm_activity_launch_time(123): [0,123,dev.sample/.MainActivity,210]
            I/wm_fully_drawn_time(123): [0,123,dev.sample/.MainActivity,540]
            I/wm_fully_drawn_time(222): [0,222,other.app/.MainActivity,999]
            """.trimIndent()

        val result = StartupEventLogParser().parse(output, "dev.sample")

        assertEquals(210, result.displayedTimeMs)
        assertEquals(540, result.fullyDrawnTimeMs)
    }
}
