package com.androidperformancestudio.perfetto.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CrossDomainAttributionAnalyzerTest {
    @Test
    fun `attributes binder and scheduling overlap without claiming causality`() {
        val frame = TraceInterval("frame-1", 100, 100, TraceEvidenceDomain.FRAME_TIMELINE, processName = "app")
        val binder = TraceInterval("binder-1", 120, 40, TraceEvidenceDomain.BINDER_CLIENT, processName = "app")
        val scheduling = TraceInterval("sched-1", 160, 80, TraceEvidenceDomain.CPU_SCHEDULING, processName = "app")
        val result =
            CrossDomainAttributionAnalyzer().attributeFrames(
                frames = listOf(frame),
                scheduling = listOf(scheduling),
                binder = listOf(binder),
            )
        assertEquals(2, result.single().contributors.size)
        assertEquals(TraceEvidenceDomain.BINDER_CLIENT, result.single().dominantDomain)
        assertTrue(result.single().contributors.all { it.confidence == AttributionConfidence.BOUNDED_OVERLAP })
    }

    @Test
    fun `requires positive overlap and bounded interval end`() {
        assertFailsWith<IllegalArgumentException> { CrossDomainAttributionAnalyzer(minimumOverlapNs = 0) }
        assertFailsWith<IllegalArgumentException> {
            TraceInterval("overflow", Long.MAX_VALUE, 1, TraceEvidenceDomain.CPU_SCHEDULING)
        }
    }
}
