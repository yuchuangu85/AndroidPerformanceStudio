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
        }
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
        )
        store.save(expected)

        assertEquals(3, values.size)
        assertEquals(expected, store.load())
    }
}
