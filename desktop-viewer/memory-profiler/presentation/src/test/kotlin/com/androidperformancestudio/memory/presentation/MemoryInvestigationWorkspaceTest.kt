package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapCapability
import com.androidperformancestudio.memory.model.HeapDiff
import com.androidperformancestudio.memory.model.HeapDiffEntry
import com.androidperformancestudio.memory.model.HeapSegment
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.memory.model.HeapSummary
import com.androidperformancestudio.memory.model.ObjectReference
import com.androidperformancestudio.ui.HeaderToolbar
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MemoryInvestigationWorkspaceTest {
    @Test
    fun `instance table fills its card without centered exterior gutters`() {
        listOf(1280, 1440).forEach { width ->
            listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
                runDesktopComposeUiTest(width = width, height = 720) {
                    setContent {
                        ViewerTheme(darkTheme = language == UiLanguage.SIMPLIFIED_CHINESE) {
                            MemoryProfilerScreen(fixture(), MemoryProfilerActions(), language)
                        }
                    }
                    val card = onNodeWithTag("memory-instances-card").fetchSemanticsNode().boundsInRoot
                    val inInstances = hasAnyAncestor(hasTestTag("memory-instances-card"))
                    val instanceLabel = if (language == UiLanguage.ENGLISH) "Instance" else "实例"
                    val retainedLabel = if (language == UiLanguage.ENGLISH) "Retained" else "保留大小"
                    val firstHeader = onNode(hasText(instanceLabel) and inInstances).fetchSemanticsNode().boundsInRoot
                    val lastHeader = onNode(hasText(retainedLabel) and inInstances).fetchSemanticsNode().boundsInRoot
                    val row =
                        onNode(hasText("▾ #1") and inInstances, useUnmergedTree = true).fetchSemanticsNode()
                    assertEquals(card.left + 8f, firstHeader.left, 0.6f)
                    assertEquals(card.right - 8f, lastHeader.right, 0.6f)
                    assertEquals(firstHeader.left, row.boundsInRoot.left, 0.6f)
                    assertEquals(28f, row.parent!!.boundsInRoot.height, 0.6f)
                }
            }
        }
    }

    @Test
    fun `narrow instance table scrolls to both ends and retains selection`() =
        runDesktopComposeUiTest(width = 500, height = 600) {
            var selected: Long? = null
            setContent {
                ViewerTheme(darkTheme = false) {
                    MemoryClassesPane(
                        fixture(),
                        MemoryProfilerActions(onSelectInstance = { selected = it }),
                        UiLanguage.ENGLISH,
                        Modifier.fillMaxSize(),
                    )
                }
            }
            val card = onNodeWithTag("memory-instances-card").fetchSemanticsNode().boundsInRoot
            val inInstances = hasAnyAncestor(hasTestTag("memory-instances-card"))
            val firstHeader = onNode(hasText("Instance") and inInstances)
            assertEquals(card.left + 8f, firstHeader.fetchSemanticsNode().boundsInRoot.left, 0.6f)
            val lastHeader = onNode(hasText("Retained") and inInstances)
            lastHeader.performScrollTo()
            assertTrue(lastHeader.fetchSemanticsNode().boundsInRoot.right <= card.right)
            val row = onNode(hasText("▾ #1") and inInstances, useUnmergedTree = true)
            row.performScrollTo().performClick()
            assertEquals(42L, selected)
        }

    @Test
    fun `dominator and comparison analysis cards preserve empty and loaded layouts`() {
        listOf(MemoryProfilerViewMode.Dominators, MemoryProfilerViewMode.Diff).forEach { mode ->
            listOf(false, true).forEach { loaded ->
                runDesktopComposeUiTest(width = 1280, height = 720) {
                    val state = if (loaded) fixture().copy(viewMode = mode) else MemoryProfilerState(viewMode = mode)
                    setContent {
                        ViewerTheme(darkTheme = false) {
                            MemoryProfilerScreen(state, MemoryProfilerActions())
                        }
                    }
                    onNodeWithTag("memory-analysis-card").assertExists()
                    onNodeWithTag("memory-filters-card").assertExists()
                    onNodeWithTag("memory-inspector-card").assertExists()
                    onNodeWithTag("memory-classes-card").assertDoesNotExist()
                    if (loaded) {
                        onNodeWithTag("memory-distribution-card").assertExists()
                    } else {
                        onNodeWithTag("memory-distribution-card").assertDoesNotExist()
                    }
                    val analysis = onNodeWithTag("memory-analysis-card").fetchSemanticsNode().boundsInRoot
                    val footer = onNodeWithTag("memory-profiler-status-bar").fetchSemanticsNode().boundsInRoot
                    assertTrue(analysis.height > 0f)
                    assertTrue(analysis.bottom < footer.top)
                }
            }
        }
    }

    @Test
    fun `analysis sections have separate aligned cards with compact gaps`() {
        listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
            listOf(false, true).forEach { dark ->
                runDesktopComposeUiTest(width = 1280, height = 720) {
                    setContent {
                        ViewerTheme(darkTheme = dark) {
                            MemoryProfilerScreen(fixture(), MemoryProfilerActions(), language)
                        }
                    }
                    val metrics =
                        (0..3).map { onNodeWithTag("memory-metric-card-$it").fetchSemanticsNode().boundsInRoot }
                    metrics.zipWithNext().forEach { (left, right) ->
                        assertEquals(left.top, right.top)
                        assertEquals(left.bottom, right.bottom)
                        assertEquals(4f, right.left - left.right)
                    }
                    val distribution = onNodeWithTag("memory-distribution-card").fetchSemanticsNode().boundsInRoot
                    val filters = onNodeWithTag("memory-filters-card").fetchSemanticsNode().boundsInRoot
                    val classes = onNodeWithTag("memory-classes-card").fetchSemanticsNode().boundsInRoot
                    val instances = onNodeWithTag("memory-instances-card").fetchSemanticsNode().boundsInRoot
                    val inspector = onNodeWithTag("memory-inspector-card").fetchSemanticsNode().boundsInRoot
                    assertEquals(4f, metrics.first().left)
                    assertEquals(1276f, metrics.last().right)
                    assertEquals(4f, distribution.top - metrics.first().bottom)
                    assertTrue(filters.top >= distribution.bottom + 4f)
                    assertEquals(filters.top, classes.top)
                    assertEquals(classes.top, inspector.top)
                    assertEquals(4f, classes.left - filters.right)
                    assertEquals(4f, inspector.left - classes.right)
                    assertEquals(4f, instances.top - classes.bottom)
                    assertEquals(instances.bottom, inspector.bottom)
                }
            }
        }
    }

    @Test
    fun `analysis cards remain reachable in a narrow workspace`() =
        runDesktopComposeUiTest(width = 900, height = 720) {
            setContent {
                ViewerTheme(darkTheme = false) {
                    MemoryProfilerScreen(fixture(), MemoryProfilerActions())
                }
            }
            onNodeWithTag("memory-inspector-card").performScrollTo()
            val inspector = onNodeWithTag("memory-inspector-card").fetchSemanticsNode().boundsInRoot
            assertTrue(inspector.width > 0f)
            assertTrue(inspector.right <= onRoot().fetchSemanticsNode().boundsInRoot.right)
            onNodeWithTag("memory-filters-card").performScrollTo()
            assertTrue(onNodeWithTag("memory-filters-card").fetchSemanticsNode().boundsInRoot.left >= 0f)
            onNodeWithTag("memory-profiler-status-bar").assertExists()
        }

    @Test
    fun `snapshot metrics keep labels and values in a compact content sized row`() {
        listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
            listOf(false, true).forEach { dark ->
                runDesktopComposeUiTest(width = 1280, height = 860) {
                    setContent {
                        ViewerTheme(darkTheme = dark) {
                            MemoryProfilerScreen(fixture(), MemoryProfilerActions(), language)
                        }
                    }
                    val summary = onNodeWithTag("memory-snapshot-summary").fetchSemanticsNode().boundsInRoot
                    assertTrue(
                        summary.height <= 48f,
                        "Summary should wrap its content, actual height: ${summary.height}",
                    )
                    val inSummary = hasAnyAncestor(hasTestTag("memory-snapshot-summary"))
                    val labels =
                        if (language == UiLanguage.ENGLISH) {
                            listOf("Heap Size", "Objects", "Classes", "Leak Suspects")
                        } else {
                            listOf("堆大小", "对象", "类", "泄漏疑点")
                        }
                    labels.zip(listOf("256.0 MB", "412,587", "6,484", "0")).forEach { (label, value) ->
                        val labelBounds = onNode(hasText(label) and inSummary).fetchSemanticsNode().boundsInRoot
                        val valueBounds = onNode(hasText(value) and inSummary).fetchSemanticsNode().boundsInRoot
                        assertEquals(labelBounds.left, valueBounds.left)
                        assertTrue(labelBounds.top >= summary.top)
                        assertTrue(valueBounds.top >= labelBounds.bottom)
                        assertTrue(valueBounds.bottom <= summary.bottom)
                    }
                }
            }
        }
    }

    @Test
    fun `workspace keeps mapping and compare without file action buttons`() {
        listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
            runDesktopComposeUiTest(width = 1280, height = 160) {
                val state =
                    fixture().let {
                        it.copy(snapshotSummaries = it.snapshotSummaries + HeapSnapshotSummary("before.hprof"))
                    }
                var mappingClicks = 0
                var compareClicks = 0
                setContent {
                    ViewerTheme(darkTheme = false) {
                        Column {
                            HeaderToolbar(
                                language = language,
                                onNavigateHome = null,
                                onNavigateSettings = null,
                            ) {
                                Spacer(Modifier.weight(1f))
                                MemoryWorkspaceActions(
                                    state = state,
                                    language = language,
                                    onMapping = { mappingClicks++ },
                                    onCompare = { compareClicks++ },
                                )
                            }
                            MemoryProfilerSnapshotTabs(state, {}, {}, language)
                        }
                    }
                }

                onNodeWithText(if (language == UiLanguage.ENGLISH) "Import snapshot" else "导入快照").assertDoesNotExist()
                onNodeWithContentDescription(if (language == UiLanguage.ENGLISH) "Import snapshot" else "导入快照")
                    .assertDoesNotExist()
                onNodeWithText(if (language == UiLanguage.ENGLISH) "Export" else "导出").assertDoesNotExist()
                val actions = onNodeWithTag("memory-workspace-actions").fetchSemanticsNode().boundsInRoot
                val compare =
                    onNodeWithText(if (language == UiLanguage.ENGLISH) "Compare" else "比较")
                        .fetchSemanticsNode()
                        .boundsInRoot
                assertEquals(actions.right, compare.right)
                assertEquals(onRoot().fetchSemanticsNode().boundsInRoot.right - 10f, compare.right)
                onNodeWithText(if (language == UiLanguage.ENGLISH) "Mapping" else "混淆映射").performClick()
                onNodeWithText(if (language == UiLanguage.ENGLISH) "Compare" else "比较").performClick()
                assertEquals(1, mappingClicks)
                assertEquals(1, compareClicks)
                onNodeWithText("heap-after.hprof").assertExists()
                onNodeWithText("before.hprof").assertExists()
            }
        }
    }

    @Test
    fun `default workspace has filters virtualized classes and separate object inspector`() =
        runDesktopComposeUiTest(width = 1440, height = 860) {
            var state by mutableStateOf(fixture())
            val selected = mutableListOf<Long>()
            setContent {
                ViewerTheme(darkTheme = true) {
                    MemoryProfilerScreen(
                        state,
                        MemoryProfilerActions(
                            onSelectInstance = { selected += it },
                            onMatchCaseChange = { state = state.copy(matchCase = it) },
                        ),
                    )
                }
            }
            onNodeWithTag("memory-investigation-workspace").assertExists()
            val filters = onNodeWithTag("memory-workspace-filters").fetchSemanticsNode().boundsInRoot
            val inspector = onNodeWithTag("memory-object-inspector").fetchSemanticsNode().boundsInRoot
            assertTrue(filters.right < inspector.left)
            assertTrue(inspector.right <= onRoot().fetchSemanticsNode().boundsInRoot.right)
            onNodeWithText("Match case").performClick()
            assertTrue(state.matchCase)
            onNodeWithText("sample.Child #2", useUnmergedTree = true).performClick()
            onNodeWithText("Inbound references").performClick()
            onNodeWithText("sample.Owner #3", useUnmergedTree = true).performClick()
            onNodeWithText("GC Root path").performClick()
            onNodeWithText("1. sample.Root", useUnmergedTree = true).performClick()
            assertEquals(listOf(2L, 3L, 4L), selected)
        }

    @Test
    fun `diff exposes full retained evidence and chosen snapshot pair`() =
        runDesktopComposeUiTest(width = 1440, height = 860) {
            val snapshotA = HeapSnapshotSummary("A", sourceFile = Path.of("before.hprof"))
            val snapshotB = HeapSnapshotSummary("B", sourceFile = Path.of("after.hprof"))
            val compared = mutableListOf<Pair<String, String>>()
            setContent {
                MemoryProfilerScreen(
                    fixture().copy(
                        viewMode = MemoryProfilerViewMode.Diff,
                        snapshotSummaries = listOf(snapshotA, snapshotB),
                        heapDiff =
                            HeapDiff(
                                listOf(
                                    HeapDiffEntry(
                                        "sample.Item",
                                        2,
                                        4,
                                        2,
                                        64,
                                        128,
                                        64,
                                        beforeRetainedSize = 1024,
                                        afterRetainedSize = 4096,
                                    ),
                                ),
                                snapshotA,
                                snapshotB,
                            ),
                    ),
                    MemoryProfilerActions(onCompareSnapshots = { a, b -> compared += a to b }),
                )
            }
            onNodeWithContentDescription("Snapshot A").assertExists()
            onNodeWithContentDescription("Snapshot B").assertExists()
            onNodeWithText("Compare").performClick()
            assertEquals(listOf("A" to "B"), compared)
            onNodeWithText("A Retained").assertExists()
            onNodeWithText("B Retained").assertExists()
            onNodeWithText("Δ Retained").assertExists()
            onNodeWithText("+3.0 KB").assertExists()
            onNodeWithText("A: before.hprof  |  B: after.hprof").assertExists()
        }

    @Test
    fun `workspace captures desktop layouts in both languages and themes`() {
        listOf(
            Triple(1440, UiLanguage.ENGLISH, 860),
            Triple(1280, UiLanguage.SIMPLIFIED_CHINESE, 720),
            Triple(900, UiLanguage.ENGLISH, 720),
        ).forEach { (width, language, height) ->
            listOf(false, true).forEach { dark ->
                runDesktopComposeUiTest(width = width, height = height) {
                    setContent {
                        ViewerTheme(darkTheme = dark) {
                            Column(Modifier.fillMaxSize()) {
                                HeaderToolbar(
                                    language = language,
                                    onNavigateHome = null,
                                    onNavigateSettings = null,
                                ) {
                                    Spacer(Modifier.weight(1f))
                                    MemoryWorkspaceActions(fixture(), language, {}, {})
                                }
                                MemoryProfilerSnapshotTabs(fixture(), {}, {}, language)
                                MemoryProfilerScreen(fixture(), MemoryProfilerActions(), language, Modifier.weight(1f))
                            }
                        }
                    }
                    val image = onRoot().captureToImage()
                    assertEquals(width, image.width)
                    val pixels = image.toPixelMap()
                    assertTrue(
                        (50 until height - 60 step 50)
                            .flatMap { y ->
                                (50 until width step 50).map { x -> pixels[x, y] }
                            }.distinct()
                            .size >
                            3,
                    )
                    val output =
                        Path.of(
                            "build/screenshots/memory-$width-${language.name}-${if (dark) "dark" else "light"}.png",
                        )
                    Files.createDirectories(output.parent)
                    val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                    for (y in 0 until image.height) {
                        for (x in 0 until image.width) {
                            val c = pixels[x, y]
                            bitmap.setRGB(
                                x,
                                y,
                                ((c.alpha * 255).toInt() shl 24) or ((c.red * 255).toInt() shl 16) or
                                    ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt(),
                            )
                        }
                    }
                    ImageIO.write(bitmap, "png", output.toFile())
                    val footer = onNodeWithTag("memory-profiler-status-bar").fetchSemanticsNode().boundsInRoot
                    assertEquals(height.toFloat(), footer.bottom)
                }
            }
        }
    }

    @Suppress("LongMethod")
    private fun fixture(): MemoryProfilerState {
        val snapshot =
            HeapSnapshotSummary(
                "heap-after.hprof",
                sourceFile = Path.of("heap-after.hprof"),
                objectCount = 412587,
                classCount = 6484,
                capabilities = setOf(HeapCapability.HEAP_GRAPH, HeapCapability.GC_ROOTS),
                sourceFileDigest = "sample-sha256",
                format = "JAVA PROFILE 1.0.3",
                idSize = 4,
            )
        val classStats =
            listOf(
                ClassStats(
                    "android.graphics.Bitmap",
                    12481,
                    48L * 1024 * 1024,
                    96L * 1024 * 1024,
                    heapNames = setOf("App"),
                ),
                ClassStats(
                    "java.lang.String",
                    56291,
                    9L * 1024 * 1024,
                    18L * 1024 * 1024,
                    heapNames = setOf("App", "Zygote"),
                ),
            )
        return MemoryProfilerState(
            snapshotSummary = snapshot,
            snapshotSummaries = listOf(snapshot),
            activeSnapshotId = snapshot.id,
            summary =
                HeapSummary(
                    412587,
                    256L * 1024 * 1024,
                    6484,
                    listOf(
                        HeapSegment("App", 192L * 1024 * 1024, 300000),
                        HeapSegment("Zygote", 64L * 1024 * 1024, 112587),
                    ),
                ),
            classes = classStats,
            heapBaseClasses = classStats,
            selectedClassifierId = "class:android.graphics.Bitmap",
            availableHeaps = listOf("App", "Zygote"),
            selectedClassName = "android.graphics.Bitmap",
            selectedClassInstances = listOf(MemoryInstanceRow(42, 1, 48, 4096, 3, true)),
            selectedInstanceDetail =
                MemoryInstanceDetail(
                    42,
                    "android.graphics.Bitmap",
                    48,
                    4096,
                    3,
                    false,
                    null,
                    fields = listOf(MemoryInstanceField("mBuffer", "sample.Child #2", 2, "sample.Child")),
                    references = listOf(MemoryInstanceField("owner", "sample.Owner #3", 3, "sample.Owner")),
                    referenceChain = listOf(ObjectReference("staticRoot", 4, "sample.Root")),
                ),
        )
    }
}
