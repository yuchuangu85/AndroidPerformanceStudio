package com.androidperformancestudio.desktop

import com.androidperformancestudio.analysis.AnalysisReport
import com.androidperformancestudio.analysis.LayoutMetrics
import com.androidperformancestudio.fixtures.SampleSnapshots
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.ViewNode
import com.androidperformancestudio.ui.uiAiEvidenceId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LayoutAiContextTest {
    @Test
    fun `layout selection is redacted and evidence identity binds snapshot`() {
        val root = ViewNode("root", "android.widget.TextView", Bounds(0, 0, 100, 100), text = "private runtime value")
        val snapshot = SampleSnapshots.dashboard.copy(root = root)
        val input = AiAnalysisInputBuilder().build(snapshot, root, AnalysisReport(LayoutMetrics(1, 1, 1), emptyList()), root)
        val first = layoutUiAiContext("snapshot-one", input)
        val next = layoutUiAiContext("snapshot-two", input)
        assertNotNull(first)
        assertEquals("layout-inspector", first!!.key)
        assertTrue(first.currentSelection)
        assertEquals("layout-snapshot", first.evidence.single().kind)
        assertFalse(first.evidence.single().payloadJson.contains("private runtime value"))
        assertNotEquals(first.evidence.single().id, next!!.evidence.single().id)
    }

    @Test
    fun `builder produced partial context is honest and preserves stable evidence identity`() {
        val root = ViewNode(
            "root",
            "android.view.ViewGroup",
            Bounds(0, 0, 100, 100),
            children = (1..250).map { index ->
                ViewNode("child-$index", "android.view.View", Bounds(0, 0, 10, 10), text = "private runtime value")
            },
        )
        val snapshot = SampleSnapshots.dashboard.copy(root = root)
        val input = AiAnalysisInputBuilder().build(
            snapshot,
            root,
            AnalysisReport(LayoutMetrics(251, 2, 250), emptyList()),
            root,
        )

        assertTrue(input.treeTruncated)
        assertTrue(input.hasBoundedSummary)
        val context = requireNotNull(layoutUiAiContext("snapshot", input))
        val evidence = context.evidence.single()

        assertEquals("layout-inspector", context.key)
        assertTrue(context.currentSelection)
        assertTrue(context.title.startsWith("Partial/incomplete"))
        assertTrue(context.title.contains("selected node"))
        assertEquals("layout-snapshot", evidence.kind)
        assertTrue(evidence.summary.startsWith("Partial/incomplete"))
        assertTrue(evidence.summary.contains("included nodes only"))
        assertTrue(evidence.summary.contains("runtime text and screenshots excluded"))
        assertFalse(evidence.payloadJson.contains("private runtime value"))
        assertEquals(input.json, evidence.payloadJson)
        assertEquals(uiAiEvidenceId("layout-snapshot", "snapshot", "root", input.json), evidence.id)
        assertEquals(context, layoutUiAiContext("snapshot", input))
        assertNotEquals(evidence.id, layoutUiAiContext("next-snapshot", input)!!.evidence.single().id)
    }

    @Test
    fun `validated coverage allows omitted evidence but raw and mismatched summaries are rejected`() {
        val input = boundedSummaryInput()
        val context = requireNotNull(layoutUiAiContext("snapshot", input))
        assertTrue(context.title.startsWith("Partial/incomplete"))
        assertTrue(context.evidence.single().summary.startsWith("Partial/incomplete"))
        assertEquals(input.json, context.evidence.single().payloadJson)

        val oversizedUtf8 = boundedSummaryInput(coverage = buildJsonObject {
            put("schemaVersion", 1)
            put("complete", false)
            put("includedNodeCount", 1)
            put("note", "\u754c".repeat(16_384))
        }.toString())
        assertTrue(oversizedUtf8.json.length < 49_152)
        for (invalid in listOf(
            input.copy(json = "{}"),
            input.copy(json = "{not-json}"),
            boundedSummaryInput(coverage = null),
            boundedSummaryInput(coverage = "{}"),
            boundedSummaryInput(coverage = """{"schemaVersion":1,"complete":false,"includedNodeCount":2}"""),
            boundedSummaryInput(coverage = """{"schemaVersion":1,"complete":true,"includedNodeCount":1}"""),
            boundedSummaryInput(coverage = """{"schemaVersion":2,"complete":false,"includedNodeCount":1}"""),
            input.copy(selectedNodeId = "missing"),
            input.copy(treeTruncated = false),
            input.copy(json = input.json + " ".repeat(49_152)),
            oversizedUtf8,
        )) {
            assertNull(layoutUiAiContext("snapshot", invalid))
        }
    }

    @Test
    fun `context payload cap counts UTF8 bytes and permits the exact byte boundary`() {
        val json = buildJsonObject { put("value", "\u754c".repeat(20_000)) }.toString()
        val atLimit = json + " ".repeat(65_536 - json.encodeToByteArray().size)
        val input = AiAnalysisInput(atLimit)

        assertEquals(65_536, atLimit.encodeToByteArray().size)
        assertTrue(atLimit.length < 65_536)
        assertNotNull(layoutUiAiContext("snapshot", input))
        assertNull(layoutUiAiContext("snapshot", input.copy(json = "$atLimit ")))
    }

    @Test
    fun `missing unvalidated truncated or over budget contexts never publish partial data`() {
        val input = AiAnalysisInput("{}")
        assertNull(layoutUiAiContext("snapshot", null))
        assertNull(layoutUiAiContext("snapshot", input.copy(treeTruncated = true)))
        assertNull(layoutUiAiContext("snapshot", input.copy(omittedSourceEvidenceCount = 1)))
        assertNull(layoutUiAiContext("snapshot", input.copy(json = " ".repeat(65_537))))
        assertFalse(layoutUiAiContext("snapshot", input)!!.currentSelection)
    }

    private fun boundedSummaryInput(
        coverage: String? = """{"schemaVersion":1,"complete":false,"includedNodeCount":1,"omittedNodeCount":25}""",
    ): AiAnalysisInput = AiAnalysisInput(
        json = buildJsonObject {
            put("tree", Json.parseToJsonElement("""{"id":"node","className":"android.view.View","children":[],"truncatedChildren":25}"""))
            coverage?.let { put("coverage", Json.parseToJsonElement(it)) }
        }.toString(),
        selectedNodeId = "node",
        includeSourceSnippets = false,
        omittedSourceEvidenceCount = 25,
        treeTruncated = true,
    )
}
