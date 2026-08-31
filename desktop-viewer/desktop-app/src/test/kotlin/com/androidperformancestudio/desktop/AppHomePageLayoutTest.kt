package com.androidperformancestudio.desktop

import androidx.compose.ui.graphics.Color
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppHomePageLayoutTest {
    @Test
    fun `home cards use a compact adaptive macOS grid`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/AppHomePage.kt"))

        assertEquals(Color(0xFFECECEC), HOME_BACKGROUND_LIGHT)
        assertEquals(Color(0xFF1E1E1E), HOME_BACKGROUND_DARK)
        assertEquals(4, homeGridColumnCount(HOME_MAX_CONTENT_WIDTH_DP))
        assertEquals(3, homeGridColumnCount(900))
        assertEquals(2, homeGridColumnCount(600))
        assertEquals(1, homeGridColumnCount(420))
        assertTrue(HOME_CARD_HEIGHT_DP < 220)
        assertEquals(17, HOME_ITEM_TITLE_FONT_SIZE_SP)
        assertEquals(14, HOME_CARD_CORNER_RADIUS_DP)
        assertTrue(source.contains("BoxWithConstraints(modifier = Modifier.fillMaxWidth())"))
        assertTrue(source.contains("entries.chunked(columnCount)"))
        assertTrue(source.contains("height(HOME_CARD_HEIGHT_DP.dp)"))
        assertTrue(source.contains("shadow("))
        assertTrue(source.contains("fontSize = HOME_ITEM_TITLE_FONT_SIZE_SP.sp"))
        assertTrue(source.contains("text = entry.title.take(1)"))
        assertTrue(source.contains("text = \"›\""))
    }

    @Test
    fun `source workspace is the last home entry`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/AppHomePage.kt"))
        val entries = source.substringAfter("val entries =").substringBefore("val colors =")

        val lastEntry = entries.substring(entries.lastIndexOf("HomeFeatureEntry("))
        assertTrue(lastEntry.contains("onClick = onOpenSourceWorkspaces"))
    }
}
