package com.androidperformancestudio.desktop

import com.androidperformancestudio.desktop.shell.NAVIGATION_COLLAPSE_BREAKPOINT_DP
import com.androidperformancestudio.desktop.shell.WorkspaceContext
import com.androidperformancestudio.desktop.shell.navigationRailCollapsedFor
import com.androidperformancestudio.desktop.shell.resolvedNavigationRailCollapsed
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommercialAppShellTest {
    @Test
    fun `navigation rail collapses below the documented minimum width`() {
        assertTrue(navigationRailCollapsedFor(NAVIGATION_COLLAPSE_BREAKPOINT_DP - 1))
        assertFalse(navigationRailCollapsedFor(NAVIGATION_COLLAPSE_BREAKPOINT_DP))
        assertFalse(navigationRailCollapsedFor(NAVIGATION_COLLAPSE_BREAKPOINT_DP + 160))
        assertTrue(resolvedNavigationRailCollapsed(1100, null))
        assertFalse(resolvedNavigationRailCollapsed(1100, false))
        assertTrue(resolvedNavigationRailCollapsed(1440, true))
    }

    @Test
    fun `workspace context is a read only shell projection`() {
        val context =
            WorkspaceContext(
                deviceLabel = "Pixel 9",
                packageName = "com.example.app",
                processName = "com.example.app:worker",
                artifactLabel = "baseline.perfetto-trace",
            )

        assertEquals("Pixel 9", context.deviceLabel)
        assertEquals("com.example.app", context.packageName)
        assertEquals("com.example.app:worker", context.processName)
        assertEquals("baseline.perfetto-trace", context.artifactLabel)
    }

    @Test
    fun `hidden retained destinations block keyboard while shell handles global shortcuts first`() {
        val sourceRoot = Path.of("src/main/kotlin/com/androidperformancestudio/desktop")
        val desktopMain = Files.readString(sourceRoot.resolve("DesktopAppMainPage.kt"))
        val shell = Files.readString(sourceRoot.resolve("shell/CommercialAppShell.kt"))

        assertTrue(desktopMain.contains(".blockKeyboardInputWhenInactive(active)"))
        assertTrue(desktopMain.contains("onPreviewKeyEvent { true }"))
        assertTrue(shell.contains(".onPreviewKeyEvent { event ->"))
        assertTrue(shell.contains("LaunchedEffect(navigator.destination)"))
        assertTrue(shell.contains("shellFocusRequester.requestFocus()"))
        assertTrue(shell.contains("event.key == Key.Comma"))
    }

    @Test
    fun `desktop main page delegates chrome to commercial shell and retains its feature host`() {
        val sourceRoot = Path.of("src/main/kotlin/com/androidperformancestudio/desktop")
        val desktopMain = Files.readString(sourceRoot.resolve("DesktopAppMainPage.kt"))
        val shell = Files.readString(sourceRoot.resolve("shell/CommercialAppShell.kt"))

        assertTrue(desktopMain.contains("CommercialAppShell("))
        assertTrue(desktopMain.contains("navigator.retainedDestinations.forEach"))
        assertTrue(shell.contains("StudioTopBar("))
        assertTrue(shell.contains("StudioNavigationRail("))
        assertTrue(shell.contains("StudioStatusBar("))
        assertTrue(shell.contains("StudioCommandPalette("))
        assertTrue(shell.contains("navigator::open"))
        assertFalse(shell.contains("UniversalProfilerModel"))
        assertFalse(shell.contains("UniversalTimelineEvent"))
    }
}
