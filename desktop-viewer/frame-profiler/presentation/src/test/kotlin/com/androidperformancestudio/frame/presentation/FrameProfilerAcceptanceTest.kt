package com.androidperformancestudio.frame.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.frame.analysis.FrameJankAnalyzer
import com.androidperformancestudio.frame.model.ExpectedDurationSource
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import com.androidperformancestudio.frame.model.FrameStages
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerDimensions
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FrameProfilerAcceptanceTest {
    @Test
    fun `single frame detail separates metrics property rows and stage rows`() =
        runDesktopComposeUiTest(width = 1280, height = 820) {
            val analysis =
                FrameJankAnalyzer().analyze(
                    listOf(sample(7, 24_000_000L).copy(stages = FrameStages(drawNs = 4_000_000L))),
                )
            setContent {
                ViewerTheme(darkTheme = false) {
                    FrameProfilerScreen(FrameProfilerState(analysis = analysis), FrameProfilerActions(), UiLanguage.ENGLISH)
                }
            }
            onNodeWithTag("frame-detail-metrics-divider").assertExists()
            onNodeWithTag("frame-stage-divider-0").assertExists()
            onNodeWithTag("frame-detail-divider-Budget Source").assertExists()
            onNodeWithTag("frame-detail-divider-Source").performScrollTo().assertIsDisplayed()
        }

    @Test
    fun `short native content viewport keeps panes attribution and warnings reachable by scrolling`() =
        runDesktopComposeUiTest(width = 1064, height = 600) {
            val analysis =
                FrameJankAnalyzer().analyze(
                    (1L..5L).map { id -> sample(id, 34_000_000L).copy(activityName = "FixtureActivity$id") },
                )
            setContent {
                ViewerTheme(darkTheme = false) {
                    FrameProfilerScreen(
                        FrameProfilerState(
                            analysis = analysis,
                            selectedFrameId = 1L,
                            warnings = listOf("Fixture warning one", "Fixture warning two", "Fixture warning three"),
                        ),
                        FrameProfilerActions(),
                        UiLanguage.ENGLISH,
                    )
                }
            }

            onNodeWithTag("frame-workspace-context").performScrollTo().assertIsDisplayed()
            onNodeWithTag("frame-attribution-entry-4").performScrollTo().assertIsDisplayed()
            listOf("Fixture warning one", "Fixture warning two", "Fixture warning three").forEach { warning ->
                onNodeWithText(warning).performScrollTo().assertIsDisplayed()
            }
            onNodeWithTag("frame-attribution-toggle").assertDoesNotExist()
            onNodeWithTag("frame-workspace-body").performScrollTo().assertIsDisplayed()
            listOf("frame-clusters-panel", "frame-problems-panel", "frame-detail-panel").forEach { tag ->
                onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(96.dp)
            }
            onNodeWithTag("frame-selected-budget").performScrollTo().assertIsDisplayed()
        }

    @Test
    fun `short native content viewport keeps all analysis panes reachable without a top banner`() =
        runDesktopComposeUiTest(width = 1064, height = 560) {
            val analysis = FrameJankAnalyzer().analyze(listOf(sample(7, 34_000_000L)))
            setContent {
                ViewerTheme(darkTheme = true) {
                    FrameProfilerScreen(
                        FrameProfilerState(analysis = analysis, selectedFrameId = 7L),
                        FrameProfilerActions(),
                        UiLanguage.ENGLISH,
                    )
                }
            }

            onNodeWithTag("frame-workspace-timeline").performScrollTo().assertIsDisplayed()
            onNodeWithTag("frame-workspace-body").performScrollTo().assertIsDisplayed()
            listOf("frame-clusters-panel", "frame-problems-panel", "frame-detail-panel").forEach { tag ->
                onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(96.dp)
            }
            onNodeWithTag("frame-selected-budget").performScrollTo().assertIsDisplayed()
        }

    @Test
    fun `empty and analyzed frames share one compact workspace layout`() =
        runDesktopComposeUiTest(width = 1280, height = 820) {
            var state by mutableStateOf(FrameProfilerState())
            val analysis = FrameJankAnalyzer().analyze(listOf(sample(7, 34_000_000L)))
            setContent {
                ViewerTheme(darkTheme = true) {
                    FrameProfilerScreen(state, FrameProfilerActions(), UiLanguage.ENGLISH)
                }
            }

            assertUnifiedCardPanes()
            onNodeWithTag("frame-metric-frames").assertIsDisplayed()
            onNodeWithTag("frame-timeline-panel").assertIsDisplayed()
            onNodeWithTag("frame-source-filter").assertIsDisplayed()
            onNodeWithTag("frame-context-panel").assertDoesNotExist()
            onNodeWithTag("frame-workspace-context").assertDoesNotExist()
            onNodeWithTag("frame-context-height-resizer").assertDoesNotExist()
            onNodeWithTag("frame-clusters-panel").assertIsDisplayed()
            onNodeWithTag("frame-problems-panel").assertIsDisplayed()
            onNodeWithTag("frame-detail-panel").assertIsDisplayed()
            onNodeWithText("Capture online or import FrameStats").assertIsDisplayed()
            val emptyTimelineBounds = onNodeWithTag("frame-timeline-panel").fetchSemanticsNode().boundsInRoot
            val emptyFilterBounds = onNodeWithTag("frame-source-filter").fetchSemanticsNode().boundsInRoot
            val emptyClusterBounds = onNodeWithTag("frame-clusters-panel").fetchSemanticsNode().boundsInRoot
            assertTrue(emptyTimelineBounds.top < emptyFilterBounds.top)
            assertTrue(emptyFilterBounds.bottom < emptyTimelineBounds.bottom)
            assertTrue(emptyTimelineBounds.bottom < emptyClusterBounds.top)

            runOnIdle { state = state.copy(analysis = analysis, importedFileName = "capture.txt") }
            onNodeWithTag("frame-timeline-canvas").assertIsDisplayed()
            assertUnifiedCardPanes()
            val sourceFilterBounds = onNodeWithTag("frame-source-filter").fetchSemanticsNode().boundsInRoot
            val timelineBounds = onNodeWithTag("frame-timeline-panel").fetchSemanticsNode().boundsInRoot
            val clusterBounds = onNodeWithTag("frame-clusters-panel").fetchSemanticsNode().boundsInRoot
            val detailBounds = onNodeWithTag("frame-detail-panel").fetchSemanticsNode().boundsInRoot
            onNodeWithTag("frame-context-panel")
                .assertIsDisplayed()
                .assert(hasAnyAncestor(hasTestTag("frame-analysis-card")))
            onNodeWithTag("frame-workspace-context")
                .assert(hasAnyAncestor(hasTestTag("frame-analysis-card")))
            onNodeWithTag("frame-context-height-resizer").assertHeightIsEqualTo(7.dp)
            val contextBounds = onNodeWithTag("frame-context-panel").fetchSemanticsNode().boundsInRoot
            val timelineRangeBounds = onNodeWithTag("frame-timeline-range").fetchSemanticsNode().boundsInRoot
            val timelineTitleBounds = onNodeWithText("Frame Timeline").fetchSemanticsNode().boundsInRoot
            assertTrue(kotlin.math.abs(timelineTitleBounds.center.y - sourceFilterBounds.center.y) < 4f)
            assertTrue(timelineBounds.top < sourceFilterBounds.top)
            assertTrue(sourceFilterBounds.bottom < timelineRangeBounds.top)
            assertTrue(timelineBounds.bottom < clusterBounds.top)
            assertTrue(clusterBounds.bottom > detailBounds.top)
            assertEquals(7f, contextBounds.top - detailBounds.bottom, 0.1f)
            listOf("frame-clusters-panel", "frame-problems-panel", "frame-detail-panel").forEach { tag ->
                onNodeWithTag(tag).assertExists()
            }
        }

    private fun DesktopComposeUiTest.assertUnifiedCardPanes() {
        onAllNodesWithTag("frame-analysis-card").assertCountEquals(1)
        listOf(
            "frame-timeline-panel",
            "frame-clusters-panel",
            "frame-problems-panel",
            "frame-detail-panel",
            "frame-workspace-timeline",
            "frame-workspace-body",
            "frame-workspace-clusters",
            "frame-workspace-problems",
            "frame-workspace-details",
        ).forEach { tag ->
            onNodeWithTag(tag).assert(hasAnyAncestor(hasTestTag("frame-analysis-card")))
        }
    }

    @Test
    fun `timeline uses compact eight dp padding and a labeled duration scale`() =
        runDesktopComposeUiTest(width = 1280, height = 820) {
            val analysis = FrameJankAnalyzer().analyze(listOf(sample(7, 34_000_000L), sample(42, 8_000_000L)))
            var selectedFrameId by mutableStateOf(7L)
            setContent {
                ViewerTheme(darkTheme = false) {
                    FrameProfilerScreen(
                        FrameProfilerState(analysis = analysis, selectedFrameId = selectedFrameId),
                        FrameProfilerActions(onSelectFrame = { selectedFrameId = it }),
                        UiLanguage.ENGLISH,
                    )
                }
            }

            val titleBounds = onNodeWithText("Frame Timeline").fetchSemanticsNode().boundsInRoot
            val panelBounds = onNodeWithTag("frame-clusters-panel").fetchSemanticsNode().boundsInRoot
            val timelineBounds = onNodeWithTag("frame-timeline-panel").fetchSemanticsNode().boundsInRoot
            val problemsBounds = onNodeWithTag("frame-problems-panel").fetchSemanticsNode().boundsInRoot
            val detailBounds = onNodeWithTag("frame-detail-panel").fetchSemanticsNode().boundsInRoot
            assertEquals(4f, timelineBounds.left)
            assertEquals(1276f, timelineBounds.right)
            assertEquals(7f, panelBounds.top - timelineBounds.bottom, 0.1f)
            assertEquals(7f, problemsBounds.left - panelBounds.right, 0.1f)
            assertEquals(7f, detailBounds.left - problemsBounds.right, 0.1f)
            assertEquals(8f, titleBounds.left - timelineBounds.left, 0.1f)
            onNodeWithTag("frame-timeline-height-resizer").assertHeightIsEqualTo(7.dp)
            onNodeWithText("Frame time (ms)").assertExists()
            onNodeWithTag("frame-timeline-duration-axis")
                .assertContentDescriptionEquals("0 ms, 16 ms, 32 ms, 48 ms, 64 ms")
            onNodeWithTag("frame-timeline-frame-axis").assertContentDescriptionEquals("#7, #42")
            onNodeWithTag("frame-timeline-canvas").performMouseInput {
                moveTo(Offset(center.x * 1.5f, center.y))
                press()
                release()
            }
            runOnIdle { assertEquals(42L, selectedFrameId) }
        }

    @Test
    fun `timeline hover follows the cluster hint without moving the analysis panels`() =
        assertStableTimelineHover(hasClusters = true, language = UiLanguage.SIMPLIFIED_CHINESE)

    @Test
    fun `timeline hover reserves a stable caption line when there are no clusters`() =
        assertStableTimelineHover(hasClusters = false, language = UiLanguage.ENGLISH)

    @Test
    fun `timeline title shares a row with filters and keeps controls compact`() =
        runDesktopComposeUiTest(width = 1280, height = 820) {
            val analysis =
                FrameJankAnalyzer().analyze(
                    (1L..1_500L).map { sample(it, if (it <= 500L) 8_000_000L else 34_000_000L) },
                )
            setContent {
                ViewerTheme(darkTheme = false) {
                    FrameProfilerScreen(
                        FrameProfilerState(analysis = analysis),
                        FrameProfilerActions(),
                        UiLanguage.ENGLISH,
                    )
                }
            }

            val titleBounds = onNodeWithText("Frame Timeline").fetchSemanticsNode().boundsInRoot
            val sourceBounds = onNodeWithTag("frame-source-filter").fetchSemanticsNode().boundsInRoot
            val resultFilterBounds = onNodeWithTag("frame-result-filter").fetchSemanticsNode().boundsInRoot
            val sourceLabelBounds = onNodeWithTag("frame-source-label").fetchSemanticsNode().boundsInRoot
            val filterLabelBounds = onNodeWithTag("frame-filter-label").fetchSemanticsNode().boundsInRoot
            assertEquals(8f, sourceLabelBounds.left - titleBounds.right, 0.1f)
            assertEquals(4f, filterLabelBounds.left - sourceBounds.right, 0.1f)
            assertTrue(kotlin.math.abs(titleBounds.center.y - sourceBounds.center.y) < 4f)
            assertTrue(kotlin.math.abs(titleBounds.center.y - resultFilterBounds.center.y) < 4f)
            val controlLabels = listOf("Previous", "Next", "Zoom in", "Zoom out")
            val controlBounds = controlLabels.map { onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
            val controlCenters = controlBounds.map { it.center.y }
            assertTrue(controlCenters.max() - controlCenters.min() < 1f, "Navigation controls must share a row")
            assertTrue(
                kotlin.math.abs(titleBounds.center.y - controlCenters.average()) < 4f,
                "Timeline heading and controls must share a row",
            )
            listOf("Previous", "Next", "Zoom in", "Zoom out").forEach {
                onNodeWithText(it).assertHeightIsEqualTo(ViewerDimensions.compactControlHeight)
            }
            onNodeWithTag("frame-timeline-duration-axis").assertContentDescriptionEquals("0 ms, 2 ms, 4 ms, 6 ms, 8 ms")
            onNodeWithText("Previous").assertIsNotEnabled()
            onNodeWithText("Next").assertIsEnabled().performClick()
            onNodeWithTag("frame-timeline-range").assertTextContains("Frames 501–1000 of 1500")
            onNodeWithTag("frame-timeline-frame-axis").assertContentDescriptionEquals("#501, #625, #750, #875, #1000")
            onNodeWithTag("frame-timeline-duration-axis")
                .assertContentDescriptionEquals("0 ms, 16 ms, 32 ms, 48 ms, 64 ms")
            onNodeWithText("Zoom in").performClick()
            onNodeWithTag("frame-timeline-range").assertTextContains("Frames 626–875 of 1500")
            onNodeWithTag("frame-timeline-frame-axis").assertContentDescriptionEquals("#626, #688, #750, #812, #875")
            onNodeWithText("Zoom out").performClick()
            onNodeWithTag("frame-timeline-range").assertTextContains("Frames 501–1000 of 1500")
            onNodeWithText("Previous").assertIsEnabled().performClick()
            onNodeWithTag("frame-timeline-range").assertTextContains("Frames 1–500 of 1500")
            onNodeWithTag("frame-timeline-duration-axis").assertContentDescriptionEquals("0 ms, 2 ms, 4 ms, 6 ms, 8 ms")
        }

    @Test
    fun `core metrics and attribution are visible together without expanding`() =
        runDesktopComposeUiTest(width = 1280, height = 820) {
            val analysis =
                FrameJankAnalyzer().analyze(
                    listOf(
                        sample(1, 8_000_000L).copy(platformJank = false, refreshRateHz = 60.0),
                        sample(2, 24_000_000L).copy(platformJank = true, refreshRateHz = 120.0),
                    ),
                )
            setContent {
                ViewerTheme(darkTheme = false) {
                    FrameProfilerScreen(
                        FrameProfilerState(analysis = analysis),
                        FrameProfilerActions(),
                        UiLanguage.ENGLISH,
                    )
                }
            }

            onNodeWithTag("frame-metric-frames").assertTextContains("2", substring = true)
            onNodeWithTag("frame-metric-deadline").assertTextContains("50.0%", substring = true)
            onNodeWithTag("frame-metric-p50").assertTextContains("8.00 ms", substring = true)
            onNodeWithTag("frame-metric-p95").assertTextContains("24.00 ms", substring = true)
            onNodeWithTag("frame-metric-worst").assertTextContains("24.00 ms", substring = true)
            onNodeWithTag("frame-metric-platform-jank").assertTextEquals("50.0%")
            onNodeWithTag("frame-metric-refresh-rate").assertTextEquals("60–120 Hz")
            val metricBounds =
                listOf("frames", "deadline", "p50", "p95", "worst", "platform-jank", "refresh-rate").map {
                    onNodeWithTag("frame-metric-$it").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                }
            assertTrue(metricBounds.all { it.top == metricBounds.first().top }, "All summary metrics must share a row")
            assertTrue(metricBounds.zipWithNext().all { (left, right) -> left.right < right.left })
            onNodeWithTag("frame-attribution-toggle").assertDoesNotExist()
            onNodeWithTag("frame-attribution-entry-0").assertTextContains("GalleryActivity", substring = true)
            onNodeWithTag("frame-attribution-entry-0").assertTextContains("1 jank / 2 frames", substring = true)
        }

    @Test
    fun `summary signal cards keep unavailable values visible without data`() =
        runDesktopComposeUiTest(width = 1064, height = 600) {
            setContent {
                ViewerTheme(darkTheme = false) {
                    FrameProfilerScreen(FrameProfilerState(), FrameProfilerActions(), UiLanguage.SIMPLIFIED_CHINESE)
                }
            }
            onNodeWithTag("frame-metric-platform-jank").assertIsDisplayed().assertTextEquals("—")
            onNodeWithTag("frame-metric-refresh-rate").assertIsDisplayed().assertTextEquals("—")
            onNodeWithText("平台 Jank 信号率").assertIsDisplayed()
            onNodeWithText("刷新率").assertIsDisplayed()
        }

    @Test
    fun `compact workspace retains ordered analysis columns and a date free heading`() =
        runDesktopComposeUiTest(width = 1280, height = 820) {
            val analysis = FrameJankAnalyzer().analyze(listOf(sample(1, 24_000_000L)))
            setContent {
                ViewerTheme(darkTheme = false) {
                    FrameProfilerScreen(
                        FrameProfilerState(analysis = analysis),
                        FrameProfilerActions(),
                        UiLanguage.ENGLISH,
                    )
                }
            }

            val clusters = onNodeWithTag("frame-clusters-panel").fetchSemanticsNode().boundsInRoot
            val problems = onNodeWithTag("frame-problems-panel").fetchSemanticsNode().boundsInRoot
            val detail = onNodeWithTag("frame-detail-panel").fetchSemanticsNode().boundsInRoot
            assertTrue(clusters.right < problems.left && problems.right < detail.left)
            assertTrue(clusters.top == problems.top && problems.top == detail.top)
            assertTrue(clusters.height > 0f && problems.height > 0f && detail.height > 0f)
            onNodeWithText("Date").assertDoesNotExist()
            onNodeWithText("Time range").assertDoesNotExist()
        }

    @Test
    fun `unknown budget stays unavailable despite an untrusted numeric duration`() =
        runDesktopComposeUiTest(width = 1440, height = 860) {
            val analysis =
                FrameJankAnalyzer().analyze(
                    listOf(sample(1, 24_000_000L).copy(expectedDurationSource = ExpectedDurationSource.UNKNOWN)),
                )
            setContent {
                ViewerTheme(darkTheme = true) {
                    FrameProfilerScreen(
                        FrameProfilerState(analysis = analysis),
                        FrameProfilerActions(),
                        UiLanguage.ENGLISH,
                    )
                }
            }

            onNodeWithTag("frame-selected-budget").assertTextContains("—", substring = true)
            onNodeWithTag("frame-metric-deadline").assertTextContains("—", substring = true)
            onNodeWithText("16.67 ms").assertDoesNotExist()
        }

    @Test
    fun `ten thousand frames auto scroll selection and bound the rendered window`() =
        runDesktopComposeUiTest(width = 1440, height = 860) {
            val analysis = FrameJankAnalyzer().analyze((1L..10_001L).map { sample(it, 8_000_000L) })
            var selectedFrameId by mutableStateOf(9_500L)
            setContent {
                ViewerTheme(darkTheme = false) {
                    FrameProfilerScreen(
                        FrameProfilerState(analysis = analysis, selectedFrameId = selectedFrameId),
                        FrameProfilerActions(onSelectFrame = { selectedFrameId = it }),
                        UiLanguage.ENGLISH,
                    )
                }
            }

            onNodeWithTag("frame-timeline-canvas").assertExists()
            onNodeWithTag("frame-timeline-range").assertTextContains("Frames 9250–9749 of 10001")
            onNodeWithText("Zoom out").performClick()
            onNodeWithTag("frame-timeline-range").assertTextContains("Frames 9000–9999 of 10001")
            onNodeWithText("Zoom out").assertExists()
            onNodeWithText("Zoom in").performClick()
            runOnIdle { selectedFrameId = 10_001L }
            onNodeWithTag("frame-timeline-range").assertTextContains("Frames 9502–10001 of 10001")
        }

    @Test
    fun `selected problem frame scrolls both timeline and problem list into view`() =
        runDesktopComposeUiTest(width = 1440, height = 860) {
            val analysis = FrameJankAnalyzer().analyze((1L..2_000L).map { sample(it, 24_000_000L) })
            var selectedFrameId by mutableStateOf(1L)
            setContent {
                ViewerTheme(darkTheme = false) {
                    FrameProfilerScreen(
                        FrameProfilerState(analysis = analysis, selectedFrameId = selectedFrameId),
                        FrameProfilerActions(onSelectFrame = { selectedFrameId = it }),
                        UiLanguage.ENGLISH,
                    )
                }
            }

            runOnIdle { selectedFrameId = 1_800L }
            onNodeWithTag("frame-timeline-range").assertTextContains("Frames 1501–2000 of 2000")
            onNodeWithTag("problem-frame-row-1800", useUnmergedTree = true).assertIsDisplayed()
        }

    private fun assertStableTimelineHover(
        hasClusters: Boolean,
        language: UiLanguage,
    ) = runDesktopComposeUiTest(width = 1280, height = 820) {
        val durationNs = if (hasClusters) 24_000_000L else 8_000_000L
        val analysis = FrameJankAnalyzer().analyze((1L..3L).map { sample(it, durationNs) })
        assertEquals(hasClusters, analysis.clusters.isNotEmpty())
        setContent {
            ViewerTheme(darkTheme = true) {
                FrameProfilerScreen(FrameProfilerState(analysis = analysis), FrameProfilerActions(), language)
            }
        }

        val canvas = onNodeWithTag("frame-timeline-canvas")
        val canvasBounds = canvas.fetchSemanticsNode().boundsInRoot
        val panelTags = listOf("frame-clusters-panel", "frame-problems-panel", "frame-detail-panel")
        val panelBounds = panelTags.map { onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        val hint = if (hasClusters) "顶部标记表示截止时间未命中区间" else ""
        val frameLabel = if (language == UiLanguage.SIMPLIFIED_CHINESE) "帧" else "Frame"
        val duration = if (hasClusters) "24.00 ms" else "8.00 ms"
        val verdict = if (hasClusters) "MISSED" else "MET"

        (1..3).forEach { frameId ->
            canvas.performMouseInput {
                moveTo(Offset(1f, center.y))
                moveTo(Offset(center.x * (2 * frameId - 1) / 3f, center.y))
            }
            val hoverText = "$frameLabel #$frameId · $duration · $verdict"
            onNodeWithText(hoverText, substring = true).assertExists()
            assertEquals(panelBounds, panelTags.map { onNodeWithTag(it).fetchSemanticsNode().boundsInRoot })
            assertEquals(canvasBounds, canvas.fetchSemanticsNode().boundsInRoot)
            onNodeWithTag("frame-timeline-caption").assertTextEquals(
                if (hasClusters) "$hint  |  $hoverText" else hoverText,
            )
            canvas.performMouseInput { exit() }
            onNodeWithText(hoverText, substring = true).assertDoesNotExist()
            onNodeWithTag("frame-timeline-caption").assertTextEquals(hint)
            assertEquals(panelBounds, panelTags.map { onNodeWithTag(it).fetchSemanticsNode().boundsInRoot })
        }
    }

    private fun sample(
        frameId: Long,
        durationNs: Long,
    ): FrameSample =
        FrameSample(
            frameId = frameId,
            sessionId = "acceptance-session",
            source = FrameSource.PERFETTO,
            activityName = "com.example.GalleryActivity",
            intendedVsyncNs = frameId * 20_000_000L,
            totalDurationNs = durationNs,
            expectedDurationNs = 16_666_667L,
            expectedDurationSource = ExpectedDurationSource.FRAME_INTERVAL,
            states = mapOf("screen" to "Gallery"),
        )
}
