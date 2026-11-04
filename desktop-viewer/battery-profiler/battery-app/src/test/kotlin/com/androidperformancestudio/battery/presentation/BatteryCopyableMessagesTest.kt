package com.androidperformancestudio.battery.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class BatteryCopyableMessagesTest {
    @Test
    fun `errors empty evidence warnings and comparison notices use copyable text`() {
        val screen = source("presentation/BatteryProfilerScreen.kt")
        listOf(
            "state.errorMessage",
            "localizedStringResource(Res.string.no_delta_or_unavailable, language)",
            "localizedStringResource(Res.string.text, language, it)",
        ).forEach { assertCopyable(screen, it) }
        assertTrue(screen.contains("state.operationMessage?.let { CopyableText(it"))
        assertTrue(screen.contains("state.warnings.forEach { CopyableText(it"))
        assertTrue(screen.contains("state.errorMessage?.let { CopyableText(it"))
        assertTrue(screen.contains("    Text(text, modifier = Modifier.weight(weight), maxLines = 1)"))
    }

    @Test
    fun `run evidence missing data notes and warning details are copyable without replacing measurements`() {
        val workspace = source("presentation/BatteryRunWorkspace.kt")
        listOf(
            "bw_no_snapshots",
            "bw_no_level",
            "bw_empty",
            "bw_select",
            "bw_track_note",
            "bw_timer_note",
            "bw_counter_note",
            "bw_aggregate_network",
        ).forEach { resource -> assertCopyable(workspace, "localizedStringResource(Res.string.$resource, language)") }
        assertCopyable(workspace, "item.message")
        assertTrue(workspace.contains("if (item is BatteryWorkspaceItem.Warning)"))
        assertCopyable(workspace, "names.getValue(item.id)")
        assertTrue(workspace.contains("EvidenceCell(itemValue(item), 1.4f)"))
        assertTrue(workspace.contains(".clickable { onSelect(item.id) }"))
        assertTrue(workspace.contains("overflow = TextOverflow.Ellipsis"))
    }

    @Test
    fun `session errors empty states and run status are copyable but entry labels remain unchanged`() {
        val sessions = source("app/BatterySessionWorkspace.kt")
        assertTrue(sessions.contains("state.sessionError?.let {\n            CopyableText(it"))
        assertTrue(
            Regex("CopyableText\\(\\s*localizedStringResource\\(\\s*if \\(state.sessions.isEmpty\\(\\)\\)")
                .containsMatchIn(sessions),
        )
        assertTrue(
            Regex("CopyableText\\(\\s*localizedStringResource\\(\\s*Res.string.sessions_run_status")
                .containsMatchIn(sessions),
        )
        assertTrue(Regex("\\bText\\(\\s*session.packageName").containsMatchIn(sessions))
    }

    @Test
    fun `confirmation reasons and inner status message are copyable without changing the status bar layout`() {
        val page = source("app/BatteryProfilerMainPage.kt")
        listOf(
            "this_clears_battery_statistics_and_battery_historian_history_for_every",
            "bugreports_may_contain_accounts_ssids_app_lists_logs_and_device",
        ).forEach { resource -> assertCopyable(page, "localizedStringResource(Res.string.$resource, language)") }
        val statusBar =
            page.substringAfter("private fun BatteryProfilerStatusBar(").substringBefore("private fun BatteryCaptureMode.label(")
        assertCopyable(statusBar, "text = it")
        assertTrue(statusBar.contains("SelectionContainer {\n        Row("))
        assertTrue(statusBar.contains(".horizontalScroll(rememberScrollState())"))
        assertTrue(statusBar.contains("maxLines = 1"))
        assertTrue(page.contains("controller.resetStatistics()"))
        assertTrue(page.contains("controller.generateBugreport(file.toPath())"))
    }

    private fun source(relativePath: String): String =
        Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/battery/$relativePath"))

    private fun assertCopyable(source: String, argument: String) {
        assertTrue(Regex("CopyableText\\(\\s*" + Regex.escape(argument)).containsMatchIn(source), argument)
    }
}
