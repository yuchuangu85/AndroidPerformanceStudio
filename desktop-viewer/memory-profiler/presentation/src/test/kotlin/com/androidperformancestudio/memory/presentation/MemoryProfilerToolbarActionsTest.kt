package com.androidperformancestudio.memory.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.ui.ProfilerMacOsToolbar
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MemoryProfilerToolbarActionsTest {
    @Test
    fun `dump heap action dispatches from the top toolbar`() =
        runDesktopComposeUiTest(width = 1000, height = 120) {
            var dumpCount = 0
            setContent {
                ProfilerMacOsToolbar {
                    MemoryProfilerDumpHeapButton(
                        state =
                            MemoryProfilerState(
                                selectedDeviceSerial = "emulator-5554",
                                selectedProcessId = 42,
                            ),
                        onDumpHeap = { dumpCount++ },
                    )
                }
            }

            onNodeWithText("Dump Heap").performClick()
            assertEquals(1, dumpCount)
        }

    @Test
    fun `dump heap action is disabled until device and process are selected`() =
        runDesktopComposeUiTest(width = 1000, height = 120) {
            setContent {
                ProfilerMacOsToolbar {
                    MemoryProfilerDumpHeapButton(
                        state = MemoryProfilerState(),
                        onDumpHeap = {},
                    )
                }
            }

            onNodeWithText("Dump Heap").assertIsNotEnabled()
        }
}
