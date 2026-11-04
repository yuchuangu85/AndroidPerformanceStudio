package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.model.HprofEngine
import com.androidperformancestudio.memory.presentation.MemoryProfilerViewMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RustEngineLifecycleTest {
    @Test
    fun `backend defaults to Rust for Android and standard heaps without an explicit selection`() =
        runBlocking {
            val root = createTempDirectory("rust-engine-default")
            try {
                for (android in listOf(false, true)) {
                    val file = RustHprofFixture.write(root.resolve("heap-$android.hprof"), 4, android = android)
                    val backend =
                        DesktopMemoryProfilerBackend(
                            root.resolve("data-$android"),
                            adbLocator = { null },
                            rustExecutable = { Path.of(checkNotNull(System.getProperty("aps.hprofEngine.path"))) },
                            hprofClassMetricsAnalyzer = HprofClassMetricsAnalyzer { null },
                        )

                    val result = assertIs<MemoryBackendResult.Success<LoadedSnapshot>>(backend.importHprof(file))
                    result.value.use { snapshot ->
                        assertIs<RustLoadedSnapshot>(snapshot)
                        assertEquals(HprofEngine.RUST, snapshot.engine)
                        assertNotNull(snapshot.source.query.detailOf(RustHprofFixture.NODE))
                    }
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    @Suppress("ThrowsCount")
    fun `cleanup attempts all resources and preserves suppressed failures and cancellation`() {
        val closed = mutableListOf<Int>()
        val first = IOException("first close")
        val second = IOException("second close")
        val resources =
            listOf(
                AutoCloseable {
                    closed += 1
                    throw first
                },
                AutoCloseable {
                    closed += 2
                    throw second
                },
                AutoCloseable { closed += 3 },
            )
        assertSame(first, assertFailsWith<IOException> { closeHeapResources(resources) })
        assertEquals(listOf(1, 2, 3), closed)
        assertSame(second, first.suppressed.single())
        val cancellation = kotlinx.coroutines.CancellationException("cancel")
        closeHeapResources(listOf(AutoCloseable { throw IOException("close on cancel") }), cancellation)
        assertEquals("close on cancel", cancellation.suppressed.single().message)
    }

    @Test
    fun `cancelled dispatcher delivery releases the successful Rust snapshot before mapping reload`() =
        runBlocking {
            val root = createTempDirectory("rust-cancelled-delivery")
            val release = CountDownLatch(1)
            val ready = CompletableDeferred<Unit>()
            try {
                val file = RustHprofFixture.write(root.resolve("heap.hprof"), 4, android = true)
                val backend =
                    DesktopMemoryProfilerBackend(
                        root.resolve("data"),
                        adbLocator = { null },
                        hprofEngine = HprofEngine.RUST,
                        rustExecutable = { Path.of(checkNotNull(System.getProperty("aps.hprofEngine.path"))) },
                        hprofClassMetricsAnalyzer = HprofClassMetricsAnalyzer { null },
                    )
                val delivery =
                    launch(Dispatchers.Default) {
                        backend.importHprof(file) { progress ->
                            if (progress == 100) {
                                ready.complete(Unit)
                                check(release.await(10, TimeUnit.SECONDS))
                            }
                        }
                    }
                withTimeout(10_000) { ready.await() }
                delivery.cancel()
                release.countDown()
                delivery.join()
                Files.list(root.resolve("data/hprof-sources")).use { files ->
                    assertEquals(0L, files.filter { it.fileName.toString().endsWith(".hprof") }.count())
                }
                val mapping = Files.writeString(root.resolve("mapping.txt"), "example.Node -> a.Node:\n")
                val result = assertIs<MemoryBackendResult.Success<LoadedSnapshot?>>(backend.importMapping(mapping))
                assertEquals(null, result.value)
            } finally {
                release.countDown()
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `missing default Rust engine fails without Legacy fallback or source copies`() =
        runBlocking {
            val root = createTempDirectory("rust-engine-missing")
            try {
                val file = RustHprofFixture.write(root.resolve("heap.hprof"), 4, android = true)
                val backend =
                    DesktopMemoryProfilerBackend(
                        root.resolve("data"),
                        adbLocator = { null },
                        rustExecutable = { root.resolve("missing-engine") },
                        hprofClassMetricsAnalyzer = HprofClassMetricsAnalyzer { null },
                    )
                val failure = assertIs<MemoryBackendResult.Failure>(backend.importHprof(file))
                assertTrue(failure.title.contains("Rust"))
                assertFalse(Files.exists(root.resolve("data/hprof-sources")))
                backend.changeHprofEngine(HprofEngine.LEGACY)
                val legacy = assertIs<MemoryBackendResult.Success<LoadedSnapshot>>(backend.importHprof(file)).value
                assertIs<LoadedHeap>(legacy)
                legacy.close()
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    @Suppress("LongMethod")
    fun `controller pages Rust objects and branches without changing Legacy list behavior`() =
        runBlocking {
            val root = createTempDirectory("rust-controller-pages")
            val backend =
                DesktopMemoryProfilerBackend(
                    root.resolve("data"),
                    adbLocator = { null },
                    hprofEngine = HprofEngine.RUST,
                    rustExecutable = { Path.of(checkNotNull(System.getProperty("aps.hprofEngine.path"))) },
                    hprofClassMetricsAnalyzer = HprofClassMetricsAnalyzer { null },
                )
            val controller = MemoryProfilerController(backend)
            try {
                val file = RustHprofFixture.write(root.resolve("heap.hprof"), 8, android = true, extraNodes = 1200)
                controller.importHprof(file)
                val rust = assertIs<RustLoadedSnapshot>(controller.loadedHeap)
                val managed = assertNotNull(rust.rawHprofFile)
                assertEquals(
                    HprofEngine.RUST,
                    controller.state.value.snapshotSummary
                        ?.engine,
                )
                controller.selectClassAsync("a.Node")
                val count = controller.state.value.instanceTotalCount
                assertTrue(count > 1000)
                assertEquals(500, controller.state.value.selectedClassInstances.size)
                controller.loadInstancePageAsync(500)
                assertEquals(500, controller.state.value.instancePageOffset)
                assertEquals(count, controller.state.value.instanceTotalCount)

                val lastObject = RustHprofFixture.EXTRA_NODE_START + 1199
                controller.selectInstanceAsync(lastObject)
                assertEquals(
                    lastObject,
                    controller.state.value.selectedInstanceDetail
                        ?.objectId,
                )
                assertEquals(1000, controller.state.value.instancePageOffset)
                assertTrue(
                    controller.state.value.selectedClassInstances
                        .any { it.objectId == lastObject },
                )
                controller.changeViewModeAsync(MemoryProfilerViewMode.Dominators)
                assertTrue(controller.state.value.remoteDominators)
                assertTrue(
                    controller.state.value.dominatorRows
                        .any { it.objectId == RustHprofFixture.ROOT_ARRAY },
                )
                controller.loadDominatorChildrenAsync(RustHprofFixture.ROOT_ARRAY, 0)
                assertEquals(
                    500,
                    controller.state.value.dominatorChildren[RustHprofFixture.ROOT_ARRAY]
                        ?.size,
                )
                controller.loadDominatorChildrenAsync(RustHprofFixture.ROOT_ARRAY, 500)
                assertEquals(500, controller.state.value.dominatorChildOffsets[RustHprofFixture.ROOT_ARRAY])

                controller.changeHprofEngine(HprofEngine.LEGACY)
                assertSame(rust, controller.loadedHeap)
                assertNotNull(rust.source.query.detailOf(lastObject))
                controller.importHprof(file)
                assertIs<LoadedHeap>(controller.loadedHeap)
                assertFalse(Files.exists(managed), "Replacing the snapshot must release its managed source")
                controller.selectClassAsync("a.Node")
                assertEquals(count, controller.state.value.selectedClassInstances.size)
                assertEquals(0, controller.state.value.instancePageOffset)
                assertFalse(controller.state.value.remoteDominators)
            } finally {
                controller.close()
                root.toFile().deleteRecursively()
            }
        }
}
