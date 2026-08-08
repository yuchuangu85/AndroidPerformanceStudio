package com.androidperformancestudio.memory.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.ui.ProfilerMacOsToolbar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MemoryProfilerToolbarSelectorsTest {
    @Test
    fun `top toolbar selectors keep compact sizing and dispatch selections`() =
        runDesktopComposeUiTest(width = 1000, height = 700) {
            val events = mutableListOf<String>()
            setContent {
                ProfilerMacOsToolbar {
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
            assertTrue(device.height <= MEMORY_TOOLBAR_BUTTON_HEIGHT_DP + 1f)
            assertEquals(device.height, process.height)

            onNodeWithContentDescription("Device selector").performClick()
            onNode(hasText("Pixel 8") and isSelected()).performClick()
            onNodeWithContentDescription("Process selector").performClick()
            onNode(hasText("com.example (42)") and isSelected()).performClick()

            assertEquals(listOf("device:emulator-5554", "process:42"), events)
        }
}
