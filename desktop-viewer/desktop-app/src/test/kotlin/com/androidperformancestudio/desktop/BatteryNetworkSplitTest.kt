package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BatteryNetworkSplitTest {
    private val sourceRoot = Path.of("src/main/kotlin/com/androidperformancestudio/desktop")

    @Test
    fun `navigation exposes battery and network as separate destinations`() {
        val destinations = Files.readString(sourceRoot.resolve("AppDestination.kt"))
        val shell = Files.readString(sourceRoot.resolve("shell/CommercialAppShell.kt"))
        val home = Files.readString(sourceRoot.resolve("AppHomePage.kt"))

        assertTrue(destinations.contains("BATTERY_PROFILER"))
        assertTrue(destinations.contains("NETWORK_PROFILER"))
        assertFalse(destinations.contains("BATTERY_NETWORK"))
        assertTrue(shell.contains("AppDestination.BATTERY_PROFILER"))
        assertTrue(shell.contains("AppDestination.NETWORK_PROFILER"))
        assertTrue(home.contains("Res.string.battery_profiler"))
        assertTrue(home.contains("Res.string.network_profiler"))
    }

    @Test
    fun `home dashboard opens each profiler through its own entry`() {
        val home = Files.readString(sourceRoot.resolve("AppHomePage.kt"))
        val entries = home.substringAfter("val entries =").substringBefore("val quickActions =")

        assertTrue(entries.contains("onClick = onOpenBatteryProfiler"))
        assertTrue(entries.contains("onClick = onOpenNetworkProfiler"))
        assertTrue(entries.contains("Res.string.batterystats_analysis_with_wakelock_alarm_and_network_usage_stats"))
        assertTrue(entries.contains("Res.string.http_https_traffic_capture_and_request_timeline_analysis"))
        assertFalse(entries.contains("Res.string.battery_network"))
    }

    @Test
    fun `shell hosts each profiler directly instead of a composite tab workspace`() {
        val shell = Files.readString(sourceRoot.resolve("DesktopAppMainPage.kt"))

        assertTrue(shell.contains("BatteryProfilerMainPage("))
        assertTrue(shell.contains("NetworkProfilerMainPage("))
        assertFalse(shell.contains("BatteryNetworkMainPage("))
        assertFalse(Files.exists(sourceRoot.resolve("BatteryNetworkMainPage.kt")))
    }

    @Test
    fun `both profilers keep distinct icons and only battery publishes a native menu`() {
        val icons =
            Files.readString(
                sourceRoot.resolve("shell/StudioNavigationIcon.kt"),
            )
        val networkPage =
            Files.readString(
                Path.of(
                    "..",
                    "network-profiler/network-app/src/main/kotlin/com/androidperformancestudio/network/app/NetworkProfilerMainPage.kt",
                ),
            )

        assertTrue(icons.contains("AppDestination.BATTERY_PROFILER -> UiComponentsRes.drawable.ic_nav_battery"))
        assertTrue(icons.contains("AppDestination.NETWORK_PROFILER -> UiComponentsRes.drawable.ic_nav_network"))
        assertTrue(AppDestination.BATTERY_PROFILER.hasNativeFeatureMenu())
        // The window title updates only after the active page reports its menu bar ready. The
        // Network page publishes no ActiveWindowMenuBar, so it must use the host fallback menu;
        // advertising a native menu here leaves the window title stuck on the previous page.
        assertFalse(networkPage.contains("ActiveWindowMenuBar"))
        assertFalse(AppDestination.NETWORK_PROFILER.hasNativeFeatureMenu())
    }
}
