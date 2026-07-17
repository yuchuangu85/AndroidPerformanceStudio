package com.androidperformancestudio.visualization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlameGraphPaletteTest {
    @Test
    fun `captured categories map to pinned Firefox color roles`() {
        assertEquals(FlameCategoryRole.SYSTEM, FlameGraphPalette.categoryRole(" Android Runtime "))
        assertEquals(FlameCategoryRole.KERNEL, FlameGraphPalette.categoryRole("Kernel"))
        assertEquals(FlameCategoryRole.NATIVE, FlameGraphPalette.categoryRole("JNI native"))
        assertEquals(FlameCategoryRole.MANAGED, FlameGraphPalette.categoryRole("JAVA"))
        assertEquals(FlameCategoryRole.GRAPHICS, FlameGraphPalette.categoryRole("RenderThread"))
        assertEquals(FlameCategoryRole.IO, FlameGraphPalette.categoryRole("disk I/O"))
        assertEquals(FlameCategoryRole.NETWORK, FlameGraphPalette.categoryRole("HTTP socket"))
        assertEquals(FlameCategoryRole.OTHER, FlameGraphPalette.categoryRole(null))
    }

    @Test
    fun `light style resolves pinned Firefox surfaces and Photon native colors`() {
        val style = FirefoxFlameGraphStyle.resolve(FlameTheme.LIGHT)
        val native = style.categoryStyle(FlameCategoryRole.NATIVE)

        assertEquals(0xFFFFFFFF.toInt(), style.canvasBackground.argb)
        assertEquals(0xFF000000.toInt(), style.canvasForeground.argb)
        assertEquals(0xFFD7D7DB.toInt(), style.viewportBorder.argb)
        assertEquals(0xFFFFE129.toInt(), native.selectedFill.argb)
        assertEquals(0x70FFE900, native.unselectedFill.argb)
        assertEquals(0xFF000000.toInt(), native.selectedText.argb)
    }

    @Test
    fun `dark style resolves pinned Firefox surfaces and Photon category colors`() {
        val style = FirefoxFlameGraphStyle.resolve(FlameTheme.DARK)

        assertEquals(0xFF18181A.toInt(), style.canvasBackground.argb)
        assertEquals(0xFFEDEDF0.toInt(), style.canvasForeground.argb)
        assertEquals(0xFF8A00EB.toInt(), style.categoryStyle(FlameCategoryRole.KERNEL).selectedFill.argb)
        assertEquals(0x708A00EB, style.categoryStyle(FlameCategoryRole.KERNEL).unselectedFill.argb)
        assertEquals(0x85BE9B00.toInt(), style.categoryStyle(FlameCategoryRole.NATIVE).unselectedFill.argb)
        assertEquals(0xFFB5007F.toInt(), style.categoryStyle(FlameCategoryRole.NETWORK).selectedFill.argb)
    }

    @Test
    fun `highlight states share selected fill text and a non color outline`() {
        val style = FirefoxFlameGraphStyle.resolve(FlameTheme.DARK)
        val ordinary = style.nodeColors("managed")
        val selected = style.nodeColors("managed", FlameNodeVisualState(selected = true))
        val hovered = style.nodeColors("managed", FlameNodeVisualState(hovered = true))
        val context = style.nodeColors("managed", FlameNodeVisualState(context = true))

        assertEquals(0x6045A1FF, ordinary.fill.argb)
        assertEquals(style.canvasForeground, ordinary.foreground)
        assertNull(ordinary.outline)
        assertEquals(selected, hovered)
        assertEquals(selected, context)
        assertEquals(0xFF45A1FF.toInt(), selected.fill.argb)
        assertEquals(0xFFEDEDF0.toInt(), selected.foreground.argb)
        assertEquals(style.focusOutline, selected.outline)
    }

    @Test
    fun `device scale resolves CSS geometry without changing color tokens`() {
        val standard = FirefoxFlameGraphStyle.resolve(FlameTheme.LIGHT)
        val retina = FirefoxFlameGraphStyle.resolve(FlameTheme.LIGHT, devicePixelRatio = 2f)

        assertEquals(16f, standard.rowHeightPx)
        assertEquals(32f, retina.rowHeightPx)
        assertEquals(20f, retina.labelFontSizePx)
        assertEquals(6f, retina.labelStartOffsetPx)
        assertEquals(22f, retina.labelBaselineOffsetPx)
        assertEquals(standard.categoryStyles, retina.categoryStyles)
    }
}
