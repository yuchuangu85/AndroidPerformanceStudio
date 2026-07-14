package com.androidperformancestudio.analysis

import com.androidperformancestudio.storage.DataQualitySummary
import com.androidperformancestudio.storage.ProfileOverview
import com.androidperformancestudio.storage.ThreadSummary
import com.androidperformancestudio.storage.TopFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DiagnosticEngineTest {
    @Test
    fun `default rules report quality cpu and thread hotspots with navigation targets`() {
        val findings = DefaultDiagnosticRules.engine().analyze(hotSnapshot())

        assertTrue(findings.any { it.ruleId == "data-quality" && it.severity == DiagnosticSeverity.CRITICAL })
        val cpu = findings.first { it.ruleId == "cpu-hotspot" }
        assertEquals(DiagnosticSeverity.WARNING, cpu.severity)
        assertIs<DiagnosticTarget.Function>(cpu.target)
        assertEquals("renderFrame", cpu.target.symbolName)
        assertTrue(cpu.evidence.any { it.label == "Inclusive share" && it.value == "60.00%" })

        val thread = findings.first { it.ruleId == "thread-hotspot" }
        assertIs<DiagnosticTarget.Thread>(thread.target)
        assertEquals(10, thread.target.threadId)
        assertTrue(thread.recommendations.isNotEmpty())
    }

    @Test
    fun `healthy snapshot still explains sample weight semantics`() {
        val findings = DefaultDiagnosticRules.engine().analyze(healthySnapshot())

        assertEquals(listOf("data-quality", "sample-weight-semantics"), findings.map(DiagnosticFinding::ruleId))
        assertEquals(DiagnosticSeverity.INFO, findings.first().severity)
        assertTrue(findings.last().conclusion.contains("not exact wall-clock", ignoreCase = true))
    }

    @Test
    fun `engine preserves registration order and isolates empty rule output`() {
        val first = DiagnosticRule { listOf(finding("first")) }
        val empty = DiagnosticRule { emptyList() }
        val third = DiagnosticRule { listOf(finding("third")) }

        assertEquals(
            listOf("first", "third"),
            DiagnosticEngine(listOf(first, empty, third)).analyze(healthySnapshot()).map(DiagnosticFinding::ruleId),
        )
    }

    private fun hotSnapshot() =
        AnalysisSnapshot(
            overview = overview(totalWeight = 100),
            quality = quality(samples = 100, lost = 8, unwind = 3, unknown = 7, empty = 2),
            threads =
                listOf(
                    ThreadSummary(1, 10, "main", 70, 70),
                    ThreadSummary(1, 11, "worker-1", 30, 30),
                ),
            topFunctions =
                listOf(
                    TopFunction("renderFrame", "libui.so", 60, 55, 60, 1),
                    TopFunction("worker", "libapp.so", 40, 35, 40, 1),
                ),
        )

    private fun healthySnapshot() =
        AnalysisSnapshot(
            overview = overview(totalWeight = 100),
            quality = quality(samples = 100),
            threads =
                listOf(
                    ThreadSummary(1, 10, "main", 50, 50),
                    ThreadSummary(1, 11, "worker", 50, 50),
                ),
            topFunctions =
                listOf(
                    TopFunction("a", "lib.so", 15, 10, 15, 1),
                    TopFunction("b", "lib.so", 10, 5, 10, 1),
                ),
        )

    private fun overview(totalWeight: Long) = ProfileOverview(0, 99, 100, totalWeight, 1, 2, listOf("cpu-cycles"))

    private fun quality(
        samples: Long,
        lost: Long = 0,
        unwind: Long = 0,
        unknown: Long = 0,
        empty: Long = 0,
    ) = DataQualitySummary(samples, samples + lost, lost, unwind, unknown, empty, 0, emptyList())

    private fun finding(id: String) =
        DiagnosticFinding(
            ruleId = id,
            title = id,
            severity = DiagnosticSeverity.INFO,
            conclusion = id,
            evidence = emptyList(),
            recommendations = emptyList(),
        )
}
