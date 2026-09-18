@file:Suppress("MaxLineLength")

package com.androidperformancestudio.memory.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.memory.model.BitmapDumpImage
import com.androidperformancestudio.memory.model.BitmapDumpSession
import com.androidperformancestudio.memory.model.BitmapDumpSummary
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapSummary
import com.androidperformancestudio.memory.model.LeakSuspect
import com.androidperformancestudio.memory.model.ObjectReference
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
            onNodeWithText("Class List").assertDoesNotExist()
            onNodeWithTag("memory-profiler-status-bar").assertExists()
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

            val histogramBounds = onNodeWithTag("memory-profiler-class-histogram").fetchSemanticsNode().boundsInRoot
            val histogramTitleBounds =
                onNodeWithText("Class histogram", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(histogramBounds.left, histogramTitleBounds.left)
            assertEquals(histogramBounds.top, histogramTitleBounds.top)
        }

    @Test
    fun `bitmap dump view has a dedicated page while capture is in progress`() =
        runDesktopComposeUiTest(width = 1000, height = 700) {
            setContent {
                MemoryProfilerScreen(
                    state =
                        MemoryProfilerState(
                            viewMode = MemoryProfilerViewMode.BitmapDump,
                            isDumping = true,
                        ),
                    actions = MemoryProfilerActions(),
                )
            }

            onNodeWithTag("memory-profiler-bitmap-dump-page").assertExists()
            onNodeWithText("Bitmap dump gallery").assertExists()
            onNodeWithText("Capturing bitmaps…").assertExists()
        }

    @Test
    fun `bitmap grid renders unavailable image cards`() =
        runDesktopComposeUiTest(width = 1000, height = 700) {
            setContent {
                MemoryProfilerScreen(
                    state = missingBitmapGridState(),
                    actions = MemoryProfilerActions(),
                )
            }

            onNodeWithText("com.example · pid 42 · API 35").assertExists()
            onNodeWithTag("memory-profiler-bitmap-grid").assertExists()
            onNodeWithTag("memory-profiler-bitmap-summary-metrics").assertExists()
            onNodeWithText("Total content").assertExists()
            onNodeWithText("832 B").assertExists()
            onNodeWithText("Image unavailable: missing.png").assertExists()
            onNodeWithText("Image unavailable: missing-2.png").assertExists()
            val firstCard = onNodeWithTag("bitmap-dump-image-card-1").fetchSemanticsNode().boundsInRoot
            val secondCard = onNodeWithTag("bitmap-dump-image-card-2").fetchSemanticsNode().boundsInRoot
            assertEquals(firstCard.top, secondCard.top)
            assertTrue(secondCard.left > firstCard.left)
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
    fun `busy state shows progress in the compact bottom status bar`() =
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
            onNodeWithText("In progress:", useUnmergedTree = true).assertExists()
            onNodeWithText("Importing sample.hprof…", useUnmergedTree = true).assertExists()
            val statusBar = onNodeWithTag("memory-profiler-status-bar").fetchSemanticsNode().boundsInRoot
            assertTrue(statusBar.height <= 32f, "Status bar must remain a compact footer: $statusBar")
            assertTrue(statusBar.bottom >= 699f, "Status bar must remain anchored to the bottom: $statusBar")
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
    fun `status bar renders error and warnings with retry action`() =
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

            onNodeWithTag("memory-profiler-status-bar").assertExists()
            onNodeWithText("Capture failed:", useUnmergedTree = true).assertExists()
            onNodeWithText("Target app may not be debuggable or permission was denied.", useUnmergedTree = true).assertExists()
            onNodeWithText("Cleanup warning:", useUnmergedTree = true).assertExists()
            onNodeWithText("Warning:", useUnmergedTree = true).assertExists()
            onNodeWithText("Install SDK Platform Tools to enable standard HPROF conversion.", useUnmergedTree = true).assertExists()
            onNodeWithText(
                "Could not remove /data/local/tmp/heap-1.hprof; local analysis is still available.",
                useUnmergedTree = true,
            ).assertExists()
            onNodeWithText("Retry", useUnmergedTree = true).performClick()
            assertEquals(1, retryCount)
        }

    @Test
    fun `loaded profiler workspace renders a desktop screenshot with footer and analysis content`() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            setContent {
                MemoryProfilerScreen(
                    state = loadedState(),
                    actions = MemoryProfilerActions(),
                )
            }

            val screenshot = onRoot().captureToImage()
            assertEquals(1200, screenshot.width)
            assertEquals(800, screenshot.height)
            onNodeWithTag("memory-profiler-status-bar").assertExists()
            onNodeWithText("Overview").assertExists()
            onNodeWithText("Class histogram").assertExists()
        }

    @Test
    fun `dominator view renders retained rows and opens selected objects`() =
        runDesktopComposeUiTest(width = 1000, height = 700) {
            val selected = mutableListOf<Long>()
            setContent {
                MemoryProfilerScreen(
                    state =
                        MemoryProfilerState(
                            viewMode = MemoryProfilerViewMode.Dominators,
                            dominatorRows =
                                listOf(
                                    MemoryDominatorRow(
                                        objectId = 42L,
                                        className = "com.example.Root",
                                        shallowSize = 24L,
                                        retainedSize = 96L,
                                        depth = 0,
                                        parentObjectId = null,
                                    ),
                                    MemoryDominatorRow(
                                        objectId = 43L,
                                        className = "com.example.Child",
                                        shallowSize = 16L,
                                        retainedSize = 32L,
                                        depth = 1,
                                        parentObjectId = 42L,
                                    ),
                                ),
                        ),
                    actions = MemoryProfilerActions(onSelectInstance = { selected += it }),
                )
            }

            onNodeWithText("Dominator tree").assertExists()
            onNodeWithText("0x2a · com.example.Root").performClick()
            onNodeWithText("0x2b · com.example.Child").assertExists()
            onNodeWithText("▾").performClick()
            onNodeWithText("0x2b · com.example.Child").assertDoesNotExist()
            assertEquals(listOf(42L), selected)
        }

    @Test
    @Suppress("LongMethod")
    fun `class list follows clickable references and exposes instance navigation controls`() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            val followed = mutableListOf<Long>()
            var pinCount = 0
            setContent {
                MemoryProfilerScreen(
                    state =
                        loadedState().copy(
                            viewMode = MemoryProfilerViewMode.ClassList,
                            classifierRows =
                                listOf(
                                    MemoryClassifierRow(
                                        id = "class:com.example.Root",
                                        label = "com.example.Root",
                                        className = "com.example.Root",
                                        totalCount = 1,
                                    ),
                                ),
                            selectedClassifierId = "class:com.example.Root",
                            selectedClassifierLabel = "com.example.Root",
                            selectedClassName = "com.example.Root",
                            selectedClassInstances =
                                listOf(
                                    MemoryInstanceRow(
                                        objectId = 1L,
                                        index = 0,
                                        shallowSize = 24L,
                                        retainedSize = 64L,
                                        depth = 1,
                                        reachable = true,
                                    ),
                                ),
                            selectedInstanceDetail =
                                MemoryInstanceDetail(
                                    objectId = 1L,
                                    className = "com.example.Root",
                                    shallowSize = 24L,
                                    retainedSize = 64L,
                                    depth = 1,
                                    isArray = false,
                                    elementCount = null,
                                    fields =
                                        listOf(
                                            MemoryInstanceField(
                                                name = "child",
                                                displayValue = "com.example.Child #2",
                                                targetObjectId = 2L,
                                                targetClassName = "com.example.Child",
                                            ),
                                        ),
                                    references =
                                        listOf(
                                            MemoryInstanceField(
                                                name = "owner",
                                                displayValue = "com.example.Owner #3",
                                                targetObjectId = 3L,
                                                targetClassName = "com.example.Owner",
                                            ),
                                        ),
                                    referenceChain =
                                        listOf(ObjectReference("root", 4L, "com.example.RootKeeper")),
                                ),
                        ),
                    actions =
                        MemoryProfilerActions(
                            onSelectInstance = { followed += it },
                            onTogglePinnedInstance = { pinCount++ },
                        ),
                )
            }

            onNodeWithText("com.example.Child #2", useUnmergedTree = true).performClick()
            onNodeWithText("owner ← com.example.Owner #3", useUnmergedTree = true).performClick()
            onAllNodesWithText("↳ root → com.example.RootKeeper", useUnmergedTree = true)[1].performClick()
            onNodeWithText("Back").assertExists()
            onNodeWithText("Forward").assertExists()
            onNodeWithText("Pin object").performClick()

            assertEquals(listOf(2L, 3L, 4L), followed)
            assertEquals(1, pinCount)
        }

    private fun missingBitmapGridState(): MemoryProfilerState =
        MemoryProfilerState(
            viewMode = MemoryProfilerViewMode.BitmapDump,
            bitmapDumpSession =
                BitmapDumpSession(
                    id = "bitmap-1",
                    packageName = "com.example",
                    pid = 42,
                    deviceSerial = "emulator-5554",
                    sdkLevel = 35,
                    capturedAt = Instant.EPOCH,
                    hprofFile = Path.of("bitmap.hprof"),
                    imagesDirectory = Path.of("images"),
                    images =
                        listOf(
                            missingBitmapImage(1, "missing.png", 8, 8, 256L),
                            missingBitmapImage(2, "missing-2.png", 12, 12, 576L),
                        ),
                    summary =
                        BitmapDumpSummary(
                            recordedBitmapCount = 2,
                            discoveredBitmapCount = 2,
                            exportedImageCount = 2,
                            uniqueImageCount = 2,
                            duplicateGroupCount = 0,
                            totalPngBytes = 40L,
                            estimatedBitmapBytes = 832L,
                        ),
                ),
        )

    private fun missingBitmapImage(
        index: Int,
        fileName: String,
        width: Int,
        height: Int,
        estimatedBytes: Long,
    ): BitmapDumpImage =
        BitmapDumpImage(
            recordIndex = index,
            arrayObjectId = index.toLong() + 6L,
            file = Path.of(fileName),
            width = width,
            height = height,
            pngBytes = estimatedBytes / 16L,
            estimatedMemoryBytes = estimatedBytes,
            sha256 = "sha-$index",
        )

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
