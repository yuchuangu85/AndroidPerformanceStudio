package com.androidperformancestudio.frame.presentation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.frame.analysis.FRAME_TIMELINE_JANK_TAG
import com.androidperformancestudio.frame.analysis.FrameJankAnalyzer
import com.androidperformancestudio.frame.analysis.FrameTimelineStatus
import com.androidperformancestudio.frame.model.ExpectedDurationSource
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FrameTimelineColorsTest {
    @Test
    fun `the five states use Perfetto base colors rather than theme accents`() {
        assertEquals(Color(0xFF4CAF50), FrameTimelineStatus.GOOD.color(Color.Gray))
        assertEquals(Color(0xFFC0D588), FrameTimelineStatus.HIGH_LATENCY.color(Color.Gray))
        assertEquals(Color(0xFFFF5722), FrameTimelineStatus.SELF_JANK.color(Color.Gray))
        assertEquals(Color(0xFFFFEB3B), FrameTimelineStatus.OTHER_JANK.color(Color.Gray))
        assertEquals(Color(0xFF03A9F4), FrameTimelineStatus.DROPPED.color(Color.Gray))
        assertEquals(Color.Gray, FrameTimelineStatus.UNKNOWN.color(Color.Gray))
    }

    @Test
    fun `timeline pixels use platform status and the localized legend fits narrow and wide workspaces`() {
        listOf(760, 1280).forEach { width ->
            listOf(false, true).forEach { dark ->
                listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
                    checkTimeline(width, dark, language)
                }
            }
        }
    }

    private fun checkTimeline(
        width: Int,
        dark: Boolean,
        language: UiLanguage,
    ) = runDesktopComposeUiTest(width = width, height = 820) {
        val analysis = FrameJankAnalyzer().analyze(colorSamples())
        var unknownColor = Color.Gray
        setContent {
            ViewerTheme(darkTheme = dark) {
                unknownColor = LocalViewerColors.current.secondaryText
                FrameProfilerScreen(FrameProfilerState(analysis = analysis), FrameProfilerActions(), language)
            }
        }

        val canvas = onNodeWithTag("frame-timeline-canvas")
        val pixels = canvas.captureToImage().toPixelMap()
        FrameTimelineStatus.entries.forEachIndexed { index, status ->
            val x = ((index + 0.25f) * pixels.width / FrameTimelineStatus.entries.size).toInt()
            assertEquals(status.color(unknownColor).toArgb(), pixels[x, pixels.height - 3].toArgb(), status.name)
        }

        val legend = onNodeWithTag("frame-timeline-legend").fetchSemanticsNode().boundsInRoot
        val items =
            FrameTimelineStatus.entries.map { status ->
                onNodeWithTag("frame-timeline-legend-${status.name}").fetchSemanticsNode().boundsInRoot
            }
        items.forEach { item ->
            assertTrue(item.left >= legend.left && item.right <= legend.right, "Legend text must fit its width")
            assertTrue(item.top >= legend.top && item.bottom <= legend.bottom, "Legend rows must fit their height")
        }
        items.zipWithNext().forEach { (first, second) ->
            assertTrue(first.right <= second.left || first.bottom <= second.top, "Legend items must not overlap")
        }

        val image = onNodeWithTag("frame-timeline-panel").captureToImage()
        val path = Path.of("build/reports/frame-timeline-colors/timeline-$width-$dark-${language.name}.png")
        Files.createDirectories(path.parent)
        Files.write(path, requireNotNull(Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData()).bytes)
    }

    private fun colorSamples(): List<FrameSample> =
        listOf("No Jank", "Buffer Stuffing", "Self Jank", "Other Jank", "Dropped Frame", "Unspecified")
            .mapIndexed { index, tag ->
                FrameSample(
                    frameId = index.toLong(),
                    sessionId = "colors",
                    source = FrameSource.PERFETTO,
                    intendedVsyncNs = index * 50_000_000L,
                    expectedDurationNs = 16_000_000L,
                    expectedDurationSource = ExpectedDurationSource.PLATFORM_DEADLINE,
                    totalDurationNs = if (index == 0) 34_000_000L else 8_000_000L,
                    states = mapOf(FRAME_TIMELINE_JANK_TAG to tag),
                )
            }
}
