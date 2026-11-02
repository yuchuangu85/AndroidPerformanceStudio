package com.androidperformancestudio.visualization

import com.androidperformancestudio.profileanalysis.CallStackFrame
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FrameImplementation
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
    fun `light style resolves Firefox Simpleperf category colors`() {
        val style = FirefoxFlameGraphStyle.resolve(FlameTheme.LIGHT)
        val native = style.categoryStyle(FlameCategoryRole.NATIVE)

        assertEquals(0xFFFFFFFF.toInt(), style.canvasBackground.argb)
        assertEquals(0xFF000000.toInt(), style.canvasForeground.argb)
        assertEquals(0xFFD7D7DB.toInt(), style.viewportBorder.argb)
        assertEquals(0xFFED00B5.toInt(), native.selectedFill.argb)
        assertEquals(0x60ED00B5, native.unselectedFill.argb)
        assertEquals(0xFFFFFFFF.toInt(), native.selectedText.argb)
        assertEquals(0xFFFFE129.toInt(), style.categoryStyle(FlameCategoryRole.SYSTEM).selectedFill.argb)
        assertEquals(0xFFFF9400.toInt(), style.categoryStyle(FlameCategoryRole.KERNEL).selectedFill.argb)
        assertEquals(0xFF12BC00.toInt(), style.categoryStyle(FlameCategoryRole.MANAGED).selectedFill.argb)
    }

    @Test
    fun `dark style resolves Firefox Simpleperf category colors`() {
        val style = FirefoxFlameGraphStyle.resolve(FlameTheme.DARK)

        assertEquals(0xFF18181A.toInt(), style.canvasBackground.argb)
        assertEquals(0xFFEDEDF0.toInt(), style.canvasForeground.argb)
        assertEquals(0xFFD76E00.toInt(), style.categoryStyle(FlameCategoryRole.KERNEL).selectedFill.argb)
        assertEquals(0x60D76E00, style.categoryStyle(FlameCategoryRole.KERNEL).unselectedFill.argb)
        assertEquals(0x60B5007F, style.categoryStyle(FlameCategoryRole.NATIVE).unselectedFill.argb)
        assertEquals(0xFF058B00.toInt(), style.categoryStyle(FlameCategoryRole.MANAGED).selectedFill.argb)
        assertEquals(0xFF45A1FF.toInt(), style.categoryStyle(FlameCategoryRole.NETWORK).selectedFill.argb)
    }

    @Test
    fun `highlight states share selected fill and text without adding an outline`() {
        val style = FirefoxFlameGraphStyle.resolve(FlameTheme.DARK)
        val ordinary = style.nodeColors("managed")
        val selected = style.nodeColors("managed", FlameNodeVisualState(selected = true))
        val hovered = style.nodeColors("managed", FlameNodeVisualState(hovered = true))
        val context = style.nodeColors("managed", FlameNodeVisualState(context = true))

        assertEquals(0x60058B00, ordinary.fill.argb)
        assertEquals(style.canvasForeground, ordinary.foreground)
        assertNull(ordinary.outline)
        assertEquals(selected, hovered)
        assertEquals(selected, context)
        assertEquals(0xFF058B00.toInt(), selected.fill.argb)
        assertEquals(0xFFFFFFFF.toInt(), selected.foreground.argb)
        assertNull(selected.outline)
    }

    @Test
    fun `uncategorized frames follow Firefox Simpleperf path classification`() {
        assertEquals(
            FlameCategoryRole.KERNEL,
            FlameGraphPalette.categoryRole(null, frame("schedule", "[kernel.kallsyms]")),
        )
        assertEquals(
            FlameCategoryRole.SYSTEM,
            FlameGraphPalette.categoryRole(null, frame("malloc", "/system/lib64/libc.so")),
        )
        assertEquals(
            FlameCategoryRole.NATIVE,
            FlameGraphPalette.categoryRole(null, frame("app_tick", "/data/app/lib/arm64/libapp.so")),
        )
        assertEquals(
            FlameCategoryRole.SYSTEM,
            FlameGraphPalette.categoryRole(null, frame("android.view.View.draw", "/data/app/base.apk")),
        )
        assertEquals(
            FlameCategoryRole.MANAGED,
            FlameGraphPalette.categoryRole(null, frame("com.example.Home.render", "/data/app/base.apk")),
        )
        assertEquals(
            FlameCategoryRole.GRAPHICS,
            FlameGraphPalette.categoryRole("Graphics", frame("ignored", "[kernel.kallsyms]")),
        )
    }

    @Test
    fun `device scale resolves CSS geometry without changing color tokens`() {
        val standard = FirefoxFlameGraphStyle.resolve(FlameTheme.LIGHT)
        val retina = FirefoxFlameGraphStyle.resolve(FlameTheme.LIGHT, devicePixelRatio = 2f)

        assertEquals(16f, standard.rowHeightPx)
        assertEquals(32f, retina.rowHeightPx)
        assertEquals(10f, standard.labelFontSizePx)
        assertEquals(20f, retina.labelFontSizePx)
        assertEquals(6f, retina.labelStartOffsetPx)
        assertEquals(22f, retina.labelBaselineOffsetPx)
        assertEquals(standard.categoryStyles, retina.categoryStyles)
    }

    @Test
    fun `fractional device scales snap label offsets but keep font and row CSS sizes`() {
        val style = FirefoxFlameGraphStyle.resolve(FlameTheme.LIGHT, devicePixelRatio = 1.25f)

        assertEquals(20f, style.rowHeightPx)
        assertEquals(12.5f, style.labelFontSizePx)
        assertEquals(4f, style.labelStartOffsetPx)
        assertEquals(14f, style.labelBaselineOffsetPx)
    }

    @Test
    fun `stack chart dimmed colors follow Firefox light and dark neutral styles`() {
        val light = FirefoxFlameGraphStyle.resolve(FlameTheme.LIGHT)
        val dark = FirefoxFlameGraphStyle.resolve(FlameTheme.DARK)

        assertEquals(0xFFF9F9FA.toInt(), light.dimmedFill.argb)
        assertEquals(0xFF737373.toInt(), light.dimmedForeground.argb)
        assertEquals(0xFF2A2A2E.toInt(), dark.dimmedFill.argb)
        assertEquals(0xFFB1B1B3.toInt(), dark.dimmedForeground.argb)
    }

    private fun frame(
        symbol: String,
        resource: String,
    ): CallStackFrame =
        CallStackFrame(
            frameId = 1,
            functionId = FlameFunctionId(1),
            symbolName = symbol,
            resource = resource,
            virtualAddress = 0,
            implementation = FrameImplementation.UNKNOWN,
        )
}
