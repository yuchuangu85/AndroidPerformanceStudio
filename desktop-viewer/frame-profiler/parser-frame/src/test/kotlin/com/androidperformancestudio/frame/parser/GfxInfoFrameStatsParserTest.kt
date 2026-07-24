package com.androidperformancestudio.frame.parser

import com.androidperformancestudio.frame.model.ExpectedDurationSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GfxInfoFrameStatsParserTest {
    private val parser = GfxInfoFrameStatsParser()

    @Test
    fun `parses deadline and stage durations by column name`() {
        val result = parser.parse(FRAMESTATS_WITH_DEADLINE, sessionId = "session", packageName = "com.example")

        assertTrue(result.warnings.isEmpty())
        assertEquals(2, result.frames.size)
        val first = result.frames.first()
        assertEquals(8_333_333L, first.expectedDurationNs)
        assertEquals(ExpectedDurationSource.PLATFORM_DEADLINE, first.expectedDurationSource)
        assertEquals(10_000_000L, first.totalDurationNs)
        assertEquals(1_000_000L, first.stages.inputNs)
        assertEquals(2_000_000L, first.stages.layoutMeasureNs)
        assertEquals("com.example", first.packageName)
    }

    @Test
    fun `marks nonzero flag rows ineligible for jank classification`() {
        val result = parser.parse(FRAMESTATS_WITH_DEADLINE.replaceFirst("0,100000000", "1,100000000"), "session")

        assertFalse(result.frames.first().eligibleForJank)
        assertEquals("1", result.frames.first().states["gfxinfo.flags"])
    }

    @Test
    fun `infers refresh interval when legacy rows have no deadline`() {
        val result = parser.parse(LEGACY_FRAMESTATS, "legacy")

        assertEquals(8_333_333L, result.frames.first().expectedDurationNs)
        assertEquals(ExpectedDurationSource.INFERRED_VSYNC, result.frames.first().expectedDurationSource)
    }

    @Test
    fun `keeps unavailable zero timestamp stages unknown`() {
        val result =
            parser.parse(
                FRAMESTATS_WITH_DEADLINE.replace("101000000,102000000", "0,0"),
                "session",
            )

        assertEquals(
            null,
            result.frames
                .first()
                .stages.inputNs,
        )
    }

    private companion object {
        val FRAMESTATS_WITH_DEADLINE =
            """
            ---PROFILEDATA---
            Flags,IntendedVsync,Vsync,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,FrameDeadline,FrameInterval,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted,GpuCompleted,DisplayPresentTime
            0,100000000,100000000,101000000,102000000,103000000,105000000,108333333,8333333,106000000,106500000,107000000,108000000,110000000,111000000,110000000
            0,108333333,108333333,109000000,110000000,111000000,112000000,116666666,8333333,113000000,113500000,114000000,115000000,116000000,117000000,116000000
            ---PROFILEDATA---
            """.trimIndent()

        val LEGACY_FRAMESTATS =
            """
            ---PROFILEDATA---
            Flags,IntendedVsync,Vsync,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted
            0,100000000,100000000,101000000,102000000,103000000,104000000,105000000,105000000,106000000,107000000,108000000
            0,108333333,108333333,109000000,110000000,111000000,112000000,113000000,113000000,114000000,115000000,116000000
            0,116666666,116666666,117000000,118000000,119000000,120000000,121000000,121000000,122000000,123000000,124000000
            ---PROFILEDATA---
            """.trimIndent()
    }
}
