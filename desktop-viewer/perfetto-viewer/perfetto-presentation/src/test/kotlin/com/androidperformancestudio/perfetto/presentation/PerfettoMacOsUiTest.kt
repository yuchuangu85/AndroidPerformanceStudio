package com.androidperformancestudio.perfetto.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class PerfettoMacOsUiTest {
    @Test
    fun `compact primitives use inspector dimensions and theme roles`() {
        val path =
            Path.of(
                "src/main/kotlin/com/androidperformancestudio/perfetto/presentation/PerfettoMacOsUi.kt",
            )

        assertTrue(Files.exists(path), "PerfettoMacOsUi.kt must define the shared compact controls")
        val source = Files.readString(path)
        val workspacePanel =
            source
                .substringAfter("fun PerfettoWorkspacePanel(")
                .substringBefore("fun PerfettoCompactTextField(")
        assertTrue(workspacePanel.contains("HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)"))
        assertTrue(workspacePanel.contains("background(MaterialTheme.colorScheme.surface)"))
        assertTrue(!workspacePanel.contains("RoundedCornerShape"))
        assertTrue(!workspacePanel.contains(".border("))
        assertTrue(source.contains("RoundedCornerShape(4.dp)"))
        assertTrue(source.contains(".border(1.dp, MaterialTheme.colorScheme.primary"))
        assertTrue(source.contains(".height(24.dp)"))
        assertTrue(source.contains("fontSize = ViewerTypography.secondary.fontSize"))
        assertTrue(source.contains("MaterialTheme.colorScheme.primary"))
        assertTrue(source.contains("MaterialTheme.colorScheme.onPrimary"))
    }
}
