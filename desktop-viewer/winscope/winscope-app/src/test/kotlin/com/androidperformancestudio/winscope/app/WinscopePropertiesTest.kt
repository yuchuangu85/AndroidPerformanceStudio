package com.androidperformancestudio.winscope.app

import com.androidperformancestudio.winscope.model.WinscopeProperty
import kotlin.test.Test
import kotlin.test.assertEquals

class WinscopePropertiesTest {
    @Test
    fun `property paths are displayed as a Winscope style tree`() {
        val tree =
            buildWinscopePropertyTree(
                listOf(
                    WinscopeProperty("crop.left", "10"),
                    WinscopeProperty("crop.right", "20"),
                    WinscopeProperty("visible", "true"),
                ),
            )

        assertEquals(listOf("crop", "visible"), tree.map(WinscopePropertyTreeItem::key))
        assertEquals(listOf("left", "right"), tree.first().children.map(WinscopePropertyTreeItem::key))
        assertEquals(
            "10",
            tree
                .first()
                .children
                .first()
                .property
                ?.value,
        )
    }
}
