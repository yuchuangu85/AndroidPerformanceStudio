package dev.agentperf.desktop

import dev.agentperf.analysis.AiAnalysisReport
import dev.agentperf.analysis.AiFinding
import dev.agentperf.analysis.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AiAnalysisReportJsonTest {
    @Test
    fun `round trips ai analysis report`() {
        val report = AiAnalysisReport(
            model = "gpt-test",
            summary = "Two risks found",
            findings = listOf(
                AiFinding(
                    ruleId = "ai.accessibility.touch-target",
                    severity = Severity.WARNING,
                    nodeId = "save",
                    title = "Small touch target",
                    message = "Button is likely too small",
                    recommendation = "Increase min height to 48dp",
                    confidence = 0.82f,
                ),
            ),
        )

        val restored = AiAnalysisReportJson().decode(AiAnalysisReportJson().encode(report))

        assertEquals(report, restored)
    }
}
