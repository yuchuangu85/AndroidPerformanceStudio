package com.androidperformancestudio.perfetto.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticFindingsTest {
    @Test
    fun `core preset is the documented commercial diagnostic list and every preset resolves`() {
        assertEquals(
            listOf(
                "CPU Scheduling Hotspots",
                "CPU Frequency Distribution",
                "Binder Transaction Latency",
                "Frame Jank Detection",
                "Memory Usage Timeline",
                "Input Event Latency",
                "Thread State Breakdown",
                "Thread Wakeup Latency",
            ),
            PerfettoDiagnosticPresets.core.queries().map(DiagnosticQuery::title),
        )
        PerfettoDiagnosticPresets.all.forEach { preset ->
            assertEquals(preset.queryIds.size, preset.queries().size, preset.id)
        }
        assertEquals(PerfettoDiagnostics.all.size, PerfettoDiagnosticPresets.byId("all").queries().size)
        assertEquals("core", PerfettoDiagnosticPresets.byId("missing").id)
    }

    @Test
    fun `frame jank severity uses FrameTimeline jank counts across processes`() {
        val query = diagnostic("frame_jank")
        val critical = result(query, listOf("app", "100", "12", "8", "40", "24"), listOf("launcher", "100", "0", "7", "9", "0"))
        val finding = DiagnosticFindings.evaluate(query, critical)
        assertEquals(DiagnosticFindingLevel.CRITICAL, finding.level)
        assertEquals(DiagnosticMetric(DiagnosticMetricKind.JANKY_FRAMES, 12.0, 200.0), finding.metric)

        val warning = result(query, listOf("app", "1000", "2", "8", "40", "24"))
        assertEquals(DiagnosticFindingLevel.WARNING, DiagnosticFindings.evaluate(query, warning).level)
        val smooth = result(query, listOf("app", "1000", "0", "8", "12", "0"))
        assertEquals(DiagnosticFindingLevel.INFO, DiagnosticFindings.evaluate(query, smooth).level)
    }

    @Test
    fun `latency thresholds pick the worst reported row and keep its subject`() {
        val query = diagnostic("binder_latency")
        val finding =
            DiagnosticFindings.evaluate(
                query,
                result(query, listOf("app", "system_server", "4", "3", "18.5"), listOf("app", "surfaceflinger", "2", "1", "2")),
            )
        assertEquals(DiagnosticFindingLevel.WARNING, finding.level)
        assertEquals(18.5, finding.metric.value)
        assertEquals("app", finding.metric.subject)
        assertTrue(finding.heuristic)
    }

    @Test
    fun `empty results are no data and unparsable aggregates fall back to row counts`() {
        val query = diagnostic("frame_jank")
        assertEquals(DiagnosticFindingLevel.NO_DATA, DiagnosticFindings.evaluate(query, DiagnosticResult(query.columns, emptyList())).level)
        val unknown = DiagnosticFindings.evaluate(query, result(query, listOf("app", null, null, null, null, null)))
        assertEquals(DiagnosticMetric(DiagnosticMetricKind.ROWS, 1.0), unknown.metric)
        assertFalse(unknown.heuristic)
    }

    @Test
    fun `slice search escapes literals bounds terms and ignores blanks`() {
        assertNull(TraceSliceSearch.query("   "))
        val query = TraceSliceSearch.query("Choreographer' OR 1=1 --\n")!!
        assertTrue(query.sql.contains("instr(lower(slice.name), 'choreographer'' or 1=1 --') > 0"))
        assertEquals(TraceSliceSearch.columns, query.columns)
        val long = TraceSliceSearch.query("x".repeat(500))!!
        assertTrue(long.sql.contains("'" + "x".repeat(TraceSliceSearch.MAX_TERM_LENGTH) + "'"))
    }

    private fun diagnostic(id: String): DiagnosticQuery = PerfettoDiagnostics.all.first { it.id == id }

    private fun result(
        query: DiagnosticQuery,
        vararg rows: List<String?>,
    ): DiagnosticResult = DiagnosticResult(query.columns, rows.map(::DiagnosticRow))
}
