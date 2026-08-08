package com.androidperformancestudio.frame.capture

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GfxInfoPollingCaptureSessionTest {
    @Test
    fun `deduplicates overlapping gfxinfo snapshots`() =
        runBlocking {
            var poll = 0
            val runner =
                AdbShellRunner { _, arguments ->
                    if (arguments.last() == "reset") {
                        ""
                    } else {
                        poll += 1
                        if (poll == 1) frameStats(listOf(FRAME_ONE)) else frameStats(listOf(FRAME_ONE, FRAME_TWO))
                    }
                }
            val session =
                GfxInfoPollingCaptureSession(
                    target = GfxInfoCaptureTarget("device", "com.example", processId = 42),
                    sessionId = "session",
                    runner = runner,
                )

            assertTrue(session.start().isEmpty())
            val first = session.poll()
            val second = session.poll()

            assertEquals(listOf(0L), first.frames.map { it.frameId })
            assertEquals(listOf(1L), second.frames.map { it.frameId })
            assertEquals(42, second.frames.single().processId)
            assertEquals("com.example", second.frames.single().packageName)
        }

    @Test
    fun `reset failure is a warning and does not block polling`() =
        runBlocking {
            val runner =
                AdbShellRunner { _, arguments ->
                    if (arguments.last() == "reset") error("reset unsupported") else frameStats(listOf(FRAME_ONE))
                }
            val session =
                GfxInfoPollingCaptureSession(
                    target = GfxInfoCaptureTarget("device", "com.example", processId = 42),
                    sessionId = "session",
                    runner = runner,
                )

            assertEquals(1, session.start().size)
            assertEquals(1, session.poll().frames.size)
        }

    @Test
    fun `warns instead of inventing a drop count when a full window has no overlap`() =
        runBlocking {
            var poll = 0
            val runner =
                AdbShellRunner { _, arguments ->
                    if (arguments.last() == "reset") {
                        ""
                    } else {
                        val start = if (poll++ == 0) 100_000_000L else 10_000_000_000L
                        frameStats((0 until 110).map { index -> frameRow(start + index * 8_333_333L) })
                    }
                }
            val session =
                GfxInfoPollingCaptureSession(
                    target = GfxInfoCaptureTarget("device", "com.example", processId = 42),
                    sessionId = "session",
                    runner = runner,
                )

            session.start()
            session.poll()
            val second = session.poll()

            assertTrue(second.warnings.any { it.contains("may have been overwritten") })
            assertTrue(second.frames.all { it.droppedBeforeSample == 0L })
        }

    private fun frameStats(rows: List<String>): String =
        buildString {
            appendLine("---PROFILEDATA---")
            appendLine(HEADER)
            rows.forEach(::appendLine)
            appendLine("---PROFILEDATA---")
        }

    private fun frameRow(intendedVsyncNs: Long): String =
        "0,$intendedVsyncNs,$intendedVsyncNs,${intendedVsyncNs + 1},${intendedVsyncNs + 2}," +
            "${intendedVsyncNs + 3},${intendedVsyncNs + 4},${intendedVsyncNs + 8_333_333},8333333," +
            "${intendedVsyncNs + 5},${intendedVsyncNs + 5},${intendedVsyncNs + 6}," +
            "${intendedVsyncNs + 7},${intendedVsyncNs + 8}"

    private companion object {
        const val HEADER =
            "Flags,IntendedVsync,Vsync,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart," +
                "FrameDeadline,FrameInterval,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted"
        const val FRAME_ONE =
            "0,100000000,100000000,101000000,102000000,103000000,105000000,108333333,8333333," +
                "106000000,106500000,107000000,108000000,110000000"
        const val FRAME_TWO =
            "0,108333333,108333333,109000000,110000000,111000000,112000000,116666666,8333333," +
                "113000000,113500000,114000000,115000000,116000000"
    }
}
