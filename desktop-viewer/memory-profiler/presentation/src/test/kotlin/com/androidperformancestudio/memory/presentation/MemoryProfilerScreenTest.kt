@file:Suppress("MaxLineLength")

package com.androidperformancestudio.memory.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapSummary
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MemoryProfilerScreenTest {
    @Test
    fun `initial state displays selectors dump and import entry`() =
        runDesktopComposeUiTest(width = 1000, height = 700) {
            setContent {
                MemoryProfilerScreen(
                    state = MemoryProfilerState(),
                    actions = MemoryProfilerActions(),
                )
            }

            onNodeWithContentDescription("Device selector").assertExists()
            onNodeWithContentDescription("Process selector").assertExists()
            onNodeWithText("Dump Heap").assertExists().assertIsNotEnabled()
            onNodeWithText("Import hprof").assertExists()
        }

    @Test
    fun `loaded heap shows overview histogram and phase two placeholders`() =
        runDesktopComposeUiTest(width = 1000, height = 700) {
            setContent {
                MemoryProfilerScreen(
                    state = loadedState(),
                    actions = MemoryProfilerActions(),
                )
            }

            onNodeWithText("Overview").assertExists()
            onNodeWithText("Heap Size").assertExists()
            onNodeWithText("6.0 MB").assertExists()
            onAllNodesWithText("12,451")[0].assertExists()
            onNodeWithText("java.lang.String").assertExists()
            onNodeWithText("byte[]").assertExists()
            onNodeWithText("Retained").assertExists()
            onAllNodesWithText("Phase 2 available")[0].assertExists()
            onNodeWithText("Leak Suspects").assertExists()
        }

    @Test
    fun `busy state disables import and shows progress feedback`() =
        runDesktopComposeUiTest(width = 1000, height = 700) {
            setContent {
                MemoryProfilerScreen(
                    state =
                        MemoryProfilerState(
                            isDumping = true,
                            operationMessage = "Importing sample.hprof…",
                        ),
                    actions = MemoryProfilerActions(),
                )
            }

            onNodeWithText("Working…").assertExists().assertIsNotEnabled()
            onNodeWithText("In progress").assertExists()
            onNodeWithText("Importing sample.hprof…").assertExists()
        }

    @Test
    fun `toolbar selectors dump import and sort actions are wired`() =
        runDesktopComposeUiTest(width = 1000, height = 700) {
            val events = mutableListOf<String>()
            setContent {
                MemoryProfilerScreen(
                    state = loadedState(),
                    actions =
                        MemoryProfilerActions(
                            onSelectDevice = { events += "device:$it" },
                            onSelectProcess = { events += "process:$it" },
                            onDumpHeap = { events += "dump" },
                            onImportHprof = { events += "import" },
                            onSortHistogram = { events += "sort:$it" },
                        ),
                )
            }

            onNodeWithContentDescription("Device selector").performClick()
            onAllNodesWithText("Pixel 8")[1].performClick()
            onNodeWithContentDescription("Process selector").performClick()
            onNodeWithText("com.example (42)").performClick()
            onNodeWithText("Dump Heap").performClick()
            onNodeWithText("Import hprof").performClick()
            onNodeWithText("Shallow").performClick()
            onNodeWithText("Count ↓").performClick()

            assertEquals(
                listOf("device:emulator-5554", "process:42", "dump", "import", "sort:Shallow", "sort:Count"),
                events,
            )
        }

    @Test
    fun `error and cleanup warning are rendered with retry action`() =
        runDesktopComposeUiTest(width = 1000, height = 700) {
            var retryCount = 0
            setContent {
                MemoryProfilerScreen(
                    state =
                        loadedState().copy(
                            error =
                                MemoryProfilerError(
                                    title = "Capture failed",
                                    detail = "Target app may not be debuggable or permission was denied.",
                                ),
                            warning = "Install SDK Platform Tools to enable standard HPROF conversion.",
                            cleanupWarning = "Could not remove /data/local/tmp/heap-1.hprof; local analysis is still available.",
                        ),
                    actions = MemoryProfilerActions(onRetry = { retryCount++ }),
                )
            }

            onNodeWithText("Capture failed").assertExists()
            onNodeWithText("Target app may not be debuggable or permission was denied.").assertExists()
            onNodeWithText("Cleanup warning").assertExists()
            onNodeWithText("Warning").assertExists()
            onNodeWithText("Install SDK Platform Tools to enable standard HPROF conversion.").assertExists()
            onNodeWithText("Could not remove /data/local/tmp/heap-1.hprof; local analysis is still available.").assertExists()
            onNodeWithText("Retry").performClick()
            assertEquals(1, retryCount)
        }

    private fun loadedState(): MemoryProfilerState =
        MemoryProfilerState(
            devices = listOf(MemoryDeviceOption("emulator-5554", "Pixel 8")),
            selectedDeviceSerial = "emulator-5554",
            processes = listOf(MemoryProcessOption(pid = 42, name = "com.example")),
            selectedProcessId = 42,
            summary = HeapSummary(objectCount = 12_451, shallowSize = 6L * 1024L * 1024L, classCount = 384),
            classes =
                listOf(
                    ClassStats("byte[]", instanceCount = 8_234, shallowSize = 4L * 1024L * 1024L),
                    ClassStats("java.lang.String", instanceCount = 12_451, shallowSize = 2L * 1024L * 1024L),
                ),
        )
}
