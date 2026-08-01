package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiAnalysisEntryVisibilityTest {
    @Test
    fun `AI analysis entry is enabled with an explicit payload preflight`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"),
        )

        assertTrue(source.contains("internal const val AI_ANALYSIS_ENTRY_VISIBLE = true"))
        assertTrue(source.contains("if (AI_ANALYSIS_ENTRY_VISIBLE) {"))
        assertTrue(source.contains("Performance data only (do not upload source snippets)"))
    }
}
