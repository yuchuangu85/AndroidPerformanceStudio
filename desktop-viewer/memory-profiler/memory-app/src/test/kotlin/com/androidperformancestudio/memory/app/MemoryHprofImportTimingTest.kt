package com.androidperformancestudio.memory.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.memory.model.HeapLoadPhase
import com.androidperformancestudio.memory.presentation.MemoryProfilerActions
import com.androidperformancestudio.memory.presentation.MemoryProfilerScreen
import com.androidperformancestudio.memory.presentation.MemoryProfilerViewMode
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MemoryHprofImportTimingTest {
    @Test
    fun `real HPROF import duration flows through controller state into every memory view`() {
        val root = createTempDirectory("hprof-import-timing")
        try {
            val input = HprofSnapshotFixture.write(root.resolve("input.hprof"), 8, android = true)
            val backend = backend(root)
            val ticks = ArrayDeque(listOf(1_000_000_000L, 13_340_999_999L))
            val controller = MemoryProfilerController(backend, nanoTime = { ticks.removeFirst() })
            try {
                runDesktopComposeUiTest(width = 1280, height = 720) {
                    setContent {
                        val state by controller.state.collectAsState()
                        ViewerTheme(darkTheme = false) {
                            MemoryProfilerScreen(state, MemoryProfilerActions(), UiLanguage.SIMPLIFIED_CHINESE)
                        }
                    }
                    onNodeWithTag("memory-import-timing").assertDoesNotExist()
                    runBlocking { controller.importHprof(input) }
                    assertNotNull(controller.loadedHeap)
                    assertTrue(controller.state.value.classes.isNotEmpty())
                    assertEquals(12_340L, controller.state.value.importDurationMillis)
                    assertFalse(controller.state.value.isDumping)
                    assertNull(controller.state.value.operationMessage)
                    MemoryProfilerViewMode.entries.forEach { mode ->
                        runOnIdle { controller.changeViewMode(mode) }
                        onNodeWithTag("memory-import-timing").assertIsDisplayed()
                        onNodeWithText("KOTLIN · 导入耗时：12.34 秒", useUnmergedTree = true).assertIsDisplayed()
                        onNodeWithTag("memory-import-path").assertIsDisplayed()
                    }
                }
            } finally {
                controller.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `failed and exceptional imports do not retain previous file duration`() =
        runTest {
            val root = createTempDirectory("hprof-import-failure-timing")
            try {
                val input = HprofSnapshotFixture.write(root.resolve("input.hprof"), 4, android = false)
                val invalid = root.resolve("invalid.hprof")
                Files.writeString(invalid, "not a heap dump")
                val actual = backend(root)
                var throwOnImport = false
                val backend =
                    object : MemoryProfilerBackend by actual {
                        override suspend fun importHprof(file: Path, onProgress: (Int) -> Unit): MemoryBackendResult<LoadedSnapshot> {
                            if (throwOnImport) error("parser crashed")
                            return actual.importHprof(file, onProgress)
                        }
                    }
                var now = 0L
                val controller = MemoryProfilerController(backend, nanoTime = { now.also { now += 2_000_000_000L } })
                try {
                    listOf(false, true).forEach { throws ->
                        throwOnImport = false
                        controller.importHprof(input)
                        assertEquals(2_000L, controller.state.value.importDurationMillis)
                        throwOnImport = throws
                        controller.importHprof(invalid)
                        assertNotNull(controller.state.value.error)
                        assertNull(controller.state.value.importDurationMillis)
                        assertNull(controller.state.value.importEngine)
                        assertFalse(controller.state.value.isDumping)
                        assertNull(controller.state.value.operationMessage)
                    }
                } finally {
                    controller.close()
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `new import clears duration while pending and cancellation keeps it cleared`() =
        runTest {
            val root = createTempDirectory("hprof-import-cancel-timing")
            try {
                val input = HprofSnapshotFixture.write(root.resolve("input.hprof"), 4, android = false)
                val actual = backend(root)
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val backend =
                    object : MemoryProfilerBackend by actual {
                        override suspend fun importHprof(file: Path, onProgress: (Int) -> Unit): MemoryBackendResult<LoadedSnapshot> {
                            if (file.fileName.toString() == "pending.hprof") {
                                started.complete(Unit)
                                release.await()
                            }
                            return actual.importHprof(file, onProgress)
                        }
                    }
                var now = 0L
                val controller = MemoryProfilerController(backend, nanoTime = { now.also { now += 2_000_000_000L } })
                try {
                    controller.importHprof(input)
                    val snapshotId = controller.state.value.activeSnapshotId
                    assertEquals(2_000L, controller.state.value.importDurationMillis)
                    val importing = launch { controller.importHprof(root.resolve("pending.hprof")) }
                    started.await()
                    assertTrue(controller.state.value.isDumping)
                    assertNull(controller.state.value.importDurationMillis)
                    assertNull(controller.state.value.importEngine)
                    controller.cancelActiveOperation()
                    importing.join()
                    assertEquals(HeapLoadPhase.CANCELLED, controller.state.value.loadPhase)
                    assertEquals(snapshotId, controller.state.value.activeSnapshotId)
                    assertNull(controller.state.value.importDurationMillis)
                    assertNull(controller.state.value.importEngine)
                    controller.selectSnapshot(assertNotNull(snapshotId))
                    assertEquals(2_000L, controller.state.value.importDurationMillis)
                } finally {
                    controller.close()
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    private fun backend(root: Path) =
        DesktopMemoryProfilerBackend(
            root.resolve("data"),
            adbLocator = { null },
            hprofClassMetricsAnalyzer = HprofClassMetricsAnalyzer { null },
        )
}
