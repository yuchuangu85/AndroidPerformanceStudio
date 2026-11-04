package com.androidperformancestudio.perfetto.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class PerfettoAiEntryWorkspaceSourceTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/perfetto/app/PerfettoMainPage.kt"),
        )

    @Test
    fun `toolbar uses the canonical published diagnostic context`() {
        assertTrue(source.contains("val aiContext = remember(activeArtifact?.id, diagnosticQuery, diagnosticOutcomes)"))
        assertTrue(source.contains("val outcome = diagnosticQuery?.id?.let { diagnosticOutcomes[it] }"))
        assertTrue(
            source.contains(
                "boundedAiContext(traceAiContextForArtifact(activeArtifact?.id?.value, outcome?.artifactId, diagnosticQuery, outcome?.result))",
            ),
        )
        assertTrue(source.contains("PublishAiContext(\"trace\", aiContext)"))
        val header = source.substringAfter("HeaderToolbar(").substringBefore("HorizontalDivider(")
        assertTrue(header.contains("ProfilerAiAnalysisButton(aiContext, language)"))
    }
}
