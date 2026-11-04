package com.androidperformancestudio.startup.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupCopyableMessagesSourceTest {
    private val workspace =
        Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/startup/app/StartupProfilerMainPage.kt"))

    @Test
    fun `startup diagnostics empty states and comparison reasons remain copyable`() {
        val screen = presentationSource("StartupProfilerScreen.kt")
        val resources =
            listOf(
                "choose_a_device_and_app_to_run_repeatable_cold_warm",
                "report_fully_drawn_hint",
                "optimization_empty",
                "trace_failure_reason",
                "trace_open_failed",
                "diagnostics",
                "no_platform_durations",
                "waterfall_no_intervals",
                "metric_evidence",
                "progress_runs",
            )
        for (resource in resources) {
            assertCopyableResource(screen, resource)
        }
        assertTrue(screen.contains("state.operationMessage?.let { CopyableText(it,"))
        assertTrue(screen.contains("state.errorMessage?.let { CopyableText(it,"))
        val comparison = presentationSource("StartupBaselineComparison.kt")
        assertCopyableResource(comparison, "baseline_comparison_empty")
        assertCopyableResource(comparison, "comparison_descriptive")
        assertTrue(comparison.contains("comparison.reasons.forEach { CopyableText("))
        val waterfall = presentationSource("StartupRunsWaterfall.kt")
        for (resource in listOf("waterfall_no_intervals", "waterfall_platform_only", "waterfall_unplotted_phases")) {
            assertCopyableResource(waterfall, resource)
        }
    }

    @Test
    fun `confirmation copies displayed title and body without changing action callbacks or footer layout`() {
        assertTrue(
            workspace.contains("title = { CopyableText(localizedStringResource(Res.string.compilation_change_title, language)"),
        )
        assertTrue(
            workspace.contains("text = { CopyableText(localizedStringResource(Res.string.compilation_change_message, language)"),
        )
        assertTrue(workspace.contains("Text(localizedStringResource(Res.string.cancel, language))"))
        assertTrue(workspace.contains("onDismissRequest = controller::dismissCompilationConfirmation"))
        assertTrue(workspace.contains("controller.runExperiment(compilationChangeConfirmed = true)"))
        val footer = workspace.substringAfter("private fun StartupProfilerStatusBar(")
        assertTrue(Regex("SelectionContainer\\s*\\{\\s*Row\\(").containsMatchIn(footer))
        assertTrue(footer.contains(".horizontalScroll(rememberScrollState())"))
        assertTrue(Regex("CopyableText\\(\\s*text = it,").containsMatchIn(footer))
        assertTrue(footer.contains("errorMessage ?: operationMessage"))
    }

    @Test
    fun `compilation failure details profile status and low sample hints remain copyable`() {
        val screen = presentationSource("StartupProfilerScreen.kt")
        assertTrue(screen.contains("compilationEvidence.detailRows(language).forEach { row -> CopyableText("))
        assertTrue(screen.contains("rootCause.limitations.forEach { CopyableText("))
        val controls = presentationSource("StartupWorkspaceControls.kt")
        assertCopyableResource(controls, "profile_artifact_hint")
        val profileStatus = controls.substringAfter("internal fun StartupProfileSummary(")
        assertTrue(Regex("CopyableText\\(\\s*localizedStringResource\\(\\s*when").containsMatchIn(profileStatus))
        val summary = presentationSource("StartupWorkspaceMetrics.kt").substringAfter("analysis?.let {")
        assertTrue(summary.trimStart().startsWith("CopyableText("))
        assertTrue(summary.contains("Res.string.low_tail_resolution"))
    }

    private fun presentationSource(file: String): String =
        Files.readString(Path.of("../presentation/src/main/kotlin/com/androidperformancestudio/startup/presentation", file))

    private fun assertCopyableResource(source: String, resource: String) {
        val argument = "\\(\\s*(?:text\\s*=\\s*)?localizedStringResource\\(\\s*Res\\.string\\.$resource\\b"
        assertTrue(Regex("CopyableText$argument").containsMatchIn(source), "$resource must remain copyable")
        assertFalse(Regex("\\bText$argument").containsMatchIn(source), "$resource must not fall back to plain Text")
    }
}
