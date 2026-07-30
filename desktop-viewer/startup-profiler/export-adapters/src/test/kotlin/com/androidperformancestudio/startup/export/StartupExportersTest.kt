package com.androidperformancestudio.startup.export

import com.androidperformancestudio.startup.analysis.StartupAnalyzer
import com.androidperformancestudio.startup.model.EvidenceConfidence
import com.androidperformancestudio.startup.model.PlatformLaunchMetrics
import com.androidperformancestudio.startup.model.StartupMilestone
import com.androidperformancestudio.startup.model.StartupMilestoneKind
import com.androidperformancestudio.startup.model.StartupRawEvidence
import com.androidperformancestudio.startup.model.StartupRun
import com.androidperformancestudio.startup.model.StartupSource
import com.androidperformancestudio.startup.model.StartupType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    @Test
    fun `imports an exported startup json report`() {
        val original = startupAnalysisFixture()
        val report = Files.createTempFile("startup-import", ".json")
        StartupJsonExporter().export(original, report)

        val imported = StartupJsonImporter().import(report)

        assertEquals(original.totalTime, imported.totalTime)
        assertEquals(original.firstFrame, imported.firstFrame)
        assertEquals(original.fullyDrawn, imported.fullyDrawn)
        assertEquals(original.warnings, imported.warnings)
        assertEquals(1, imported.runs.size)
        with(imported.runs.single()) {
            assertEquals("run-7", id)
            assertEquals(7, iteration)
            assertEquals(StartupType.COLD, requestedType)
            assertEquals(StartupType.WARM, observedType)
            assertEquals(123, platform.totalTimeMs)
            assertEquals(2, milestones.size)
            assertEquals("Warm launch observed", warnings.single())
            assertEquals("Status: ok", rawEvidence.amStartOutput)
            assertEquals("event", rawEvidence.eventLogOutput)
            assertEquals("compile", rawEvidence.compilationOutput)
            assertEquals(true, rawEvidence.agentAvailable)
        }
    }

    @Test
    fun `rejects unsupported startup json schema`() {
        val report = Files.createTempFile("startup-import-unsupported", ".json")
        Files.writeString(report, """{"schemaVersion":2,"summary":{},"warnings":[],"runs":[]}""")

        val error =
            assertFailsWith<IllegalArgumentException> {
                StartupJsonImporter().import(report)
            }

        assertContains(error.message.orEmpty(), "Unsupported Startup Profiler schema version")
    }
}

private fun startupAnalysisFixture() =
    StartupAnalyzer().analyze(
        listOf(
            StartupRun(
                id = "run-7",
                sessionId = "session",
                iteration = 7,
                requestedType = StartupType.COLD,
                observedType = StartupType.WARM,
                platform =
                    PlatformLaunchMetrics(
                        thisTimeMs = 101,
                        totalTimeMs = 123,
                        waitTimeMs = 130,
                        displayedTimeMs = 115,
                        fullyDrawnTimeMs = 220,
                    ),
                milestones =
                    listOf(
                        StartupMilestone(
                            kind = StartupMilestoneKind.PROCESS_START,
                            elapsedRealtimeNs = 1_000_000,
                            source = StartupSource.EVENT_LOG,
                            confidence = EvidenceConfidence.EXACT,
                            activityName = "MainActivity",
                        ),
                        StartupMilestone(
                            kind = StartupMilestoneKind.FIRST_FRAME,
                            elapsedRealtimeNs = 116_000_000,
                            durationMs = 115,
                            source = StartupSource.AM_START,
                            confidence = EvidenceConfidence.ESTIMATED,
                            activityName = "MainActivity",
                        ),
                    ),
                warnings = listOf("Warm launch observed"),
                rawEvidence =
                    StartupRawEvidence(
                        amStartOutput = "Status: ok",
                        eventLogOutput = "event",
                        compilationOutput = "compile",
                        agentAvailable = true,
                    ),
            ),
        ),
    )
