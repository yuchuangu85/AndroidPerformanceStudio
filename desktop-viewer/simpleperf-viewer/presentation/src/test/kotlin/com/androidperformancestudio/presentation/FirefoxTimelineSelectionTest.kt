package com.androidperformancestudio.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.ui.viewerColors
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FirefoxTimelineSelectionTest {
    @Test
    fun `aggregate data never pretends the first visible thread is selected`() =
        runDesktopComposeUiTest(width = 1000, height = 300) {
            var state by mutableStateOf(sampleReportState())
            setContent {
                TimelineReport(
                    state,
                    state.lastReadyReport!!,
                    goldenActions().copy(
                        onThreads = { state = state.copy(filter = state.filter.copy(threadIds = it)) },
                    ),
                    viewerColors(false),
                )
            }
            val main = onNodeWithContentDescription("Timeline track main (7421)")
            val render = onNodeWithContentDescription("Timeline track RenderThread (7440)")
            main.assertIsNotSelected()
            render.assertIsNotSelected()
            onNodeWithText("RenderThread").performClick()
            assertEquals(setOf(7440), state.filter.threadIds)
            render.assertIsSelected()
            main.assertIsNotSelected()
            state = state.copy(filter = state.filter.copy(threadIds = emptySet()))
            waitForIdle()
            render.assertIsNotSelected()
            main.assertIsNotSelected()
        }
}
