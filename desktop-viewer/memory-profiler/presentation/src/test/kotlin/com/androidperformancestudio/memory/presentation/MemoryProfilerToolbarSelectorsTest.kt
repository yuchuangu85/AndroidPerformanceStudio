package com.androidperformancestudio.memory.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.ui.HeaderToolbar
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MemoryProfilerToolbarSelectorsTest {
    @Test
    fun `heap filter row halves vertical padding without shrinking its controls`() {
        listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
            runDesktopComposeUiTest(width = 900, height = 120) {
                setContent {
                    ViewerTheme(darkTheme = language == UiLanguage.SIMPLIFIED_CHINESE) {
                        MemoryPrimaryFilterBar(
                            MemoryProfilerState(availableHeaps = listOf("App", "Zygote")),
                            MemoryProfilerActions(),
                            language,
                        )
                    }
                }
                val row = onNodeWithTag("memory-primary-filter-bar").fetchSemanticsNode().boundsInRoot
                val search = onNodeWithTag("memory-workspace-search").fetchSemanticsNode().boundsInRoot
                val heapLabel = if (language == UiLanguage.ENGLISH) "Heap" else "堆"
                val heap = onNodeWithContentDescription(heapLabel).fetchSemanticsNode().boundsInRoot
                assertEquals(38f, row.height, 0.6f)
                assertEquals(32f, search.height, 0.6f)
                assertEquals(3f, search.top - row.top, 0.6f)
                assertEquals(3f, row.bottom - search.bottom, 0.6f)
                assertEquals(search.center.y, heap.center.y, 0.6f)
            }
        }
    }

    @Test
    fun `top toolbar selectors keep compact sizing and dispatch selections`() =
        runDesktopComposeUiTest(width = 1000, height = 700) {
            val events = mutableListOf<String>()
            setContent {
                HeaderToolbar(
                    language = UiLanguage.ENGLISH,
                    onNavigateHome = null,
                    onNavigateSettings = null,
                ) {
                    MemoryProfilerToolbarSelectors(
                        state =
                            MemoryProfilerState(
                                devices = listOf(MemoryDeviceOption("emulator-5554", "Pixel 8")),
                                selectedDeviceSerial = "emulator-5554",
                                processes = listOf(MemoryProcessOption(pid = 42, name = "com.example")),
                                selectedProcessId = 42,
                            ),
                        onSelectDevice = { events += "device:$it" },
                        onSelectProcess = { events += "process:$it" },
                    )
                }
            }

            val device = onNodeWithContentDescription("Device selector").fetchSemanticsNode().boundsInRoot
            val process = onNodeWithContentDescription("Process selector").fetchSemanticsNode().boundsInRoot
            assertTrue(device.height <= 23f)
            assertEquals(device.height, process.height)
            assertTrue(process.left > device.right)

            onNodeWithContentDescription("Device selector").performClick()
            onNode(hasText("Pixel 8") and isSelected()).performClick()
            onNodeWithContentDescription("Process selector").performClick()
            onNode(hasText("com.example (42)") and isSelected()).performClick()

            assertEquals(listOf("device:emulator-5554", "process:42"), events)
        }
}
