package com.androidperformancestudio.visualization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FlameGraphPaletteTest {
    @Test
    fun `captured categories map to stable roles independent of case and spacing`() {
        assertEquals(FlameCategoryRole.KERNEL, FlameGraphPalette.categoryRole(" Kernel "))
        assertEquals(FlameCategoryRole.MANAGED, FlameGraphPalette.categoryRole("JAVA"))
        assertEquals(FlameCategoryRole.GRAPHICS, FlameGraphPalette.categoryRole("RenderThread"))
        assertEquals(FlameCategoryRole.IO, FlameGraphPalette.categoryRole("disk I/O"))
        assertEquals(FlameCategoryRole.IO, FlameGraphPalette.categoryRole("IO"))
        assertEquals(FlameCategoryRole.OTHER, FlameGraphPalette.categoryRole(null))
    }

    @Test
    fun `theme palettes choose a deterministic accessible foreground`() {
        FlameTheme.entries.forEach { theme ->
            FlameCategoryRole.entries.forEach { role ->
                val colors = FlameGraphPalette.colors(role.name, theme)
                assertTrue(FlameGraphPalette.contrastRatio(colors.fill, colors.foreground) >= 4.5)
                assertEquals(
                    colors.foreground,
                    FlameGraphPalette.contrastingForeground(colors.fill),
                )
            }
        }
    }

    @Test
    fun `hover selection and context states remain distinct and compose in stable precedence`() {
        val normal = FlameGraphPalette.colors("native", FlameTheme.DARK)
        val hovered = FlameGraphPalette.colors("native", FlameTheme.DARK, FlameNodeVisualState(hovered = true))
        val selected = FlameGraphPalette.colors("native", FlameTheme.DARK, FlameNodeVisualState(selected = true))
        val context = FlameGraphPalette.colors("native", FlameTheme.DARK, FlameNodeVisualState(context = true))
        val all =
            FlameGraphPalette.colors(
                "native",
                FlameTheme.DARK,
                FlameNodeVisualState(selected = true, hovered = true, context = true),
            )

        assertNotEquals(normal, hovered)
        assertNotEquals(hovered, selected)
        assertNotEquals(selected, context)
        assertNotEquals(context, all)
        assertNotEquals(selected.fill, all.fill)
        assertEquals(context.outline, all.outline)
        assertTrue(FlameGraphPalette.contrastRatio(all.fill, all.foreground) >= 4.5)
    }

    @Test
    fun `unknown category values have a stable fallback role`() {
        assertEquals(FlameCategoryRole.OTHER, FlameGraphPalette.categoryRole("new-captured-category"))
        assertEquals(
            FlameGraphPalette.colors("new-captured-category", FlameTheme.LIGHT),
            FlameGraphPalette.colors("another-new-category", FlameTheme.LIGHT),
        )
    }
}
