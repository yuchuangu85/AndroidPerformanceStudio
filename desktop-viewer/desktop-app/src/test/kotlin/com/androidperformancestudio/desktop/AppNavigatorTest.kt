package com.androidperformancestudio.desktop

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppNavigatorTest {
    @Test
    fun `application starts on the home page`() {
        assertEquals(AppDestination.HOME, AppNavigator().destination)
    }

    @Test
    fun `opening a feature replaces the home page`() {
        val navigator = AppNavigator()

        navigator.open(AppDestination.SIMPLEPERF)

        assertEquals(AppDestination.SIMPLEPERF, navigator.destination)
    }

    @Test
    fun `feature destinations request a maximized window but home does not`() {
        assertFalse(AppDestination.HOME.shouldMaximizeWindow())
        assertTrue(AppDestination.LAYOUT_INSPECTOR.shouldMaximizeWindow())
        assertTrue(AppDestination.SIMPLEPERF.shouldMaximizeWindow())
    }

    @Test
    fun `frame correlation context is retained only for layout inspector navigation`() {
        val navigator = AppNavigator()
        val hint =
            InspectorCorrelationHint(
                deviceSerial = "device",
                targetPackageName = "dev.example",
                message = "Frame #7",
                correlationNotice = "correlation only",
                foregroundMismatchPrefix = "package differs",
            )

        navigator.openLayoutInspector(hint)

        assertEquals(AppDestination.LAYOUT_INSPECTOR, navigator.destination)
        assertEquals(hint, navigator.inspectorCorrelationHint)

        navigator.open(AppDestination.HOME)
        assertEquals(null, navigator.inspectorCorrelationHint)
    }

    @Test
    fun `ecosystem artifacts can open Perfetto with an explicit correlation notice`() {
        val navigator = AppNavigator()
        val trace = Path.of("capture.perfetto-trace")

        val notice = "Opened from Benchmark Regression for correlation only; no causal relationship is inferred."
        navigator.openPerfettoTrace(trace, notice)

        assertEquals(AppDestination.PERFETTO, navigator.destination)
        assertEquals(trace, navigator.perfettoTraceFile)
        assertEquals(notice, navigator.perfettoTraceNotice)

        navigator.open(AppDestination.HOME)
        assertEquals(null, navigator.perfettoTraceFile)
        assertEquals(null, navigator.perfettoTraceNotice)
    }
}
