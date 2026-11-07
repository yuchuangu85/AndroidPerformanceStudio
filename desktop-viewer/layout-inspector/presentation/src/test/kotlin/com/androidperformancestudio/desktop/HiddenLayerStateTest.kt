package com.androidperformancestudio.desktop

import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.ViewNode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HiddenLayerStateTest {
    @Test
    fun `toggle hides and restores a layer`() {
        val hidden = HiddenLayerState().toggle("cover")

        assertTrue(hidden.isHidden("cover"))
        assertEquals(setOf("cover"), hidden.hiddenNodeIds)
        assertFalse(hidden.toggle("cover").isHidden("cover"))
    }

    @Test
    fun `clear removes all hidden layers`() {
        val state = HiddenLayerState(setOf("one", "two"))

        assertEquals(HiddenLayerState(), state.clear())
    }

    @Test
    fun `sanitize drops ids that are no longer in the hierarchy`() {
        val rows = listOf(
            TreeRowModel("root", "0-0", "Root", depth = 0, selected = false, visible = true, hasChildren = true),
            TreeRowModel("kept", "1-0", "View", depth = 1, selected = false, visible = true, hasChildren = false),
        )

        assertEquals(
            HiddenLayerState(setOf("kept")),
            HiddenLayerState(setOf("kept", "stale")).sanitize(rows),
        )
    }

    @Test
    fun `visibility distinguishes direct and inherited hidden nodes`() {
        val root = ViewNode(
            id = "root",
            className = "Root",
            bounds = Bounds(0, 0, 100, 100),
            children = listOf(
                ViewNode(
                    id = "hidden-parent",
                    className = "Parent",
                    bounds = Bounds(0, 0, 100, 50),
                    children = listOf(ViewNode("child", "Child", Bounds(0, 0, 50, 25))),
                ),
                ViewNode("visible", "Visible", Bounds(0, 50, 100, 100)),
            ),
        )

        val visibility = HiddenLayerState(setOf("hidden-parent")).visibility(root)

        assertEquals(HiddenLayerVisibility.DIRECT, visibility["hidden-parent"])
        assertEquals(HiddenLayerVisibility.INHERITED, visibility["child"])
        assertEquals(HiddenLayerVisibility.VISIBLE, visibility["visible"])
    }
}
