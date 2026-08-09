package com.androidperformancestudio.perfetto.capture

import kotlin.test.Test
import kotlin.test.assertEquals

class PerfettoCapabilityDetectorTest {
    @Test
    fun `capability query works with Android Perfetto versions before long option`() {
        assertEquals(listOf("perfetto", "--query"), PERFETTO_QUERY_ARGUMENTS)
    }

    @Test
    fun `query output yields advertised data source names`() {
        val output =
            """
            Service: 57.2

            DATA SOURCES REGISTERED:

            NAME                                     PRODUCER                     DETAILS
            ===                                      ========                     ========
            linux.ftrace                             traced_probes (1)             am,gfx
            android.heapprofd                        traced_probes (1)
            track_event                              app (2)                       gfx

            TRACING SESSIONS:
            """.trimIndent()

        assertEquals(setOf("linux.ftrace", "android.heapprofd", "track_event"), parsePerfettoDataSourceNames(output))
    }
}
