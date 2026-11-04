package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import com.androidperformancestudio.ai.ProfilerKind
import com.androidperformancestudio.ui.UiAiContext
import com.androidperformancestudio.ui.UiAiEvidence
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

class DesktopAiContextRoutingTest {
    @Test
    fun `profiler page actions construct real requests with the same displayed facts and independent method recording identity`() {
        val context = UiAiContext("method-recording", "Recorded methods", false,
            listOf(UiAiEvidence("method:ref", "method-recording-summary", "Recorded intervals", "{\"selfMicros\":200}")))
        val request = profilerAnalysisRequest(context)
        assertEquals(ProfilerKind.METHOD_RECORDING, request.originProfiler)
        assertEquals(context.evidence.single().payloadJson, request.evidence.single().structuredPayload)
        assertTrue(request.sourceCandidates.isEmpty())
        assertEquals(request, profilerAnalysisRequest(context))
        val oversized = context.copy(evidence = listOf(context.evidence.single().copy(payloadJson = "测".repeat(3000))))
        assertThrows(IllegalArgumentException::class.java) { profilerAnalysisRequest(oversized) }
    }

    @Test
    fun `hosted page analysis prepares through the global coordinator and rejects inactive destinations`() {
        val shell = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppMainPage.kt"))
        assertTrue(shell.contains("override fun analyze(context:"))
        assertTrue(shell.contains("context.key != navigator.destination.aiContextKey()"))
        assertTrue(shell.contains("aiCoordinator.analyze(profilerAnalysisRequest(context))"))
        val sourcePage = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/SourceWorkspacesPage.kt"))
        assertTrue(sourcePage.contains("ProfilerAiAnalysisButton(aiContext, language)"))
    }

    @Test
    fun `global AI entry selects the exact context producer for each supported destination`() {
        val expected = mapOf(
            AppDestination.LAYOUT_INSPECTOR to "layout-inspector",
            AppDestination.SIMPLEPERF to "simpleperf",
            AppDestination.METHOD_RECORDING to "method-recording",
            AppDestination.PERFETTO to "trace",
            AppDestination.MEMORY_PROFILER to "memory",
            AppDestination.FRAME_PROFILER to "frame",
            AppDestination.STARTUP_PROFILER to "startup",
            AppDestination.BATTERY_PROFILER to "battery",
            AppDestination.NETWORK_PROFILER to "network",
            AppDestination.GPU_INSPECTOR to "gpu",
            AppDestination.BENCHMARK_REGRESSION to "benchmark",
            AppDestination.SOURCE_WORKSPACES to "source-workspace",
        )
        AppDestination.entries.forEach { destination ->
            if (destination in expected) assertEquals(expected[destination], destination.aiContextKey())
            else assertNull(destination.aiContextKey(), destination.name)
        }
    }
}
