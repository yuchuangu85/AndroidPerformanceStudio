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

    @Test
    fun `infers legacy budgets per frame across a refresh mode change`() {
        val result = parser.parse(LEGACY_FRAMESTATS_WITH_MODE_CHANGE, "legacy")

        assertEquals(
            listOf(8_333_333L, 8_333_333L, 16_666_667L, 16_666_667L),
            result.frames.map { it.expectedDurationNs },
        )
        assertTrue(result.warnings.any { it.contains("Multiple frame intervals") })
    }

    @Test
    fun `parses repeated window schemas and preserves unknown numeric columns`() {
        val result = parser.parse(MULTI_WINDOW_FRAMESTATS, "multi")

        assertEquals(listOf("Window A", "Window B"), result.frames.map { it.windowId })
        assertEquals("7", result.frames.last().states["gfxinfo.column.FutureStage"])
        assertTrue(result.warnings.any { it.contains("FutureStage") })
    }

    @Test
    fun `parses modern device columns with trailing separators without unsupported warnings`() {
        val result = parser.parse(MODERN_FRAMESTATS, "modern")

        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
        assertEquals(2, result.frames.size)
        val first = result.frames.first()
        assertEquals(477300L, first.frameTimelineVsyncId)
        assertEquals(24_132_507L, first.expectedDurationNs)
        assertEquals(ExpectedDurationSource.PLATFORM_DEADLINE, first.expectedDurationSource)
        assertEquals(13_826_484L, first.totalDurationNs)
        assertEquals("477300", first.states["gfxinfo.column.FrameTimelineVsyncId"])
        assertEquals("975831300", first.states["gfxinfo.column.InputEventId"])
        assertEquals("8373159", first.states["gfxinfo.column.FrameStartTime"])
        assertEquals("104583071253309", first.states["gfxinfo.column.CommandSubmissionCompleted"])
        assertEquals(null, first.platformJank)
    }

    @Test
    fun `accepts trailing separators independently on headers and rows`() {
        listOf(false, true).forEach { headerSeparator ->
            listOf(false, true).forEach { rowSeparator ->
                val text =
                    """
                    ---PROFILEDATA---
                    Flags,IntendedVsync,FrameCompleted${if (headerSeparator) "," else ""}
                    0,100000000,110000000${if (rowSeparator) "," else ""}
                    ---PROFILEDATA---
                    """.trimIndent()
                val result = parser.parse(text, "separators")

                assertEquals(1, result.frames.size)
                assertTrue(result.warnings.isEmpty(), result.warnings.toString())
            }
        }
    }

    @Test
    fun `keeps an empty named last column without treating it as a missing field`() {
        val result =
            parser.parse(
                """
                ---PROFILEDATA---
                Flags,IntendedVsync,FrameCompleted,CommandSubmissionCompleted,
                0,100000000,110000000,,
                ---PROFILEDATA---
                """.trimIndent(),
                "empty-column",
            )

        assertEquals(1, result.frames.size)
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
    }

    @Test
    fun `still preserves and warns about unknown columns with trailing separators`() {
        val result =
            parser.parse(
                """
                ---PROFILEDATA---
                Flags,IntendedVsync,FrameCompleted,FutureStage,
                0,100000000,110000000,7,
                ---PROFILEDATA---
                """.trimIndent(),
                "future",
            )

        assertEquals("7", result.frames.single().states["gfxinfo.column.FutureStage"])
        assertEquals(listOf("Preserved unsupported framestats column(s): FutureStage."), result.warnings)
    }

    @Test
    fun `retains malformed row warnings when suppressing empty snapshot warnings`() {
        val result =
            parser.parse(
                """
                ---PROFILEDATA---
                Flags,IntendedVsync,FrameCompleted,
                0,100000000
                0,100000000,110000000,7,
                ---PROFILEDATA---
                """.trimIndent(),
                "malformed",
                warnOnEmptyFrames = false,
            )

        assertTrue(result.frames.isEmpty())
        assertEquals(listOf("2 malformed frame row(s) were skipped."), result.warnings)
    }

    @Test
    fun `empty imports retain the warning while live snapshots can suppress it`() {
        val text = "---PROFILEDATA---\nFlags,IntendedVsync,FrameCompleted,\n---PROFILEDATA---"

        assertEquals(
            listOf("The framestats section did not contain usable frames."),
            parser.parse(text, "import").warnings,
        )
        assertTrue(parser.parse(text, "live", warnOnEmptyFrames = false).warnings.isEmpty())
        assertEquals(
            listOf("No gfxinfo framestats header was found."),
            parser.parse("invalid", "live", warnOnEmptyFrames = false).warnings,
        )
    }

    @Test
    fun `explicit frame interval changes are not described as legacy inference`() {
        val result =
            parser.parse(
                MULTI_WINDOW_FRAMESTATS.replace("16666667,210000000", "8333333,210000000"),
                "explicit",
            )

        assertEquals(listOf(16_666_667L, 8_333_333L), result.frames.map { it.expectedDurationNs })
        assertTrue(result.frames.all { it.expectedDurationSource == ExpectedDurationSource.FRAME_INTERVAL })
        assertFalse(result.warnings.any { it.contains("legacy frame budgets") })
    }

    private companion object {
        val MODERN_FRAMESTATS =
            """
            ---PROFILEDATA---
            Flags,FrameTimelineVsyncId,IntendedVsync,Vsync,InputEventId,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,FrameDeadline,FrameInterval,FrameStartTime,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted,DequeueBufferDuration,QueueBufferDuration,GpuCompleted,SwapBuffersCompleted,DisplayPresentTime,CommandSubmissionCompleted,
            0,477300,104583059254518,104583059254518,975831300,104583064459771,104583067407848,104583067413002,104583070297694,104583083387025,104583064449386,8373159,104583070469925,104583070543156,104583070723540,104583071253309,104583073081002,19384,221923,104583073081002,104583071906232,104583082254848,104583071253309,
            0,477315,104583067638732,104583067638732,530497226,104583073060771,104583074875463,104583074876694,104583074956463,104583091760184,104583072699771,8374588,104583075110848,104583075177079,104583075347540,104583075921079,104583077835540,20231,468616,104583077835540,104583077273617,104583090604079,104583075921079,
            ---PROFILEDATA---
            """.trimIndent()

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

        val LEGACY_FRAMESTATS_WITH_MODE_CHANGE =
            """
            ---PROFILEDATA---
            Flags,IntendedVsync,Vsync,FrameCompleted
            0,100000000,100000000,108000000
            0,108333333,108333333,116000000
            0,116666666,116666666,124000000
            0,133333333,133333333,141000000
            ---PROFILEDATA---
            """.trimIndent()

        val MULTI_WINDOW_FRAMESTATS =
            """
            Window: Window A
            ---PROFILEDATA---
            Flags,IntendedVsync,Vsync,FrameInterval,FrameCompleted
            0,100000000,100000000,16666667,110000000
            ---PROFILEDATA---
            Window: Window B
            ---PROFILEDATA---
            Flags,IntendedVsync,Vsync,FrameInterval,FrameCompleted,FutureStage
            0,200000000,200000000,16666667,210000000,7
            ---PROFILEDATA---
            """.trimIndent()
    }
}
