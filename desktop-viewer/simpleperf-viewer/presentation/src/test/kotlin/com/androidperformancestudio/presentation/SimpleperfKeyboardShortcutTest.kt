package com.androidperformancestudio.presentation

import androidx.compose.ui.input.key.Key
import com.androidperformancestudio.visualization.NavigationAction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimpleperfKeyboardShortcutTest {
    @Test
    fun `maps wasd keys to simpleperf timeline navigation`() {
        assertEquals(NavigationAction.ZOOM_IN, simpleperfNavigationAction(Key.W))
        assertEquals(NavigationAction.ZOOM_OUT, simpleperfNavigationAction(Key.S))
        assertEquals(NavigationAction.PAN_LEFT, simpleperfNavigationAction(Key.A))
        assertEquals(NavigationAction.PAN_RIGHT, simpleperfNavigationAction(Key.D))
        assertNull(simpleperfNavigationAction(Key.Spacebar))
    }

    @Test
    fun `timeline requests focus and handles shortcuts above child controls`() {
        val source =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/presentation/ReportPage.kt"),
            )
        val timeline =
            source
                .substringAfter("private fun TimelineReport(")
                .substringBefore("private fun NavigationButtons(")

        assertTrue(timeline.contains("focusRequester(shortcutFocusRequester)"))
        assertTrue(timeline.contains("onPreviewKeyEvent"))
        assertTrue(timeline.contains("shortcutFocusRequester.requestFocus()"))
    }
}
