package com.androidperformancestudio.perfetto.app

import com.androidperformancestudio.perfetto.analysis.DiagnosticCategory
import com.androidperformancestudio.perfetto.analysis.DiagnosticQuery
import com.androidperformancestudio.perfetto.analysis.DiagnosticResult
import com.androidperformancestudio.perfetto.analysis.DiagnosticRow
import com.androidperformancestudio.ui.UiAiContext
import com.androidperformancestudio.ui.UiAiEvidence
import com.androidperformancestudio.ui.boundedAiContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceAiContextTest {
    @Test
    fun `artifact switch rejects old result before asynchronous outcomes cleanup`() {
        val query = DiagnosticQuery("count", DiagnosticCategory.CPU, "title", "description", listOf("count"), "SELECT 1")
        val result = DiagnosticResult(listOf("count"), listOf(DiagnosticRow(listOf("12"))))
        assertTrue(traceAiContextForArtifact("artifact-1", "artifact-1", query, result) != null)
        assertEquals(null, traceAiContextForArtifact("artifact-2", "artifact-1", query, result))
        assertEquals(null, traceAiContextForArtifact("artifact-1", null, query, result))
        assertEquals(null, traceAiContextForArtifact(null, "artifact-1", query, result))
        assertEquals(null, traceAiContextForArtifact("artifact-2", "artifact-2", query, null))
        assertNull(boundedAiContext(traceAiContextForArtifact("artifact-1", "artifact-1", null, result)))
        assertNull(boundedAiContext(traceAiContextForArtifact("artifact-1", "artifact-1", query, null)))
    }
    @Test
    fun `diagnostic context is trace query bound numeric only and explicitly bounded`() {
        val columns = listOf("thread_name", "total_dur_ms")
        val query = DiagnosticQuery("cpu-hotspot", DiagnosticCategory.CPU, "title", "description", columns, "SELECT secret")
        val result = DiagnosticResult(columns, List(22) { DiagnosticRow(listOf("private-thread-name", "12.5")) })
        val context = traceAiContext("artifact-1", query, result)!!
        val payload = context.evidence.single().payloadJson
        assertTrue("\"includedRows\":20" in payload)
        assertTrue("\"omittedReturnedRows\":2" in payload)
        assertTrue("12.5" in payload)
        assertFalse("private-thread-name" in payload)
        assertFalse("SELECT secret" in payload)
        assertEquals(null, traceAiContext("artifact-1", query, null))
        assertEquals(null, traceAiContext("artifact-1", query, result.copy(columns = listOf("other"))))
        assertFalse(context.evidence.single().id == traceAiContext("artifact-2", query, result)!!.evidence.single().id)
    }

    @Test
    fun `bounded context rejects oversized UTF8 payload`() {
        val oversized = UiAiContext("trace", "title", true, listOf(UiAiEvidence("id", "kind", "ok", "\u4e2d".repeat(3_000))))
        assertNull(boundedAiContext(oversized))
    }
}
