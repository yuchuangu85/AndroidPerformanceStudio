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

    @Test
    fun `default thresholds flag realistic deep and wide hierarchies`() {
        val deepRoot = (1..10).fold(node("leaf")) { child, level ->
            node("level-$level", children = listOf(child))
        }
        val wideRoot = node(
            "wide-root",
            children = (1..11).map { index -> node("child-$index") },
        )

        val deepReport = LayoutAnalyzer().analyze(deepRoot)
        val wideReport = LayoutAnalyzer().analyze(wideRoot)

        assertTrue(deepReport.findings.any { it.ruleId == "layout.deep-hierarchy" })
        assertTrue(wideReport.findings.any { it.ruleId == "layout.excessive-children" })
    }

    @Test
    fun `reports a structural risk when several sibling bounds substantially overlap`() {
        val overlappingRoot = node(
            "overlapping-root",
            children = listOf(
                node("back", bounds = Bounds(0, 0, 100, 100)),
                node("middle", bounds = Bounds(5, 5, 95, 95)),
                node("front", bounds = Bounds(10, 10, 90, 90)),
            ),
        )
        val adjacentRoot = node(
            "adjacent-root",
            children = listOf(
                node("left", bounds = Bounds(0, 0, 50, 100)),
                node("center", bounds = Bounds(50, 0, 100, 100)),
                node("right", bounds = Bounds(100, 0, 150, 100)),
            ),
        )

        val overlappingReport = LayoutAnalyzer().analyze(overlappingRoot)
        val adjacentReport = LayoutAnalyzer().analyze(adjacentRoot)

        assertTrue(
            overlappingReport.findings.any {
                it.ruleId == "layout.overlapping-siblings" &&
                    it.nodeId == "overlapping-root" &&
                    it.severity == Severity.WARNING
            },
        )
        assertTrue(adjacentReport.findings.none { it.ruleId == "layout.overlapping-siblings" })
    }

    private fun node(
        id: String,
        visible: Boolean = true,
        bounds: Bounds = Bounds(0, 0, 100, 100),
        children: List<ViewNode> = emptyList(),
    ) = ViewNode(
        id = id,
        className = "View",
        bounds = bounds,
        visible = visible,
        children = children,
    )
}
