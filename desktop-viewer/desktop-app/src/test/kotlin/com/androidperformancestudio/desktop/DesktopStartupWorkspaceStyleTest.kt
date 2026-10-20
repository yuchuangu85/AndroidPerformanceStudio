package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DesktopStartupWorkspaceStyleTest {
    private val sourceRoot = Path.of("src/main/kotlin/com/androidperformancestudio/desktop")

    @Test
    fun `desktop entry pages omit the decorative workspace banner`() {
        listOf(
            "AppHomePage.kt",
            "DesktopAppSettingsPage.kt",
            "SourceWorkspacesPage.kt",
        ).forEach { file ->
            val source = Files.readString(sourceRoot.resolve(file))
            assertFalse(source.contains("StudioWorkspaceHeader("), file)
            assertFalse(source.contains("Brush.horizontalGradient("), file)
            assertFalse(source.contains("ViewerTheme("), file)
        }
    }

    @Test
    fun `home keeps both responsive dashboard branches without a promotional banner`() {
        val source = Files.readString(sourceRoot.resolve("AppHomePage.kt"))
        assertFalse(source.contains("StudioWorkspaceHeader("))
        assertTrue(source.contains("if (maxWidth.value >= HOME_TWO_COLUMN_MIN_WIDTH_DP)"))
        assertTrue(source.contains(".padding(StudioTokens.compactContentPadding)"))
        assertTrue(source.contains("color = colors.workspace"))
        assertFalse(source.contains("HomeHero("))
        assertFalse(source.contains("Res.string.home_hero_subtitle"))
        assertTrue(source.contains("tone = colors.info"))
        assertTrue(source.contains("tone = colors.accent"))
    }

    @Test
    fun `settings retains sidebar theme context and persistence feedback`() {
        val source = Files.readString(sourceRoot.resolve("DesktopAppSettingsPage.kt"))
        assertFalse(source.contains("StudioWorkspaceHeader("))
        assertTrue(source.contains("ProvideViewerThemeContext(viewerThemeContext)"))
        assertTrue(source.contains("SettingsSidebar("))
        assertTrue(source.contains("onPageSelected = onPageSelected"))
        assertTrue(source.contains("if (persistenceErrorPage != null)"))
        assertTrue(source.contains("Modifier.weight(1f).padding(StudioTokens.compactContentPadding)"))
    }

    @Test
    fun `source workspace leaves the native toolbar and resizable source workflow intact`() {
        val source = Files.readString(sourceRoot.resolve("SourceWorkspacesPage.kt"))
        assertFalse(source.contains("StudioWorkspaceHeader("))
        assertFalse(source.contains("HomeButton("))
        assertTrue(source.contains(".height(HEADER_TOOL_BAR_HEIGHT)"))
        assertTrue(source.contains("onClick = onOpenAiSettings"))
        assertTrue(source.contains("Res.string.source_add_local"))
        assertTrue(source.contains("SourceWorkspacePaneLayout.dragWorkspaces("))
        assertTrue(source.contains("SourceWorkspacePaneLayout.dragFiles("))
        assertTrue(source.contains("runtime.service.read(location)"))
        assertTrue(source.contains("pageError?.let"))
    }
}
