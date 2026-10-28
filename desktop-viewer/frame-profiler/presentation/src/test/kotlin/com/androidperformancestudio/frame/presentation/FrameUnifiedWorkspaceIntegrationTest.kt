package com.androidperformancestudio.frame.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.frame.analysis.FRAME_TIMELINE_JANK_TAG
import com.androidperformancestudio.frame.analysis.FrameJankAnalyzer
import com.androidperformancestudio.frame.model.ExpectedDurationSource
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import com.androidperformancestudio.frame.model.FrameStages
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FrameUnifiedWorkspaceIntegrationTest {
    @Test
    fun `timeline height resizes its plot and preserves selected frame and locale state`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            var state by mutableStateOf(FrameProfilerState(analysis = fixture(), selectedFrameId = 1L))
            var language by mutableStateOf(UiLanguage.ENGLISH)
            setContent {
                ViewerTheme(darkTheme = false) {
                    FrameProfilerScreen(
                        state,
                        FrameProfilerActions(onSelectFrame = { state = state.copy(selectedFrameId = it) }),
                        language,
                    )
                }
            }

            onNodeWithTag("problem-frame-row-3", useUnmergedTree = true).performClick()
            assertEquals(3L, state.selectedFrameId)
            val card = bounds("frame-analysis-card")
            val beforeTimeline = bounds("frame-workspace-timeline")
            val beforePlot = bounds("frame-timeline-canvas")
            drag("frame-timeline-height-resizer", Offset(0f, 48f))
            val afterTimeline = bounds("frame-workspace-timeline")
            val afterPlot = bounds("frame-timeline-canvas")
            assertTrue(afterTimeline.height > beforeTimeline.height)
            assertEquals(afterTimeline.height - beforeTimeline.height, afterPlot.height - beforePlot.height, 1f)
            assertEquals(card, bounds("frame-analysis-card"))
            assertEquals(3L, state.selectedFrameId)

            val beforeClusters = bounds("frame-workspace-clusters")
            drag("frame-clusters-width-resizer", Offset(40f, 0f))
            val resizedClusters = bounds("frame-workspace-clusters")
            assertTrue(resizedClusters.width > beforeClusters.width)
            runOnIdle { language = UiLanguage.SIMPLIFIED_CHINESE }
            assertEquals(afterTimeline, bounds("frame-workspace-timeline"))
            assertEquals(resizedClusters, bounds("frame-workspace-clusters"))
            assertEquals(3L, state.selectedFrameId)
        }

    @Test
    fun `one card fills the remaining page and all sections remain reachable across window sizes and themes`() {
        val sizes = listOf(760 to 560, 760 to 860, 1280 to 560, 1280 to 860)
        sizes.forEach { (width, height) ->
            listOf(false, true).forEach { dark ->
                listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
                    checkWorkspace(width, height, dark, language)
                }
            }
        }
    }

    private fun checkWorkspace(
        width: Int,
        height: Int,
        dark: Boolean,
        language: UiLanguage,
    ) = runDesktopComposeUiTest(width = width, height = height) {
        setContent {
            ViewerTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().testTag("frame-unified-capture")) {
                    FrameProfilerScreen(FrameProfilerState(analysis = fixture()), FrameProfilerActions(), language)
                }
            }
        }

        val card = bounds("frame-analysis-card")
        assertEquals(4f, card.left)
        assertEquals(width - 4f, card.right)
        assertEquals(height - 4f, card.bottom)
        assertTrue(card.top > bounds("frame-metric-frames").bottom)
        onNodeWithTag("frame-timeline-height-resizer").assertExists()
        onNodeWithTag("frame-context-height-resizer").assertExists()

        val image = onNodeWithTag("frame-unified-capture").captureToImage()
        val path = Path.of("build/reports/frame-unified-workspace/frame-$width-$height-$dark-${language.name}.png")
        Files.createDirectories(path.parent)
        Files.write(path, requireNotNull(Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData()).bytes)

        onNodeWithTag("frame-context-panel").performScrollTo()
        onNodeWithTag("frame-attribution-entry-4").performScrollTo().assertIsDisplayed()
        onNodeWithTag("frame-clusters-panel").performScrollTo().assertIsDisplayed()
        onNodeWithTag("frame-problems-panel").performScrollTo().assertIsDisplayed()
        onNodeWithTag("frame-detail-panel").performScrollTo().assertIsDisplayed()
        onNodeWithTag("frame-layout-preview-action").performScrollTo().assertIsDisplayed()
    }

    private fun DesktopComposeUiTest.bounds(tag: String): Rect = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun DesktopComposeUiTest.drag(
        tag: String,
        amount: Offset,
    ) {
        onNodeWithTag(tag).performMouseInput {
            moveTo(center)
            press()
            moveBy(amount / 2f)
            moveBy(amount / 2f)
            release()
        }
        waitForIdle()
    }

    private fun fixture() =
        FrameJankAnalyzer().analyze(
            (1L..40L).map { id ->
                FrameSample(
                    frameId = id,
                    sessionId = "unified-workspace",
                    source = FrameSource.PERFETTO,
                    packageName = "com.example.gallery",
                    activityName = "GalleryActivity${id % 5}",
                    intendedVsyncNs = id * 50_000_000L,
                    totalDurationNs = 24_000_000L,
                    expectedDurationNs = 16_666_667L,
                    expectedDurationSource = ExpectedDurationSource.PLATFORM_DEADLINE,
                    stages = FrameStages(drawNs = 8_000_000L),
                    platformJank = true,
                    states = mapOf(FRAME_TIMELINE_JANK_TAG to "Self Jank"),
                )
            },
        )
}
