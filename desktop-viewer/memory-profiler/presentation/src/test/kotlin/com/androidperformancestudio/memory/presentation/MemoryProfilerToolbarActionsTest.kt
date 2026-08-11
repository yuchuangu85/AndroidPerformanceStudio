package com.androidperformancestudio.memory.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.ui.HeaderToolbar
import com.androidperformancestudio.ui.UiLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MemoryProfilerToolbarActionsTest {
    @Test
    fun `dump heap action dispatches from the top toolbar`() =
        runDesktopComposeUiTest(width = 1000, height = 120) {
            var dumpCount = 0
            setContent {
                HeaderToolbar(
                    language = UiLanguage.ENGLISH,
                    onNavigateHome = null,
                    onNavigateSettings = null,
                ) {
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
                HeaderToolbar(
                    language = UiLanguage.ENGLISH,
                    onNavigateHome = null,
                    onNavigateSettings = null,
                ) {
                    MemoryProfilerDumpHeapButton(
                        state = MemoryProfilerState(),
                        onDumpHeap = {},
                    )
                }
            }

            onNodeWithText("Dump Heap").assertIsNotEnabled()
        }

    @Test
    fun `bitmap dump is enabled only for supported selected device`() =
        runDesktopComposeUiTest(width = 1000, height = 120) {
            var dumpCount = 0
            setContent {
                HeaderToolbar(
                    language = UiLanguage.ENGLISH,
                    onNavigateHome = null,
                    onNavigateSettings = null,
                ) {
                    MemoryProfilerDumpBitmapsButton(
                        state =
                            MemoryProfilerState(
                                devices =
                                    listOf(
                                        MemoryDeviceOption(
                                            "old",
                                            "Old device",
                                            apiLevel = 34,
                                            supportsBitmapDump = false,
                                        ),
                                    ),
                                selectedDeviceSerial = "old",
                                selectedProcessId = 42,
                            ),
                        onDumpBitmaps = { dumpCount++ },
                    )
                }
            }

            onNodeWithText("Dump Bitmaps").assertIsNotEnabled().performClick()
            assertEquals(0, dumpCount)
        }
}
