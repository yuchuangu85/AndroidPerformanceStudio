package com.androidperformancestudio.frame.presentation

import com.androidperformancestudio.frame.analysis.FrameJankAnalyzer
import com.androidperformancestudio.frame.model.ExpectedDurationSource
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import com.androidperformancestudio.frame.model.FrameStages
import com.androidperformancestudio.ui.UiLanguage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameEvidenceTextTest {
    @Test
    fun `copy text retains real frame evidence but never prints an unknown budget`() {
        val sample =
            FrameSample(
                frameId = 42L,
                sessionId = "session-1",
                source = FrameSource.PERFETTO,
                frameTimelineVsyncId = 123L,
                totalDurationNs = 20_000_000L,
                expectedDurationNs = 16_666_667L,
                expectedDurationSource = ExpectedDurationSource.UNKNOWN,
                platformJank = true,
                stages = FrameStages(drawNs = 2_000_000L),
                states = mapOf("screen" to "Home\nBudget: 1 ms"),
            )

        val text = FrameJankAnalyzer().analyzeFrame(sample).copyEvidenceText(UiLanguage.ENGLISH)

        assertTrue(text.contains("Frame: #42"))
        assertTrue(text.contains("FrameTimeline VSync ID: 123"))
        assertTrue(text.contains("Duration: 20.00 ms"))
        assertTrue(text.contains("Budget: —"))
        assertTrue(text.contains("Budget Source: UNKNOWN"))
        assertTrue(text.contains("Draw: 2.00 ms"))
        assertTrue(text.contains("State · screen: Home\\nBudget: 1 ms"))
        assertFalse(text.contains("\nBudget: 1 ms"))
        assertFalse(text.contains("16.67 ms"))
    }

    @Test
    fun `copy text includes a known sourced budget`() {
        val sample =
            FrameSample(
                frameId = 1L,
                sessionId = "session-1",
                source = FrameSource.GFXINFO,
                totalDurationNs = 20_000_000L,
                expectedDurationNs = 16_666_667L,
                expectedDurationSource = ExpectedDurationSource.FRAME_INTERVAL,
            )

        val text = FrameJankAnalyzer().analyzeFrame(sample).copyEvidenceText(UiLanguage.ENGLISH)

        assertTrue(text.contains("Budget: 16.67 ms"))
        assertTrue(text.contains("Budget Source: FRAME_INTERVAL"))
    }

    @Test
    fun `copy action is explicit and reports clipboard outcome`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/frame/presentation/FrameProfilerScreen.kt"))
        val detail = source.substringAfter("private fun FrameDetail(").substringBefore("private fun DetailRow(")

        assertTrue(detail.contains("onCopyEvidence?.let"))
        assertTrue(detail.contains("copyEvidenceText(language)"))
        assertTrue(detail.contains("copySucceeded"))
    }
}
