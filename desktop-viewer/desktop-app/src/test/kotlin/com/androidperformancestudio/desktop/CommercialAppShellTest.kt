package com.androidperformancestudio.desktop

import com.androidperformancestudio.desktop.shell.WorkspaceContext
import com.androidperformancestudio.desktop.shell.navigationIconResource
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommercialAppShellTest {
    @Test
    fun `global and feature status bars keep text selection scoped to their footer`() {
        val footers =
            mapOf(
                "src/main/kotlin/com/androidperformancestudio/desktop/shell/CommercialAppShell.kt" to "StudioStatusBar",
                "../layout-inspector/presentation/src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt" to
                    "LayoutInspectorStatusBar",
                "../perfetto-viewer/perfetto-app/src/main/kotlin/com/androidperformancestudio/perfetto/app/PerfettoMainPage.kt" to
                    "PerfettoStatusBar",
                "../memory-profiler/presentation/src/main/kotlin/com/androidperformancestudio/memory/presentation/MemoryProfilerScreen.kt" to
                    "MemoryProfilerStatusBar",
                "../frame-profiler/frame-app/src/main/kotlin/com/androidperformancestudio/frame/app/FrameProfilerMainPage.kt" to
                    "FrameProfilerStatusBar",
                "../startup-profiler/startup-app/src/main/kotlin/com/androidperformancestudio/startup/app/StartupProfilerMainPage.kt" to
                    "StartupProfilerStatusBar",
                "../simpleperf-viewer/method-recording-app/src/main/kotlin/com/androidperformancestudio/methodrecording/app/MethodRecordingMainPage.kt" to
                    "MethodRecordingStatusBar",
                "../simpleperf-viewer/presentation/src/main/kotlin/com/androidperformancestudio/presentation/DeviceTargetPage.kt" to
                    "WorkspaceFooter",
                "../battery-profiler/battery-app/src/main/kotlin/com/androidperformancestudio/battery/app/BatteryProfilerMainPage.kt" to
                    "BatteryProfilerStatusBar",
            )

        footers.forEach { (path, functionName) ->
            val source = Files.readString(Path.of(path))
            val footer = source.substringAfter("private fun $functionName(").substringBefore("\n@Composable")
            assertTrue(source.contains("import androidx.compose.foundation.text.selection.SelectionContainer"))
            assertTrue(Regex("SelectionContainer \\{\\s*Row\\(").containsMatchIn(footer), functionName)
            assertTrue(footer.contains(".height("), "$functionName must retain its fixed height")
        }
    }

    @Test
    fun `desktop window honors the commercial minimum size`() {
        val main = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/Main.kt"))

        assertTrue(main.contains("window.minimumSize = java.awt.Dimension(1280, 720)"))
    }

    @Test
    fun `navigation rail collapse is purely user-controlled with no width fallback`() {
        val shell =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/shell/CommercialAppShell.kt"),
            )
        // The auto-collapse helpers and breakpoint are gone; the rail reads the persisted state directly.
        assertFalse(shell.contains("NAVIGATION_COLLAPSE_BREAKPOINT_DP"))
        assertFalse(shell.contains("navigationRailCollapsedFor"))
        assertFalse(shell.contains("resolvedNavigationRailCollapsed"))
        assertTrue(shell.contains("val collapsed = navigationRailCollapsed"))
    }

    @Test
    fun `every destination projects a distinct navigation icon`() {
        val icons = AppDestination.entries.map { destination -> destination.navigationIconResource }

        assertEquals(AppDestination.entries.size, icons.toSet().size)
    }

    @Test
    fun `navigation rail draws an icon per item and keeps only icons when collapsed`() {
        val shell =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/shell/CommercialAppShell.kt"),
            )

        assertTrue(shell.contains("val icon: DrawableResource"))
        assertTrue(shell.contains("icon = it.navigationIconResource"))
        // A single shared row renders the icon, so collapsing cannot shift it horizontally.
        assertEquals(1, Regex("painterResource\\(icon\\)").findAll(shell).count())
        assertTrue(shell.contains("NavigationRailEntry("))
        assertTrue(shell.contains("if (!collapsed) {"))
        assertTrue(
            shell.contains(
                "PaddingValues(horizontal = if (collapsed) 0.dp else StudioTokens.navigationItemHorizontalPadding)",
            ),
        )
        // A single shared entry row centres its icon while the rail is collapsed.
        assertTrue(
            shell.contains(
                "if (collapsed) {\n                    Arrangement.Center",
            ),
        )
        assertEquals(1, shell.split("Arrangement.Center").size - 1)
        assertFalse(shell.contains("item.label.take(1)"))
    }

    @Test
    fun `navigation icon drawables ship with the shared UI resources`() {
        val drawables = Path.of("../ui-components/src/main/composeResources/drawable")
        val expected =
            listOf(
                "ic_nav_home",
                "ic_nav_source_workspaces",
                "ic_nav_layout_inspector",
                "ic_nav_cpu_profiler",
                "ic_nav_trace_analyzer",
                "ic_nav_memory_profiler",
                "ic_nav_frame_profiler",
                "ic_nav_startup_profiler",
                "ic_nav_battery",
                "ic_nav_gpu_inspector",
                "ic_nav_benchmark",
                "ic_nav_method_recording",
                "ic_nav_rail_collapse",
                "ic_nav_rail_expand",
            )

        expected.forEach { name ->
            assertTrue(Files.exists(drawables.resolve("$name.svg")), "missing navigation icon: $name")
            assertTrue(
                Files.readString(drawables.resolve("$name.svg")).startsWith("<svg"),
                "navigation icon must be an SVG vector: $name",
            )
        }
    }

    @Test
    fun `navigation rail collapse is a persisted preference with an in-rail toggle and a settings switch`() {
        val shell =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/shell/CommercialAppShell.kt"),
            )
        val main =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppMainPage.kt"),
            )
        val settings =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppSettingsPage.kt"),
            )

        // The shell exposes a toggle callback and renders the toggle with the rail icons.
        assertTrue(shell.contains("navigationRailCollapsed: Boolean"))
        assertTrue(shell.contains("onToggleNavigationRailCollapsed: () -> Unit"))
        assertTrue(shell.contains("onToggleCollapsed = onToggleNavigationRailCollapsed"))
        assertTrue(shell.contains("ic_nav_rail_collapse"))
        assertTrue(shell.contains("ic_nav_rail_expand"))
        // The rail width animates between collapsed and expanded.
        assertTrue(shell.contains("animateDpAsState"))
        // The labels reuse the existing collapse/expand strings.
        assertTrue(shell.contains("Res.string.expand_navigation"))
        assertTrue(shell.contains("Res.string.collapse_navigation"))
        // DesktopAppMainPage wires the toggle to flip the persisted setting.
        assertTrue(main.contains("navigationRailCollapsed = applicationSettings.navigationRailCollapsed"))
        assertTrue(main.contains("onToggleNavigationRailCollapsed"))
        assertTrue(main.contains("navigationRailCollapsed = !applicationSettings.navigationRailCollapsed"))
        // Settings -> General keeps its switch as the second entry point (same field).
        assertTrue(settings.contains("Res.string.show_sidebar_titles"))
        assertTrue(settings.contains("checked = !settings.navigationRailCollapsed"))
        assertTrue(settings.contains("navigationRailCollapsed = !showSidebarTitles"))
    }

    @Test
    fun `primary rail lists source workspaces`() {
        val shell =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/shell/CommercialAppShell.kt"),
            )
        // The rail is driven by the shared, user-customizable module order (Home pinned first),
        // not a hardcoded destination list. Source Workspaces is part of the default module order;
        // Method Recording never is (it stays command-palette only).
        assertTrue(shell.contains("listOf(AppDestination.HOME) + moduleOrder"))
        assertTrue(DEFAULT_MODULE_ORDER.contains(AppDestination.SOURCE_WORKSPACES))
        assertFalse(DEFAULT_MODULE_ORDER.contains(AppDestination.METHOD_RECORDING))
    }

    @Test
    fun `collapsed rail reveals the entry title on hover`() {
        val shell =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/shell/CommercialAppShell.kt"),
            )

        assertTrue(shell.contains("private fun RailTooltip(label: String)"))
        assertTrue(shell.contains("TooltipArea(tooltip = { RailTooltip(label) }"))
        // Only the icon-only state needs the hover title.
        val entry = shell.substringAfter("private fun NavigationRailEntry(").substringBefore("private fun RailEntryButton(")
        assertTrue(entry.contains("if (collapsed) {"))
    }

    @Test
    fun `home rail label uses the short home string while the window title keeps the product name`() {
        val shell =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/shell/CommercialAppShell.kt"),
            )

        assertNotEquals(AppDestination.HOME.titleResource, AppDestination.HOME.navigationLabelResource)
        assertEquals(
            AppDestination.LAYOUT_INSPECTOR.titleResource,
            AppDestination.LAYOUT_INSPECTOR.navigationLabelResource,
        )
        assertTrue(shell.contains("it.navigationLabelResource"))
        assertTrue(shell.contains("navigator.destination.titleResource"))
    }

    @Test
    fun `navigation labels have Chinese translations for every rail destination`() {
        val chinese = Files.readString(Path.of("src/main/composeResources/values-zh/strings.xml"))
        val expectedLabels =
            mapOf(
                "home" to "首页",
                "layout_inspector" to "布局检查器",
                "cpu_profiler" to "CPU 性能分析",
                "trace_analyzer" to "系统跟踪分析",
                "memory_profiler" to "内存分析",
                "frame_profiler" to "帧率分析",
                "startup_profiler" to "启动分析",
                "battery_network" to "电量与网络",
                "gpu_inspector" to "GPU 分析",
                "benchmark_regression" to "基准回归",
                "source_workspaces" to "源码工作区",
                "method_recording" to "方法录制",
                "settings" to "设置",
            )

        expectedLabels.forEach { (resourceName, label) ->
            assertTrue(
                chinese.contains("<string name=\"$resourceName\">$label</string>"),
                "Missing Chinese navigation label for $resourceName",
            )
        }
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
        assertFalse(shell.contains("StudioTopBar("))
        assertTrue(shell.contains("StudioNavigationRail("))
        assertTrue(shell.contains("NavigationRailEntry("))
        assertTrue(shell.contains("AppDestination.SETTINGS.navigationIconResource"))
        assertTrue(shell.contains("settingsSelected"))
        assertTrue(shell.contains("onClick = onOpenSettings"))
        assertTrue(shell.contains("height(StudioTokens.borderWidth)"))
        assertTrue(shell.contains("StudioStatusBar("))
        assertTrue(shell.contains("StudioCommandPalette("))
        // SCREENCAST is dispatched to its own OS window; every other destination still routes through
        // navigator.open inside the rail/command-palette lambdas.
        assertTrue(shell.contains("navigator.open(destination)"))
        assertTrue(shell.contains("onOpenScreencastWindow"))
        assertFalse(shell.contains("UniversalProfilerModel"))
        assertFalse(shell.contains("UniversalTimelineEvent"))
    }
}
