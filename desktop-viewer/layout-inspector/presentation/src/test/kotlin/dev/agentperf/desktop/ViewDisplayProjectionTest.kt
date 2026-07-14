package dev.agentperf.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ViewDisplayProjectionTest {
    private val rows = listOf(
        row(id = "root", number = "0-0", label = "Root", depth = 0),
        row(id = "hidden", number = "1-0", label = "Hidden", depth = 1, visible = false),
        row(id = "hidden-child", number = "2-0", label = "HiddenChild", depth = 2),
        row(id = "visible-sibling", number = "1-1", label = "Button", depth = 1),
    )

    @Test
    fun `hierarchy filtering removes invisible rows and their descendants`() {
        assertEquals(
            listOf("root", "visible-sibling"),
            ViewDisplayProjection.hierarchyRows(rows, hideInvisible = true)
                .map(TreeRowModel::id),
        )
        assertEquals(rows, ViewDisplayProjection.hierarchyRows(rows, hideInvisible = false))
    }

    @Test
    fun `finding filtering removes hidden subtrees but keeps unknown nodes`() {
        val findings = listOf(
            finding(key = "hidden", nodeId = "hidden", tone = FindingTone.INFO),
            finding(key = "hidden-child", nodeId = "hidden-child", tone = FindingTone.WARNING),
            finding(key = "visible", nodeId = "visible-sibling", tone = FindingTone.ERROR),
            finding(key = "unknown", nodeId = "missing", tone = FindingTone.INFO),
        )

        val displayed = ViewDisplayProjection.findings(
            findings = findings,
            rows = rows,
            hideInvisible = true,
        )

        assertEquals(listOf("visible", "unknown"), displayed.map(FindingRowModel::key))
        assertEquals(
            SeveritySummary(info = 1, warning = 0, error = 1),
            ViewDisplayProjection.severitySummary(displayed),
        )
        assertEquals(
            findings,
            ViewDisplayProjection.findings(
                findings = findings,
                rows = rows,
                hideInvisible = false,
            ),
        )
    }

    @Test
    fun `hierarchy label can omit only the index prefix`() {
        val row = rows.last()

        assertEquals(
            "1-1  Button",
            ViewDisplayProjection.hierarchyLabel(
                row = row,
                hideIndex = false,
                showId = true,
            ),
        )
        assertEquals(
            "Button",
            ViewDisplayProjection.hierarchyLabel(
                row = row,
                hideIndex = true,
                showId = true,
            ),
        )
    }

    @Test
    fun `hierarchy label can hide resource id while keeping index and class`() {
        val row = rows.last().copy(resourceLabel = "id/submit")

        assertEquals(
            "1-1  id/submit  Button",
            ViewDisplayProjection.hierarchyLabel(
                row = row,
                hideIndex = false,
                showId = true,
            ),
        )
        assertEquals(
            "1-1  Button",
            ViewDisplayProjection.hierarchyLabel(
                row = row,
                hideIndex = false,
                showId = false,
            ),
        )
    }

    private fun row(
        id: String,
        number: String,
        label: String,
        depth: Int,
        visible: Boolean = true,
    ): TreeRowModel = TreeRowModel(
        id = id,
        number = number,
        label = label,
        depth = depth,
        selected = false,
        visible = visible,
        hasChildren = false,
    )

    private fun finding(
        key: String,
        nodeId: String,
        tone: FindingTone,
    ): FindingRowModel = FindingRowModel(
        key = key,
        title = key,
        nodeNumber = "—",
        nodeId = nodeId,
        message = key,
        tone = tone,
    )
}
