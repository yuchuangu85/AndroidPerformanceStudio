package com.androidperformancestudio.desktop

import com.androidperformancestudio.analysis.AnalysisReport
import com.androidperformancestudio.analysis.Finding
import com.androidperformancestudio.analysis.LayoutMetrics
import com.androidperformancestudio.analysis.Severity
import com.androidperformancestudio.fixtures.SampleSnapshots
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.ViewAttributes
import com.androidperformancestudio.protocol.ViewNode
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiAnalysisInputBuilderTest {
    @Test
    fun `builds compact redacted layout input for the active window`() {
        val root = ViewNode(
            id = "root",
            className = "RootLayout",
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
        val snapshot = SampleSnapshots.dashboard.copy(root = root)
        val report = AnalysisReport(
            metrics = LayoutMetrics(nodeCount = 2, maxDepth = 2, widestLevel = 1),
            findings = listOf(Finding("layout.test", Severity.WARNING, "password", "rule message")),
        )

        val input = AiAnalysisInputBuilder().build(
            snapshot = snapshot,
            activeRoot = root,
            analysis = report,
            screenshotAvailable = true,
        )

        assertTrue(input.json.contains("\"packageName\""))
        assertTrue(input.json.contains("\"screenshotAvailable\": true"))
        assertTrue(input.json.contains("android.widget.TextView"))
        assertTrue(input.json.contains("layout.test"))
        assertTrue(input.json.contains("\"textLength\": 15"))
        assertFalse(input.json.contains("secret password"))
        assertFalse(input.json.contains("private description"))
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

        val fullInput = AiAnalysisInputBuilder().build(snapshot, root, report, screenshotAvailable = false)
        val selectedInput = AiAnalysisInputBuilder().build(
            snapshot,
            root,
            report,
            screenshotAvailable = false,
            selectedNode = children.last(),
        )

        assertTrue(fullInput.treeTruncated)
        assertFalse(selectedInput.treeTruncated)
        assertTrue(selectedInput.json.contains("Child25"))
        assertTrue(selectedInput.json.contains("RootLayout"))
        assertFalse(selectedInput.json.contains("Child1\""))
        assertTrue(selectedInput.json.contains("\"nodeCount\": 2"))
    }
}
