package dev.agentperf.analysis

import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.ViewNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LayoutAnalyzerTest {
    @Test
    fun `computes structural metrics in one hierarchy`() {
        val root = node(
            "root",
            children = listOf(
                node("left", children = listOf(node("leaf"))),
                node("right"),
            ),
        )

        val report = LayoutAnalyzer().analyze(root)

        assertEquals(4, report.metrics.nodeCount)
        assertEquals(3, report.metrics.maxDepth)
        assertEquals(2, report.metrics.widestLevel)
    }

    @Test
    fun `reports invisible nodes with a stable rule id`() {
        val root = node("root", children = listOf(node("hidden", visible = false)))

        val report = LayoutAnalyzer().analyze(root)

        assertTrue(report.findings.any { it.ruleId == "layout.invisible-node" && it.nodeId == "hidden" })
    }

    @Test
    fun `reports hierarchy depth above configured threshold`() {
        val root = node("root", children = listOf(node("child", children = listOf(node("leaf")))))

        val report = LayoutAnalyzer(AnalysisConfig(maxDepth = 2)).analyze(root)

        assertTrue(report.findings.any { it.ruleId == "layout.deep-hierarchy" && it.severity == Severity.WARNING })
    }

    @Test
    fun `reports parents with excessive direct children`() {
        val root = node("root", children = listOf(node("a"), node("b"), node("c")))

        val report = LayoutAnalyzer(AnalysisConfig(maxChildrenPerNode = 2)).analyze(root)

        assertTrue(report.findings.any { it.ruleId == "layout.excessive-children" && it.nodeId == "root" })
    }

    private fun node(
        id: String,
        visible: Boolean = true,
        children: List<ViewNode> = emptyList(),
    ) = ViewNode(
        id = id,
        className = "View",
        bounds = Bounds(0, 0, 100, 100),
        visible = visible,
        children = children,
    )
}
