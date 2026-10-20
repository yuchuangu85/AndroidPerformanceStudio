package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfilerThemeSupportTest {
    @Test
    fun `theme preference resolves light dark and system modes`() {
        assertFalse(ApplicationThemePreference.LIGHT.resolveDark(systemDark = true))
        assertTrue(ApplicationThemePreference.DARK.resolveDark(systemDark = false))
        assertFalse(ApplicationThemePreference.SYSTEM.resolveDark(systemDark = false))
        assertTrue(ApplicationThemePreference.SYSTEM.resolveDark(systemDark = true))
    }

    @Test
    fun `unified shell paints an adaptive background around every profiler`() {
        val shell = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppMainPage.kt"))
        val commercialShell =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/shell/CommercialAppShell.kt"),
            )

        assertTrue(shell.contains("ViewerTheme("))
        val viewerTheme =
            Files.readString(
                Path.of("../ui-components/src/main/kotlin/com/androidperformancestudio/ui/ViewerTheme.kt"),
            )
        assertTrue(viewerTheme.contains("private val ViewerMaterialTypography"))
        assertTrue(viewerTheme.contains("headlineMedium = ViewerTypography.pageTitle"))
        assertTrue(viewerTheme.contains("private val ViewerShapes"))
        assertFalse(Files.exists(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/CompactDesktopTheme.kt")))
        assertTrue(shell.contains("LocalMinimumInteractiveComponentSize provides 32.dp"))
        assertTrue(shell.contains("CommercialAppShell("))
        assertTrue(commercialShell.contains("background(colors.toolbar)"))
        assertTrue(commercialShell.contains("background(colors.toolbar)"))
        assertTrue(commercialShell.contains("StudioTokens.navigationItemHeight"))
    }

    @Test
    fun `feature pages inherit the unified shell viewer theme`() {
        val shell = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppMainPage.kt"))

        assertEquals(1, Regex("ViewerTheme\\(").findAll(shell).count())
        featurePageSources().forEach { source ->
            assertFalse(
                Files.readString(source).contains("ViewerTheme("),
                "$source must inherit ViewerTheme from DesktopAppMainPage",
            )
        }
    }

    @Test
    fun `profiler workspaces retain the compact inspector toolbar boundary`() {
        profilerWorkspaceSources().forEach { source ->
            val content = Files.readString(source)
            assertTrue(content.contains("HeaderToolbar("), "$source must use the shared header toolbar")
        }
    }

    @Test
    fun `profiler presentation layers use theme roles instead of fixed light colors`() {
        profilerPresentationSources().forEach { source ->
            val content = Files.readString(source)
            assertTrue(
                content.contains("MaterialTheme.colorScheme") || content.contains("LocalViewerColors.current"),
                "$source must consume active Material or shared Viewer theme roles",
            )
            assertFalse(
                FIXED_HEX_COLOR.containsMatchIn(content),
                "$source must not hard-code a light-only color",
            )
        }
    }

    @Test
    fun `every feature uses the shared home button style`() {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        val sharedButton =
            Files.readString(
                desktopViewer.resolve(
                    "ui-components/src/main/kotlin/" +
                        "com/androidperformancestudio/ui/button/HomeButton.kt",
                ),
            )
        val cpuProfiler =
            Files.readString(
                desktopViewer.resolve(
                    "simpleperf-viewer/presentation/src/main/kotlin/" +
                        "com/androidperformancestudio/presentation/DeviceTargetPage.kt",
                ),
            )
        val viewerTheme =
            Files.readString(
                desktopViewer.resolve(
                    "ui-components/src/main/kotlin/" +
                        "com/androidperformancestudio/ui/ViewerTheme.kt",
                ),
            )
        val settingsButton =
            Files.readString(
                desktopViewer.resolve(
                    "ui-components/src/main/kotlin/" +
                        "com/androidperformancestudio/ui/button/SettingButton.kt",
                ),
            )
        val perfetto =
            Files.readString(
                desktopViewer.resolve(
                    "perfetto-viewer/perfetto-app/src/main/kotlin/" +
                        "com/androidperformancestudio/perfetto/app/PerfettoMainPage.kt",
                ),
            )

        assertTrue(settingsButton.contains(".width(28.dp)"))
        assertTrue(settingsButton.contains(".height(ViewerDimensions.buttonHeight)"))
        assertTrue(settingsButton.contains("RoundedCornerShape(ViewerDimensions.controlRadius)"))
        assertTrue(viewerTheme.contains("buttonHeight = 28.dp"))
        assertTrue(viewerTheme.contains("controlRadius = 6.dp"))
        assertTrue(cpuProfiler.contains(".background(style.panel)"))
        assertTrue(cpuProfiler.contains(".border("))
        assertSharedHomeButtonStyle(sharedButton)
        assertTrue(perfetto.contains("import com.androidperformancestudio.ui.HeaderToolbar"))
        assertTrue(perfetto.contains("HeaderToolbar("))

        profilerHomeButtonConsumers().forEach { source ->
            val content = Files.readString(source)
            assertTrue(
                content.contains("HomeButton(") || content.contains("HeaderToolbar("),
                "$source must use the shared home button",
            )
        }
    }

    @Test
    fun `only the active retained destination installs a window menu bar`() {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        val shell =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppMainPage.kt"),
            )
        val sharedMenuBar =
            Files.readString(
                desktopViewer.resolve(
                    "ui-components/src/main/kotlin/com/androidperformancestudio/ui/ActiveWindowMenuBar.kt",
                ),
            )

        assertTrue(shell.contains("WindowMenuBarHost("))
        assertTrue(shell.contains("native_file_menu"))
        assertTrue(shell.contains("native_method_recording_menu"))
        assertTrue(shell.indexOf("    WindowMenuBarHost(") < shell.indexOf("    ViewerTheme("))
        assertTrue(shell.contains("LocalWindowMenuBarActive provides active"))
        assertTrue(sharedMenuBar.contains("WindowMenuBarHost("))
        assertTrue(sharedMenuBar.contains("preloadedMenuKey"))
        assertTrue(sharedMenuBar.contains("onMenuBarReady"))
        assertTrue(sharedMenuBar.contains("notifyNativeMenuBarReady()"))
        assertTrue(sharedMenuBar.contains("if (!LocalWindowMenuBarActive.current) return"))
        assertTrue(sharedMenuBar.contains("SideEffect {"))
        // Compose's own MenuBar control owns the window bar: it creates the JMenuBar once for its
        // composition and only clears it when the window is disposed. Composing a menu bar per page
        // cleared every menu on navigation, because disposing a menu composition calls
        // SwingApplier.onClear() -> JMenuBar.removeAll(); that is what made the macOS screen menu bar
        // flash or stay empty.
        assertTrue(sharedMenuBar.contains("import androidx.compose.ui.window.MenuBar"))
        assertTrue(sharedMenuBar.contains("MenuBar {"))
        // A page without a feature menu must not leave the native bar with zero menus.
        assertTrue(sharedMenuBar.contains("fallbackMenu"))
        assertTrue(shell.contains("fallbackMenu = {"))
        assertTrue(sharedMenuBar.contains("pageContent.invoke(scope)"))
        // Pages only declare menus; the host renders them into positional JMenus, so switching pages
        // updates titles/items in place instead of deleting every top-level menu.
        assertTrue(sharedMenuBar.contains("scope.menus.forEach { spec -> StableTopLevelMenu(spec) }"))
        assertFalse(sharedMenuBar.contains("JMenuBar()"))
        assertFalse(sharedMenuBar.contains("setContent("))
        assertFalse(sharedMenuBar.contains("window.jMenuBar = null"))
        assertFalse(sharedMenuBar.contains("val ownsMenuBar"))
        profilerWindowMenuSources().forEach { source ->
            val content = Files.readString(source)
            assertTrue(content.contains("ActiveWindowMenuBar {"), "$source must gate its window menu bar")
            assertFalse(content.contains("import androidx.compose.ui.window.MenuBar"))
        }
    }

    private fun assertSharedHomeButtonStyle(source: String) {
        assertTrue(source.contains(".width(28.dp)"))
        assertTrue(source.contains(".height(ViewerDimensions.buttonHeight)"))
        assertTrue(source.contains("RoundedCornerShape(ViewerDimensions.controlRadius)"))
        assertTrue(source.contains(".background("))
        assertTrue(source.contains(".border("))
    }

    private fun featurePageSources(): List<Path> {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        return listOf(
            "layout-inspector/presentation/src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt",
            "simpleperf-viewer/presentation/src/main/kotlin/com/androidperformancestudio/presentation/HomeScreen.kt",
            "perfetto-viewer/perfetto-app/src/main/kotlin/com/androidperformancestudio/perfetto/app/PerfettoMainPage.kt",
            "memory-profiler/memory-app/src/main/kotlin/com/androidperformancestudio/memory/app/MemoryProfilerMainPage.kt",
            "frame-profiler/frame-app/src/main/kotlin/com/androidperformancestudio/frame/app/FrameProfilerMainPage.kt",
            "startup-profiler/startup-app/src/main/kotlin/com/androidperformancestudio/startup/app/StartupProfilerMainPage.kt",
            "battery-profiler/battery-app/src/main/kotlin/com/androidperformancestudio/battery/app/BatteryProfilerMainPage.kt",
            "network-profiler/network-app/src/main/kotlin/com/androidperformancestudio/network/app/NetworkProfilerMainPage.kt",
            "gpu-inspector-integration/gpu-integration-app/src/main/kotlin/com/androidperformancestudio/gpu/app/GpuIntegrationMainPage.kt",
            "benchmark-regression/benchmark-app/src/main/kotlin/com/androidperformancestudio/benchmark/app/BenchmarkRegressionMainPage.kt",
            "simpleperf-viewer/method-recording-app/src/main/kotlin/com/androidperformancestudio/methodrecording/app/MethodRecordingMainPage.kt",
        ).map(desktopViewer::resolve)
    }

    private fun profilerHomeButtonConsumers(): List<Path> {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        return listOf(
            "layout-inspector/presentation/src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt",
            "memory-profiler/memory-app/src/main/kotlin/com/androidperformancestudio/memory/app/MemoryProfilerMainPage.kt",
            "frame-profiler/frame-app/src/main/kotlin/com/androidperformancestudio/frame/app/FrameProfilerMainPage.kt",
            "startup-profiler/startup-app/src/main/kotlin/com/androidperformancestudio/startup/app/StartupProfilerMainPage.kt",
            "battery-profiler/battery-app/src/main/kotlin/com/androidperformancestudio/battery/app/BatteryProfilerMainPage.kt",
            "network-profiler/network-app/src/main/kotlin/com/androidperformancestudio/network/app/NetworkProfilerMainPage.kt",
            "gpu-inspector-integration/gpu-integration-app/src/main/kotlin/com/androidperformancestudio/gpu/app/GpuIntegrationMainPage.kt",
            "benchmark-regression/benchmark-app/src/main/kotlin/com/androidperformancestudio/benchmark/app/BenchmarkRegressionMainPage.kt",
            "simpleperf-viewer/method-recording-app/src/main/kotlin/com/androidperformancestudio/methodrecording/app/MethodRecordingMainPage.kt",
        ).map(desktopViewer::resolve)
    }

    private fun profilerPresentationSources(): List<Path> {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        return listOf(
            "memory-profiler/presentation/src/main/kotlin/com/androidperformancestudio/memory/presentation/MemoryProfilerScreen.kt",
            "frame-profiler/presentation/src/main/kotlin/com/androidperformancestudio/frame/presentation/FrameProfilerScreen.kt",
            "startup-profiler/presentation/src/main/kotlin/com/androidperformancestudio/startup/presentation/StartupProfilerScreen.kt",
            "battery-profiler/battery-app/src/main/kotlin/com/androidperformancestudio/battery/presentation/BatteryProfilerScreen.kt",
            "network-profiler/presentation/src/main/kotlin/com/androidperformancestudio/network/presentation/NetworkProfilerScreen.kt",
            "gpu-inspector-integration/presentation/src/main/kotlin/com/androidperformancestudio/gpu/presentation/GpuIntegrationScreen.kt",
            "benchmark-regression/presentation/src/main/kotlin/com/androidperformancestudio/benchmark/presentation/BenchmarkReportsScreen.kt",
            "benchmark-regression/presentation/src/main/kotlin/com/androidperformancestudio/benchmark/presentation/BenchmarkComparisonTable.kt",
            "benchmark-regression/presentation/src/main/kotlin/com/androidperformancestudio/benchmark/presentation/BenchmarkTrendPanel.kt",
            "benchmark-regression/presentation/src/main/kotlin/com/androidperformancestudio/benchmark/presentation/BenchmarkCompareSettingsDialog.kt",
        ).map(desktopViewer::resolve)
    }

    private fun profilerWindowMenuSources(): List<Path> {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        return listOf(
            "layout-inspector/presentation/src/main/kotlin/com/androidperformancestudio/desktop/NativeViewerMenuBar.kt",
            "simpleperf-viewer/app-desktop/src/main/kotlin/com/androidperformancestudio/desktop/SimpleperfFileMenu.kt",
            "simpleperf-viewer/method-recording-app/src/main/kotlin/com/androidperformancestudio/methodrecording/app/MethodRecordingMainPage.kt",
            "perfetto-viewer/perfetto-app/src/main/kotlin/com/androidperformancestudio/perfetto/app/PerfettoFileMenu.kt",
            "memory-profiler/memory-app/src/main/kotlin/com/androidperformancestudio/memory/app/MemoryProfilerFileMenu.kt",
            "frame-profiler/frame-app/src/main/kotlin/com/androidperformancestudio/frame/app/FrameProfilerFileMenu.kt",
            "startup-profiler/startup-app/src/main/kotlin/com/androidperformancestudio/startup/app/StartupProfilerFileMenu.kt",
            "battery-profiler/battery-app/src/main/kotlin/com/androidperformancestudio/battery/app/BatteryProfilerMenu.kt",
        ).map(desktopViewer::resolve)
    }

    private fun profilerWorkspaceSources(): List<Path> {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        return listOf(
            "memory-profiler/memory-app/src/main/kotlin/com/androidperformancestudio/memory/app/MemoryProfilerMainPage.kt",
            "frame-profiler/frame-app/src/main/kotlin/com/androidperformancestudio/frame/app/FrameProfilerMainPage.kt",
            "startup-profiler/startup-app/src/main/kotlin/com/androidperformancestudio/startup/app/StartupProfilerMainPage.kt",
            "battery-profiler/battery-app/src/main/kotlin/com/androidperformancestudio/battery/app/BatteryProfilerMainPage.kt",
            "network-profiler/network-app/src/main/kotlin/com/androidperformancestudio/network/app/NetworkProfilerMainPage.kt",
            "gpu-inspector-integration/gpu-integration-app/src/main/kotlin/com/androidperformancestudio/gpu/app/GpuIntegrationMainPage.kt",
            "benchmark-regression/benchmark-app/src/main/kotlin/com/androidperformancestudio/benchmark/app/BenchmarkRegressionMainPage.kt",
        ).map(desktopViewer::resolve)
    }

    private companion object {
        val FIXED_HEX_COLOR = Regex("Color\\(0x[0-9A-Fa-f]+")
    }
}
