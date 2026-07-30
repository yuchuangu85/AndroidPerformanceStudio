package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ViewDisplayOptionsTest {
    @Test
    fun `visible view bounds default to enabled while other optional overlays stay disabled`() {
        assertEquals(
            ViewDisplayOptions(
                hideInvisibleHierarchyViews = false,
                hideInvisibleFindings = false,
                hideHierarchyIndices = false,
                showHierarchyIds = true,
                showHierarchyLayerVisibilityButtons = false,
                showVisibleViewBounds = true,
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
                option != ViewDisplayOption.SHOW_VISIBLE_VIEW_BOUNDS,
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
    fun `store defaults visible view bounds to enabled and round trips independent values`() {
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
            showVisibleViewBounds = false,
            canvasHitTestOrder = CanvasHitTestOrder.Z_ORDER,
        )
        store.save(expected)

        assertEquals(7, values.size)
        assertEquals(expected, store.load())
    }
}
