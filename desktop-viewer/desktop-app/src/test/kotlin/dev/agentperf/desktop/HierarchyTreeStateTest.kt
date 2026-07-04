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

    @Test
    fun `keyboard navigation follows visible rows and stops at edges`() {
        val rows = listOf(
            row("root", depth = 0, hasChildren = true),
            row("branch", depth = 1, hasChildren = true),
            row("leaf", depth = 2),
            row("sibling", depth = 1),
        )
        val collapsed = HierarchyTreeState().toggle("branch")

        assertEquals(
            "branch",
            collapsed.adjacentNodeId(rows, "root", HierarchyNavigationDirection.DOWN),
        )
        assertEquals(
            "sibling",
            collapsed.adjacentNodeId(rows, "branch", HierarchyNavigationDirection.DOWN),
        )
        assertEquals(
            "branch",
            collapsed.adjacentNodeId(rows, "sibling", HierarchyNavigationDirection.UP),
        )
        assertEquals(
            "root",
            collapsed.adjacentNodeId(rows, "root", HierarchyNavigationDirection.UP),
        )
        assertEquals(
            "sibling",
            collapsed.adjacentNodeId(rows, "sibling", HierarchyNavigationDirection.DOWN),
        )
    }

    @Test
    fun `enter toggles only rows with children`() {
        val rows = listOf(
            row("root", depth = 0, hasChildren = true),
            row("leaf", depth = 1),
        )
        val initial = HierarchyTreeState()

        assertEquals(
            listOf("root"),
            initial.toggleExpandable("root", rows).visibleRows(rows).map { it.id },
        )
        assertEquals(initial, initial.toggleExpandable("leaf", rows))
        assertEquals(initial, initial.toggleExpandable("missing", rows))
    }

    @Test
    fun `selecting an already visible row does not move the tree`() {
        assertEquals(
            null,
            HierarchySelectionScrollPolicy.targetIndex(
                selectedIndex = 6,
                firstVisibleIndex = 3,
                lastVisibleIndex = 9,
            ),
        )
        assertEquals(
            10,
            HierarchySelectionScrollPolicy.targetIndex(
                selectedIndex = 10,
                firstVisibleIndex = 3,
                lastVisibleIndex = 9,
            ),
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
