package com.androidperformancestudio.benchmark.app

import com.androidperformancestudio.benchmark.presentation.BenchmarkReportsState
import com.androidperformancestudio.benchmark.presentation.comparisonKey
import com.androidperformancestudio.benchmark.model.*
import com.androidperformancestudio.ui.UiAiContext
import com.androidperformancestudio.ui.UiAiEvidence
import com.androidperformancestudio.ui.boundedAiContext
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BenchmarkAiContextTest {
    @Test
    fun `canonical selection preserves metrics missing states and rejects stale ID`() {

        val comparison=MetricComparison("case-1","frameDuration","ms",10.0,15.0,5.0,50.0,RegressionClassification.REGRESSED,EvidenceConfidence.EXACT,emptyList())
        val report=RegressionReport("base-1","current-1",Instant.EPOCH,listOf(comparison),emptyList())
        val state=BenchmarkReportsState(report=report,selectedComparisonKey=comparisonKey(comparison))
        val context=benchmarkAiContext(state)!!
        assertTrue(context.currentSelection)
        assertTrue("\"unit\":\"ms\"" in context.evidence.single().payloadJson)
        assertTrue("\"relativeDeltaPercent\":50.0" in context.evidence.single().payloadJson)
        assertEquals(null,benchmarkAiContext(state.copy(selectedComparisonKey="stale")))
    }

    @Test
    fun `bounded context rejects oversized summary labels`() {
        val oversized = UiAiContext("benchmark", "title", false, listOf(UiAiEvidence("id", "kind", "\u4e2d".repeat(700), "{}")))
        assertNull(boundedAiContext(oversized))
    }

    @Test
    fun `report summary keeps eight comparisons and explicit truncation counts`() {
        val comparisons = List(22) {
            MetricComparison("case-$it", "frameDuration", "ms", 10.0, 15.0, 5.0, 50.0,
                RegressionClassification.REGRESSED, EvidenceConfidence.EXACT, emptyList())
        }
        val report = RegressionReport("base-1", "current-1", Instant.EPOCH, comparisons, emptyList())
        val context = boundedAiContext(benchmarkAiContext(BenchmarkReportsState(report = report)))!!
        val payload = context.evidence.single().payloadJson
        assertFalse(context.currentSelection)
        assertTrue("\"includedComparisons\":8" in payload)
        assertTrue("\"omittedVisibleComparisons\":14" in payload)
        assertTrue(payload.toByteArray(Charsets.UTF_8).size <= 8 * 1024)
    }
}
