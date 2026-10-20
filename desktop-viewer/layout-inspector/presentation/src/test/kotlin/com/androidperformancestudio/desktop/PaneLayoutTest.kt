package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class PaneLayoutTest {
    @Test
    fun `default hierarchy pane fits the commercial workspace range`() {
        assertEquals(320f, PaneWidths().hierarchy)
    }

    @Test
    fun `dragging separators changes only the adjacent side pane`() {
        val initial = PaneWidths()
        assertEquals(
            PaneWidths(hierarchy = 400f, properties = 300f),
            PaneLayout.dragHierarchy(initial, deltaDp = 80f, availableWidthDp = 1200f),
        )
        assertEquals(
            PaneWidths(hierarchy = 320f, properties = 260f),
            PaneLayout.dragProperties(initial, deltaDp = 40f, availableWidthDp = 1200f),
        )
    }

    @Test
    fun `dragging clamps side panes and preserves canvas minimum width`() {
        val initial = PaneWidths()
        assertEquals(PaneWidths(280f, 300f), PaneLayout.dragHierarchy(initial, -1000f, 1100f))
        assertEquals(PaneWidths(466f, 300f), PaneLayout.dragHierarchy(initial, 1000f, 1100f))
        assertEquals(PaneWidths(320f, 240f), PaneLayout.dragProperties(initial, 1000f, 1100f))
        assertEquals(PaneWidths(320f, 446f), PaneLayout.dragProperties(initial, -1000f, 1100f))
        val hierarchyMaximum = PaneLayout.dragHierarchy(initial, 1000f, 1100f)
        assertEquals(hierarchyMaximum, PaneLayout.dragHierarchy(hierarchyMaximum, 1000f, 1100f))
    }

    @Test
    fun `wide windows allow dragging the hierarchy well past the old 360dp cap`() {
        val dragged = PaneLayout.dragHierarchy(PaneWidths(), deltaDp = 10_000f, availableWidthDp = 1920f)
        assertEquals(PaneLayout.HIERARCHY_MAX_WIDTH_DP, dragged.hierarchy)
        assertEquals(300f, dragged.properties)
        assertEquals(PaneWidths(560f, 300f), PaneLayout.dragHierarchy(PaneWidths(), deltaDp = 240f, availableWidthDp = 1440f))
    }

    @Test
    fun `fitting remembered widths preserves canvas minimum after window shrinks`() {
        val fitted =
            PaneLayout.fit(
                widths = PaneWidths(hierarchy = 500f, properties = 500f),
                availableWidthDp = 1100f,
            )

        assertEquals(PaneWidths(hierarchy = 500f, properties = 266f), fitted)
        assertEquals(
            PaneLayout.CANVAS_MIN_WIDTH_DP,
            1100f -
                fitted.hierarchy -
                fitted.properties -
                PaneLayout.SPLITTER_WIDTH_DP * 2,
        )
    }

    @Test
    fun `compact windows keep canvas usable without changing normal pane defaults`() {
        val fitted = PaneLayout.fit(PaneWidths(), 800f)

        assertEquals(PaneWidths(hierarchy = 306f, properties = 200f), fitted)
        assertEquals(280f, 800f - fitted.hierarchy - fitted.properties - PaneLayout.SPLITTER_WIDTH_DP * 2)
        assertEquals(PaneWidths(hierarchy = 180f, properties = 200f), PaneLayout.dragHierarchy(fitted, -1000f, 800f))
    }

    @Test
    fun `temporary window shrink does not overwrite the user's preferred widths`() {
        val preferred = PaneWidths(hierarchy = 350f, properties = 380f)
        assertEquals(PaneWidths(306f, 200f), PaneLayout.fit(preferred, 800f))
        assertEquals(preferred, PaneLayout.fit(preferred, 1200f))

        val workspace = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"))
        assertFalse(workspace.contains("paneWidths = normalizedPaneWidths"))
    }

    @Test
    fun `hierarchy alone keeps its regular preferred width at 800 pixels`() {
        val preferred = PaneWidths(hierarchy = 350f, properties = 10_000f)

        assertEquals(preferred, PaneLayout.fit(preferred, 800f, showHierarchy = true, showDetails = false))
    }

    @Test
    fun `details alone keep their regular preferred width at 800 pixels`() {
        val preferred = PaneWidths(hierarchy = 10_000f, properties = 380f)

        assertEquals(preferred, PaneLayout.fit(preferred, 800f, showHierarchy = false, showDetails = true))
    }

    @Test
    fun `both hidden panes preserve full preferences without spending canvas budget`() {
        val preferred = PaneWidths(hierarchy = 10_000f, properties = 20_000f)

        assertEquals(preferred, PaneLayout.fit(preferred, 800f, showHierarchy = false, showDetails = false))
    }

    @Test
    fun `properties only can grow to all available space except regular canvas and one splitter`() {
        val preferred = PaneWidths(hierarchy = 10_000f, properties = 380f)

        assertEquals(
            preferred.copy(properties = 800f - 320f - 7f),
            PaneLayout.dragProperties(preferred, -10_000f, 800f, showHierarchy = false, showDetails = true),
        )
    }

    @Test
    fun `hierarchy only drag clamps to regular bounds and preserves hidden details`() {
        val preferred = PaneWidths(hierarchy = 350f, properties = 10_000f)

        assertEquals(
            preferred.copy(hierarchy = 473f),
            PaneLayout.dragHierarchy(preferred, 10_000f, 800f, showHierarchy = true, showDetails = false),
        )
        assertEquals(
            preferred.copy(hierarchy = 280f),
            PaneLayout.dragHierarchy(preferred, -10_000f, 800f, showHierarchy = true, showDetails = false),
        )
    }

    @Test
    fun `preferred widths are restored when panes reappear and space returns`() {
        val preferred = PaneWidths(hierarchy = 350f, properties = 380f)

        assertEquals(PaneWidths(306f, 200f), PaneLayout.fit(preferred, 800f))
        assertEquals(preferred, PaneLayout.fit(preferred, 800f, showHierarchy = true, showDetails = false))
        assertEquals(preferred, PaneLayout.fit(preferred, 800f, showHierarchy = false, showDetails = true))
        assertEquals(preferred, PaneLayout.fit(preferred, 800f, showHierarchy = false, showDetails = false))
        assertEquals(preferred, PaneLayout.fit(preferred, 1200f, showHierarchy = true, showDetails = true))
        assertEquals(PaneWidths(350f, 380f), preferred)
    }

    @Test
    fun `fit matrix preserves hidden preferences visible bounds canvas budget and idempotence`() {
        forEachSupportedLayout { showHierarchy, showDetails, available, preferred ->
            val context = "fit $preferred at $available hierarchy=$showHierarchy details=$showDetails"
            val fitted = PaneLayout.fit(preferred, available, showHierarchy, showDetails)

            assertVisibleLayout(fitted, preferred, available, showHierarchy, showDetails, context)
            assertEquals(fitted, PaneLayout.fit(fitted, available, showHierarchy, showDetails), context)
            if (showHierarchy && showDetails) {
                assertEquals(PaneLayout.fit(preferred, available), fitted, context)
            }
        }
    }

    @Test
    fun `drag matrix preserves visible bounds canvas budget adjacent panes and clamped idempotence`() {
        forEachSupportedLayout { showHierarchy, showDetails, available, preferred ->
            val fitted = PaneLayout.fit(preferred, available, showHierarchy, showDetails)
            for (delta in listOf(-10_000f, -40f, 0f, 40f, 10_000f)) {
                val context = "drag $delta from $fitted at $available hierarchy=$showHierarchy details=$showDetails"
                val hierarchy = PaneLayout.dragHierarchy(fitted, delta, available, showHierarchy, showDetails)
                val properties = PaneLayout.dragProperties(fitted, delta, available, showHierarchy, showDetails)

                assertVisibleLayout(hierarchy, preferred, available, showHierarchy, showDetails, context)
                assertVisibleLayout(properties, preferred, available, showHierarchy, showDetails, context)
                assertEquals(fitted.properties, hierarchy.properties, context)
                assertEquals(fitted.hierarchy, properties.hierarchy, context)
                if (showHierarchy) {
                    assertEquals(
                        hierarchy,
                        PaneLayout.dragHierarchy(preferred, delta, available, showHierarchy, showDetails),
                        context,
                    )
                }
                if (showDetails) {
                    assertEquals(
                        properties,
                        PaneLayout.dragProperties(preferred, delta, available, showHierarchy, showDetails),
                        context,
                    )
                }
                if (!showHierarchy || delta == 0f) {
                    assertEquals(fitted, hierarchy, context)
                }
                if (!showDetails || delta == 0f) {
                    assertEquals(fitted, properties, context)
                }
                if (delta == -10_000f || delta == 0f || delta == 10_000f) {
                    assertEquals(
                        hierarchy,
                        PaneLayout.dragHierarchy(hierarchy, delta, available, showHierarchy, showDetails),
                        context,
                    )
                    assertEquals(
                        properties,
                        PaneLayout.dragProperties(properties, delta, available, showHierarchy, showDetails),
                        context,
                    )
                }
                if (showHierarchy && showDetails) {
                    assertEquals(PaneLayout.dragHierarchy(fitted, delta, available), hierarchy, context)
                    assertEquals(PaneLayout.dragProperties(fitted, delta, available), properties, context)
                }
            }
        }
    }

    @Test
    fun `hidden drag matrix is a no-op even when visible preferences would need fitting`() {
        forEachSupportedLayout { showHierarchy, showDetails, available, preferred ->
            for (delta in listOf(-10_000f, 0f, 10_000f)) {
                val context = "hidden drag $delta from $preferred at $available hierarchy=$showHierarchy details=$showDetails"
                if (!showHierarchy) {
                    assertEquals(
                        preferred,
                        PaneLayout.dragHierarchy(preferred, delta, available, showHierarchy, showDetails),
                        context,
                    )
                }
                if (!showDetails) {
                    assertEquals(
                        preferred,
                        PaneLayout.dragProperties(preferred, delta, available, showHierarchy, showDetails),
                        context,
                    )
                }
            }
        }
    }

    private fun forEachSupportedLayout(check: (Boolean, Boolean, Float, PaneWidths) -> Unit) {
        val preferences =
            listOf(
                PaneWidths(0f, 0f),
                PaneWidths(179f, 199f),
                PaneWidths(180f, 200f),
                PaneWidths(279f, 239f),
                PaneWidths(280f, 240f),
                PaneWidths(),
                PaneWidths(350f, 380f),
                PaneWidths(360f, 240f),
                PaneWidths(361f, 241f),
                PaneWidths(640f, 240f),
                PaneWidths(641f, 241f),
                PaneWidths(10_000f, 20_000f),
            )
        for (showHierarchy in listOf(false, true)) {
            for (showDetails in listOf(false, true)) {
                val compactMinimum = minimumWorkspaceWidth(showHierarchy, showDetails, compact = true)
                val regularMinimum = minimumWorkspaceWidth(showHierarchy, showDetails, compact = false)
                val availableWidths =
                    listOf(
                        compactMinimum,
                        compactMinimum + 1f,
                        regularMinimum - 1f,
                        regularMinimum,
                        regularMinimum + 1f,
                        800f,
                        1200f,
                    ).filter { it >= compactMinimum }.distinct()
                for (available in availableWidths) {
                    for (preferred in preferences) {
                        check(showHierarchy, showDetails, available, preferred)
                    }
                }
            }
        }
    }

    private fun minimumWorkspaceWidth(
        showHierarchy: Boolean,
        showDetails: Boolean,
        compact: Boolean,
    ): Float =
        (if (compact) 280f else 320f) +
            (if (showHierarchy) (if (compact) 180f else 280f) + 7f else 0f) +
            (if (showDetails) (if (compact) 200f else 240f) + 7f else 0f)

    private fun assertVisibleLayout(
        actual: PaneWidths,
        preferred: PaneWidths,
        available: Float,
        showHierarchy: Boolean,
        showDetails: Boolean,
        context: String,
    ) {
        val compact = available < minimumWorkspaceWidth(showHierarchy, showDetails, compact = false)
        if (showHierarchy) {
            assertTrue(actual.hierarchy >= (if (compact) 180f else 280f), context)
            assertTrue(actual.hierarchy <= PaneLayout.HIERARCHY_MAX_WIDTH_DP, context)
        } else {
            assertEquals(preferred.hierarchy, actual.hierarchy, context)
        }
        if (showDetails) {
            assertTrue(actual.properties >= (if (compact) 200f else 240f), context)
        } else {
            assertEquals(preferred.properties, actual.properties, context)
        }
        val canvas =
            available -
                (if (showHierarchy) actual.hierarchy + 7f else 0f) -
                (if (showDetails) actual.properties + 7f else 0f)
        assertTrue(canvas >= (if (compact) 280f else 320f), "$context canvas=$canvas")
    }
}
