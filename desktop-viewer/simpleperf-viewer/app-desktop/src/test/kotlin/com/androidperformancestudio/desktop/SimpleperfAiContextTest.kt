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
import com.androidperformancestudio.profileanalysis.CallStackWeightType
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
import com.androidperformancestudio.storage.ThreadSummary
import com.androidperformancestudio.storage.TopFunction
import com.androidperformancestudio.storage.UnwindErrorSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimpleperfAiContextTest {
    @Test
    fun `ambiguous selected names cannot authorize the wrong library`() {
        val report =
            fixture().copy(
                topFunctions =
                    listOf(
                        TopFunction("render", "/liba.so", 9, 4, 3, 1),
                        TopFunction("render", "/libb.so", 7, 2, 2, 1),
                    ),
            )
        val state =
            ReportState(
                selectedTab = ReportTab.TOP_FUNCTIONS,
                workspace = ReportWorkspaceUiState(selections = ReportPanelSelections(topFunctionKey = "render")),
            )
        assertEquals("E_SELECTION_UNAVAILABLE", assertFailsWith<IllegalArgumentException> { simpleperfUiAiContext(report, state) }.message)
    }

    @Test
    fun `ready report sends one minimized summary without report paths or raw projections`() {
        val report = fixture()
        val context = simpleperfUiAiContext(ReportState(loadState = ReportLoadState.Ready(report)))!!
        val item = context.evidence.single()
        val payload = payload(report)
        assertEquals("simpleperf", context.key)
        assertFalse(context.currentSelection)
        assertEquals("simpleperf-hotspots", item.kind)
        assertEquals("simpleperf:summary", item.id)
        assertEquals("2", payload["schemaVersion"].toString())
        assertEquals("REPORT_SUMMARY", payload.getValue("scope").jsonPrimitive.content)
        assertEquals(
            "libui.so",
            payload
                .getValue("hotspots")
                .jsonArray
                .single()
                .jsonObject
                .getValue("resourceName")
                .jsonPrimitive.content,
        )
        assertEquals(
            setOf(
                "schemaVersion",
                "reportRef",
                "scope",
                "panel",
                "weightSemantics",
                "view",
                "range",
                "filters",
                "quality",
                "hotspots",
                "hotspotLimit",
                "availableTopFunctionCount",
                "omittedHotspotCount",
                "budgetOmittedHotspotCount",
                "hotspotCoverage",
                "threads",
            ),
            payload.keys,
        )
        for (forbidden in listOf(
            "/private",
            "/Users",
            "perf.data",
            "session-name",
            "framesById",
            "timelineTracks",
            "markers",
            "unwindErrors",
        )) {
            assertFalse(context.toString().contains(forbidden), forbidden)
        }
    }

    @Test
    fun `selected objects preserve canonical identity and share one bounded filter summary`() {
        for ((tab, selections, id) in listOf(
            Triple(ReportTab.FLAME_GRAPH, ReportPanelSelections(callNodeId = FlameCallNodeId(101)), "simpleperf:call:101"),
            Triple(ReportTab.CALL_TREE, ReportPanelSelections(callNodeId = FlameCallNodeId(101)), "simpleperf:call:101"),
            Triple(
                ReportTab.STACK_CHART,
                ReportPanelSelections(stackChartBlockId = StackChartBlockId("block-1")),
                "simpleperf:stack:block-1",
            ),
        )) {
            val state =
                ReportState(
                    selectedTab = tab,
                    filter = ProfileQuery(100, 200, setOf(42), setOf("cpu-cycles")),
                    workspace = ReportWorkspaceUiState(selections = selections),
                )
            val context = simpleperfUiAiContext(fixture(), state)
            val payload = payload(fixture(), state)
            assertEquals(id, context.evidence.single().id)
            assertTrue(context.currentSelection)
            assertEquals("CURRENT_SELECTION", payload.getValue("scope").jsonPrimitive.content)
            assertEquals("100", payload.getValue("range").jsonObject["startNanosInclusive"].toString())
            assertEquals("200", payload.getValue("range").jsonObject["endNanosExclusive"].toString())
            assertEquals("[42]", payload.getValue("filters").jsonObject["threadIds"].toString())
            assertEquals("[\"cpu-cycles\"]", payload.getValue("filters").jsonObject["eventTypes"].toString())
            val hotspot =
                payload
                    .getValue("hotspots")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("render", hotspot.getValue("symbol").jsonPrimitive.content)
            assertFalse("scope" in hotspot && tab != ReportTab.STACK_CHART)
        }
    }

    @Test
    fun `expired node selection cannot fall back to a report summary`() {
        for ((tab, selections) in listOf(
            ReportTab.FLAME_GRAPH to ReportPanelSelections(callNodeId = FlameCallNodeId(999)),
            ReportTab.CALL_TREE to ReportPanelSelections(callNodeId = FlameCallNodeId(999)),
            ReportTab.STACK_CHART to ReportPanelSelections(stackChartBlockId = StackChartBlockId("expired")),
            ReportTab.TOP_FUNCTIONS to ReportPanelSelections(topFunctionKey = "expired"),
        )) {
            val report = fixture()
            val state =
                ReportState(
                    loadState = ReportLoadState.Ready(report),
                    selectedTab = tab,
                    workspace = ReportWorkspaceUiState(selections = selections),
                )
            assertEquals(
                "E_SELECTION_UNAVAILABLE",
                assertFailsWith<IllegalArgumentException> { simpleperfUiAiContext(report, state) }.message,
            )
            assertNull(simpleperfUiAiContext(state))
        }
    }

    @Test
    fun `closed loading and empty reports provide no AI context`() {
        assertNull(simpleperfUiAiContext(ReportState()))
        assertNull(simpleperfUiAiContext(ReportState(loadState = ReportLoadState.Loading(Path.of("/private/session")))))
        val empty = fixture().copy(topFunctions = emptyList())
        assertNull(simpleperfUiAiContext(ReportState(loadState = ReportLoadState.Ready(empty))))
        assertEquals(
            "E_EVIDENCE_UNAVAILABLE",
            assertFailsWith<IllegalArgumentException> { simpleperfUiAiContext(empty, ReportState()) }.message,
        )
    }

    @Test
    fun `only active tab selection narrows analysis`() {
        val selections = ReportPanelSelections(topFunctionKey = "render", callNodeId = FlameCallNodeId(999))
        val selected =
            simpleperfUiAiContext(
                fixture(),
                ReportState(selectedTab = ReportTab.TOP_FUNCTIONS, workspace = ReportWorkspaceUiState(selections = selections)),
            )
        assertTrue(selected.currentSelection)
        assertEquals("simpleperf:function:selected", selected.evidence.single().id)
        val overview =
            simpleperfUiAiContext(
                fixture(),
                ReportState(selectedTab = ReportTab.OVERVIEW, workspace = ReportWorkspaceUiState(selections = selections)),
            )
        assertFalse(overview.currentSelection)
        assertEquals("simpleperf:summary", overview.evidence.single().id)
    }

    @Test
    fun `summary ranks at most eight loaded hotspots and reports missing coverage`() {
        val report =
            fixture().copy(
                topFunctions = List(200) { index -> TopFunction("render$index", "/private/libui.so", index.toLong(), 1, 1, 1) },
            )
        val context = simpleperfUiAiContext(report, ReportState())
        val payload = payload(report)
        val hotspots = payload.getValue("hotspots").jsonArray
        assertEquals(1, context.evidence.size)
        assertEquals(8, hotspots.size)
        assertEquals(
            "render199",
            hotspots
                .first()
                .jsonObject
                .getValue("symbol")
                .jsonPrimitive.content,
        )
        assertEquals(
            "render192",
            hotspots
                .last()
                .jsonObject
                .getValue("symbol")
                .jsonPrimitive.content,
        )
        assertEquals("192", payload["omittedHotspotCount"].toString())
        assertEquals("LOADED_TOP_FUNCTIONS_ONLY", payload.getValue("hotspotCoverage").jsonPrimitive.content)
        assertTrue(
            context.evidence
                .single()
                .payloadJson
                .toByteArray()
                .size <= SIMPLEPERF_AI_MAX_PAYLOAD_BYTES,
        )
    }

    @Test
    fun `UTF8 budget bounds long labels and escaped strings with explicit omission markers`() {
        for (label in listOf("测".repeat(4000), "\"\\\u0000".repeat(4000))) {
            val report = fixture().copy(topFunctions = List(40) { TopFunction(label + it, "/private/$label.so", 9, 4, 3, 1) })
            val context = simpleperfUiAiContext(report, ReportState())
            val payload = payload(report)
            assertTrue(
                context.evidence
                    .single()
                    .payloadJson
                    .toByteArray(Charsets.UTF_8)
                    .size <= 8192,
            )
            val hotspots = payload.getValue("hotspots").jsonArray
            assertTrue(hotspots.size in 1..8)
            val omitted = payload.getValue("budgetOmittedHotspotCount").jsonPrimitive.content
            assertEquals(8 - hotspots.size, omitted.toInt())
            assertEquals((40 - hotspots.size).toString(), payload["omittedHotspotCount"].toString())
            assertTrue(hotspots.all { it.jsonObject["labelTruncated"].toString() == "true" })
        }
    }

    @Test
    fun `thread and event filters are bounded and event metric types stay explicit`() {
        val report = fixture().copy(topFunctions = listOf(TopFunction("render", "C:\\private\\render.dll", 9, 4, 3, 1)))
        val state = ReportState(filter = ProfileQuery(threadIds = (1..100).toSet(), eventTypes = (1..100).map { "event$it" }.toSet()))
        val payload = payload(report, state)
        val filters = payload.getValue("filters").jsonObject
        assertEquals(8, filters.getValue("threadIds").jsonArray.size)
        assertEquals(4, filters.getValue("eventTypes").jsonArray.size)
        assertEquals("92", filters["omittedThreadIdCount"].toString())
        assertEquals("96", filters["omittedEventTypeCount"].toString())
        assertEquals("true", filters["mixedEvents"].toString())
        assertEquals(
            "render.dll",
            payload
                .getValue("hotspots")
                .jsonArray
                .single()
                .jsonObject
                .getValue("resourceName")
                .jsonPrimitive.content,
        )
        assertFalse(payload.toString().contains("private"))
    }

    @Test
    fun `top three threads disclose role and counts rather than user controlled names`() {
        val threads = List(10) { index -> ThreadSummary(5, index, "/private/thread-$index", 1, index.toLong()) }
        val payload = payload(fixture().copy(topThreads = threads))
        val summary = payload.getValue("threads").jsonObject
        assertEquals("COMMITTED_RANGE_NOT_SELECTED_FUNCTION", summary.getValue("scope").jsonPrimitive.content)
        assertEquals("7", summary["omittedCount"].toString())
        assertEquals(3, summary.getValue("items").jsonArray.size)
        assertEquals(
            "9",
            summary
                .getValue("items")
                .jsonArray
                .first()
                .jsonObject["threadId"]
                .toString(),
        )
        assertFalse(payload.toString().contains("/private"))
    }

    @Test
    fun `session quality is separate from filtered metrics and excludes unwind addresses`() {
        val quality = DataQualitySummary(100, 105, 5, 4, 3, 2, 1, listOf(UnwindErrorSummary("private-error", 42, 123456, 4)))
        val summary = payload(fixture().copy(quality = quality)).getValue("quality").jsonObject
        assertEquals("SESSION", summary.getValue("scope").jsonPrimitive.content)
        assertEquals("5", summary["lostSampleCount"].toString())
        assertEquals("100", summary["sampleCount"].toString())
        assertFalse(summary.toString().contains("private-error"))
        assertFalse(summary.toString().contains("123456"))
    }

    @Test
    fun `weights retain their projection type and never estimate method durations`() {
        for (type in CallStackWeightType.entries) {
            val report = fixture().let { it.copy(flameGraph = it.flameGraph.copy(weightType = type)) }
            val summary = payload(report)
            assertEquals(
                type.name,
                summary
                    .getValue("view")
                    .jsonObject
                    .getValue("weightType")
                    .jsonPrimitive.content,
            )
            assertEquals("9", summary.getValue("view").jsonObject["totalWeight"].toString())
            assertTrue(
                summary
                    .getValue("weightSemantics")
                    .jsonPrimitive.content
                    .contains("not exact"),
            )
            assertFalse(summary.toString().contains("durationMillis"))
        }
    }

    @Test
    fun `stack blocks do not claim single samples or exclusive function cost`() {
        val state =
            ReportState(
                selectedTab = ReportTab.STACK_CHART,
                workspace = ReportWorkspaceUiState(selections = ReportPanelSelections(stackChartBlockId = StackChartBlockId("block-1"))),
            )
        val summary = payload(fixture(), state)
        val block =
            summary
                .getValue("hotspots")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("9", block["blockWeight"].toString())
        assertEquals("100", block["startNanosInclusive"].toString())
        assertFalse("sampleCount" in block)
        assertFalse("exclusiveWeight" in block)
        assertEquals(
            "UNKNOWN",
            summary
                .getValue("view")
                .jsonObject
                .getValue("weightType")
                .jsonPrimitive.content,
        )
        assertFalse("totalWeight" in summary.getValue("view").jsonObject)
    }

    private fun payload(
        report: ReportData,
        state: ReportState = ReportState(),
    ): JsonObject = Json.parseToJsonElement(simpleperfUiAiContext(report, state).evidence.single().payloadJson).jsonObject

    @Test
    fun `minimized reports and truncated labels retain distinct opaque identities`() {
        val first = fixture()
        val second = first.copy(session = first.session.copy(directory = Path.of("/private/other")))
        assertFalse(payload(first)["reportRef"] == payload(second)["reportRef"])
        val prefix = "x".repeat(300)
        val a = first.copy(topFunctions = listOf(TopFunction(prefix + "a", "/lib.so", 1, 1, 1, 1)))
        val b = first.copy(topFunctions = listOf(TopFunction(prefix + "b", "/lib.so", 1, 1, 1, 1)))
        val rowA =
            payload(a)
                .getValue("hotspots")
                .jsonArray
                .single()
                .jsonObject
        val rowB =
            payload(b)
                .getValue("hotspots")
                .jsonArray
                .single()
                .jsonObject
        assertEquals(rowA["symbol"], rowB["symbol"])
        assertFalse(rowA["labelRef"] == rowB["labelRef"])
    }

    private fun fixture(): ReportData {
        val frame = CallStackFrame(9, FlameFunctionId(9), "render", "/private/symbols/libui.so", 0, FrameImplementation.NATIVE)
        val nodes =
            CallNodeTable(
                longArrayOf(101),
                intArrayOf(-1),
                longArrayOf(9),
                intArrayOf(0),
                longArrayOf(9),
                longArrayOf(4),
                longArrayOf(3),
                intArrayOf(1),
                listOf("Native"),
                mapOf(
                    9L to frame,
                ),
            )
        val query = CallStackAnalysisQuery()
        val graph = FlameGraphSnapshot(query, nodes, FlameGraphRowProjector.project(nodes, query.direction), 9, null, emptyList())
        val overview = ProfileOverview(100, 199, 3, 9, 1, 1, listOf("cpu-cycles"))
        return ReportData(
            session =
                ReportSessionSummary(
                    "/Users/private/session-name",
                    Path.of("/private/session"),
                    emptyMap(),
                    listOf(ReportArtifact("perf.data", Path.of("/private/perf.data"), true)),
                ),
            sessionOverview = overview,
            overview = overview,
            quality = DataQualitySummary(3, 3, 0, 0, 0, 0, 0, emptyList()),
            sessionThreads = emptyList(),
            topThreads = emptyList(),
            topFunctions = listOf(TopFunction("render", "/private/symbols/libui.so", 9, 4, 3, 1)),
            timeline = emptyList(),
            callTree = emptyList(),
            flameGraph = graph,
            stackChart =
                PanelProjection.Ready(
                    StackChartSnapshot(
                        mapOf(9L to frame),
                        listOf(StackChartBlock(StackChartBlockId("block-1"), 7, 100, 200, 0, 9, "42", 9, FlameCallNodeId(101))),
                        100,
                        200,
                        0,
                        null,
                    ),
                ),
            markers = PanelProjection.Failed("TEST", "unused"),
            diagnostics = emptyList(),
        )
    }
}
