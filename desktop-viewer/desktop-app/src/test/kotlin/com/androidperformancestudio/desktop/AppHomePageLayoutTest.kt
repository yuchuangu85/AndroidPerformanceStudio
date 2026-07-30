package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppHomePageLayoutTest {
    @Test
    fun `home cards use a compact four-column grid`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/AppHomePage.kt"))

        assertEquals(4, HOME_GRID_COLUMN_COUNT)
        assertTrue(HOME_CARD_HEIGHT_DP < 220)
        assertEquals(18, HOME_ITEM_TITLE_FONT_SIZE_SP)
        assertTrue(source.contains("entries.chunked(HOME_GRID_COLUMN_COUNT)"))
        assertTrue(source.contains("height(HOME_CARD_HEIGHT_DP.dp)"))
        assertTrue(source.contains("fontSize = HOME_ITEM_TITLE_FONT_SIZE_SP.sp"))
    }
}
