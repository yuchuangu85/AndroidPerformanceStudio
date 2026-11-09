package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DesktopCardSurfaceTest {
    @Test
    fun `home entry cards retain hover feedback callbacks and fixed layouts`() {
        listOf(
            Triple("QuickActionCard", "action", "HOME_QUICK_ACTION_HEIGHT_DP"),
            Triple("FeatureEntryCard", "entry", "HOME_MODULE_CARD_HEIGHT_DP"),
        ).forEach { (name, item, height) ->
            val card = functionSource("AppHomePage.kt", name)
            assertTrue(card.contains("remember { MutableInteractionSource() }"), name)
            assertTrue(card.contains("interactionSource.collectIsHoveredAsState()"), name)
            assertTrue(card.contains(".height($height.dp)"), name)
            assertTrue(card.contains(".shadow(elevation = if (hovered) 6.dp else 1.dp"), name)
            assertTrue(
                card.contains(".clickable(interactionSource = interactionSource, indication = null, onClick = $item.onClick)"),
                name,
            )
            assertTrue(card.contains("if (hovered) colors.selectedRow else colors.panel"), name)
            assertTrue(card.contains("if (hovered) colors.accent.copy(alpha = 0.42f) else colors.border.copy(alpha = 0.72f)"), name)
            assertTrue(card.contains("verticalAlignment = Alignment.CenterVertically"), name)
            assertTrue(card.contains("maxLines = 2"), name)
        }
        assertTrue(functionSource("AppHomePage.kt", "QuickActionCard").contains("padding(horizontal = 16.dp, vertical = 14.dp)"))
        assertTrue(functionSource("AppHomePage.kt", "FeatureEntryCard").contains("padding(horizontal = 14.dp, vertical = 12.dp)"))
    }

    @Test
    fun `home entry cards share their outer surface without doubling padding`() {
        listOf("QuickActionCard", "FeatureEntryCard").forEach { name ->
            val card = functionSource("AppHomePage.kt", name)
            assertEquals(1, Regex("StudioCard\\(").findAll(card).count(), name)
            val surface = card.substringAfter("StudioCard(").substringBefore(") {")
            assertTrue(surface.contains(".clickable("), name)
            assertTrue(surface.contains("containerColor = if (hovered)"), name)
            assertTrue(surface.contains("borderColor = if (hovered)"), name)
            assertTrue(surface.contains("contentPadding = 0.dp"), name)
            assertTrue(card.contains("RoundedCornerShape(StudioTokens.cardRadius)"), name)
            assertTrue(card.contains("Modifier.fillMaxSize().padding("), name)
            assertFalse(card.contains(".clip(shape)"), name)
            assertFalse(card.contains(".border("), name)
            assertFalse(card.contains(".background(if (hovered)"), name)
        }
    }

    @Test
    fun `home omits the decorative workspace banner`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/AppHomePage.kt"))
        assertFalse(source.contains("StudioWorkspaceHeader("))
        assertFalse(source.contains("HomeHero("))
        assertFalse(source.contains("Brush.horizontalGradient("))
    }

    @Test
    fun `workspace card retains selection progress and independent action callbacks`() {
        val card = functionSource("SourceWorkspacesPage.kt", "WorkspaceCard")
        assertTrue(card.contains("onClick = onSelect"))
        assertTrue(card.contains("borderColor = if (selected) colors.accent else colors.border"))
        assertTrue(
            card.contains(
                "containerColor = if (selected) colors.selectedRow else colors.panel",
            ),
        )
        assertTrue(card.contains("LinearProgressIndicator(progress = { workspace.progress }"))
        assertTrue(card.contains("workspace.localizedMessage(language)"))
        listOf("onRefresh", "onToggleAiUpload", "onRemove").forEach { callback ->
            assertTrue(card.contains("onClick = $callback"), callback)
        }
        assertTrue(card.contains("contentPadding = StudioTokens.compactContentPadding"))
        assertTrue(card.contains("contentSpacing = 4.dp"))
    }

    @Test
    fun `workspace card uses a shared surface and retains material button semantics and text colors`() {
        val card = functionSource("SourceWorkspacesPage.kt", "WorkspaceCard")
        assertEquals(1, Regex("StudioCard\\(").findAll(card).count())
        assertTrue(card.contains(".clickable(role = Role.Button, onClick = onSelect)"))
        assertTrue(card.contains("contentPadding = StudioTokens.compactContentPadding"))
        assertFalse(card.contains("Column("))
        assertTrue(
            card.contains(
                "val contentColor = colors.primaryText",
            ),
        )
        assertEquals(2, Regex("color = contentColor").findAll(card).count())
        assertFalse(card.contains("CardDefaults"))
    }

    @Test
    fun `theme options retain their compact swatches selection colors and click target`() {
        val option = functionSource("DesktopAppSettingsPage.kt", "ThemeColorOption")
        assertTrue(option.contains("modifier.height(38.dp).clickable(onClick = onClick)"))
        assertTrue(
            option.contains(
                "containerColor = if (selected) LocalViewerColors.current.selectedRow else LocalViewerColors.current.panel",
            ),
        )
        assertTrue(option.contains("if (selected) color.color else MaterialTheme.colorScheme.outline"))
        assertTrue(option.contains("Modifier.fillMaxSize().padding(horizontal = 10.dp)"))
        assertTrue(option.contains("Modifier.size(18.dp).background(color.color, CircleShape)"))
        assertTrue(option.contains("fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal"))
        assertTrue(option.contains("if (selected) {"))
    }

    @Test
    fun `theme option surfaces share the card primitive without changing label contrast`() {
        val option = functionSource("DesktopAppSettingsPage.kt", "ThemeColorOption")
        assertEquals(1, Regex("StudioCard\\(").findAll(option).count())
        assertTrue(option.contains("contentPadding = 0.dp"))
        assertTrue(option.contains("borderColor = if (selected) color.color else MaterialTheme.colorScheme.outline"))
        assertTrue(
            option.contains(
                "color = if (selected) LocalViewerColors.current.primaryText else MaterialTheme.colorScheme.onSurfaceVariant",
            ),
        )
        assertFalse(option.contains("Surface("))
        assertFalse(option.contains("BorderStroke("))
    }

    private fun functionSource(
        file: String,
        name: String,
    ): String {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/$file"))
        val signature = "private fun $name("
        assertTrue(source.contains(signature), "missing $name in $file")
        return source.substringAfter(signature).substringBefore("\n@Composable")
    }
}
