@file:Suppress("MagicNumber")

package com.androidperformancestudio.ui.studio

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.ViewerPalettes
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.viewerColors
import com.androidperformancestudio.ui.viewerMaterialColorScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalTestApi::class)
class StudioWorkspaceStyleTest {
    @Test
    fun `workspace surfaces use fixed colors independent of the accent`() {
        listOf(false, true).forEach { dark ->
            val expected = if (dark) Color(0xFF29292B) else Color(0xFFFAFAFB)
            val baseline = viewerColors(dark)
            listOf(Color(0xFFFACA2E), Color(0xFF722169), Color(0xFF41B5C2)).forEach { accent ->
                val colors = viewerColors(dark, accent)
                listOf(
                    colors.panel,
                    colors.canvasBackground,
                    colors.workspace,
                    colors.toolbar,
                    colors.sectionBackground,
                    colors.field,
                    colors.detailRowDeep,
                    colors.detailRowLight,
                    colors.previewSurface,
                    colors.previewCanvas,
                    colors.firefoxLocalTrack,
                ).forEach { assertEquals(expected, it) }
                assertEquals(baseline.border, colors.border)
                assertEquals(baseline.selectedRow, colors.selectedRow)
                assertNotEquals(expected, colors.selectedRow)
                assertEquals(baseline.searchMatchRow, colors.searchMatchRow)
                assertEquals(baseline.searchCurrentMatchRow, colors.searchCurrentMatchRow)
                assertEquals(accent, colors.accent)
            }
        }
    }

    @Test
    fun `elevated Material surfaces do not reintroduce accent tint`() {
        listOf(false, true).forEach { dark ->
            listOf(Color(0xFFFACA2E), Color(0xFF722169)).forEach { accent ->
                val scheme = viewerMaterialColorScheme(dark, accent)
                assertEquals(scheme.surface, scheme.surfaceTint)
                listOf(0.dp, 1.dp, 6.dp, 12.dp, 24.dp).forEach { elevation ->
                    assertEquals(scheme.surface, scheme.surfaceColorAtElevation(elevation))
                }
            }
        }
    }

    @Test
    fun `shared light palette remains stable`() {
        val shared = viewerColors(false)
        assertEquals(ViewerPalettes.forDark(false, shared.accent).copy(workspace = shared.toolbar), shared)
    }

    @Test
    fun `Material containers and foregrounds follow shared workspace roles in both themes`() {
        listOf(false, true).forEach { dark ->
            val colors = viewerColors(dark)
            val scheme = viewerMaterialColorScheme(dark)
            assertEquals(colors.workspace, scheme.background)
            assertEquals(colors.panel, scheme.surface)
            assertEquals(colors.panel, scheme.surfaceContainer)
            assertEquals(colors.panel, scheme.surfaceContainerLow)
            assertEquals(colors.sectionBackground, scheme.surfaceVariant)
            assertEquals(colors.sectionBackground, scheme.surfaceContainerHigh)
            assertEquals(colors.border, scheme.outlineVariant)
            assertEquals(colors.primaryText, scheme.onSurface)
            assertEquals(colors.primaryText, scheme.onSecondaryContainer)
        }
    }

    @Test
    fun `nested viewer themes do not apply Startup surface tint twice`() =
        runDesktopComposeUiTest {
            var outer: ViewerColors? = null
            var inner: ViewerColors? = null
            var materialSurface = Color.Unspecified
            setContent {
                ViewerTheme(darkTheme = true) {
                    outer = LocalViewerColors.current
                    ViewerTheme(darkTheme = true) {
                        inner = LocalViewerColors.current
                        materialSurface = MaterialTheme.colorScheme.surface
                    }
                }
            }

            assertEquals(viewerColors(true), outer)
            assertEquals(outer, inner)
            assertEquals(outer?.panel, materialSurface)
        }
}
