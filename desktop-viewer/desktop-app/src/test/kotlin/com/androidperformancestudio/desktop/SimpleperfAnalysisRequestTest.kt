package com.androidperformancestudio.desktop

import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.PerformanceEvidence
import com.androidperformancestudio.ai.payloadText
import com.androidperformancestudio.aiplatform.app.createBusinessContext
import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.application.ReportPanelSelections
import com.androidperformancestudio.application.ReportSessionSummary
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.application.ReportWorkspaceUiState
import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.FlameGraphRowProjector
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.storage.DataQualitySummary
import com.androidperformancestudio.storage.PanelProjection
import com.androidperformancestudio.storage.ProfileOverview
import com.androidperformancestudio.storage.ProfileQuery
import com.androidperformancestudio.storage.TopFunction
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class SimpleperfAnalysisRequestTest {
    @Test
    fun `source resolution uses only retained hotspots and their aggregate evidence identity`() {
        val report = reportFixture().copy(topFunctions = listOf(
            TopFunction("Keep", "/lib.so", 9, 4, 3, 1),
            TopFunction("Omitted", "/lib.so", 7, 2, 2, 1),
        ))
        val state = ReportState(loadState = ReportLoadState.Ready(report))
        val request = simpleperfAnalysisRequest(report, state)
        val summary = request.evidence.single()
        val payload = Json.parseToJsonElement(summary.structuredPayload).jsonObject
        val limited = JsonObject(payload + ("hotspots" to JsonArray(payload.getValue("hotspots").jsonArray.take(1))))
        val bounded = request.copy(evidence = listOf(summary.copy(structuredPayload = limited.toString())))
        val candidates = simpleperfSourceEvidence(report, state, bounded)

        assertEquals(listOf("Keep"), candidates.map { it.qualifiedName })
        assertEquals(listOf(summary.id), candidates.map { it.id.value })
    }

    @Test
    fun `large CPU reports reach the Provider as one bounded key facts summary`() {
        val base = reportFixture()
        val report = base.copy(topFunctions = List(1000) { index -> TopFunction("render$index", "/private/libui.so", index.toLong(), 1, 1, 1) })
        val request = simpleperfAnalysisRequest(report, ReportState(loadState = ReportLoadState.Ready(report)))
        val item = request.evidence.single()
        val payload = Json.parseToJsonElement(item.structuredPayload).jsonObject

        assertEquals(8, payload.getValue("hotspots").jsonArray.size)
        assertTrue(item.structuredPayload.toByteArray(Charsets.UTF_8).size <= 8192)
        assertTrue(request.payloadText().toByteArray(Charsets.UTF_8).size <= 12 * 1024)
        assertTrue(request.sourceCandidates.isEmpty())
        assertTrue(!request.payloadText().contains("/private"))
        assertTrue(!request.payloadText().contains("framesById"))
    }

    @Test
    fun `repeated legacy requests retain the same canonical identity and payload`() {
        val report = reportFixture()
        for (state in reportStates(report)) {
            val first = simpleperfAnalysisRequest(report, state)
            val repeated = simpleperfAnalysisRequest(report, state)

            assertEquals(first, repeated)
            assertEquals(first.sessionId, repeated.sessionId)
            assertEquals(first.payloadText(), repeated.payloadText())
        }
    }

    @Test
    fun `legacy summary selected hotspot and filtered requests exactly match the global bridge`() {
        val report = reportFixture()
        for (state in reportStates(report)) {
            val request = simpleperfAnalysisRequest(report, state)
            val global = globalRequest(state)
            val context = checkNotNull(simpleperfUiAiContext(state))

            assertEquals(global, request)
            assertEquals(global.payloadText(), request.payloadText())
            assertEquals(context.evidence.map { it.payloadJson }, request.evidence.map { it.structuredPayload })
            assertEquals("aps-main-v1", request.promptVersion)
            assertEquals("aps-minimized-v1", request.payloadPolicyVersion)
            assertTrue(request.sourceCandidates.isEmpty())
        }
    }

    @Test
    fun `changed performance evidence produces a new canonical identity matching the global bridge`() {
        val report = reportFixture()
        val changed = report.copy(topFunctions = report.topFunctions.map { it.copy(sampleCount = it.sampleCount + 1) })
        for (state in reportStates(report)) {
            val changedState = state.copy(loadState = ReportLoadState.Ready(changed))
            val original = simpleperfAnalysisRequest(report, state)
            val updated = simpleperfAnalysisRequest(changed, changedState)

            assertNotEquals(original.sessionId, updated.sessionId)
            assertNotEquals(original.evidence, updated.evidence)
            assertNotEquals(original.payloadText(), updated.payloadText())
            assertEquals(globalRequest(changedState), updated)
        }
    }

    @Test
    fun `changed filter scope produces a new canonical identity matching the global bridge`() {
        val report = reportFixture()
        val state = ReportState(loadState = ReportLoadState.Ready(report))
        val filtered = state.copy(filter = ProfileQuery(100, 200, setOf(42), setOf("cpu-cycles")))
        val original = simpleperfAnalysisRequest(report, state)
        val updated = simpleperfAnalysisRequest(report, filtered)

        assertNotEquals(original.sessionId, updated.sessionId)
        assertNotEquals(original.scope, updated.scope)
        assertNotEquals(original.payloadText(), updated.payloadText())
        assertEquals(globalRequest(filtered), updated)
    }

    private fun globalRequest(state: ReportState): AnalysisRequest {
        val context = checkNotNull(simpleperfUiAiContext(state))
        val evidence = context.evidence.map { PerformanceEvidence(it.id, it.kind, it.summary, it.payloadJson) }
        return createBusinessContext(context.key, context.title, evidence, context.currentSelection).request
    }

    private fun reportStates(report: ReportData): List<ReportState> {
        val summary = ReportState(loadState = ReportLoadState.Ready(report))
        return listOf(
            summary,
            summary.copy(
                selectedTab = ReportTab.TOP_FUNCTIONS,
                workspace = ReportWorkspaceUiState(selections = ReportPanelSelections(topFunctionKey = "render")),
            ),
            summary.copy(filter = ProfileQuery(100, 200, setOf(42), setOf("cpu-cycles"))),
        )
    }

    private fun reportFixture(): ReportData {
        val nodes = CallNodeTable(
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
        val overview = ProfileOverview(100, 199, 3, 9, 1, 1, listOf("cpu-cycles"))
        return ReportData(
            session = ReportSessionSummary("test", Path.of("test"), emptyMap(), emptyList()),
            sessionOverview = overview,
            overview = overview,
            quality = DataQualitySummary(3, 3, 0, 0, 0, 0, 0, emptyList()),
            sessionThreads = emptyList(),
            topThreads = emptyList(),
            topFunctions = listOf(TopFunction("render", "/private/symbols/libui.so", 9, 4, 3, 1)),
            timeline = emptyList(),
            callTree = emptyList(),
            flameGraph = FlameGraphSnapshot(query, nodes, FlameGraphRowProjector.project(nodes, query.direction), 0, null, emptyList()),
            stackChart = PanelProjection.Failed("TEST", "unused"),
            markers = PanelProjection.Failed("TEST", "unused"),
            diagnostics = emptyList(),
        )
    }
}
