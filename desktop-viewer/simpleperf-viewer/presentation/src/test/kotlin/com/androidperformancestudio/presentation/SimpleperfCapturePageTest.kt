package com.androidperformancestudio.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
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

        assertTrue(page.indexOf("CaptureControls(") < page.indexOf("AdvancedCaptureParameters("))
        assertTrue(page.contains("verticalScroll(rememberScrollState())"))
    }
}
