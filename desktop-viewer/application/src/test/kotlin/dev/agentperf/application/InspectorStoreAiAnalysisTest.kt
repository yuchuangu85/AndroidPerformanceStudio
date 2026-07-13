package dev.agentperf.application

import dev.agentperf.analysis.AiAnalysisReport
import dev.agentperf.fixtures.SampleSnapshots
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InspectorStoreAiAnalysisTest {
    @Test
    fun `stores ai analysis and clears it when capture changes`() {
        val store = InspectorStore().apply { load(SampleSnapshots.dashboard) }
        val report = AiAnalysisReport(model = "gpt-test", summary = "summary")

        store.loadAiAnalysis(report)
        assertEquals(report, store.state.aiAnalysis)

        store.loadCapture(SampleSnapshots.dashboard.copy(capturedAtEpochMillis = 1234), byteArrayOf(1, 2, 3))

        assertNull(store.state.aiAnalysis)
    }
}
