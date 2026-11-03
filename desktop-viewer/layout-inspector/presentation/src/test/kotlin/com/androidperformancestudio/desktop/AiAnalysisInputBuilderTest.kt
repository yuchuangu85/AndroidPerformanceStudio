package com.androidperformancestudio.desktop

import com.androidperformancestudio.analysis.AnalysisReport
import com.androidperformancestudio.analysis.Finding
import com.androidperformancestudio.analysis.LayoutMetrics
import com.androidperformancestudio.analysis.Severity
import com.androidperformancestudio.fixtures.SampleSnapshots
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.ViewAttributes
import com.androidperformancestudio.protocol.ViewNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiAnalysisInputBuilderTest {
    @Test
    fun `builds compact redacted layout input for the active window`() {
        val root = ViewNode(
            id = "root",
            className = "com.private.ui.RootLayout",
            bounds = Bounds(0, 0, 100, 80),
            children = listOf(
                ViewNode(
                    id = "password",
                    className = "android.widget.TextView",
                    bounds = Bounds(4, 8, 96, 28),
                    resourceName = "com.example:id/password",
                    text = "secret password",
                    attributes = ViewAttributes(contentDescription = "private description"),
                ),
            ),
        )
        val snapshot = SampleSnapshots.dashboard.copy(root = root, packageName = "com.private.target")
        val report = AnalysisReport(
            metrics = LayoutMetrics(nodeCount = 2, maxDepth = 2, widestLevel = 1),
            findings = listOf(Finding(
                "layout.test", Severity.WARNING, "password", "private rule message containing secret password",
                arguments = mapOf(
                    "count" to "26", "threshold" to "12", "depth" to "NaN", "ratioPercent" to "Infinity",
                    "className" to "com.private.ui.RootLayout", "label" to "private description",
                ),
            )),
        )

        val input = AiAnalysisInputBuilder().build(
            snapshot = snapshot,
            activeRoot = root,
            analysis = report,
        )

        val payload = Json.parseToJsonElement(input.json).jsonObject
        assertEquals(setOf("display", "metrics", "layoutMetrics", "ruleFindings", "tree", "coverage"), payload.keys)
        assertTrue(payload.keysRecursively().intersect(setOf(
            "packageName", "capturedAtEpochMillis", "screenshotAvailable", "resourceName", "contentDescriptionLength",
            "text", "contentDescription", "message", "arguments",
        )).isEmpty())
        assertFalse(input.json.contains("com.private"))
        assertFalse(input.json.contains("android.widget"))
        assertFalse(input.json.contains("com.example:id/password"))
        assertTrue(input.json.contains("layout.test"))
        val child = (payload.getValue("tree").jsonObject.getValue("children") as JsonArray).single().jsonObject
        assertEquals("TextView", child.getValue("className").jsonPrimitive.content)
        assertEquals(15, child.getValue("textLength").jsonPrimitive.int)
        val rule = (payload.getValue("ruleFindings") as JsonArray).single().jsonObject
        assertEquals(setOf("ruleId", "severity", "nodeId", "parameters"), rule.keys)
        val parameters = rule.getValue("parameters").jsonObject
        assertEquals(setOf("count", "threshold"), parameters.keys)
        assertEquals(26, parameters.getValue("count").jsonPrimitive.int)
        assertEquals(12, parameters.getValue("threshold").jsonPrimitive.int)
        assertFalse(parameters.getValue("count").jsonPrimitive.isString)
        assertEquals("android.widget.TextView", input.sourceEvidence.single { it.nodeId == "password" }.className)
        assertEquals("com.example:id/password", input.sourceEvidence.single { it.nodeId == "password" }.resourceName)
        assertFalse(input.json.contains("secret password"))
        assertFalse(input.json.contains("private description"))
        assertFalse(input.json.contains("private rule message"))
    }

    @Test
    fun `non performance metadata and runtime text changes do not change the outbound projection`() {
        val first = ViewNode(
            "root", "com.private.one.CustomView", Bounds(0, 0, 10, 10), resourceName = "private-resource-one",
            text = "secret-one", attributes = ViewAttributes(contentDescription = "private-description"),
        )
        val second = first.copy(
            className = "com.private.two.CustomView", resourceName = "private-resource-two".repeat(1000),
            text = "secret-two", attributes = ViewAttributes(contentDescription = "changed-description".repeat(1000)),
        )
        val analysis = AnalysisReport(LayoutMetrics(1, 1, 1), emptyList())
        val builder = AiAnalysisInputBuilder()
        val firstInput = builder.build(SampleSnapshots.dashboard.copy(root = first, packageName = "private.one", capturedAtEpochMillis = 1), first, analysis)
        val nextInput = builder.build(SampleSnapshots.dashboard.copy(root = second, packageName = "private.two", capturedAtEpochMillis = 2), second, analysis)

        assertEquals(firstInput.json, nextInput.json)
        assertFalse(firstInput.treeTruncated)
        assertFalse(nextInput.treeTruncated)
        assertEquals(second.resourceName, nextInput.sourceEvidence.single().resourceName)
        assertEquals(second.className, nextInput.sourceEvidence.single().className)
    }

    @Test
    fun `reports tree truncation and scopes selected-node input`() {
        val children = (1..25).map { index ->
            ViewNode(
                id = "child-$index",
                className = "Child$index",
                bounds = Bounds(0, 0, 10, 10),
            )
        }
        val root = ViewNode(
            id = "root",
            className = "RootLayout",
            bounds = Bounds(0, 0, 100, 80),
            children = children,
        )
        val snapshot = SampleSnapshots.dashboard.copy(root = root)
        val report = AnalysisReport(
            metrics = LayoutMetrics(nodeCount = 26, maxDepth = 2, widestLevel = 25),
            findings = emptyList(),
        )

        val fullInput = AiAnalysisInputBuilder().build(snapshot, root, report)
        val selectedInput = AiAnalysisInputBuilder().build(
            snapshot,
            root,
            report,
            selectedNode = children.last(),
        )

        assertTrue(fullInput.treeTruncated)
        assertFalse(selectedInput.treeTruncated)
        assertTrue(selectedInput.json.contains("Child25"))
        assertTrue(selectedInput.json.contains("RootLayout"))
        assertFalse(selectedInput.json.contains("Child1\""))
        assertEquals(2, Json.parseToJsonElement(selectedInput.json).jsonObject.getValue("metrics").jsonObject.getValue("nodeCount").jsonPrimitive.int)
    }

    @Test
    fun `wide trees produce an honest bounded summary instead of an unusable truncation flag`() {
        val children = (1..2000).map { index ->
            ViewNode("node-$index", "android.view.View", Bounds(0, 0, 10, 10), text = "private view text")
        }
        val root = ViewNode("root", "RootLayout", Bounds(0, 0, 100, 80), children = children)
        val input = AiAnalysisInputBuilder().build(
            SampleSnapshots.dashboard.copy(root = root), root,
            AnalysisReport(LayoutMetrics(2001, 2, 2000), emptyList()), root,
        )

        assertTrue(input.treeTruncated)
        assertTrue(input.hasBoundedSummary)
        val payload = Json.parseToJsonElement(input.json).jsonObject
        val coverage = payload.getValue("coverage").jsonObject
        val tree = payload.getValue("tree").jsonObject
        assertFalse(coverage.getValue("complete").jsonPrimitive.boolean)
        assertEquals(25, countNodes(tree))
        assertEquals(1976, tree.getValue("truncatedChildren").jsonPrimitive.int)
        assertEquals(countNodes(tree), coverage.getValue("includedNodeCount").jsonPrimitive.int)
        assertEquals(2001, payload.getValue("layoutMetrics").jsonObject.getValue("nodeCount").jsonPrimitive.int)
        assertEquals(25, payload.getValue("metrics").jsonObject.getValue("nodeCount").jsonPrimitive.int)
        assertTrue(coverage.getValue("limitations").jsonPrimitive.content.contains("Partial layout evidence"))
        assertFalse(input.json.contains("private view text"))
        assertTrue(input.json.encodeToByteArray().size <= 49_152)
    }

    @Test
    fun `total node and UTF8 byte budgets apply across branches and oversized metadata`() {
        val grandchildren = (1..24).map { branch ->
            ViewNode(
                "branch-$branch", "\u5e03\u5c40\u7c7b".repeat(100), Bounds(0, 0, 10, 10),
                resourceName = "\u8d44\u6e90".repeat(100),
                children = (1..24).map { leaf ->
                    ViewNode("leaf-$branch-$leaf", "\u7ec4\u4ef6".repeat(100), Bounds(0, 0, 1, 1), resourceName = "\u8282\u70b9".repeat(100))
                },
            )
        }
        val root = ViewNode("root", "RootLayout", Bounds(0, 0, 100, 80), children = grandchildren)
        val findings = (1..1000).map {
            Finding("layout.rule-$it", Severity.WARNING, "root", "\u89c4\u5219\u8bf4\u660e".repeat(1000))
        }
        val input = AiAnalysisInputBuilder().build(
            SampleSnapshots.dashboard.copy(root = root), root,
            AnalysisReport(LayoutMetrics(601, 3, 576), findings), root,
        )

        assertTrue(input.hasBoundedSummary)
        assertTrue(input.json.encodeToByteArray().size <= 49_152)
        val payload = Json.parseToJsonElement(input.json).jsonObject
        val included = countNodes(payload.getValue("tree").jsonObject)
        assertTrue(included in 1..200)
        assertEquals(included, payload.getValue("coverage").jsonObject.getValue("includedNodeCount").jsonPrimitive.int)
        assertTrue((payload.getValue("ruleFindings") as JsonArray).size <= 24)
        assertTrue(input.sourceEvidence.single().className.length <= 160)
    }

    @Test
    fun `deep root and leaf projections avoid recursive traversal of the whole capture`() {
        val leaf = ViewNode("leaf", "android.view.View", Bounds(0, 0, 1, 1))
        var root = leaf
        repeat(12_000) { index -> root = ViewNode("parent-$index", "Container", Bounds(0, 0, 2, 2), children = listOf(root)) }
        val snapshot = SampleSnapshots.dashboard.copy(root = root)
        val analysis = AnalysisReport(LayoutMetrics(12_001, 12_001, 1), emptyList())
        val builder = AiAnalysisInputBuilder()

        val rootInput = builder.build(snapshot, root, analysis, root)
        val reportInput = builder.build(snapshot, root, analysis)
        val leafInput = builder.build(snapshot, root, analysis, leaf)

        assertTrue(rootInput.hasBoundedSummary)
        assertTrue(reportInput.hasBoundedSummary)
        assertTrue(reportInput.sourceEvidence.size <= 200)
        assertTrue(reportInput.omittedSourceEvidenceCount > 0)
        assertFalse(leafInput.treeTruncated)
        assertEquals(2, countNodes(Json.parseToJsonElement(leafInput.json).jsonObject.getValue("tree").jsonObject))
        assertEquals("leaf", leafInput.selectedNodeId)
    }

    @Test
    fun `partial eligibility rejects missing mismatched and over budget coverage`() {
        val tree = """{"id":"node","children":[],"truncatedChildren":1}"""
        fun input(coverage: String, selected: String = "node") = AiAnalysisInput(
            """{"tree":$tree,"coverage":$coverage}""", selectedNodeId = selected, treeTruncated = true,
        )
        val coverage = """{"schemaVersion":1,"complete":false,"includedNodeCount":1}"""
        assertTrue(input(coverage).hasBoundedSummary)
        assertFalse(input("{}").hasBoundedSummary)
        assertFalse(input("""{"schemaVersion":1,"complete":true,"includedNodeCount":1}""").hasBoundedSummary)
        assertFalse(input("""{"schemaVersion":1,"complete":false,"includedNodeCount":2}""").hasBoundedSummary)
        assertFalse(input("""{"schemaVersion":"1","complete":false,"includedNodeCount":1}""").hasBoundedSummary)
        assertFalse(input(coverage, "missing").hasBoundedSummary)
        assertFalse(input(coverage).copy(json = input(coverage).json + " ".repeat(49_152)).hasBoundedSummary)
        assertFalse(AiAnalysisInput("{}", treeTruncated = true).hasBoundedSummary)
    }

    private fun countNodes(node: JsonObject): Int =
        1 + (node["children"] as? JsonArray).orEmpty().sumOf { countNodes(it.jsonObject) }

    private fun JsonElement.keysRecursively(): Set<String> = when (this) {
        is JsonObject -> keys + values.flatMap { it.keysRecursively() }
        is JsonArray -> flatMap { it.keysRecursively() }.toSet()
        else -> emptySet()
    }
}
