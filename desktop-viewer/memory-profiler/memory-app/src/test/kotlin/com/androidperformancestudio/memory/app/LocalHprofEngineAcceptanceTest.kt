package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HprofEngine
import com.androidperformancestudio.memory.model.LeakCanaryReport
import com.androidperformancestudio.memory.model.LeakCanaryStatus
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Private fixtures are opt-in and never copied into the repository. */
class LocalHprofEngineAcceptanceTest {
    @Test
    fun `selected engine supports the representative large Android heap`() =
        runBlocking {
            val fixture = System.getenv("APS_HPROF_FIXTURE")?.let(Path::of) ?: return@runBlocking
            val engine = HprofEngine.parse(System.getenv("APS_HPROF_ENGINE_MODE"))
            val verifyTraceProcessor = System.getenv("APS_HPROF_VERIFY_TP") == "true"
            val verifyShark = System.getenv("APS_HPROF_VERIFY_SHARK") == "true"
            assertTrue(Files.isRegularFile(fixture))
            val root = createTempDirectory("aps-hprof-engine-acceptance")
            val rss = RssSampler()
            var snapshot: LoadedSnapshot? = null
            try {
                val metrics =
                    if (verifyTraceProcessor) {
                        TraceProcessorHprofClassMetricsAnalyzer()
                    } else {
                        HprofClassMetricsAnalyzer { null }
                    }
                val backend =
                    DesktopMemoryProfilerBackend(
                        dataRoot = root,
                        hprofEngine = engine,
                        hprofClassMetricsAnalyzer = metrics,
                    )
                val started = System.nanoTime()
                val imported = backend.importHprof(fixture)
                val loaded = assertIs<MemoryBackendResult.Success<LoadedSnapshot>>(imported).value
                snapshot = loaded
                val importMillis = (System.nanoTime() - started) / 1_000_000
                val app = assertNotNull(loaded.perHeapClasses["App"])
                verifyAppMetrics(app, verifyTraceProcessor)
                assertEquals(
                    41,
                    loaded.source
                        .findings()
                        .lifecycleLeakObjects
                        .count { it.heapName == "App" },
                )
                val page = loaded.source.query.instancesPage(listOf("java.lang.Object[]"), "App", 0, 500)
                assertTrue(page.total > 500)
                assertEquals(500, page.rows.size)
                val objectId = page.rows.first().objectId
                val detail = assertNotNull(loaded.source.query.detailOf(objectId))
                assertTrue(detail.isArray)
                assertTrue(detail.fields.size <= 200)
                val roots = loaded.source.dominatorsPage(0, 500)
                assertTrue(roots.rows.isNotEmpty())
                if (verifyShark) verifySharkReport(backend, loaded)
                println(
                    "HPROF_ENGINE_ACCEPTANCE engine=$engine importMs=$importMillis " +
                        "classes=${app.size} appObjects=${app.sumOf { it.instanceCount }} " +
                        "arrayTotal=${page.total} fields=${detail.fields.size} dominatorRoots=${roots.total} " +
                        "sampledPeakRssKiB=${rss.peak.get().takeIf { it > 0 }} samples=${rss.samples.get()} " +
                        "traceProcessor=$verifyTraceProcessor shark=$verifyShark",
                )
            } finally {
                snapshot?.close()
                rss.close()
                root.toFile().deleteRecursively()
            }
        }

    private fun verifyAppMetrics(
        app: List<ClassStats>,
        verifyTraceProcessor: Boolean,
    ) {
        assertEquals(5_378, app.size)
        assertEquals(2_073_125, app.sumOf { it.instanceCount })
        assertEquals(109_063_260L, app.sumOf { it.shallowSize })
        if (verifyTraceProcessor) {
            assertEquals(34_945_672L, app.sumOf { assertNotNull(it.nativeSize) })
            assertEquals(34_944_078L, app.sumOf { assertNotNull(it.reachableNativeSize) })
            assertEquals(109_030_672L, app.sumOf { assertNotNull(it.reachableShallowSize) })
        }
    }

    private suspend fun verifySharkReport(
        backend: MemoryProfilerBackend,
        loaded: LoadedSnapshot,
    ) {
        val result = backend.analyzeLeaks(loaded) {}
        val leaks = assertIs<MemoryBackendResult.Success<LeakCanaryReport>>(result).value
        assertTrue(leaks.status in setOf(LeakCanaryStatus.COMPLETED, LeakCanaryStatus.NO_CANDIDATES))
        println(
            "HPROF_SHARK_ACCEPTANCE status=${leaks.status} " +
                "candidates=${leaks.candidateObjectCount} leaks=${leaks.totalLeaks}",
        )
    }

    private class RssSampler : AutoCloseable {
        val peak = AtomicLong()
        val samples = AtomicLong()
        private val running = AtomicBoolean(true)
        private val worker =
            Thread({ sample() }, "hprof-acceptance-rss").apply {
                isDaemon = true
                start()
            }

        private fun sample() {
            if (System.getProperty("os.name").contains("win", ignoreCase = true)) return
            while (running.get()) {
                runCatching {
                    val current = ProcessHandle.current()
                    val ids =
                        current.descendants().use { stream ->
                            listOf(current.pid()) + stream.map { it.pid() }.toList()
                        }
                    val process = ProcessBuilder("ps", "-o", "rss=", "-p", ids.joinToString(",")).start()
                    val total =
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.mapNotNull { it.trim().toLongOrNull() }.sum()
                        }
                    process.waitFor()
                    if (total > 0) {
                        peak.accumulateAndGet(total, ::maxOf)
                        samples.incrementAndGet()
                    }
                }
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }

        override fun close() {
            running.set(false)
            worker.interrupt()
            worker.join(2000)
        }
    }
}
