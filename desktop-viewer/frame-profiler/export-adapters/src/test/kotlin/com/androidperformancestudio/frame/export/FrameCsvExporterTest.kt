package com.androidperformancestudio.frame.export

import com.androidperformancestudio.frame.analysis.FrameJankAnalyzer
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import kotlin.io.path.createTempFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains

class FrameCsvExporterTest {
    @Test
    fun `exports normalized deadline and verdict`() {
        val output = createTempFile("frames", ".csv")
        val result =
            FrameJankAnalyzer().analyze(
                listOf(
                    FrameSample(
                        frameId = 1,
                        sessionId = "session",
                        source = FrameSource.GFXINFO,
                        totalDurationNs = 10_000_000,
                        expectedDurationNs = 8_333_333,
                    ),
                ),
            )

        FrameCsvExporter().export(result, output)

        assertContains(output.readText(), "JANK")
        assertContains(output.readText(), "8333333")
    }
}
