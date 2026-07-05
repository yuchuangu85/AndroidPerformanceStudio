package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class HeaderMenuPlacementTest {
    @Test
    fun `header leaves actions and advanced commands to the native menu bar`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt"),
        )
        val header = source
            .substringAfter("private fun Header(")
            .substringBefore("private fun ExportResultDialog(")

        assertFalse(header.contains("ViewerActionDropdown("))
        assertFalse(header.contains("AdvancedMenu("))
    }
}
