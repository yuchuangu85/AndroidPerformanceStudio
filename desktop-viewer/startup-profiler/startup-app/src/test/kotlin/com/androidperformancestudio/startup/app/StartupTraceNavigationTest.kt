package com.androidperformancestudio.startup.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupTraceNavigationTest {
    @Test
    fun `only an existing local trace is passed to navigation`() {
        val trace = Files.createTempFile("startup-trace-navigation", ".perfetto-trace")
        try {
            var opened = 0
            assertTrue(
                openStartupTraceFile(trace.toString()) { path ->
                    assertEquals(trace, path)
                    opened++
                },
            )
            assertFalse(openStartupTraceFile(trace.resolveSibling("missing.perfetto-trace").toString()) { opened++ })
            assertFalse(openStartupTraceFile("bad\u0000path") { opened++ })
            assertFalse(openStartupTraceFile(trace.toString()) { error("Navigation failed") })
            assertEquals(1, opened)
        } finally {
            Files.deleteIfExists(trace)
        }
    }
}
