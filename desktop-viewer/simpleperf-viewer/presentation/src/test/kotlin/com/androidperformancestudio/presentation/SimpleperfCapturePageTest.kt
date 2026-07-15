package com.androidperformancestudio.presentation

import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleperfCapturePageTest {
    @Test
    fun `capture page starts data collection directly without manual commands`() {
        val source =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/presentation/CapturePage.kt"),
            )

        assertTrue(source.contains("Text(\"Get data\")"))
        assertTrue(source.contains("onClick = onStartCapture"))
        assertFalse(source.contains("Command preview"))
        assertFalse(source.contains("Copy command"))
        assertFalse(source.contains("copyToClipboard"))
    }

    @Test
    fun `capture action remains visible above optional advanced parameters`() {
        val source =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/presentation/CapturePage.kt"),
            )
        val page =
            source
                .substringAfter("internal fun CapturePage(")
                .substringBefore("private fun AdvancedCaptureParameters(")

        assertTrue(page.indexOf("CaptureControls(") < page.indexOf("ResponsiveCaptureConfiguration("))
        assertTrue(page.contains("verticalScroll(rememberScrollState())"))
    }

    @Test
    fun `capture configuration uses horizontal panels at normal desktop widths`() {
        assertEquals(CaptureConfigurationLayout.HORIZONTAL, captureConfigurationLayout(900.dp))
        assertEquals(CaptureConfigurationLayout.HORIZONTAL, captureConfigurationLayout(1200.dp))
        assertEquals(CaptureConfigurationLayout.STACKED, captureConfigurationLayout(899.dp))
    }

    @Test
    fun `capture configuration keeps a compact responsive hierarchy`() {
        val source =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/presentation/CapturePage.kt"),
            )
        val page =
            source
                .substringAfter("internal fun CapturePage(")
                .substringBefore("private fun AdvancedCaptureParameters(")

        assertTrue(page.contains("MaterialTheme.typography.titleLarge"))
        assertTrue(page.contains("ResponsiveCaptureConfiguration("))
        assertTrue(source.contains("BoxWithConstraints"))
        assertFalse(source.contains("private fun CaptureDetails("))
    }
}
