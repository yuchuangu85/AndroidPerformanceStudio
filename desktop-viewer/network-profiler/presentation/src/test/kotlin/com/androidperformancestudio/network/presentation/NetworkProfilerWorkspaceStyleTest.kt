package com.androidperformancestudio.network.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkProfilerWorkspaceStyleTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/network/presentation/NetworkProfilerScreen.kt"),
        )

    @Test
    fun `network child adopts compact workspace spacing without adding a second page header or theme`() {
        val workspace = source.substringAfter("public fun NetworkProfilerScreen(").substringBefore("private fun SummaryCards(")
        assertTrue(workspace.contains(".background(colors.workspace)"))
        assertTrue(workspace.contains(".padding(StudioTokens.workspacePadding)"))
        assertTrue(workspace.contains("Arrangement.spacedBy(StudioTokens.cardGap)"))
        assertFalse(source.contains("StudioWorkspaceHeader("))
        assertFalse(source.contains("ViewerTheme("))
    }

    @Test
    fun `card exteriors including stacked and exchange cards use four dp spacing without shrinking contents`() {
        assertEquals(5, Regex("Arrangement\\.spacedBy\\(StudioTokens\\.cardGap\\)").findAll(source).count())
        val detail = source.substringAfter("private fun RequestDetail(").substringBefore("private fun ExchangeDetail(")
        assertTrue(detail.contains("verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)"))
        val exchange = source.substringAfter("private fun ExchangeDetail(").substringBefore("private fun HeaderEvidence(")
        assertTrue(exchange.contains("Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(7.dp))"))
        assertTrue(exchange.contains("horizontalArrangement = Arrangement.spacedBy(6.dp)"))
        val coverage = source.substringAfter("private fun EvidenceCoverage(").substringBefore("private fun RequestTable(")
        assertTrue(coverage.contains("horizontalArrangement = Arrangement.spacedBy(8.dp)"))
    }

    @Test
    fun `request search uses the compact toolbar input height`() {
        val panels =
            Files.readString(
                Path.of(
                    "src/main/kotlin/com/androidperformancestudio/network/presentation/NetworkWorkspacePanels.kt",
                ),
            )
        assertTrue(panels.contains("compact = true"))
        assertTrue(panels.contains("network-request-search"))
    }

    @Test
    fun `compact adoption retains real summaries request selection and failure evidence`() {
        assertTrue(source.contains("height(IntrinsicSize.Max)"))
        assertFalse(source.contains("height(108.dp)"))
        assertTrue(source.contains("summary?.callCount?.toString()"))
        assertTrue(source.contains("summary?.failureCount?.toString()"))
        assertTrue(source.contains("summary?.transferredBytes?.let(::formatBytes)"))
        assertTrue(source.contains("onSelectCall = actions.selectCall"))
        assertTrue(source.contains("state.message?.let"))
        assertTrue(source.contains("state.error?.let"))
        assertTrue(source.contains("EvidenceCoverage(state, language)"))
        assertTrue(source.contains("select_a_call_to_inspect_phase_evidence"))
        assertTrue(source.contains("tone = colors.info"))
        assertTrue(source.contains("tone = colors.accent"))
        assertTrue(source.contains("tone = if ((summary?.failureCount ?: 0) > 0) colors.warning else colors.info"))
    }
}
