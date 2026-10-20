@file:Suppress("MagicNumber")

package com.androidperformancestudio.perfetto.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TraceAnalyzerWorkspaceFeaturesTest {
    @Test
    fun `toolbar exposes open capture export search and preset actions`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            val events = mutableListOf<String>()
            setContent {
                ViewerTheme(darkTheme = true) {
                    TraceAnalyzerWorkspace(
                        state = featureState().copy(traceSearchQuery = "Choreographer"),
                        actions =
                            TraceAnalyzerWorkspaceActions(
                                onOpenTraceFile = { events += "open" },
                                onToggleCapture = { events += "capture" },
                                onExport = { events += "export:${it.artifactId}" },
                                onOpenViewer = { events += "viewer:${it.artifactId}" },
                                onRunTraceSearch = { events += "search" },
                                onRunPreset = { events += "preset" },
                            ),
                    )
                }
            }

            listOf(
                "toolbar-open-trace",
                "toolbar-capture-trace",
                "toolbar-export-trace",
                "toolbar-open-perfetto",
                "toolbar-run-search",
                "toolbar-run-preset",
            ).forEach { tag -> onNodeWithTag(tag).performClick() }
            assertEquals(listOf("open", "capture", "export:artifact-1", "viewer:artifact-1", "search", "preset"), events)
            val toolbar = onNodeWithTag("trace-analyzer-toolbar").fetchSemanticsNode().boundsInRoot
            val summary = onNodeWithTag("trace-summary-grid").fetchSemanticsNode().boundsInRoot
            assertTrue(toolbar.bottom <= summary.top)
        }

    @Test
    fun `trace-dependent toolbar actions are disabled without an active trace`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent {
                ViewerTheme(darkTheme = false) {
                    TraceAnalyzerWorkspace(
                        state = featureState().copy(activeTrace = null, traceSearchQuery = "main"),
                        actions = TraceAnalyzerWorkspaceActions(),
                    )
                }
            }

            onNodeWithTag("toolbar-open-trace").assertIsEnabled()
            onNodeWithTag("toolbar-capture-trace").assertIsEnabled()
            listOf("toolbar-export-trace", "toolbar-open-perfetto", "toolbar-run-search", "toolbar-run-preset").forEach { tag ->
                onNodeWithTag(tag).assertIsNotEnabled()
            }
        }

    @Test
    fun `insights are scoped to the selected preset and filtered by severity`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            var filter: TraceInsightFilter? = null
            setContent {
                ViewerTheme(darkTheme = true) {
                    TraceAnalyzerWorkspace(
                        state = featureState().copy(insightFilter = TraceInsightFilter.CRITICAL),
                        actions = TraceAnalyzerWorkspaceActions(onInsightFilterChange = { filter = it }),
                    )
                }
            }

            onNodeWithTag("trace-insight-frame-jank").assertIsDisplayed()
            onNodeWithTag("trace-insight-binder-latency").assertDoesNotExist()
            onNodeWithTag("trace-insight-power-rails").assertDoesNotExist()
            onNodeWithText("All 2").assertIsDisplayed()
            onNodeWithText("Critical 1").assertIsDisplayed()
            onNodeWithTag("trace-insight-filter-warning").performClick()
            assertEquals(TraceInsightFilter.WARNING, filter)
        }

    @Test
    fun `result tab renders real rows with timestamp jumps and bookmarks`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            var opened: Long? = null
            var bookmark: TraceBookmarkRequest? = null
            var tab: TraceDetailTab? = null
            setContent {
                ViewerTheme(darkTheme = true) {
                    TraceAnalyzerWorkspace(
                        state = featureState(),
                        actions =
                            TraceAnalyzerWorkspaceActions(
                                onOpenTimestamp = { opened = it },
                                onAddBookmark = { bookmark = it },
                                onSelectDetailTab = { tab = it },
                            ),
                    )
                }
            }

            onNodeWithTag("trace-result-table").assertIsDisplayed()
            onNodeWithText("Frame jank evidence  ·  2 rows").assertIsDisplayed()
            onNodeWithTag("open-timestamp-result-0").performClick()
            onNodeWithTag("open-timestamp-result-1").assertIsNotEnabled()
            onNodeWithTag("add-bookmark-result-0").performClick()
            onNodeWithTag("add-bookmark-result-1").assertIsNotEnabled()
            onNodeWithTag("trace-detail-tab-query").performClick()
            assertEquals(58_123_000_000, opened)
            assertEquals(TraceBookmarkRequest("Frame jank evidence", 58_123_000_000, "frame-jank"), bookmark)
            assertEquals(TraceDetailTab.QUERY, tab)
        }

    @Test
    fun `query and search tabs show read-only SQL and real slice hits`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent {
                ViewerTheme(darkTheme = true) {
                    TraceAnalyzerWorkspace(
                        state = featureState().copy(detailTab = TraceDetailTab.QUERY),
                        actions = TraceAnalyzerWorkspaceActions(),
                    )
                }
            }
            onNodeWithTag("trace-query-sql").assertIsDisplayed()
            onNodeWithText("SELECT process_name FROM android_frame_stats").assertIsDisplayed()
        }

    @Test
    fun `search tab lists hits and bookmarks panel jumps and deletes`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            val events = mutableListOf<String>()
            setContent {
                ViewerTheme(darkTheme = false) {
                    TraceAnalyzerWorkspace(
                        state = featureState().copy(detailTab = TraceDetailTab.SEARCH),
                        actions =
                            TraceAnalyzerWorkspaceActions(
                                onOpenTimestamp = { events += "open:$it" },
                                onDeleteBookmark = { events += "delete:$it" },
                                onAddBookmark = { events += "bookmark:${it.label}" },
                            ),
                        language = UiLanguage.ENGLISH,
                    )
                }
            }

            onNodeWithText("1 slices matched “Choreographer” (longest first)").assertIsDisplayed()
            onNodeWithTag("open-timestamp-search-0").performClick()
            onNodeWithTag("add-bookmark-search-0").performClick()
            onNodeWithTag("open-bookmark-bm-1").performClick()
            onNodeWithTag("delete-bookmark-bm-1").performClick()
            assertEquals(
                listOf("open:1500000000", "bookmark:Choreographer#doFrame", "open:58123000000", "delete:bm-1"),
                events,
            )
        }

    @Test
    fun `evidence anchors open locatable insights and narrow layout keeps every region`() =
        runDesktopComposeUiTest(width = 900, height = 720) {
            var anchor: String? = null
            setContent {
                ViewerTheme(darkTheme = true) {
                    TraceAnalyzerWorkspace(
                        state = featureState(),
                        actions = TraceAnalyzerWorkspaceActions(onOpenInTimeline = { anchor = it.queryId }),
                        language = UiLanguage.SIMPLIFIED_CHINESE,
                    )
                }
            }

            // performScrollTo only scrolls the nearest scrollable parent, so bring the viewer panel into the page first.
            onNodeWithTag("trace-viewer-panel").performScrollTo()
            onNodeWithTag("trace-anchor-frame-jank").performClick()
            assertEquals("frame-jank", anchor)
            listOf("trace-analyzer-toolbar", "trace-bookmarks-panel", "trace-detail-panel", "trace-insights-panel").forEach { tag ->
                onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            }
        }

    private fun featureState(): TraceAnalyzerWorkspaceState {
        val base = traceWorkspaceState()
        val artifactId = base.activeTrace!!.artifactId
        return base.copy(
            insights =
                base.insights +
                    TraceInsightEvidence(
                        queryId = "power-rails",
                        artifactId = artifactId,
                        title = "Power rails",
                        severity = TraceInsightSeverity.CRITICAL,
                    ),
            presets =
                listOf(
                    TraceDiagnosticPresetOption("core", "Core diagnostics", listOf("frame-jank", "binder-latency")),
                    TraceDiagnosticPresetOption("all", "All diagnostics", listOf("frame-jank", "binder-latency", "power-rails")),
                ),
            selectedPresetId = "core",
            resultTable =
                TraceResultTable(
                    queryId = "frame-jank",
                    artifactId = artifactId,
                    title = "Frame jank evidence",
                    description = "Frames that missed their deadline",
                    sql = "SELECT process_name FROM android_frame_stats",
                    schemaVersion = "v57.2",
                    columns = listOf("timestamp_ns", "process_name"),
                    rows = listOf(listOf("58123000000", "com.example"), listOf("—", "launcher")),
                    rowTimestampsNanos = listOf(58_123_000_000, null),
                ),
            searchedTerm = "Choreographer",
            searchHits = listOf(TraceSearchHit(1_500_000_000, 16_000_000, "Choreographer#doFrame", "main · com.example")),
            bookmarks = listOf(TraceWorkspaceBookmark("bm-1", "Long frame", 58_123_000_000)),
        )
    }
}
