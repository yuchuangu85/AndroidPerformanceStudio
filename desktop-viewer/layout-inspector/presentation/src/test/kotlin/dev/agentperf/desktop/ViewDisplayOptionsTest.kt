package dev.agentperf.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ViewDisplayOptionsTest {
    @Test
    fun `all view options default to disabled`() {
        assertEquals(
            ViewDisplayOptions(
                hideInvisibleHierarchyViews = false,
                hideInvisibleFindings = false,
                hideHierarchyIndices = false,
                showHierarchyIds = true,
                showHierarchyLayerVisibilityButtons = false,
                showVisibleViewBounds = false,
                canvasHitTestOrder = CanvasHitTestOrder.SMALL_AREA_FIRST,
            ),
            ViewDisplayOptions(),
        )
    }

    @Test
    fun `each view option toggles independently`() {
        ViewDisplayOption.entries.forEach { option ->
            val toggled = ViewDisplayOptions().toggle(option)

            assertEquals(
                option == ViewDisplayOption.HIDE_INVISIBLE_HIERARCHY_VIEWS,
                toggled.hideInvisibleHierarchyViews,
            )
            assertEquals(
                option == ViewDisplayOption.HIDE_INVISIBLE_FINDINGS,
                toggled.hideInvisibleFindings,
            )
            assertEquals(
                option == ViewDisplayOption.HIDE_HIERARCHY_INDICES,
                toggled.hideHierarchyIndices,
            )
            assertEquals(true, toggled.showHierarchyIds)
            assertEquals(
                option == ViewDisplayOption.SHOW_HIERARCHY_LAYER_VISIBILITY_BUTTONS,
                toggled.showHierarchyLayerVisibilityButtons,
            )
            assertEquals(
                option == ViewDisplayOption.SHOW_VISIBLE_VIEW_BOUNDS,
                toggled.showVisibleViewBounds,
            )
            assertEquals(CanvasHitTestOrder.SMALL_AREA_FIRST, toggled.canvasHitTestOrder)
        }

        assertEquals(false, ViewDisplayOptions().toggleHierarchyIds().showHierarchyIds)
        assertEquals(
            CanvasHitTestOrder.Z_ORDER,
            ViewDisplayOptions().toggleCanvasHitTestOrder().canvasHitTestOrder,
        )
        assertEquals(
            true,
            ViewDisplayOptions().toggleHierarchyLayerVisibilityButtons().showHierarchyLayerVisibilityButtons,
        )
    }

    @Test
    fun `store round trips independent values and defaults missing values to false`() {
        val values = mutableMapOf<String, Boolean>()
        val store = ViewDisplayOptionsStore(
            readBoolean = { key, default -> values[key] ?: default },
            writeBoolean = values::set,
        )
        assertEquals(ViewDisplayOptions(), store.load())

        val expected = ViewDisplayOptions(
            hideInvisibleHierarchyViews = true,
            hideInvisibleFindings = false,
            hideHierarchyIndices = true,
            showHierarchyIds = false,
            showHierarchyLayerVisibilityButtons = true,
            showVisibleViewBounds = true,
            canvasHitTestOrder = CanvasHitTestOrder.Z_ORDER,
        )
        store.save(expected)

        assertEquals(7, values.size)
        assertEquals(expected, store.load())
    }
}
