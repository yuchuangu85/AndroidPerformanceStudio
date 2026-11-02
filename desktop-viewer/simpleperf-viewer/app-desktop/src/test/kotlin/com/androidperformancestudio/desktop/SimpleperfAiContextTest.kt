package com.androidperformancestudio.desktop

import com.androidperformancestudio.application.ReportArtifact
import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.application.ReportPanelSelections
import com.androidperformancestudio.application.ReportSessionSummary
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.application.ReportWorkspaceUiState
import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.CallStackFrame
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimpleperfAiContextTest {
    @Test
    fun `ready report publishes a minimized summary without raw paths`() {
        val report = fixture()
        val context = simpleperfUiAiContext(ReportState(loadState = ReportLoadState.Ready(report)))!!
        assertEquals("simpleperf", context.key)
        assertEquals(false, context.currentSelection)
        val item = context.evidence.single()
        assertEquals("simpleperf-hotspots", item.kind)
        assertEquals("simpleperf:function:0", item.id)
        val payload = Json.parseToJsonElement(item.payloadJson).jsonObject
        assertEquals("libui.so", payload.getValue("resourceName").jsonPrimitive.content)
        assertEquals("REPORT_SUMMARY", payload.getValue("scope").jsonPrimitive.content)
        assertEquals("20", payload.getValue("summaryLimit").jsonPrimitive.content)
        assertEquals("1", payload.getValue("availableTopFunctionCount").jsonPrimitive.content)
        assertFalse(context.toString().contains("/private"))
        assertFalse(context.toString().contains("/Users"))
        assertFalse(context.toString().contains("C:\\"))
        assertEquals(setOf("symbol", "resourceName", "implementation", "inclusiveWeight", "exclusiveWeight", "sampleCount", "threadCount", "threadIds", "eventTypes", "scope", "summaryLimit", "availableTopFunctionCount"), payload.keys)
    }

    @Test
    fun `selected call node and stack block preserve canonical IDs facts and filter scope`() {
        val report = fixture()
        val filter = ProfileQuery(100, 200, setOf(42), setOf("cpu-cycles"))
        for ((tab, selections, expectedId) in listOf(
            Triple(ReportTab.FLAME_GRAPH, ReportPanelSelections(callNodeId = FlameCallNodeId(101)), "simpleperf:call:101"),
            Triple(ReportTab.CALL_TREE, ReportPanelSelections(callNodeId = FlameCallNodeId(101)), "simpleperf:call:101"),
            Triple(ReportTab.STACK_CHART, ReportPanelSelections(stackChartBlockId = StackChartBlockId("block-1")), "simpleperf:stack:block-1"),
        )) {
            val state = ReportState(loadState = ReportLoadState.Ready(report), selectedTab = tab, filter = filter, workspace = ReportWorkspaceUiState(selections = selections))
            val context = simpleperfUiAiContext(report, state)
            assertTrue(context.currentSelection)
            assertEquals(expectedId, context.evidence.single().id)
            val payload = Json.parseToJsonElement(context.evidence.single().payloadJson).jsonObject
            assertEquals("CURRENT_SELECTION", payload.getValue("scope").jsonPrimitive.content)
            assertEquals("100", payload.getValue("startNanosInclusive").jsonPrimitive.content)
            assertEquals("200", payload.getValue("endNanosExclusive").jsonPrimitive.content)
            assertEquals("[42]", payload.getValue("threadIds").toString())
            assertEquals("[\"cpu-cycles\"]", payload.getValue("eventTypes").toString())
            assertEquals("render", payload.getValue("symbol").jsonPrimitive.content)
        }
    }

    @Test
    fun `stale selected objects never fall back to report summary`() {
        val report = fixture()
        for ((tab, selections) in listOf(
            ReportTab.FLAME_GRAPH to ReportPanelSelections(callNodeId = FlameCallNodeId(999)),
            ReportTab.CALL_TREE to ReportPanelSelections(callNodeId = FlameCallNodeId(999)),
            ReportTab.STACK_CHART to ReportPanelSelections(stackChartBlockId = StackChartBlockId("expired")),
            ReportTab.TOP_FUNCTIONS to ReportPanelSelections(topFunctionKey = "expired"),
        )) {
            val state = ReportState(loadState = ReportLoadState.Ready(report), selectedTab = tab, workspace = ReportWorkspaceUiState(selections = selections))
            val failure = assertFailsWith<IllegalArgumentException> { simpleperfUiAiContext(report, state) }
            assertEquals("E_SELECTION_UNAVAILABLE", failure.message)
            assertNull(simpleperfUiAiContext(state))
        }
    }

    @Test
    fun `closed loading and no evidence remove the active context`() {
        assertNull(simpleperfUiAiContext(ReportState()))
        assertNull(simpleperfUiAiContext(ReportState(loadState = ReportLoadState.Loading(Path.of("/private/session")))))
        val empty = fixture().copy(topFunctions = emptyList())
        assertNull(simpleperfUiAiContext(ReportState(loadState = ReportLoadState.Ready(empty))))
        val failure = assertFailsWith<IllegalArgumentException> { simpleperfUiAiContext(empty, ReportState()) }
        assertEquals("E_EVIDENCE_UNAVAILABLE", failure.message)
    }

    @Test
    fun `top function selection is explicit and inactive tab selections are ignored`() {
        val report = fixture()
        val selections = ReportPanelSelections(topFunctionKey = "render", callNodeId = FlameCallNodeId(999))
        val selected = simpleperfUiAiContext(report, ReportState(selectedTab = ReportTab.TOP_FUNCTIONS, workspace = ReportWorkspaceUiState(selections = selections)))
        assertTrue(selected.currentSelection)
        assertEquals("simpleperf:function:selected", selected.evidence.single().id)

        val summary = simpleperfUiAiContext(report, ReportState(selectedTab = ReportTab.OVERVIEW, workspace = ReportWorkspaceUiState(selections = selections)))
        assertFalse(summary.currentSelection)
        assertEquals("simpleperf:function:0", summary.evidence.single().id)
    }

    @Test
    fun `summary remains bounded and reports available evidence rather than claiming completeness`() {
        val report = fixture().copy(topFunctions = List(25) { index -> TopFunction("render$index", "/private/symbols/libui.so", 9, 4, 3, 1) })
        val context = simpleperfUiAiContext(report, ReportState())
        assertEquals(20, context.evidence.size)
        assertEquals("simpleperf:function:19", context.evidence.last().id)
        val payload = Json.parseToJsonElement(context.evidence.last().payloadJson).jsonObject
        assertEquals("20", payload.getValue("summaryLimit").jsonPrimitive.content)
        assertEquals("25", payload.getValue("availableTopFunctionCount").jsonPrimitive.content)
    }

    @Test
    fun `filters count as current selection and Windows resource is basename only`() {
        val report = fixture().copy(topFunctions = listOf(TopFunction("render", "C:\\private\\symbols\\render.dll", 9, 4, 3, 1)))
        val state = ReportState(loadState = ReportLoadState.Ready(report), filter = ProfileQuery(threadIds = setOf(42)))
        val context = simpleperfUiAiContext(report, state)
        assertTrue(context.currentSelection)
        val payload = Json.parseToJsonElement(context.evidence.single().payloadJson).jsonObject
        assertEquals("render.dll", payload.getValue("resourceName").jsonPrimitive.content)
        assertFalse(context.toString().contains("private"))
    }

    private fun fixture(): ReportData {
        val frame = CallStackFrame(9, FlameFunctionId(9), "render", "/private/symbols/libui.so", 0, FrameImplementation.NATIVE)
        val nodes = CallNodeTable(longArrayOf(101), intArrayOf(-1), longArrayOf(9), intArrayOf(0), longArrayOf(9), longArrayOf(4), longArrayOf(3), intArrayOf(1), listOf("Native"), mapOf(9L to frame))
        val query = CallStackAnalysisQuery()
        val graph = FlameGraphSnapshot(query, nodes, FlameGraphRowProjector.project(nodes, query.direction), 9, null, emptyList())
        val overview = ProfileOverview(100, 199, 3, 9, 1, 1, listOf("cpu-cycles"))
        return ReportData(
            session = ReportSessionSummary("/Users/private/session-name", Path.of("/private/session"), emptyMap(), listOf(ReportArtifact("perf.data", Path.of("/private/perf.data"), true))),
            sessionOverview = overview, overview = overview,
            quality = DataQualitySummary(3, 3, 0, 0, 0, 0, 0, emptyList()),
            sessionThreads = emptyList(), topThreads = emptyList(),
            topFunctions = listOf(TopFunction("render", "/private/symbols/libui.so", 9, 4, 3, 1)),
            timeline = emptyList(), callTree = emptyList(), flameGraph = graph,
            stackChart = PanelProjection.Ready(StackChartSnapshot(mapOf(9L to frame), listOf(StackChartBlock(StackChartBlockId("block-1"), 7, 100, 200, 0, 9, "42", 9, FlameCallNodeId(101))), 100, 200, 0, null)),
            markers = PanelProjection.Failed("TEST", "unused"), diagnostics = emptyList(),
        )
    }
}
