package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.analysis.MemoryHistogramAnalyzer
import com.androidperformancestudio.memory.hprof.HprofParser
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KotlinHprofImportTest {
    @Test
    fun `loaded snapshot retains source ids lazy arrays queries and exports until closed`() =
        runBlocking {
            val root = createTempDirectory("kotlin-hprof-import")
            try {
                val input = HprofSnapshotFixture.write(root.resolve("input.hprof"), 8, android = true)
                val original = Files.readAllBytes(input)
                val backend = backend(root)
                val snapshot = assertIs<MemoryBackendResult.Success<LoadedSnapshot>>(backend.importHprof(input)).value
                val retained = assertNotNull(snapshot.rawHprofFile)
                Files.delete(input)
                assertTrue(Files.exists(retained))
                val detail = assertNotNull(snapshot.source.query.detailOf(HprofSnapshotFixture.NODE))
                assertEquals(HprofSnapshotFixture.NODE, detail.objectId)
                assertEquals("a.Node", detail.className)
                assertNotNull(snapshot.source.query.detailOf(HprofSnapshotFixture.STRING_BACKING))
                val instancePage = snapshot.source.query.instancesPage(listOf("a.Node"), null, 0, 2)
                assertTrue(instancePage.total > 0)
                val dominatorPage = snapshot.source.dominatorsPage(0, 10)
                assertTrue(dominatorPage.rows.isNotEmpty())
                val exported = root.resolve("export.hprof")
                SnapshotSourceExports.copyRaw(snapshot, exported)
                assertContentEquals(original, Files.readAllBytes(exported))
                SnapshotSourceExports.instances(snapshot, "a.Node", root.resolve("instances.csv"))
                assertTrue(Files.readString(root.resolve("instances.csv")).contains("a.Node"))
                val report = assertIs<MemoryBackendResult.Success<*>>(backend.analyzeLeaks(snapshot) {}).value
                assertNotNull(report)
                snapshot.close()
                assertFalse(Files.exists(retained))
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `mapping reload retains both snapshot leases and failed import releases only its source`() =
        runBlocking {
            val root = createTempDirectory("kotlin-hprof-reload")
            try {
                val input = HprofSnapshotFixture.write(root.resolve("input.hprof"), 4, android = false)
                val backend = backend(root)
                val first = assertIs<MemoryBackendResult.Success<LoadedSnapshot>>(backend.importHprof(input)).value
                val retained = assertNotNull(first.rawHprofFile)
                val mapping = root.resolve("mapping.txt")
                Files.writeString(mapping, "example.Node -> a.Node:\n")
                val reloaded = assertIs<MemoryBackendResult.Success<LoadedSnapshot?>>(backend.importMapping(mapping))
                val second = assertNotNull(reloaded.value)
                val mappedDetail = assertNotNull(second.source.query.detailOf(HprofSnapshotFixture.NODE))
                assertEquals("example.Node", mappedDetail.className)
                val invalid = root.resolve("invalid.hprof")
                Files.writeString(invalid, "not a heap dump")
                assertIs<MemoryBackendResult.Failure>(backend.importHprof(invalid))
                Files.list(root.resolve("data/hprof-sources")).use { files ->
                    assertEquals(1L, files.filter { it.toString().endsWith(".hprof") }.count())
                }
                first.close()
                assertTrue(Files.exists(retained))
                assertNotNull(second.source.query.detailOf(HprofSnapshotFixture.NODE))
                second.close()
                assertFalse(Files.exists(retained))
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `TP metrics require complete exact raw heap and class evidence`() =
        runBlocking {
            val root = createTempDirectory("kotlin-hprof-metrics")
            try {
                val input = HprofSnapshotFixture.write(root.resolve("input.hprof"), 4, android = true)
                val imported = assertIs<MemoryBackendResult.Success<LoadedSnapshot>>(backend(root).importHprof(input))
                val snapshot = imported.value
                snapshot.use {
                    val loaded = assertIs<HeapDumpLoadedSnapshot>(it)
                    val raw = HprofParser().parse(input)
                    val metrics =
                        loaded.availableHeaps.flatMap { heap ->
                            MemoryHistogramAnalyzer().histogram(raw, heapName = heap).classes.map { stats ->
                                HprofClassMetric(
                                    heap.lowercase(),
                                    stats.className,
                                    stats.instanceCount.toLong(),
                                    stats.shallowSize,
                                    7,
                                    11,
                                    13,
                                )
                            }
                        }
                    val enriched = enrichSnapshotMetrics(loaded, raw, metrics, null)
                    assertEquals(7L * metrics.size, enriched.histogram.classes.sumOf { requireNotNull(it.nativeSize) })
                    assertSame(loaded, enrichSnapshotMetrics(loaded, raw, metrics.drop(1), null))
                    assertSame(loaded, enrichSnapshotMetrics(loaded, raw, metrics + metrics.first(), null))
                    val wrongCount =
                        metrics.toMutableList().apply {
                            this[0] = this[0].copy(objectCount = this[0].objectCount + 1)
                        }
                    assertSame(loaded, enrichSnapshotMetrics(loaded, raw, wrongCount, null))
                    val wrongSize =
                        metrics.toMutableList().apply {
                            this[0] = this[0].copy(shallowSize = this[0].shallowSize + 1)
                        }
                    assertSame(loaded, enrichSnapshotMetrics(loaded, raw, wrongSize, null))
                    assertTrue(loaded.histogram.classes.all { row -> row.nativeSize == null || row.nativeSize == 0L })
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
