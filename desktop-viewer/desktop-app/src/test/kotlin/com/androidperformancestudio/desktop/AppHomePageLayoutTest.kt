package com.androidperformancestudio.desktop

import com.androidperformancestudio.ui.viewerColors
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppHomePageLayoutTest {
    private val source: String
        get() = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/AppHomePage.kt"))

    @Test
    fun `home dashboard renders on the shared sidebar background`() {
        assertTrue(source.contains("color = colors.workspace"))
        assertEquals(viewerColors(darkTheme = false).toolbar, viewerColors(darkTheme = false).workspace)
        assertEquals(viewerColors(darkTheme = true).toolbar, viewerColors(darkTheme = true).workspace)
    }

    @Test
    fun `home dashboard splits into a main column and a fixed width rail`() {
        assertTrue(source.contains("HOME_TWO_COLUMN_MIN_WIDTH_DP"))
        assertTrue(source.contains("Modifier.width(HOME_RAIL_WIDTH_DP.dp)"))
        assertTrue(source.contains("HomeMainColumn("))
        assertTrue(source.contains("HomeRailColumn("))
        assertTrue(source.contains("HomeDateCard(language)"))
        assertTrue(source.contains("HomeDeviceSummary("))
    }

    @Test
    fun `home module grid stays adaptive`() {
        assertEquals(3, homeGridColumnCount(HOME_RAIL_WIDTH_DP + 500))
        assertEquals(3, homeGridColumnCount(900))
        assertEquals(2, homeGridColumnCount(600))
        assertEquals(1, homeGridColumnCount(420))
        assertTrue(HOME_MODULE_CARD_HEIGHT_DP < 120)
        assertTrue(source.contains("RoundedCornerShape(StudioTokens.cardRadius)"))
        // The module grid is a lazy, reorderable grid; non-dragged cards animate on reorder.
        assertTrue(source.contains("LazyVerticalGrid"))
        assertTrue(source.contains("GridCells.Fixed"))
        assertTrue(source.contains("Modifier.animateItem()"))
        assertTrue(source.contains("height(HOME_MODULE_CARD_HEIGHT_DP.dp)"))
        assertTrue(source.contains("entry.destination.navigationIconResource"))
    }

    @Test
    fun `recent session rows stay compact`() {
        val row =
            source
                .substringAfter("private fun RecentSessionRow(")
                .substringBefore("private fun StudioRecentItem.featureLabel(")

        assertTrue(row.contains("vertical = 3.dp"))
        // Material's 40 dp minimum button height made every row tall.
        assertFalse(row.contains("TextButton("))
        assertTrue(row.contains(".clickable(onClick = onOpen)"))
    }

    @Test
    fun `device refresh updates in place instead of flashing the loading state`() {
        val effect =
            source
                .substringAfter("LaunchedEffect(active, deviceOverviewSource, deviceRefreshRevision)")
                .substringBefore("val entries =")

        assertTrue(effect.contains("deviceOverview = deviceOverviewSource.load()"))
        // Clearing the state before loading swapped the panel to the loading state on every refresh.
        assertFalse(effect.contains("deviceOverview = null"))
        // The very first load still shows loading because the state starts empty.
        assertTrue(
            source.contains(
                "var deviceOverview by remember { mutableStateOf<StudioDeviceOverview?>(null) }",
            ),
        )
    }

    @Test
    fun `home metrics only read the real device and recent projections`() {
        assertEquals(2, Regex("StudioMetricCard\\(").findAll(source).count())
        assertTrue(source.contains("connectedCount"))
        assertTrue(source.contains("val recentValue ="))
        assertTrue(source.contains("recent_artifacts_scope"))
        // Sections without a backing data source are intentionally not rendered.
        assertFalse(source.contains("recent_reports"))
        assertFalse(source.contains("ai_insights"))
    }

    @Test
    fun `recent sessions table shows only columns backed by real artifacts`() {
        listOf(
            "recent_column_name",
            "recent_column_package",
            "recent_column_type",
            "recent_column_started",
            "recent_column_action",
        ).forEach { column -> assertTrue(source.contains(column), "missing $column") }
        assertFalse(source.contains("recent_column_device"))
        assertFalse(source.contains("recent_column_duration"))
        assertFalse(source.contains("recent_column_status"))
    }

    @Test
    fun `screencast is the last default home entry`() {
        // The default module order (fresh install / no customization) keeps Device Screencast last;
        // it was appended after Reverse Net when the screencast module was added.
        assertEquals(AppDestination.SCREENCAST, DEFAULT_MODULE_ORDER.last())
        assertEquals(AppDestination.REVERSE_NET, DEFAULT_MODULE_ORDER[DEFAULT_MODULE_ORDER.lastIndex - 1])
    }

    @Test
    fun `dashboard exposes all implemented module routes`() {
        // The module grid is driven by the shared module order; the open callback covers every
        // reorderable module. Method Recording stays out of the home grid (command-palette only).
        assertTrue(source.contains("homeLayout.moduleOrder"))
        assertTrue(source.contains("onOpenGpuInspector"))
        assertTrue(source.contains("onOpenBenchmarkRegression"))
        assertFalse(DEFAULT_MODULE_ORDER.contains(AppDestination.METHOD_RECORDING))
        val shell =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/shell/CommercialAppShell.kt"),
            )
        assertTrue(shell.contains("listOf(AppDestination.METHOD_RECORDING)"))
        assertTrue(shell.contains("navigator.open(destination)"))
    }
}
