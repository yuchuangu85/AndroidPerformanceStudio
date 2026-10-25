@file:Suppress("MagicNumber")

package com.androidperformancestudio.perfetto.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
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
class TraceAnalyzerWorkspaceTest {
    @Test
    fun `trace open errors remain accessible with or without a previously opened artifact`() {
        listOf(900, 1280).forEach { width ->
            listOf(false, true).forEach { hasActiveTrace ->
                runDesktopComposeUiTest(width = width, height = 720) {
                    val error = "Trace file is missing: /temporary/deleted.pftrace. Re-import or record a new capture."
                    val state = if (hasActiveTrace) traceWorkspaceState() else TraceAnalyzerWorkspaceState()
                    setContent {
                        ViewerTheme(darkTheme = hasActiveTrace) {
                            TraceAnalyzerWorkspace(
                                state = state.copy(error = error),
                                actions = TraceAnalyzerWorkspaceActions(),
                            )
                        }
                    }
                    if (width == 900) onNodeWithTag("trace-workspace-error").performScrollTo()
                    onNodeWithTag("trace-workspace-error").assertIsDisplayed().assertTextEquals(error)
                    if (hasActiveTrace) {
                        onNodeWithTag("active-trace-name").assertExists()
                        onNodeWithTag("open-perfetto-viewer").assertIsEnabled()
                    } else {
                        onNodeWithTag("active-trace-name").assertDoesNotExist()
                        onNodeWithTag("open-perfetto-viewer").assertDoesNotExist()
                    }
                }
            }
        }
    }

    @Test
    fun `wide workspace keeps sessions viewer and insights in separate columns without a top banner`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                ViewerTheme(darkTheme = true) {
                    TraceAnalyzerWorkspace(
                        state = traceWorkspaceState(),
                        actions = TraceAnalyzerWorkspaceActions(),
                        language = UiLanguage.ENGLISH,
                    )
                }
            }

            onNodeWithTag("trace-analyzer-wide").assertIsDisplayed()
            val sessions = onNodeWithTag("trace-sessions-panel").fetchSemanticsNode().boundsInRoot
            val viewer = onNodeWithTag("trace-viewer-panel").fetchSemanticsNode().boundsInRoot
            val insights = onNodeWithTag("trace-insights-panel").fetchSemanticsNode().boundsInRoot
            assertTrue(sessions.right <= viewer.left)
            assertTrue(viewer.right <= insights.left)
            onNodeWithTag("perfetto-source-of-truth").assertIsDisplayed()
            onAllNodesWithText("Unavailable").assertCountEquals(3)
        }

    @Test
    fun `workspace actions preserve evidence identities`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            var selectedSession: String? = null
            var openedSession: String? = null
            var deletedSession: String? = null
            var diagnostic: String? = null
            var openedTimeline: String? = null
            var openedArtifact: String? = null
            setContent {
                ViewerTheme(darkTheme = false) {
                    TraceAnalyzerWorkspace(
                        state = traceWorkspaceState(),
                        actions =
                            TraceAnalyzerWorkspaceActions(
                                onSelectSession = { selectedSession = it },
                                onOpenSession = { openedSession = it },
                                onDeleteSession = { deletedSession = it },
                                onRunDiagnostic = { diagnostic = it },
                                onOpenInTimeline = { openedTimeline = it.queryId },
                                onOpenViewer = { openedArtifact = it.artifactId },
                            ),
                    )
                }
            }

            onNodeWithTag("trace-session-session-1").performClick()
            onNodeWithTag("open-trace-session-session-1").performClick()
            onNodeWithTag("delete-trace-session-session-1").performClick()
            onNodeWithTag("run-trace-diagnostic-frame-jank").performClick()
            onNodeWithTag("open-insight-timeline-frame-jank").assertIsEnabled().performClick()
            onNodeWithTag("open-perfetto-viewer").performClick()
            assertEquals("session-1", selectedSession)
            assertEquals("session-1", openedSession)
            assertEquals("session-1", deletedSession)
            assertEquals("frame-jank", diagnostic)
            assertEquals("frame-jank", openedTimeline)
            assertEquals("artifact-1", openedArtifact)
            onNodeWithTag("open-insight-timeline-binder-latency").assertIsNotEnabled()
        }

    @Test
    fun `narrow workspace stacks real sections and hosts capture content without a top banner`() =
        runDesktopComposeUiTest(width = 900, height = 720) {
            setContent {
                ViewerTheme(darkTheme = true) {
                    TraceAnalyzerWorkspace(
                        state = traceWorkspaceState().copy(captureVisible = true),
                        actions = TraceAnalyzerWorkspaceActions(),
                        language = UiLanguage.SIMPLIFIED_CHINESE,
                        captureContent = {
                            Box(Modifier.fillMaxSize()) {
                                Text("真实采集控件")
                            }
                        },
                    )
                }
            }

            onNodeWithTag("trace-analyzer-narrow").assertIsDisplayed()
            onNodeWithTag("trace-capture-panel").performScrollTo().assertIsDisplayed()
            onNodeWithText("真实采集控件").assertIsDisplayed()
            onNodeWithTag("trace-insights-panel").performScrollTo().assertIsDisplayed()
        }

    @Test
    fun `workspace renders visible themed panels instead of a synthetic timeline`() =
        runDesktopComposeUiTest(width = 1200, height = 780) {
            setContent {
                ViewerTheme(darkTheme = true) {
                    TraceAnalyzerWorkspace(
                        state = traceWorkspaceState(),
                        actions = TraceAnalyzerWorkspaceActions(),
                    )
                }
            }

            waitForIdle()
            val screenshot = onNodeWithTag("trace-analyzer-workspace").captureToImage()
            assertEquals(1200, screenshot.width)
            assertEquals(780, screenshot.height)
            val pixels = screenshot.toPixelMap()
            val sampledColors =
                listOf(
                    pixels[8, 8],
                    pixels[screenshot.width / 4, 120],
                    pixels[screenshot.width / 2, screenshot.height / 2],
                    pixels[screenshot.width - 24, screenshot.height / 2],
                )
            assertTrue(sampledColors.distinct().size > 1, "Trace workspace should render visible themed panels")
        }
}

