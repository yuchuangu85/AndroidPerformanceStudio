package com.androidperformancestudio.frame.export

import com.androidperformancestudio.frame.analysis.FrameJankAnalyzer
import com.androidperformancestudio.frame.model.ExpectedDurationSource
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class FrameJsonExporterTest {
    @Test
    fun `exports summary frames states and clusters as structured json`() {
        val output = createTempDirectory("frame-json").resolve("report.json")
        val sample =
            FrameSample(
                frameId = 1,
                sessionId = "session",
                source = FrameSource.FRAME_METRICS,
                packageName = "dev.example",
                intendedVsyncNs = 100,
                totalDurationNs = 20_000_000,
                expectedDurationNs = 8_333_333,
                expectedDurationSource = ExpectedDurationSource.REFRESH_RATE,
                platformJank = true,
                states = mapOf("screen" to "feed\"list"),
            )

        FrameJsonExporter().export(FrameJankAnalyzer().analyze(listOf(sample)), output)

        val json = output.readText()
        assertTrue(json.contains("\"schemaVersion\": 2"))
        assertTrue(json.contains("\"deadlineMissFrames\": 1"))
        assertTrue(json.contains("\"platformJankFrames\": 1"))
        assertTrue(json.contains("\"screen\": \"feed\\\"list\""))
        assertTrue(json.contains("\"clusters\": ["))
    }
}
