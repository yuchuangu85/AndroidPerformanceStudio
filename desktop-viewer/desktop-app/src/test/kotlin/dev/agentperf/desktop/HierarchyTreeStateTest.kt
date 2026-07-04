package dev.agentperf.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HierarchyTreeStateTest {
    @Test
    fun `row height is reduced by one third`() {
        assertEquals(24, HierarchyRowLayout.BASELINE_HEIGHT_DP)
        assertEquals(16, HierarchyRowLayout.HEIGHT_DP)
        assertEquals(
            HierarchyRowLayout.BASELINE_HEIGHT_DP * 2 / 3,
            HierarchyRowLayout.HEIGHT_DP,
        )
    }

    @Test
    fun `subtrees start expanded and collapse independently`() {
        val rows = listOf(
            row("root", depth = 0, hasChildren = true),
            row("branch", depth = 1, hasChildren = true),
            row("leaf", depth = 2),
            row("sibling", depth = 1),
        )
        val initial = HierarchyTreeState()

        assertEquals(rows, initial.visibleRows(rows))
        assertEquals(
            listOf("root"),
            initial.toggle("root").visibleRows(rows).map { it.id },
        )

        val branchCollapsed = initial.toggle("branch")
        assertEquals(
            listOf("root", "branch", "sibling"),
            branchCollapsed.visibleRows(rows).map { it.id },
        )
        assertEquals(
            rows,
            branchCollapsed.toggle("branch").visibleRows(rows),
        )
    }

    private fun row(
        id: String,
        depth: Int,
        hasChildren: Boolean = false,
    ): TreeRowModel =
        TreeRowModel(
            id = id,
            number = id,
            label = id,
            depth = depth,
            selected = false,
            visible = true,
            hasChildren = hasChildren,
        )
}
