package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppHomePageLayoutTest {
    @Test
    fun `home cards use a compact four-column grid`() {
        val source = Files.readString(Path.of("src/main/kotlin/dev/agentperf/desktop/AppHomePage.kt"))

        assertEquals(4, HOME_GRID_COLUMN_COUNT)
        assertTrue(HOME_CARD_HEIGHT_DP < 220)
        assertTrue(source.contains("entries.chunked(HOME_GRID_COLUMN_COUNT)"))
        assertTrue(source.contains("height(HOME_CARD_HEIGHT_DP.dp)"))
    }
}
