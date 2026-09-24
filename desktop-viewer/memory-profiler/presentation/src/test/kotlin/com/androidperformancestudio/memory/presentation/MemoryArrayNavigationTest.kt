package com.androidperformancestudio.memory.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class MemoryArrayNavigationTest {
    @Test
    fun `final partial page returns to the preceding full page`() {
        val finalPage = arrayDetail(start = 400, count = 450, visible = 50)

        assertEquals(200, finalPage.previousArrayPageStart())
        assertNull(finalPage.nextArrayPageStart())
        assertEquals(400, arrayDetail(start = 200, count = 450, visible = 200).nextArrayPageStart())
    }

    @Test
    fun `unavailable array values do not offer a misleading next page`() {
        val unavailable = arrayDetail(start = 0, count = 450, visible = 0)

        assertNull(unavailable.previousArrayPageStart())
        assertNull(unavailable.nextArrayPageStart())
        assertNull(arrayDetail(start = 200, count = 450, visible = 2).nextArrayPageStart())
    }

    @Test
    fun `array inspector shows absolute range and navigates in both directions`() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            val loadedRanges = mutableListOf<Pair<Long, Int>>()
            setContent {
                MemoryProfilerScreen(
                    state =
                        MemoryProfilerState(
                            viewMode = MemoryProfilerViewMode.ClassList,
                            selectedClassName = "example.Array[]",
                            selectedInstanceDetail = arrayDetail(start = 200, count = 450, visible = 200),
                        ),
                    actions = MemoryProfilerActions(onLoadArrayRange = { id, start -> loadedRanges += id to start }),
                )
            }

            onNodeWithText("Elements 201–400 of 450").assertExists()
            onNodeWithText("Previous elements").performClick()
            onNodeWithText("Next elements").performClick()
            assertEquals(listOf(1L to 0, 1L to 400), loadedRanges)
        }

    @Test
    fun `array inspector labels unavailable values without implying more pages`() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            setContent {
                MemoryProfilerScreen(
                    state =
                        MemoryProfilerState(
                            viewMode = MemoryProfilerViewMode.ClassList,
                            selectedClassName = "example.Array[]",
                            selectedInstanceDetail = arrayDetail(start = 0, count = 450, visible = 0),
                        ),
                    actions = MemoryProfilerActions(),
                )
            }

            onNodeWithText("Array values are unavailable in this parsed evidence").assertExists()
            onNodeWithText("Next elements").assertDoesNotExist()
        }

    private fun arrayDetail(
        start: Int,
        count: Int,
        visible: Int,
    ): MemoryInstanceDetail =
        MemoryInstanceDetail(
            objectId = 1,
            className = "example.Array[]",
            shallowSize = 0,
            retainedSize = null,
            depth = null,
            isArray = true,
            elementCount = count,
            arrayStart = start,
            arrayPageSize = 200,
            fields = List(visible) { MemoryInstanceField("[${start + it}]", "value", null, null) },
            referenceChain = emptyList(),
        )
}
