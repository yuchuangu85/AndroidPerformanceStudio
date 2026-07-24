package com.androidperformancestudio.startup.export

import com.androidperformancestudio.startup.analysis.StartupAnalyzer
import com.androidperformancestudio.startup.model.PlatformLaunchMetrics
import com.androidperformancestudio.startup.model.StartupRawEvidence
import com.androidperformancestudio.startup.model.StartupRun
import com.androidperformancestudio.startup.model.StartupType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains

class StartupExportersTest {
    @Test
    fun `exports versioned json and flat csv`() {
        val analysis =
            StartupAnalyzer().analyze(
                listOf(
                    StartupRun(
                        "run",
                        "session",
                        1,
                        StartupType.COLD,
                        StartupType.COLD,
                        PlatformLaunchMetrics(totalTimeMs = 123),
                        rawEvidence = StartupRawEvidence("raw"),
                    ),
                ),
            )
        val directory = Files.createTempDirectory("startup-export")
        val json = directory.resolve("report.json")
        val csv = directory.resolve("report.csv")

        StartupJsonExporter().export(analysis, json)
        StartupCsvExporter().export(analysis, csv)

        assertContains(Files.readString(json), "\"schemaVersion\": 1")
        assertContains(Files.readString(csv), "123")
    }
}
