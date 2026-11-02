package com.androidperformancestudio.benchmark.app

import com.androidperformancestudio.benchmark.presentation.BenchmarkReportsState
import com.androidperformancestudio.benchmark.presentation.comparisonKey
import com.androidperformancestudio.benchmark.model.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
