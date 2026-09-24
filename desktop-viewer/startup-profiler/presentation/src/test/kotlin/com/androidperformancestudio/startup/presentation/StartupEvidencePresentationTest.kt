package com.androidperformancestudio.startup.presentation

import com.androidperformancestudio.startup.model.CompilationMode
import com.androidperformancestudio.startup.model.EvidenceConfidence
import com.androidperformancestudio.startup.model.StartupCompilationEvidence
import com.androidperformancestudio.startup.model.StartupMetricEvidence
import com.androidperformancestudio.startup.model.StartupProfileSource
import com.androidperformancestudio.ui.UiLanguage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StartupEvidencePresentationTest {
    @Test
    fun `metric reason never hides unavailable confidence`() {
        val evidence = StartupMetricEvidence(confidence = EvidenceConfidence.UNAVAILABLE, unavailableReason = "No fully drawn signal")

        assertEquals("Unavailable · No fully drawn signal", evidence.statusLabel(UiLanguage.ENGLISH))
        assertEquals("不可用 · No fully drawn signal", evidence.statusLabel(UiLanguage.SIMPLIFIED_CHINESE))
    }

    @Test
    fun `speed profile evidence exposes requested and observed configuration independently`() {
        val evidence =
            StartupCompilationEvidence(
                requestedMode = CompilationMode.SPEED_PROFILE,
                compilerFilterBefore = "verify",
                compilerFilterAfter = "speed-profile",
                verified = false,
                failureReason = "Profile source not confirmed",
                profileSource = StartupProfileSource.UNVERIFIED,
                profileSourceDeclared = false,
            )

        val rows = evidence.detailRows(UiLanguage.ENGLISH).associate { it.label to it.value }

        assertEquals("SPEED_PROFILE", rows["Requested compilation mode"])
        assertEquals("verify", rows["Compiler filter before"])
        assertEquals("speed-profile", rows["Compiler filter after"])
        assertEquals("No", rows["Compilation verified"])
        assertEquals("UNVERIFIED", rows["Profile source"])
        assertEquals("No", rows["Profile source declared"])
        assertEquals("Profile source not confirmed", rows["Compilation failure reason"])
        assertEquals("Unavailable", rows["Baseline Profile artifact"])
        assertEquals(
            "Unavailable",
            evidence
                .copy(
                    failureReason = null,
                ).detailRows(UiLanguage.ENGLISH)
                .associate { it.label to it.value }["Compilation failure reason"],
        )
        assertEquals(
            "None",
            evidence
                .copy(verified = true, failureReason = null)
                .detailRows(UiLanguage.ENGLISH)
                .associate { it.label to it.value }["Compilation failure reason"],
        )
    }

    @Test
    fun `run detail exposes trace correlation evidence and only offers explicit navigation for captured files`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/startup/presentation/StartupProfilerScreen.kt"))
        val detail = source.substringAfter("private fun RunDetail(").substringBefore("private fun TimelineBar(")

        assertTrue(detail.contains("rootCause.correlationErrorBoundNs"))
        assertTrue(detail.contains("rootCause.limitations"))
        assertTrue(detail.contains("rootCause.schedulingSlices"))
        assertTrue(detail.contains("rootCause.binderSlices"))
        assertTrue(detail.contains("rootCause.mainThreadSlices"))
        assertTrue(detail.contains("rootCause.frameSlices"))
        assertTrue(detail.contains("evidence.captured && !traceFile.isNullOrBlank()"))
        assertTrue(detail.contains("traceOpenFailed = !openTrace(traceFile)"))
    }
}
