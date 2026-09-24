package com.androidperformancestudio.frame.presentation

import com.androidperformancestudio.frame.model.ExpectedDurationSource
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FrameBudgetDisplayTest {
    @Test
    fun `unknown or invalid expected duration is unavailable instead of a numeric budget`() {
        val frame =
            FrameSample(
                frameId = 1,
                sessionId = "session",
                source = FrameSource.GFXINFO,
                expectedDurationNs = 16_666_667L,
                expectedDurationSource = ExpectedDurationSource.UNKNOWN,
            )

        assertEquals("—", frame.displayBudget())
        assertEquals(
            "—",
            frame
                .copy(expectedDurationSource = ExpectedDurationSource.FRAME_INTERVAL, expectedDurationNs = 0L)
                .displayBudget(),
        )
        assertNotEquals("—", frame.copy(expectedDurationSource = ExpectedDurationSource.FRAME_INTERVAL).displayBudget())
    }
}
