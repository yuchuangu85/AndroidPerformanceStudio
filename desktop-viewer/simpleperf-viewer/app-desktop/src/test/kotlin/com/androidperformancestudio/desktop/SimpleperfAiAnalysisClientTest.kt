package com.androidperformancestudio.desktop

import com.androidperformancestudio.application.ReportArtifact
import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportPanelSelections
import com.androidperformancestudio.application.ReportSessionSummary
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.application.ReportWorkspaceUiState
import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.CallStackFrame
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FlameGraphRowProjector
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.profileanalysis.FrameImplementation
import com.androidperformancestudio.profileanalysis.StackChartBlock
import com.androidperformancestudio.profileanalysis.StackChartBlockId
import com.androidperformancestudio.profileanalysis.StackChartSnapshot
import com.androidperformancestudio.storage.DataQualitySummary
import com.androidperformancestudio.storage.PanelProjection
import com.androidperformancestudio.storage.ProfileOverview
import com.androidperformancestudio.storage.ProfileQuery
import com.androidperformancestudio.storage.TopFunction
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimpleperfAiAnalysisClientTest {
    @Test
    fun `stack chart selection wins over a stale selection from another tab`() {
        val blockId = StackChartBlockId("sample:7:1")
        val report = reportWithStackBlock(blockId)
        val state =
            ReportState(
                selectedTab = ReportTab.STACK_CHART,
                filter = ProfileQuery(100, 200, setOf(42), setOf("cpu-cycles")),
                workspace =
                    ReportWorkspaceUiState(
                        selections =
                            ReportPanelSelections(
                                topFunctionKey = "staleFunction",
                                stackChartBlockId = blockId,
                            ),
                    ),
            )

        val evidence = extractSimpleperfEvidence(report, state).single()

        assertEquals("renderFrame", evidence.symbolName)
        assertEquals("simpleperf:stack:${blockId.value}", evidence.id)
        assertEquals(100, evidence.startNanosInclusive)
        assertEquals(listOf(42), evidence.selectedThreadIds)
        assertEquals(listOf("cpu-cycles"), evidence.selectedEventTypes)
        assertTrue(evidence.currentSelection)
    }

    @Test
    fun `single report hotspot is not mislabeled as a user selection`() {
        val evidence =
            extractSimpleperfEvidence(
                reportWithStackBlock(StackChartBlockId("unused")),
                ReportState(),
            ).single()

        assertEquals("staleFunction", evidence.symbolName)
        assertEquals(false, evidence.currentSelection)
    }
}

private fun reportWithStackBlock(blockId: StackChartBlockId): ReportData {
    val frame =
        CallStackFrame(
            frameId = 9,
            functionId = FlameFunctionId(9),
            symbolName = "renderFrame",
            resource = "Render.kt",
            virtualAddress = 0,
            implementation = FrameImplementation.MANAGED,
        )
    val overview = ProfileOverview(0, 999, 1, 5, 1, 1, listOf("cpu-cycles"))
    return ReportData(
        session =
            ReportSessionSummary(
                "test",
                Path.of("test"),
                emptyMap(),
                listOf(ReportArtifact("perf.data", Path.of("perf.data"), true)),
            ),
        sessionOverview = overview,
        overview = overview,
        quality = DataQualitySummary(1, 1, 0, 0, 0, 0, 0, emptyList()),
        sessionThreads = emptyList(),
        topThreads = emptyList(),
        topFunctions = listOf(TopFunction("staleFunction", "Stale.kt", 5, 5, 1, 1)),
        timeline = emptyList(),
        callTree = emptyList(),
        flameGraph = emptyFlameGraph(),
        stackChart =
            PanelProjection.Ready(
                StackChartSnapshot(
                    framesById = mapOf(frame.frameId to frame),
                    blocks = listOf(StackChartBlock(blockId, 7, 100, 101, 1, frame.frameId, "42", 5)),
                    startNanos = 100,
                    endNanosExclusive = 101,
                    maxDepth = 1,
                    emptyReason = null,
                ),
            ),
        markers = PanelProjection.Failed("TEST", "not needed"),
        diagnostics = emptyList(),
    )
}

private fun emptyFlameGraph(): FlameGraphSnapshot {
    val nodes =
        CallNodeTable(
            longArrayOf(),
            intArrayOf(),
            longArrayOf(),
            intArrayOf(),
            longArrayOf(),
            longArrayOf(),
            longArrayOf(),
            intArrayOf(),
            emptyList(),
            emptyMap(),
        )
    val query = CallStackAnalysisQuery()
    return FlameGraphSnapshot(
        query,
        nodes,
        FlameGraphRowProjector.project(nodes, query.direction),
        0,
        null,
        emptyList(),
    )
}
