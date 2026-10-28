@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "MagicNumber", "ktlint:standard:function-naming")

package com.androidperformancestudio.frame.presentation

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FrameAnalysisWorkspaceTest {
    @Test
    fun `one card has contiguous full size slots and centered seven dp dividers`() {
        UiLanguage.entries.forEach { language ->
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { WorkspaceFixture(language = language, dark = language == UiLanguage.SIMPLIFIED_CHINESE) }

                val card = bounds(CARD)
                val timeline = bounds(TIMELINE)
                val body = bounds(BODY)
                val context = bounds(CONTEXT)
                assertEquals(card.top, timeline.top, 0.6f)
                assertEquals(card.bottom, context.bottom, 0.6f)
                listOf(TIMELINE, BODY, CONTEXT).forEach {
                    assertEquals(card.left, bounds(it).left, 0.6f)
                    assertEquals(card.right, bounds(it).right, 0.6f)
                }
                assertDivider(TIMELINE_RESIZER, timeline.bottom, body.top, vertical = false)
                assertDivider(CONTEXT_RESIZER, body.bottom, context.top, vertical = false)
                assertDivider(CLUSTERS_RESIZER, bounds(CLUSTERS).right, bounds(PROBLEMS).left, vertical = true)
                assertDivider(DETAILS_RESIZER, bounds(PROBLEMS).right, bounds(DETAILS).left, vertical = true)
                assertEquals(card.left, bounds(CLUSTERS).left, 0.6f)
                assertEquals(card.right, bounds(DETAILS).right, 0.6f)
                listOf(CLUSTERS, PROBLEMS, DETAILS).forEach {
                    assertEquals(body.top, bounds(it).top, 0.6f)
                    assertEquals(body.bottom, bounds(it).bottom, 0.6f)
                }
                assertSlotBounds()
                onNodeWithTag(SCROLLBAR).assertDoesNotExist()
                assertEquals(0.30f, progress(TIMELINE_RESIZER).current, 0.001f)
                assertEquals(0.80f, progress(CONTEXT_RESIZER).current, 0.001f)
                assertEquals(0.258f, progress(CLUSTERS_RESIZER).current, 0.001f)
                assertEquals(0.697f, progress(DETAILS_RESIZER).current, 0.001f)
            }
        }
    }

    @Test
    fun `dragging all four dividers changes only their neighboring panes`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent { WorkspaceFixture() }
            val card = bounds(CARD)
            val originalTimeline = bounds(TIMELINE).height
            val originalContext = bounds(CONTEXT).height
            val originalClusters = bounds(CLUSTERS).width
            val originalDetails = bounds(DETAILS).width

            drag(TIMELINE_RESIZER, Offset(0f, 64f))
            assertEquals(originalTimeline + 64f, bounds(TIMELINE).height, 1f)
            assertEquals(originalContext, bounds(CONTEXT).height, 1f)
            drag(CONTEXT_RESIZER, Offset(0f, 48f))
            assertEquals(originalContext - 48f, bounds(CONTEXT).height, 1f)
            assertEquals(originalTimeline + 64f, bounds(TIMELINE).height, 1f)
            drag(CLUSTERS_RESIZER, Offset(64f, 0f))
            assertEquals(originalClusters + 64f, bounds(CLUSTERS).width, 1f)
            assertEquals(originalDetails, bounds(DETAILS).width, 1f)
            drag(DETAILS_RESIZER, Offset(48f, 0f))
            assertEquals(originalDetails - 48f, bounds(DETAILS).width, 1f)
            assertEquals(originalClusters + 64f, bounds(CLUSTERS).width, 1f)
            drag(CLUSTERS_RESIZER, Offset(-128f, 0f))
            drag(TIMELINE_RESIZER, Offset(0f, -128f))
            assertEquals(originalClusters - 64f, bounds(CLUSTERS).width, 1f)
            assertEquals((originalTimeline - 64f).coerceAtLeast(184f), bounds(TIMELINE).height, 1f)
            assertEquals(card, bounds(CARD))
            assertUsablePanes(184f)
            assertSlotBounds()
        }

    @Test
    fun `every divider supports focus arrow keys and bounded accessibility progress`() {
        val directions =
            listOf(
                Triple(TIMELINE_RESIZER, Key.DirectionDown, Key.DirectionUp),
                Triple(CONTEXT_RESIZER, Key.DirectionDown, Key.DirectionUp),
                Triple(CLUSTERS_RESIZER, Key.DirectionRight, Key.DirectionLeft),
                Triple(DETAILS_RESIZER, Key.DirectionRight, Key.DirectionLeft),
            )
        directions.forEach { (tag, forward, backward) ->
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { WorkspaceFixture() }
                val vertical = tag == CLUSTERS_RESIZER || tag == DETAILS_RESIZER
                val divider = onNodeWithTag(tag)
                val original = bounds(tag)
                divider.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
                divider.performKeyInput { pressKey(forward) }
                val moved = bounds(tag)
                assertEquals(16f, if (vertical) moved.left - original.left else moved.top - original.top, 1f)
                divider.performKeyInput { pressKey(backward) }
                assertEquals(original, bounds(tag))
                divider.performKeyInput { pressKey(if (vertical) Key.DirectionDown else Key.DirectionRight) }
                assertEquals(original, bounds(tag))

                setProgress(tag, -1f)
                assertEquals(progress(tag).range.start, progress(tag).current, 0.0001f)
                when (tag) {
                    TIMELINE_RESIZER -> assertEquals(184f, bounds(TIMELINE).height, 1f)
                    CONTEXT_RESIZER -> assertEquals(140f, bounds(BODY).height, 1f)
                    CLUSTERS_RESIZER -> assertEquals(180f, bounds(CLUSTERS).width, 1f)
                    DETAILS_RESIZER -> assertEquals(260f, bounds(PROBLEMS).width, 1f)
                }
                setProgress(tag, 2f)
                assertEquals(progress(tag).range.endInclusive, progress(tag).current, 0.0001f)
                when (tag) {
                    TIMELINE_RESIZER -> assertEquals(140f, bounds(BODY).height, 1f)
                    CONTEXT_RESIZER -> assertEquals(96f, bounds(CONTEXT).height, 1f)
                    CLUSTERS_RESIZER -> assertEquals(260f, bounds(PROBLEMS).width, 1f)
                    DETAILS_RESIZER -> assertEquals(260f, bounds(DETAILS).width, 1f)
                }
                val beforeInvalid = progress(tag)
                divider.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(!it(Float.NaN)) }
                assertEquals(beforeInvalid, progress(tag))
                assertUsablePanes(184f)
            }
        }
    }

    @Test
    fun `ratios survive slot data language theme and viewport updates`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            var width by mutableStateOf(1280.dp)
            var height by mutableStateOf(800.dp)
            var version by mutableStateOf(0)
            var language by mutableStateOf(UiLanguage.ENGLISH)
            setContent {
                WorkspaceFixture(width, height, language = language, dark = version != 0, version = version)
            }
            setProgress(TIMELINE_RESIZER, 0.36f)
            setProgress(CONTEXT_RESIZER, 0.76f)
            setProgress(CLUSTERS_RESIZER, 0.30f)
            setProgress(DETAILS_RESIZER, 0.72f)
            val tags = listOf(TIMELINE_RESIZER, CONTEXT_RESIZER, CLUSTERS_RESIZER, DETAILS_RESIZER)
            val originalProgress = tags.associateWith { progress(it).current }
            val originalBounds = listOf(TIMELINE, CONTEXT, CLUSTERS, PROBLEMS, DETAILS).associateWith { bounds(it) }
            onNodeWithTag(CLUSTERS_RESIZER).assertContentDescriptionEquals("Deadline Miss Clusters / Problem Frames")
            val englishLabel = onNodeWithTag(CLUSTERS_RESIZER).fetchSemanticsNode().config[SemanticsProperties.ContentDescription]

            runOnIdle {
                version = 1
                language = UiLanguage.SIMPLIFIED_CHINESE
            }
            assertNotEquals(
                englishLabel,
                onNodeWithTag(CLUSTERS_RESIZER).fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
            )
            originalBounds.forEach { (tag, original) -> assertEquals(original, bounds(tag)) }
            assertSlotBounds(version = 1)
            runOnIdle {
                width = 600.dp
                height = 300.dp
            }
            assertUsablePanes(248f)
            onNodeWithTag(CONTEXT).performScrollTo().assertIsDisplayed()
            onNodeWithTag(DETAILS).performScrollTo().assertIsDisplayed()
            runOnIdle {
                width = 1280.dp
                height = 800.dp
            }
            originalProgress.forEach { (tag, fraction) -> assertEquals(fraction, progress(tag).current, 0.0001f) }
            originalBounds.forEach { (tag, original) -> assertEquals(original, bounds(tag)) }
        }

    @Test
    fun `context hides without a gap or divider and restores its remembered size`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            var visible by mutableStateOf(true)
            setContent { WorkspaceFixture(showContext = visible) }
            setProgress(CONTEXT_RESIZER, 0.70f)
            val originalContext = bounds(CONTEXT)
            val originalBody = bounds(BODY)
            runOnIdle { visible = false }
            onNodeWithTag(CONTEXT).assertDoesNotExist()
            onNodeWithTag(CONTEXT_RESIZER).assertDoesNotExist()
            onNodeWithTag("slot-context-0").assertDoesNotExist()
            assertEquals(bounds(CARD).bottom, bounds(BODY).bottom, 0.6f)
            assertTrue(bounds(BODY).height > originalBody.height + originalContext.height)
            assertDivider(TIMELINE_RESIZER, bounds(TIMELINE).bottom, bounds(BODY).top, vertical = false)
            runOnIdle { visible = true }
            assertEquals(originalContext, bounds(CONTEXT))
            assertEquals(originalBody, bounds(BODY))
        }

    @Test
    fun `only middle row scrolls horizontally and scrollbar reserves exactly its real thickness`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            var width by mutableStateOf(714.dp)
            var scrollbarThickness = 0.dp
            setContent {
                scrollbarThickness = LocalScrollbarStyle.current.thickness
                WorkspaceFixture(width = width)
            }
            onNodeWithTag(SCROLLBAR).assertDoesNotExist()
            assertUsablePanes(248f)
            assertEquals(bounds(BODY).bottom, bounds(CLUSTERS).bottom, 0.6f)
            runOnIdle { width = 600.dp }
            val timeline = bounds(TIMELINE)
            val context = bounds(CONTEXT)
            val scrollbar = bounds(SCROLLBAR)
            assertEquals(scrollbarThickness.value, scrollbar.height, 0.6f)
            assertEquals(bounds(BODY).bottom, scrollbar.bottom, 0.6f)
            listOf(CLUSTERS, PROBLEMS, DETAILS).forEach {
                assertEquals(scrollbar.top, bounds(it).bottom, 0.6f)
            }
            assertEquals(180f, bounds(CLUSTERS).width, 0.6f)
            assertEquals(260f, bounds(PROBLEMS).width, 0.6f)
            assertEquals(260f, bounds(DETAILS).width, 0.6f)
            onNodeWithTag(DETAILS).performScrollTo().assertIsDisplayed()
            assertEquals(timeline, bounds(TIMELINE))
            assertEquals(context, bounds(CONTEXT))
            runOnIdle { width = 1280.dp }
            onNodeWithTag(SCROLLBAR).assertDoesNotExist()
            assertEquals(bounds(BODY).bottom, bounds(CLUSTERS).bottom, 0.6f)
        }

    @Test
    fun `short workspace keeps scaled minimum heights scroll reachable inside the card`() {
        listOf(900 to 1f, 600 to 1f, 900 to 1.5f, 600 to 1.5f).forEach { (width, scale) ->
            runDesktopComposeUiTest(width = width, height = 300) {
                setContent { WorkspaceFixture(width = width.dp, height = 300.dp, fontScale = scale) }
                val timelineMinimum = (if (width >= 760) 184f else 248f) * scale
                assertUsablePanes(timelineMinimum, scale)
                assertEquals(300f, bounds(CARD).height, 0.6f)
                assertTrue(bounds(CONTEXT).bottom > bounds(CARD).bottom)
                onNodeWithTag(TIMELINE).performScrollTo().assertIsDisplayed()
                onNodeWithTag(CONTEXT).performScrollTo().assertIsDisplayed()
                assertEquals(bounds(CARD).bottom, bounds(CONTEXT).bottom, 1f)
                assertUsablePanes(timelineMinimum, scale)
            }
        }
    }

    @Test
    fun `overflowing slot paint stays out of every divider before and after resizing`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent { WorkspaceFixture(overflow = true) }
            repeat(2) { pass ->
                if (pass != 0) {
                    setProgress(TIMELINE_RESIZER, 0.36f)
                    setProgress(DETAILS_RESIZER, 0.74f)
                }
                val pixels = onNodeWithTag(CARD).captureToImage().toPixelMap()
                val overflowColor = Color.Magenta.toArgb()
                listOf(TIMELINE, CLUSTERS, PROBLEMS, DETAILS, CONTEXT).forEach { tag ->
                    val center = bounds(tag).center
                    assertEquals(overflowColor, pixels[center.x.toInt(), center.y.toInt()].toArgb())
                }
                listOf(TIMELINE_RESIZER, CONTEXT_RESIZER, CLUSTERS_RESIZER, DETAILS_RESIZER).forEach { tag ->
                    val divider = bounds(tag)
                    val center = divider.center
                    assertNotEquals(overflowColor, pixels[center.x.toInt(), center.y.toInt()].toArgb())
                    assertNotEquals(
                        overflowColor,
                        pixels[(divider.left + 1).toInt(), (divider.top + 1).toInt()].toArgb(),
                    )
                }
            }
        }

    private fun DesktopComposeUiTest.assertDivider(
        tag: String,
        precedingEdge: Float,
        followingEdge: Float,
        vertical: Boolean,
    ) {
        val target = bounds(tag)
        val line = bounds("$tag-line")
        assertEquals(precedingEdge, if (vertical) target.left else target.top, 0.6f)
        assertEquals(followingEdge, if (vertical) target.right else target.bottom, 0.6f)
        assertEquals(7f, if (vertical) target.width else target.height, 0.6f)
        assertEquals(1f, if (vertical) line.width else line.height, 0.6f)
        assertEquals(
            if (vertical) target.center.x else target.center.y,
            if (vertical) line.center.x else line.center.y,
            0.6f,
        )
    }

    private fun DesktopComposeUiTest.assertUsablePanes(
        timelineMinimum: Float,
        fontScale: Float = 1f,
    ) {
        assertTrue(bounds(TIMELINE).height >= timelineMinimum - 1f)
        assertTrue(bounds(CLUSTERS).height >= 140f * fontScale - 1f)
        assertTrue(bounds(CONTEXT).height >= 96f * fontScale - 1f)
        assertTrue(bounds(CLUSTERS).width >= 179f)
        assertTrue(bounds(PROBLEMS).width >= 259f)
        assertTrue(bounds(DETAILS).width >= 259f)
    }

    private fun DesktopComposeUiTest.assertSlotBounds(version: Int = 0) {
        listOf("timeline", "clusters", "problems", "details", "context").forEach { pane ->
            assertEquals(bounds("frame-workspace-$pane"), bounds("slot-$pane-$version"))
        }
    }

    private fun DesktopComposeUiTest.bounds(tag: String): Rect {
        val bounds = onNodeWithTag(tag).getUnclippedBoundsInRoot()
        return Rect(bounds.left.value, bounds.top.value, bounds.right.value, bounds.bottom.value)
    }

    private fun DesktopComposeUiTest.progress(tag: String): ProgressBarRangeInfo =
        onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]

    private fun DesktopComposeUiTest.setProgress(
        tag: String,
        fraction: Float,
    ) {
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(fraction)) }
    }

    private fun DesktopComposeUiTest.drag(
        tag: String,
        delta: Offset,
    ) {
        onNodeWithTag(tag).performMouseInput {
            moveTo(center)
            press()
            moveBy(delta, delayMillis = 100L)
            release()
        }
    }

    @Composable
    private fun WorkspaceFixture(
        width: Dp = 1280.dp,
        height: Dp = 800.dp,
        language: UiLanguage = UiLanguage.ENGLISH,
        showContext: Boolean = true,
        dark: Boolean = false,
        version: Int = 0,
        fontScale: Float = 1f,
        overflow: Boolean = false,
    ) {
        ViewerTheme(darkTheme = dark) {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                Box(Modifier.width(width).height(height)) {
                    val paint =
                        if (overflow) {
                            Modifier.drawBehind {
                                drawRect(Color.Magenta, topLeft = Offset(-4096f, -4096f), size = Size(8192f, 8192f))
                            }
                        } else {
                            Modifier
                        }
                    FrameAnalysisWorkspace(
                        language,
                        showContext,
                        Modifier.fillMaxSize(),
                        timeline = { Box(it.then(paint).testTag("slot-timeline-$version")) },
                        clusters = { Box(it.then(paint).testTag("slot-clusters-$version")) },
                        problems = { Box(it.then(paint).testTag("slot-problems-$version")) },
                        details = { Box(it.then(paint).testTag("slot-details-$version")) },
                        context = { Box(it.then(paint).testTag("slot-context-$version")) },
                    )
                }
            }
        }
    }

    private companion object {
        const val CARD = "frame-analysis-card"
        const val TIMELINE = "frame-workspace-timeline"
        const val BODY = "frame-workspace-body"
        const val CONTEXT = "frame-workspace-context"
        const val CLUSTERS = "frame-workspace-clusters"
        const val PROBLEMS = "frame-workspace-problems"
        const val DETAILS = "frame-workspace-details"
        const val TIMELINE_RESIZER = "frame-timeline-height-resizer"
        const val CONTEXT_RESIZER = "frame-context-height-resizer"
        const val CLUSTERS_RESIZER = "frame-clusters-width-resizer"
        const val DETAILS_RESIZER = "frame-details-width-resizer"
        const val SCROLLBAR = "frame-workspace-horizontal-scrollbar"
    }
}
