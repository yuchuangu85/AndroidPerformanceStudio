package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiAnalysisEntryVisibilityTest {
    @Test
    fun `AI analysis entry stays hidden until roadmap work resumes`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/dev/agentperf/desktop/LayoutInspectorMainPage.kt"),
        )

        assertTrue(source.contains("internal const val AI_ANALYSIS_ENTRY_VISIBLE = false"))
        assertTrue(source.contains("if (AI_ANALYSIS_ENTRY_VISIBLE) {"))
    }
}
