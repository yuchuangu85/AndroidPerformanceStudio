@file:Suppress("MaxLineLength")

package com.androidperformancestudio.memory.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapSummary
import com.androidperformancestudio.memory.model.LeakSuspect
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MemoryProfilerScreenTest {
    @Test
    fun `content does not duplicate toolbar or file actions`() =
        runDesktopComposeUiTest(width = 1000, height = 700) {
            setContent {
                MemoryProfilerScreen(
                    state = MemoryProfilerState(),
                    actions = MemoryProfilerActions(),
                )
            }

            onNodeWithContentDescription("Device selector").assertDoesNotExist()
            onNodeWithContentDescription("Process selector").assertDoesNotExist()
            onNodeWithText("Dump Heap").assertDoesNotExist()
            onNodeWithText("Import hprof").assertDoesNotExist()
        }

    @Test
    fun `loaded heap shows overview histogram and phase two analysis sections`() =
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
            onAllNodesWithText("Unavailable")[0].assertExists()
            onNodeWithText("Leak Suspects").assertExists()
            onNodeWithText("No leak suspects detected.").assertExists()
        }

    @Test
    fun `leak suspect confidence renders a literal percent sign`() =
        runDesktopComposeUiTest(width = 1000, height = 700) {
            setContent {
                MemoryProfilerScreen(
                    state =
                        loadedState().copy(
                            leakSuspects =
                                listOf(
                                    LeakSuspect(
                                        className = "com.example.LeakingActivity",
                                        reason = "Retained by singleton",
                                        retainedSize = 2L * 1024L,
                                        confidence = 0.85f,
                                    ),
                                ),
                        ),
                    actions = MemoryProfilerActions(),
                )
            }

            onNodeWithText("retained 2.0 KB · confidence 85%").assertExists()
        }

    @Test
    fun `busy state shows progress feedback without an import button`() =
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

            onNodeWithText("Working…").assertDoesNotExist()
            onNodeWithText("In progress").assertExists()
            onNodeWithText("Importing sample.hprof…").assertExists()
        }

    @Test
    fun `sort actions are wired`() =
        runDesktopComposeUiTest(width = 1000, height = 700) {
            val events = mutableListOf<String>()
            setContent {
                MemoryProfilerScreen(
                    state = loadedState(),
                    actions =
                        MemoryProfilerActions(
                            onSortHistogram = { events += "sort:$it" },
                        ),
                )
            }

            onNodeWithText("Shallow").performClick()
            onNodeWithText("Count ↓").performClick()

            assertEquals(
                listOf("sort:Shallow", "sort:Count"),
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
