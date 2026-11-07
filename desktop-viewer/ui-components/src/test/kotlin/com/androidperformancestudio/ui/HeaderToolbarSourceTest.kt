package com.androidperformancestudio.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeaderToolbarSourceTest {
    @Test
    fun `toolbar content receives row scope`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/ui/HeaderToolbar.kt"))

        assertTrue(source.contains("content: @Composable RowScope.() -> Unit"))
        assertTrue(source.contains("modifier: Modifier = Modifier"))
        assertTrue(source.contains("horizontalScroll(rememberScrollState())"))
        assertFalse(source.contains("modifier ?:"))
    }

    @Test
    fun `top-level home and settings buttons stay hidden`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/ui/HeaderToolbar.kt"))

        assertFalse(source.contains("HomeButton("))
        assertFalse(source.contains("SettingsButton("))
        assertFalse(source.contains("back_to_home"))
    }
}
