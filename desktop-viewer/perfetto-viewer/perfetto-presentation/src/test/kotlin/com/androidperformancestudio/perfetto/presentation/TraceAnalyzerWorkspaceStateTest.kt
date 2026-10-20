package com.androidperformancestudio.perfetto.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceAnalyzerWorkspaceStateTest {
    @Test
    fun `session search covers presentation metadata and artifact identity`() {
        val sessions =
            listOf(
                traceSession("game", "Game trace", "artifact-game"),
                traceSession("startup", "Cold startup", "artifact-startup"),
            )

        assertEquals(listOf("game"), filterTraceWorkspaceSessions(sessions, "game").map { it.sessionId })
        assertEquals(listOf("startup"), filterTraceWorkspaceSessions(sessions, "ARTIFACT-STARTUP").map { it.sessionId })
        assertEquals(sessions, filterTraceWorkspaceSessions(sessions, "  "))
        assertTrue(filterTraceWorkspaceSessions(sessions, "missing").isEmpty())
    }

    @Test
    fun `summary values only come from successful result evidence`() {
        val summaries =
            buildTraceWorkspaceSummaries(
                listOf(
                    insight(TraceInsightCategory.FRAME, result = "12 rows"),
                    insight(TraceInsightCategory.BINDER, result = "18 rows", error = "schema unavailable"),
                    insight(TraceInsightCategory.LONG_TASK, result = null),
                ),
            ).associateBy(TraceWorkspaceSummary::category)

        assertEquals("12 rows", summaries.getValue(TraceInsightCategory.FRAME).value)
        assertNull(summaries.getValue(TraceInsightCategory.BINDER).value)
        assertNull(summaries.getValue(TraceInsightCategory.LONG_TASK).value)
        assertNull(summaries.getValue(TraceInsightCategory.RENDERING).value)
    }

    @Test
    fun `timeline action requires locatable evidence for the active artifact`() {
        val active = traceArtifact("artifact-active", viewerAvailable = true)
        val locatable = insight(TraceInsightCategory.FRAME, result = "1 row", timestampNanos = 58_123_000_000)

        assertTrue(canOpenInsightInTimeline(locatable, active))
        assertFalse(canOpenInsightInTimeline(locatable.copy(timestampNanos = null), active))
        assertFalse(canOpenInsightInTimeline(locatable.copy(error = "query failed"), active))
        assertFalse(canOpenInsightInTimeline(locatable.copy(artifactId = "another-artifact"), active))
        assertFalse(canOpenInsightInTimeline(locatable, active.copy(viewerAvailable = false)))
        assertFalse(canOpenInsightInTimeline(locatable, null))
    }

    @Test
    fun `summaries prefer real aggregates and the most severe evidence in a category`() {
        val summaries =
            buildTraceWorkspaceSummaries(
                listOf(
                    insight(TraceInsightCategory.FRAME, result = "4 rows").copy(title = "Surface", severity = TraceInsightSeverity.WARNING),
                    insight(TraceInsightCategory.FRAME, result = "2 rows")
                        .copy(title = "Jank", severity = TraceInsightSeverity.CRITICAL, aggregate = "12 / 840 janky frames"),
                ),
            ).associateBy(TraceWorkspaceSummary::category)

        val frame = summaries.getValue(TraceInsightCategory.FRAME)
        assertEquals("12 / 840 janky frames", frame.value)
        assertEquals(TraceInsightSeverity.CRITICAL, frame.severity)
        assertEquals("Jank", frame.sourceTitle)
    }

    @Test
    fun `visible insights follow preset membership then severity`() {
        val insights =
            listOf(
                insight(TraceInsightCategory.FRAME, result = null).copy(queryId = "a", severity = TraceInsightSeverity.CRITICAL),
                insight(TraceInsightCategory.BINDER, result = null).copy(queryId = "b", severity = TraceInsightSeverity.OPPORTUNITY),
                insight(TraceInsightCategory.CPU, result = null).copy(queryId = "c", severity = TraceInsightSeverity.WARNING),
            )
        val presets = listOf(TraceDiagnosticPresetOption("p", "Preset", listOf("a", "b")))

        assertEquals(listOf("a", "b"), visibleTraceInsights(insights, presets, "p", TraceInsightFilter.ALL).map { it.queryId })
        assertEquals(listOf("b"), visibleTraceInsights(insights, presets, "p", TraceInsightFilter.INFO).map { it.queryId })
        assertEquals(listOf("c"), visibleTraceInsights(insights, presets, null, TraceInsightFilter.WARNING).map { it.queryId })
        assertEquals(
            mapOf(
                TraceInsightFilter.ALL to 3,
                TraceInsightFilter.CRITICAL to 1,
                TraceInsightFilter.WARNING to 1,
                TraceInsightFilter.INFO to 1,
            ),
            traceInsightCounts(insights),
        )
    }

    @Test
    fun `anchors are locatable insights in trace order and time labels stay absolute`() {
        val active = traceArtifact("artifact-active")
        val later = insight(TraceInsightCategory.FRAME, result = "1 row", timestampNanos = 9_000_000_000).copy(queryId = "later")
        val earlier = insight(TraceInsightCategory.BINDER, result = "1 row", timestampNanos = 1_000_000_000).copy(queryId = "earlier")
        val unlocatable = insight(TraceInsightCategory.CPU, result = "1 row")

        assertEquals(listOf("earlier", "later"), traceTimelineAnchors(listOf(later, unlocatable, earlier), active).map { it.queryId })
        assertEquals("58.123000 s", formatTraceTimestamp(58_123_000_000))
        assertEquals("16.000 ms", formatTraceDurationNanos(16_000_000))
        assertEquals("1.250 s", formatTraceDurationNanos(1_250_000_000))
        assertEquals("—", formatTraceDurationNanos(-1))
    }

    private fun traceSession(
        id: String,
        title: String,
        artifactId: String,
    ): TraceWorkspaceSession =
        TraceWorkspaceSession(
            sessionId = id,
            title = title,
            capturedAtLabel = "2026-10-15 10:20",
            durationLabel = "12 s",
            artifact = traceArtifact(artifactId),
        )

    private fun traceArtifact(
        id: String,
        viewerAvailable: Boolean = true,
    ): TraceWorkspaceArtifact =
        TraceWorkspaceArtifact(
            artifactId = id,
            displayName = "$id.pftrace",
            locationLabel = "/traces/$id.pftrace",
            completenessLabel = "Complete",
            viewerAvailable = viewerAvailable,
        )

    private fun insight(
        category: TraceInsightCategory,
        result: String?,
        error: String? = null,
        timestampNanos: Long? = null,
    ): TraceInsightEvidence =
        TraceInsightEvidence(
            queryId = "query-${category.name.lowercase()}",
            artifactId = "artifact-active",
            title = category.name,
            resultRowsSummary = result,
            error = error,
            timestampNanos = timestampNanos,
            category = category,
        )
}
