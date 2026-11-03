package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiAnalysisEntryVisibilityTest {
    @Test
    fun `AI analysis entry is visible after source aware preflight validation`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"),
        )

        assertTrue(source.contains("internal const val AI_ANALYSIS_ENTRY_VISIBLE = true"))
        assertTrue(source.contains("if (AI_ANALYSIS_ENTRY_VISIBLE) {"))
        assertTrue(source.contains("Modifier.testTag(\"layout-inspector-run-ai-analysis\")"))
    }

    @Test
    fun `entry captures target and rejects unavailable selection before preparing`() {
        val source = pageSource().substringAfter("val runAiAnalysis: () -> Unit = runAiAnalysis@{")
            .substringBefore("val presentationCache")

        assertTrue(source.contains("if (!aiAnalysisLifecycle.canStart) return@runAiAnalysis"))
        assertTrue(source.contains("if (state.selectedNodeId != null && state.selectedNode == null) return@runAiAnalysis"))
        assertTrue(source.indexOf("store.aiAnalysisTarget()") < source.indexOf("buildAiAnalysisInput(state.selectedNode)"))
        assertTrue(source.contains("if (!effectiveAiAnalysisClient.requiresLocalConfirmation) autoScanEnabled = false"))
        assertTrue(source.contains("aiAnalysisLifecycle.start(input, target)"))
    }

    @Test
    fun `page only renders target current local preflight and disposes owned requests`() {
        val source = pageSource()

        assertTrue(source.contains("pendingAnalysis?.takeIf { store.isAiAnalysisTargetCurrent(it.target) }"))
        assertTrue(source.contains("onClick = { aiAnalysisLifecycle.confirm(pending) }"))
        assertTrue(source.contains("onDispose { aiAnalysisLifecycle.dispose() }"))
        assertTrue(source.contains("SideEffect { aiAnalysisLifecycle.cancelIfTargetChanged() }"))
        assertTrue(source.contains("store.loadAiAnalysis(report, target)"))
    }

    @Test
    fun `AI status text is bounded before trailing analysis controls`() {
        val header = pageSource().substringAfter("internal fun FindingsPane(")
            .substringBefore("if (showTimeline) {")
        val status = header.substringAfter("if (aiStatus != null) {")

        assertTrue(header.contains("modifier = Modifier.weight(1f).padding(horizontal = 12.dp)"))
        assertTrue(header.contains("contentAlignment = Alignment.CenterEnd"))
        assertTrue(status.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue(status.contains("maxLines = 1"))
        assertTrue(header.contains("Modifier.testTag(\"layout-inspector-run-ai-analysis\").width(72.dp)"))
    }

    private fun pageSource(): String = Files.readString(
        Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"),
    )
}
