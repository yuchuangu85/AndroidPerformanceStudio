package com.androidperformancestudio.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopFileDialogsSourceTest {
    @Test
    fun `open chooser preserves accept all unless caller disables it`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/ui/DesktopFileDialogs.kt"))

        assertTrue(source.contains("acceptAllFiles: Boolean = true"))
        assertTrue(source.contains("isAcceptAllFileFilterUsed = acceptAllFiles"))
    }
}
