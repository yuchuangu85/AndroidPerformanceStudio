package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.model.HprofEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class HeapSnapshotLifecycleTest {
    @Test
    fun `backend defaults to Kotlin for Android and standard heaps without an explicit selection`() =
        runBlocking {
            val root = createTempDirectory("kotlin-engine-default")
            try {
                for (android in listOf(false, true)) {
                    val file = HprofSnapshotFixture.write(root.resolve("heap-$android.hprof"), 4, android = android)
                    val backend =
                        DesktopMemoryProfilerBackend(
                            root.resolve("data-$android"),
                            adbLocator = { null },
                            hprofClassMetricsAnalyzer = HprofClassMetricsAnalyzer { null },
                        )

                    val result = assertIs<MemoryBackendResult.Success<LoadedSnapshot>>(backend.importHprof(file))
                    result.value.use { snapshot ->
                        assertIs<HeapDumpLoadedSnapshot>(snapshot)
                        assertEquals(HprofEngine.KOTLIN, snapshot.engine)
                        assertNotNull(snapshot.source.query.detailOf(HprofSnapshotFixture.NODE))
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
    fun `cancelled dispatcher delivery releases the successful Kotlin snapshot before mapping reload`() =
        runBlocking {
            val root = createTempDirectory("kotlin-cancelled-delivery")
            val release = CountDownLatch(1)
            val ready = CompletableDeferred<Unit>()
            try {
                val file = HprofSnapshotFixture.write(root.resolve("heap.hprof"), 4, android = true)
                val backend =
                    DesktopMemoryProfilerBackend(
                        root.resolve("data"),
                        adbLocator = { null },
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
            } finally {
                release.countDown()
                root.toFile().deleteRecursively()
            }
        }
}
