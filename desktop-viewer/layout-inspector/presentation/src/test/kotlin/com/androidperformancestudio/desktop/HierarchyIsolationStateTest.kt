package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HierarchyIsolationStateTest {
    @Test
    fun `isolation returns only the subtree and parent moves one level up`() {
        val rows = listOf(
            row("root", 0),
            row("parent", 1),
            row("child", 2),
            row("sibling", 1),
        )

        val isolated = HierarchyIsolationState().isolate("child", rows)

        assertEquals(listOf(row("child", 0)), isolated.rows(rows))
        assertEquals("parent", isolated.parent(rows).rootNodeId)
        assertEquals(listOf("parent", "child"), isolated.parent(rows).rows(rows).map { it.id })
    }

    private fun row(id: String, depth: Int) = TreeRowModel(
        id = id,
        number = id,
        label = id,
        depth = depth,
        selected = false,
        visible = true,
        hasChildren = false,
    )
}
