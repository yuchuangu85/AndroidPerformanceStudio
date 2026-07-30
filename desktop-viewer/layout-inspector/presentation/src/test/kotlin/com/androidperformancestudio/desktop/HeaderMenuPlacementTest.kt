package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class HeaderMenuPlacementTest {
    @Test
    fun `header leaves actions and file commands to the native menu bar`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"),
        )
        val header = source
            .substringAfter("private fun Header(")
            .substringBefore("private fun ExportResultDialog(")

        assertFalse(header.contains("ViewerActionDropdown("))
        assertFalse(header.contains("FileMenu("))
    }
}
