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
    fun `visited feature destinations remain retained across cross feature navigation`() {
        val navigator = AppNavigator()

        navigator.open(AppDestination.FRAME_PROFILER)
        navigator.openLayoutInspector(null)
        navigator.open(AppDestination.FRAME_PROFILER)

        assertEquals(
            listOf(AppDestination.HOME, AppDestination.FRAME_PROFILER, AppDestination.LAYOUT_INSPECTOR),
            navigator.retainedDestinations,
        )
    }

    @Test
    fun `recent artifact requests reopen the exact feature even for the same path twice`() {
        val navigator = AppNavigator()
        val layoutArchive = Path.of("capture.apinspect")
        val cpuSession = Path.of("capture.simpleperf")

        navigator.openLayoutArchive(layoutArchive)
        val firstLayoutRequest = navigator.layoutArchiveRequest
        assertEquals(AppDestination.LAYOUT_INSPECTOR, navigator.destination)
        assertEquals(layoutArchive, firstLayoutRequest?.path)

        navigator.open(AppDestination.HOME)
        navigator.openLayoutArchive(layoutArchive)
        assertTrue(navigator.layoutArchiveRequest!!.requestId > firstLayoutRequest!!.requestId)

        navigator.openSimpleperfSession(cpuSession)
        assertEquals(AppDestination.SIMPLEPERF, navigator.destination)
        assertEquals(cpuSession, navigator.simpleperfSessionRequest?.path)
        assertEquals(
            listOf(AppDestination.HOME, AppDestination.LAYOUT_INSPECTOR, AppDestination.SIMPLEPERF),
            navigator.retainedDestinations,
        )
    }

    @Test
    fun `feature destinations request a maximized window but home does not`() {
        assertFalse(AppDestination.HOME.shouldMaximizeWindow())
        assertTrue(AppDestination.LAYOUT_INSPECTOR.shouldMaximizeWindow())
        assertTrue(AppDestination.SIMPLEPERF.shouldMaximizeWindow())
    }

    @Test
    fun `frame correlation context is retained while another feature is open`() {
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
        assertEquals(hint, navigator.inspectorCorrelationHint)
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
        assertEquals(trace, navigator.perfettoTraceFile)
        assertEquals(notice, navigator.perfettoTraceNotice)
    }

    @Test
    fun `reopening the same trace or heap produces a new import request`() {
        val navigator = AppNavigator()
        val trace = Path.of("capture.perfetto-trace")
        val heap = Path.of("capture.hprof")

        navigator.openPerfettoTrace(trace)
        val firstTraceRequestId = navigator.perfettoTraceRequestId
        navigator.openPerfettoTrace(trace)
        assertTrue(navigator.perfettoTraceRequestId > firstTraceRequestId)

        navigator.openMemoryProfiler(heap)
        val firstHeapRequestId = navigator.memoryImportRequestId
        navigator.openMemoryProfiler(heap)
        assertTrue(navigator.memoryImportRequestId > firstHeapRequestId)
        assertEquals(heap, navigator.memoryImportFile)
    }
}
