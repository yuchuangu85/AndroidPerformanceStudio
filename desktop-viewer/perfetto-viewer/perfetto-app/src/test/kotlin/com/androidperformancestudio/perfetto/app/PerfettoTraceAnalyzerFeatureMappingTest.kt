package com.androidperformancestudio.perfetto.app

import com.androidperformancestudio.perfetto.analysis.DiagnosticFindings
import com.androidperformancestudio.perfetto.analysis.DiagnosticResult
import com.androidperformancestudio.perfetto.analysis.DiagnosticRow
import com.androidperformancestudio.perfetto.analysis.PerfettoDiagnosticPresets
import com.androidperformancestudio.perfetto.analysis.PerfettoDiagnostics
import com.androidperformancestudio.perfetto.analysis.TraceSliceSearch
import com.androidperformancestudio.perfetto.presentation.TraceInsightSeverity
import com.androidperformancestudio.ui.UiLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PerfettoTraceAnalyzerFeatureMappingTest {
    @Test
    fun `findings become localized aggregates and workspace severities`() {
        val query = PerfettoDiagnostics.all.first { it.id == "frame_jank" }
        val finding =
            DiagnosticFindings.evaluate(
                query,
                DiagnosticResult(query.columns, listOf(DiagnosticRow(listOf("com.example", "840", "84", "9.5", "60.25", "44.1")))),
            )

        assertEquals(TraceInsightSeverity.CRITICAL, finding.level.workspaceSeverity())
        assertEquals("84 / 840 janky frames", finding.aggregateText(UiLanguage.ENGLISH))
        assertEquals("84 / 840 卡顿帧", finding.aggregateText(UiLanguage.SIMPLIFIED_CHINESE))

        val binder = PerfettoDiagnostics.all.first { it.id == "binder_latency" }
        val binderFinding =
            DiagnosticFindings.evaluate(
                binder,
                DiagnosticResult(binder.columns, listOf(DiagnosticRow(listOf("app", "system_server", "3", "4.1", "120.75")))),
            )
        assertEquals("Max 120.8 ms · app", binderFinding.aggregateText(UiLanguage.ENGLISH))
        assertEquals(
            "No rows recorded",
            DiagnosticFindings.evaluate(binder, DiagnosticResult(binder.columns, emptyList())).aggregateText(UiLanguage.ENGLISH),
        )
    }

    @Test
    fun `presets are localized and keep their query identities`() {
        val option = PerfettoDiagnosticPresets.core.workspaceOption(UiLanguage.SIMPLIFIED_CHINESE)
        assertEquals("核心诊断", option.title)
        assertEquals(PerfettoDiagnosticPresets.core.queryIds, option.queryIds)
        assertEquals("All diagnostics", PerfettoDiagnosticPresets.byId("all").workspaceOption(UiLanguage.ENGLISH).title)
    }

    @Test
    fun `result tables keep real cells and only parseable row timestamps`() {
        val query = PerfettoDiagnostics.all.first { it.id == "sched_switch" }
        val table =
            DiagnosticResult(
                query.columns,
                listOf(
                    DiagnosticRow(listOf("1000", "5", "0", "main", null, "S")),
                    DiagnosticRow(listOf("unknown", "5", "1", "RenderThread", "app", "R")),
                ),
            ).workspaceTable(query, "artifact-1", "sched_switch")

        assertEquals(listOf("1000", "5", "0", "main", "—", "S"), table.rows.first())
        assertEquals(listOf(1000L, null), table.rowTimestampsNanos)
        assertEquals(query.sql, table.sql)
        assertEquals("v59.1", table.schemaVersion)

        val aggregate = PerfettoDiagnostics.all.first { it.id == "frame_jank" }
        val aggregateTable =
            DiagnosticResult(aggregate.columns, listOf(DiagnosticRow(listOf("app", "1", "0", "1", "1", "0"))))
                .workspaceTable(aggregate, "artifact-1", "jank")
        assertNull(aggregateTable.rowTimestampsNanos.single())
    }

    @Test
    fun `search hits require timestamps and join thread with process`() {
        val hits =
            DiagnosticResult(
                TraceSliceSearch.columns,
                listOf(
                    DiagnosticRow(listOf("1500", "16000000", "Choreographer#doFrame", "main", "com.example")),
                    DiagnosticRow(listOf(null, "1", "orphan", null, null)),
                    DiagnosticRow(listOf("2000", null, "binder transaction", null, "system_server")),
                ),
            ).searchHits()

        assertEquals(2, hits.size)
        assertEquals("main · com.example", hits[0].threadLabel)
        assertEquals("system_server", hits[1].threadLabel)
        assertNull(hits[1].durationNanos)
    }

    @Test
    fun `metric numbers keep whole values whole`() {
        assertEquals("840", formatMetricNumber(840.0))
        assertEquals("16.7", formatMetricNumber(16.666))
    }
}