internal fun traceWorkspaceState(): TraceAnalyzerWorkspaceState {
    val artifact =
        TraceWorkspaceArtifact(
            artifactId = "artifact-1",
            displayName = "game_trace.pftrace",
            locationLabel = "/traces/game_trace.pftrace",
            completenessLabel = "Complete",
            limitations = listOf("Input events were not requested."),
        )
    return TraceAnalyzerWorkspaceState(
        sessions =
            listOf(
                TraceWorkspaceSession(
                    sessionId = "session-1",
                    title = "Game trace",
                    capturedAtLabel = "2026-10-15 10:20",
                    durationLabel = "3 m 12 s",
                    artifact = artifact,
                ),
                TraceWorkspaceSession(
                    sessionId = "session-2",
                    title = "Cold startup",
                    capturedAtLabel = "2026-10-15 09:42",
                    durationLabel = "20 s",
                ),
            ),
        activeTrace = artifact,
        insights =
            listOf(
                TraceInsightEvidence(
                    queryId = "frame-jank",
                    artifactId = artifact.artifactId,
                    title = "Frame jank evidence",
                    resultRowsSummary = "3 rows",
                    limitations = listOf("FrameTimeline coverage is partial."),
                    timestampNanos = 58_123_000_000,
                    severity = TraceInsightSeverity.CRITICAL,
                    category = TraceInsightCategory.FRAME,
                ),
                TraceInsightEvidence(
                    queryId = "binder-latency",
                    artifactId = artifact.artifactId,
                    title = "Binder latency",
                    resultRowsSummary = null,
                    limitations = listOf("No compatible Binder schema was found."),
                    severity = TraceInsightSeverity.WARNING,
                    category = TraceInsightCategory.BINDER,
                ),
            ),
        selectedQueryId = "frame-jank",
        status = "Trace ready",
    )
}
