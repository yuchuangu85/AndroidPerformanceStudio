@file:Suppress("LargeClass", "LongMethod", "MagicNumber", "MaxLineLength", "TooManyFunctions")

package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.analysis.InstanceQueryRow
import com.androidperformancestudio.memory.analysis.ProguardMapping
import com.androidperformancestudio.memory.export.MemoryExportAdapters
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapExportContext
import com.androidperformancestudio.memory.model.HeapHistogram
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.memory.model.HeapValueKind
import com.androidperformancestudio.memory.model.HprofEngine
import com.androidperformancestudio.memory.presentation.MemoryDominatorRow
import com.androidperformancestudio.memory.storage.MemorySessionMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RustHprofParityTest {
    @Test
    fun `real native disk graph keeps all Kotlin snapshot queries and reclaims only transient edges`() =
        withRichFixtures { root, file ->
            try {
                assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"), "Disk graph mode is currently Unix-only")
                val executable = Path.of(assertNotNull(System.getProperty("aps.hprofEngine.path")))
                val digest = sha256(file)
                val cache = root.resolve("parser-cache")
                val edges = root.resolve("graph-edges")
                RustHeapSnapshotSource.open(executable, file, digest, cacheDirectory = cache).use { memory ->
                    RustHeapSnapshotSource
                        .open(
                            executable,
                            file,
                            digest,
                            cacheDirectory = cache,
                            graphStorage = "disk",
                            graphDirectory = edges,
                        ).use { disk ->
                            assertEquals(memory.metadata, disk.metadata)
                            assertHistogram(memory.histogram(), disk.histogram())
                            assertEquals(memory.findings(), disk.findings())
                            assertEquals(dominatorTree(memory), dominatorTree(disk))
                            RustHprofFixture.objectIds.forEach { id ->
                                assertEquals(memory.query.identity(id), disk.query.identity(id))
                                assertEquals(memory.query.detailOf(id), disk.query.detailOf(id))
                            }
                            val names = memory.histogram().classes.map { it.className }
                            for (heap in listOf<String?>(null) + memory.metadata.heaps) {
                                assertEquals(allInstances(memory.query, names, heap), allInstances(disk.query, names, heap))
                            }
                            assertEquals(0L, Files.list(edges).use { it.count() }, "Open disk edges must be anonymous")
                        }
                }
                assertTrue(Files.isRegularFile(file))
                assertTrue(Files.list(cache).use { it.anyMatch { entry -> entry.fileName.toString().endsWith(".idx") } })
                assertEquals(0L, Files.list(edges).use { it.count() })
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `real backend imports rich Android and standard 4 and 8 byte heaps with equal metrics`() =
        withRichFixtures { root, file ->
            pair(root, file) { legacy, rust ->
                assertIs<LoadedHeap>(legacy)
                assertIs<RustLoadedSnapshot>(rust)
                assertFalse(LoadedHeap::class.java.isInstance(rust), "Rust must not be a synthetic legacy heap")
                assertFalse(rust.javaClass.declaredFields.any { HeapDump::class.java.isAssignableFrom(it.type) })
                assertEquals(legacy.source.metadata, rust.source.metadata, "source metadata: $file")
                assertEquals(11, rust.source.metadata.instanceCount)
                assertEquals(1, rust.source.metadata.objectArrayCount)
                assertEquals(10, rust.source.metadata.primitiveArrayCount)
                assertEquals(14, rust.source.metadata.classCount)
                assertEquals(1, rust.source.metadata.rootCount)
                assertEquals(36, rust.histogram.summary.objectCount)
                assertHistogram(legacy.histogram, rust.histogram)
                assertHistogram(legacy.source.histogram(), rust.source.histogram())
                assertEquals(legacy.availableHeaps, rust.availableHeaps)
                assertEquals(legacy.perHeapClasses.keys, rust.perHeapClasses.keys)
                legacy.availableHeaps.forEach { heap ->
                    assertEquals(legacy.perHeapClasses[heap], rust.perHeapClasses[heap], "per-heap classes: $heap")
                    assertHistogram(legacy.source.histogram(heap), rust.source.histogram(heap))
                }
                assertEquals(sha256(file), rust.sourceFileDigest)
                assertEquals(legacy.sourceFileDigest, rust.sourceFileDigest)
                assertEquals(legacy.analysisSourceDigest, rust.analysisSourceDigest)
                assertContentEquals(Files.readAllBytes(file), Files.readAllBytes(assertNotNull(rust.rawHprofFile)))
            }
        }

    @Test
    fun `real query pages identities details and array slices match both engines`() =
        withRichFixtures { root, file ->
            pair(root, file) { legacy, rust ->
                val expected = legacy.source.query
                val actual = rust.source.query
                RustHprofFixture.objectIds.forEach { id ->
                    assertEquals(expected.identity(id), actual.identity(id), "identity $id in $file")
                    assertEquals(expected.groupingClassNameOf(id), actual.groupingClassNameOf(id), "group $id")
                    assertEquals(expected.heapNameOf(id), actual.heapNameOf(id), "heap $id")
                    assertEquals(expected.detailOf(id), actual.detailOf(id), "detail $id in $file")
                }
                assertNull(actual.identity(999_999))
                assertNull(actual.detailOf(999_999))
                val names = legacy.histogram.classes.map { it.className }
                for (heap in listOf<String?>(null) + legacy.availableHeaps) {
                    for (name in names) {
                        assertEquals(allInstances(expected, listOf(name), heap), allInstances(actual, listOf(name), heap))
                        assertEquals(
                            allInstances(expected, listOf(name), heap, ordinaryOnly = true),
                            allInstances(actual, listOf(name), heap, ordinaryOnly = true),
                        )
                    }
                }
                val combined = listOf("a.Node", "b.Node", "a.Node")
                assertEquals(allInstances(expected, combined), allInstances(actual, combined))
                assertEquals(
                    expected.instancesPage(combined, null, 0, 2, anchorObjectId = RustHprofFixture.WEAK_TARGET),
                    actual.instancesPage(combined, null, 0, 2, anchorObjectId = RustHprofFixture.WEAK_TARGET),
                )
                for (id in listOf(RustHprofFixture.ROOT_ARRAY, RustHprofFixture.STRING_BACKING, 20L, 27L)) {
                    for (offset in listOf(0, 1, 100)) {
                        assertEquals(expected.detailOf(id, offset, 2), actual.detailOf(id, offset, 2), "array $id offset $offset")
                    }
                }
                assertEquals(expected.instancesPage(emptyList(), null, 0, 2), actual.instancesPage(emptyList(), null, 0, 2))
            }
        }

    @Test
    fun `typed primitives nulls missing fields string backing and weak reachability remain evidence`() =
        withRichFixtures { root, file ->
            pair(root, file) { legacy, rust ->
                for (snapshot in listOf(legacy, rust)) {
                    val query = snapshot.source.query
                    val detail = assertNotNull(query.detailOf(RustHprofFixture.NODE))
                    val fields = detail.fields.associateBy { it.name }
                    val expected =
                        mapOf(
                            "flag" to "true",
                            "byteValue" to "-7",
                            "charValue" to "Z",
                            "shortValue" to "-123",
                            "intValue" to "-456",
                            "floatValue" to "1.5",
                            "doubleValue" to "-2.25",
                            "longValue" to "-3",
                        )
                    expected.forEach { (name, value) ->
                        val field = assertNotNull(fields[name])
                        assertEquals(value, field.displayValue, "${snapshot.engine}: $name")
                        assertEquals(HeapValueKind.PRIMITIVE, field.valueKind)
                        assertNull(field.targetObjectId)
                    }
                    val nil = assertNotNull(fields["nil"])
                    assertEquals(HeapValueKind.NULL, nil.valueKind)
                    assertEquals(0L, nil.targetObjectId)
                    assertEquals(0L, nil.shallowSize)
                    assertEquals("null", nil.displayValue)
                    val unknown = assertNotNull(fields["unknown"])
                    assertEquals(HeapValueKind.UNKNOWN, unknown.valueKind)
                    assertEquals(999_999L, unknown.targetObjectId)
                    assertNull(unknown.shallowSize, "An absent target has unknown size, not zero")
                    assertNull(unknown.retainedSize)
                    assertNull(unknown.nativeSize)
                    assertNull(detail.nativeSize, "HPROF does not establish a native allocation")
                    assertNull(detail.retainedNativeSize)
                    assertEquals("\"hello\"", assertNotNull(query.detailOf(RustHprofFixture.STRING)).stringPreview)
                    assertEquals(HeapValueKind.CLASS, assertNotNull(query.detailOf(RustHprofFixture.NODE_CLASS)).valueKind)
                    val weakTarget = assertNotNull(query.detailOf(RustHprofFixture.WEAK_TARGET))
                    assertNull(weakTarget.depth, "A weak referent must not become strongly reachable")
                    assertTrue(weakTarget.referenceChain.isEmpty())
                    assertTrue(weakTarget.references.isEmpty(), "The inbound view excludes weak-reference edges")
                    assertEquals(64L, weakTarget.retainedSize)
                    val missing = assertNotNull(query.detailOf(RustHprofFixture.MISSING_FRAGMENT))
                    assertFalse(missing.fields.any { it.name == "mFragmentManager" })
                    val explicitNull = assertNotNull(query.detailOf(RustHprofFixture.NULL_FRAGMENT))
                    assertEquals(HeapValueKind.NULL, explicitNull.fields.single { it.name == "mFragmentManager" }.valueKind)
                    for ((id, value) in (20L..27L).zip(listOf("true", "-7", "Z", "-123", "-456", "1.5", "-2.25", "-3"))) {
                        val array = assertNotNull(query.detailOf(id, 0, 1))
                        assertEquals(value, array.fields.single().displayValue)
                        assertEquals(HeapValueKind.PRIMITIVE, array.fields.single().valueKind)
                    }
                }
            }
        }

    @Test
    fun `dominator roots and child pagination reproduce the legacy object tree`() =
        withRichFixtures { root, file ->
            pair(root, file) { legacy, rust ->
                val expectedRoots = allDominators(legacy.source, null)
                val actualRoots = allDominators(rust.source, null)
                assertTrue(expectedRoots.isNotEmpty())
                assertEquals(expectedRoots.sortedBy { it.objectId }, actualRoots.sortedBy { it.objectId }, "roots: $file")
                val expected = dominatorTree(legacy.source)
                val actual = dominatorTree(rust.source)
                assertTrue(expected.any { it.parentObjectId == RustHprofFixture.ROOT_ARRAY })
                assertEquals(expected.sortedBy { it.objectId }, actual.sortedBy { it.objectId }, "tree: $file")
                assertEquals(HeapDominatorPage(0, emptyList()), rust.source.dominatorsPage(0, 2, 999_999))
            }
        }

    @Test
    fun `lifecycle findings and bitmap evidence agree without inventing missing native metrics`() =
        withRichFixtures { root, file ->
            pair(root, file) { legacy, rust ->
                val expected = legacy.source.findings()
                val actual = rust.source.findings()
                assertEquals(expected.leakSuspects, actual.leakSuspects, "heuristic findings: $file")
                assertEquals(expected.bitmapInstances, actual.bitmapInstances, "bitmap metadata: $file")
                assertEquals(expected.activityLeaks, actual.activityLeaks, "Activity report: $file")
                assertEquals(
                    expected.lifecycleLeakObjects.sortedBy { it.objectId },
                    actual.lifecycleLeakObjects.sortedBy { it.objectId },
                )
                assertEquals(
                    setOf(RustHprofFixture.DESTROYED_ACTIVITY, RustHprofFixture.NULL_FRAGMENT),
                    actual.lifecycleLeakObjects.map { it.objectId }.toSet(),
                )
                assertTrue(actual.leakSuspects.all { it.requiresManualVerification && it.confidence == 0f })
                val bitmap = actual.bitmapInstances.single()
                assertEquals(RustHprofFixture.BITMAP, bitmap.objectId)
                assertEquals(4, bitmap.width)
                assertEquals(3, bitmap.height)
                assertEquals(48L, bitmap.estimatedPixelBytes)
                assertEquals(40L, bitmap.javaSizeBytes)
                assertNull(bitmap.nativeSizeBytes)
                assertEquals(77L, bitmap.bitmapId)
                assertEquals(88L, bitmap.bitmapSourceId)
                val activity = actual.activityLeaks.single()
                assertEquals(2, activity.liveInstanceCount)
                assertEquals(1, activity.destroyedInstanceCount)
            }
        }

    @Test
    fun `mapping preload reload and coalescing preserve original source and object identity`() =
        runBlocking {
            val root = createTempDirectory("rust-parity-mapping")
            val file = RustHprofFixture.write(root.resolve("mapped.hprof"), 8, android = true)
            val digest = sha256(file)
            val mapping = Files.writeString(root.resolve("mapping.txt"), coalescingMapping("example.Merged"))
            val snapshots = mutableListOf<LoadedSnapshot>()
            try {
                val legacyBackend = backend(root.resolve("legacy"), HprofEngine.LEGACY)
                val rustBackend = backend(root.resolve("rust"), HprofEngine.RUST)
                val initialLegacy = importSnapshot(legacyBackend, file).also(snapshots::add)
                val initialRust = importSnapshot(rustBackend, file).also(snapshots::add)
                val mappedLegacy = reloadMapping(legacyBackend, mapping).also(snapshots::add)
                val mappedRust = reloadMapping(rustBackend, mapping).also(snapshots::add)
                assertHistogram(mappedLegacy.histogram, mappedRust.histogram)
                assertEquals(mappedLegacy.perHeapClasses, mappedRust.perHeapClasses)
                val merged = mappedRust.histogram.classes.single { it.className == "example.Merged" }
                assertEquals(6, merged.instanceCount, "Two class objects and four ordinary instances coalesce")
                assertEquals("a.Node", initialLegacy.source.query.groupingClassNameOf(RustHprofFixture.NODE))
                assertEquals("a.Node", initialRust.source.query.groupingClassNameOf(RustHprofFixture.NODE))
                assertEquals("example.Merged", mappedRust.source.query.groupingClassNameOf(RustHprofFixture.NODE))
                assertEquals("example.Merged", mappedRust.source.query.groupingClassNameOf(RustHprofFixture.OTHER_NODE))
                val expected = allInstances(mappedLegacy.source.query, listOf("example.Merged"))
                assertEquals(expected, allInstances(mappedRust.source.query, listOf("example.Merged")))
                assertEquals(
                    allInstances(mappedLegacy.source.query, listOf("example.Merged"), ordinaryOnly = true),
                    allInstances(mappedRust.source.query, listOf("example.Merged"), ordinaryOnly = true),
                )
                assertEquals(mappedLegacy.source.findings(), mappedRust.source.findings())
                assertEquals(sha256(mapping), mappedRust.mappingDigest)
                assertEquals(digest, mappedRust.sourceFileDigest)
                assertEquals(digest, sha256(file))

                val preloadedBackend = backend(root.resolve("preloaded"), HprofEngine.RUST)
                assertNull(success(preloadedBackend.importMapping(mapping)))
                val preloaded = importSnapshot(preloadedBackend, file).also(snapshots::add)
                assertHistogram(mappedRust.histogram, preloaded.histogram)

                Files.writeString(mapping, coalescingMapping("example.Reloaded"))
                val reloadedLegacy = reloadMapping(legacyBackend, mapping).also(snapshots::add)
                val reloadedRust = reloadMapping(rustBackend, mapping).also(snapshots::add)
                assertHistogram(reloadedLegacy.histogram, reloadedRust.histogram)
                assertNotEquals(mappedRust.mappingDigest, reloadedRust.mappingDigest)
                assertEquals(sha256(mapping), reloadedRust.mappingDigest)
                assertTrue(reloadedRust.histogram.classes.any { it.className == "example.Reloaded" })
                assertFalse(reloadedRust.histogram.classes.any { it.className == "example.Merged" })
                assertEquals(digest, reloadedRust.analysisSourceDigest)
            } finally {
                snapshots.asReversed().forEach { it.close() }
            }
        }

    @Test
    fun `failed regular file import preserves last good source and mapping reload request`() =
        runBlocking {
            for (engine in listOf(HprofEngine.LEGACY, HprofEngine.RUST)) {
                val root = createTempDirectory("rust-parity-failed-import-${engine.storageValue}")
                val fileA = RustHprofFixture.write(root.resolve("good-a.hprof"), 8, android = true)
                val corruptB = Files.writeString(root.resolve("corrupt-b.hprof"), "regular file, invalid HPROF header")
                val data = root.resolve("backend")
                val backend = backend(data, engine)
                importSnapshot(backend, fileA).use { oldA ->
                    val detail = assertNotNull(oldA.source.query.detailOf(RustHprofFixture.NODE))
                    val managedBefore = managedSourceFiles(data)
                    assertTrue(Files.isRegularFile(corruptB))

                    assertIs<MemoryBackendResult.Failure>(backend.importHprof(corruptB))

                    assertEquals(managedBefore, managedSourceFiles(data), "Failed B must release its managed source")
                    assertEquals(detail, oldA.source.query.detailOf(RustHprofFixture.NODE))
                    val mapping = Files.writeString(root.resolve("mapping.txt"), "example.Recovered -> a.Node:\n")
                    reloadMapping(backend, mapping).use { recovered ->
                        assertEquals(engine, recovered.engine)
                        assertEquals(sha256(fileA), recovered.sourceFileDigest)
                        assertEquals(sha256(fileA), recovered.analysisSourceDigest)
                        assertEquals(oldA.id, recovered.id)
                        assertEquals(oldA.histogram.summary.objectCount, recovered.histogram.summary.objectCount)
                        assertEquals("example.Recovered", recovered.source.query.groupingClassNameOf(RustHprofFixture.NODE))
                        assertEquals(detail, oldA.source.query.detailOf(RustHprofFixture.NODE))
                    }
                    assertEquals(detail, oldA.source.query.detailOf(RustHprofFixture.NODE), "Closing the reload must not close old A")
                    assertTrue(Files.isRegularFile(assertNotNull(oldA.rawHprofFile)))
                }
            }
        }

    @Test
    fun `engine changes affect new distinct snapshots without changing older snapshot queries`() =
        runBlocking {
            val root = createTempDirectory("rust-parity-engine-switch")
            val fileA = RustHprofFixture.write(root.resolve("legacy-a.hprof"), 4, android = false)
            val fileB = RustHprofFixture.write(root.resolve("rust-b.hprof"), 8, android = true, extraNodes = 1)
            val fileC = RustHprofFixture.write(root.resolve("legacy-c.hprof"), 8, android = false, extraNodes = 2)
            val backend = backend(root.resolve("backend"), HprofEngine.LEGACY)
            importSnapshot(backend, fileA).use { first ->
                assertIs<LoadedHeap>(first)
                val firstDetail = assertNotNull(first.source.query.detailOf(RustHprofFixture.NODE))
                backend.changeHprofEngine(HprofEngine.RUST)
                importSnapshot(backend, fileB).use { second ->
                    assertIs<RustLoadedSnapshot>(second)
                    val secondDetail = assertNotNull(second.source.query.detailOf(RustHprofFixture.NODE))
                    assertEquals(firstDetail, first.source.query.detailOf(RustHprofFixture.NODE))
                    backend.changeHprofEngine(HprofEngine.LEGACY)
                    importSnapshot(backend, fileC).use { third ->
                        assertIs<LoadedHeap>(third)
                        assertEquals(
                            listOf(HprofEngine.LEGACY, HprofEngine.RUST, HprofEngine.LEGACY),
                            listOf(first.engine, second.engine, third.engine),
                        )
                        assertEquals(3, setOf(first.id, second.id, third.id).size, "Distinct files must remain distinct open states")
                        assertEquals(firstDetail, first.source.query.detailOf(RustHprofFixture.NODE))
                        assertEquals(secondDetail, second.source.query.detailOf(RustHprofFixture.NODE))
                        assertNotNull(third.source.query.detailOf(RustHprofFixture.NODE))
                        assertEquals(listOf(36, 37, 38), listOf(first, second, third).map { it.histogram.summary.objectCount })
                    }
                    assertEquals(secondDetail, second.source.query.detailOf(RustHprofFixture.NODE))
                }
                assertEquals(firstDetail, first.source.query.detailOf(RustHprofFixture.NODE))
            }
        }

    @Test
    fun `cancellation at success progress closes Rust child and leases while retaining last good request`() =
        runBlocking {
            val root = createTempDirectory("rust-parity-success-cancellation")
            val fileA = RustHprofFixture.write(root.resolve("good-a.hprof"), 8, android = true)
            val fileB = RustHprofFixture.write(root.resolve("cancelled-b.hprof"), 4, android = false, extraNodes = 1)
            val data = root.resolve("backend")
            val backend = backend(data, HprofEngine.RUST)
            importSnapshot(backend, fileA).use { oldA ->
                val detail = assertNotNull(oldA.source.query.detailOf(RustHprofFixture.NODE))
                val managedBefore = managedSourceFiles(data)
                val childrenBefore = ProcessHandle.current().children().use { children -> children.map { it.pid() }.toList().toSet() }
                val cancellation = CancellationException("cancel exactly at successful progress 100")
                var cancelledChild: ProcessHandle? = null
                var cancelledFiles: Set<Path> = emptySet()
                try {
                    val actual =
                        assertFailsWith<CancellationException> {
                            backend.importHprof(fileB) { progress ->
                                if (progress == 100) {
                                    cancelledFiles = managedSourceFiles(data) - managedBefore
                                    assertTrue(cancelledFiles.any { it.fileName.toString().endsWith(".hprof") })
                                    cancelledChild =
                                        ProcessHandle.current().children().use { children ->
                                            children.filter { it.pid() !in childrenBefore }.toList().single()
                                        }
                                    assertTrue(assertNotNull(cancelledChild).isAlive)
                                    throw cancellation
                                }
                            }
                        }

                    assertEquals(cancellation.message, actual.message)
                    val child = assertNotNull(cancelledChild, "Import must reach successful progress before cancellation")
                    child.onExit().get(5, TimeUnit.SECONDS)
                    assertFalse(child.isAlive, "Cancelled import must terminate its sidecar")
                    assertEquals(managedBefore, managedSourceFiles(data), "Cancelled B must release all managed content")
                    assertTrue(cancelledFiles.all { !Files.exists(it) })
                    assertTrue(Files.isRegularFile(assertNotNull(oldA.rawHprofFile)))
                    assertEquals(detail, oldA.source.query.detailOf(RustHprofFixture.NODE))
                    val mapping = Files.writeString(root.resolve("mapping.txt"), "example.AfterCancel -> a.Node:\n")
                    reloadMapping(backend, mapping).use { reloaded ->
                        assertEquals(oldA.id, reloaded.id)
                        assertEquals(sha256(fileA), reloaded.sourceFileDigest)
                        assertEquals(36, reloaded.histogram.summary.objectCount)
                        assertEquals("example.AfterCancel", reloaded.source.query.groupingClassNameOf(RustHprofFixture.NODE))
                    }
                    assertEquals(detail, oldA.source.query.detailOf(RustHprofFixture.NODE))
                } finally {
                    cancelledChild?.takeIf { it.isAlive }?.let { child ->
                        child.destroyForcibly()
                        child.onExit().get(5, TimeUnit.SECONDS)
                    }
                }
            }
        }

    @Test
    fun `Rust enriches per heap and global native reachable metrics only from matching raw rows`() =
        runBlocking {
            val root = createTempDirectory("rust-parity-native-metrics")
            val file = RustHprofFixture.write(root.resolve("rich.hprof"), 8, android = true)
            importSnapshot(backend(root.resolve("legacy"), HprofEngine.LEGACY), file).use { raw ->
                val metrics = classMetrics(raw)
                val analyzer =
                    HprofClassMetricsAnalyzer { managed ->
                        assertEquals(sha256(file), sha256(managed), "Metrics must be bound to the analysis source")
                        metrics
                    }
                importSnapshot(backend(root.resolve("rust"), HprofEngine.RUST, analyzer), file).use { enriched ->
                    assertEnrichedMetrics(enriched, metrics)
                    assertEquals(raw.histogram.summary, enriched.histogram.summary)
                    assertEquals(raw.histogram.classes.map { it.className }, enriched.histogram.classes.map { it.className })
                    assertEquals(raw.source.metadata, enriched.source.metadata)
                    assertEquals(raw.source.query.identity(RustHprofFixture.NODE), enriched.source.query.identity(RustHprofFixture.NODE))
                    assertEquals(raw.source.query.detailOf(RustHprofFixture.NODE), enriched.source.query.detailOf(RustHprofFixture.NODE))
                }
            }
        }

    @Test
    fun `Rust rejects count shallow and missing raw class metrics without attributing native values`() =
        runBlocking {
            val root = createTempDirectory("rust-parity-rejected-metrics")
            val file = RustHprofFixture.write(root.resolve("rich.hprof"), 4, android = true)
            importSnapshot(backend(root.resolve("legacy"), HprofEngine.LEGACY), file).use { raw ->
                val metrics = classMetrics(raw)
                val incorrectCount =
                    metrics.mapIndexed { index, row ->
                        if (index == 0) row.copy(objectCount = row.objectCount + 1) else row
                    }
                val incorrectShallow =
                    metrics.mapIndexed { index, row ->
                        if (index == 0) row.copy(shallowSize = row.shallowSize + 1) else row
                    }
                val invalid =
                    listOf(
                        "count" to incorrectCount,
                        "shallow" to incorrectShallow,
                        "missing-class" to metrics.drop(1),
                    )
                for ((label, rows) in invalid) {
                    val backend = backend(root.resolve(label), HprofEngine.RUST, HprofClassMetricsAnalyzer { rows })
                    importSnapshot(backend, file).use { rejected ->
                        assertUnknownNativeMetrics(rejected)
                        assertHistogram(raw.histogram, rejected.histogram)
                        assertEquals(raw.perHeapClasses, rejected.perHeapClasses)
                    }
                }
            }
        }

    @Test
    fun `Rust validates raw metrics before mapping can hide balanced class mismatches`() =
        runBlocking {
            val root = createTempDirectory("rust-parity-mapped-metrics")
            val file = RustHprofFixture.write(root.resolve("rich.hprof"), 8, android = true)
            val mapping = Files.writeString(root.resolve("mapping.txt"), "example.Merged -> a.Node:\nexample.Merged -> b.Node:\n")
            val names = mapOf("a.Node" to "example.Merged", "b.Node" to "example.Merged")
            importSnapshot(backend(root.resolve("legacy"), HprofEngine.LEGACY), file).use { raw ->
                val metrics = classMetrics(raw)
                val acceptedBackend = backend(root.resolve("accepted"), HprofEngine.RUST, HprofClassMetricsAnalyzer { metrics })
                assertNull(success(acceptedBackend.importMapping(mapping)))
                importSnapshot(acceptedBackend, file).use { accepted ->
                    assertTrue(accepted.histogram.classes.any { it.className == "[Lexample.Merged;" })
                    assertEquals(
                        6,
                        accepted.histogram.classes
                            .single { it.className == "example.Merged" }
                            .instanceCount,
                    )
                    assertEnrichedMetrics(accepted, metrics, names)
                }
                for (field in listOf("count", "shallow")) {
                    val balanced =
                        metrics.map { row ->
                            val delta =
                                when {
                                    row.heapName != "app" -> 0
                                    row.className == "a.Node" -> 1
                                    row.className == "b.Node" -> -1
                                    else -> 0
                                }
                            if (field == "count") {
                                row.copy(objectCount = row.objectCount + delta)
                            } else {
                                row.copy(shallowSize = row.shallowSize + delta)
                            }
                        }
                    assertNotEquals(metrics, balanced)
                    assertEquals(metrics.sumOf { it.objectCount }, balanced.sumOf { it.objectCount })
                    assertEquals(metrics.sumOf { it.shallowSize }, balanced.sumOf { it.shallowSize })
                    val rejectedBackend = backend(root.resolve("balanced-$field"), HprofEngine.RUST, HprofClassMetricsAnalyzer { balanced })
                    assertNull(success(rejectedBackend.importMapping(mapping)))
                    importSnapshot(rejectedBackend, file).use { rejected ->
                        assertEquals(
                            6,
                            rejected.histogram.classes
                                .single { it.className == "example.Merged" }
                                .instanceCount,
                        )
                        assertUnknownNativeMetrics(rejected)
                    }
                }
            }
        }

    @Test
    fun `source exports preserve raw and converted provenance plus legacy csv json and report output`() =
        runBlocking {
            val root = createTempDirectory("rust-parity-exports")
            val raw = RustHprofFixture.write(root.resolve("android.raw.hprof"), 4, android = true, extraNodes = 505)
            val converted = RustHprofFixture.write(root.resolve("standard.hprof"), 4, android = false, extraNodes = 505)
            val metadata = MemorySessionMetadata("parity-session", "example.app", "test-device", Instant.EPOCH, raw, converted)
            val legacyBackend = backend(root.resolve("legacy"), HprofEngine.LEGACY)
            val rustBackend = backend(root.resolve("rust"), HprofEngine.RUST)
            success(legacyBackend.loadSession(metadata)).use { legacy ->
                success(rustBackend.loadSession(metadata)).use { rust ->
                    val legacyHeap = assertIs<LoadedHeap>(legacy)
                    assertIs<RustLoadedSnapshot>(rust)
                    val exports = MemoryExportAdapters()
                    val legacyOutput = Files.createDirectory(root.resolve("legacy-exports"))
                    val rustOutput = Files.createDirectory(root.resolve("rust-exports"))
                    exports.copyRawHprof(legacyHeap.heapDump, legacyOutput.resolve("raw.hprof"))
                    exports.copyConvertedHprof(legacyHeap.heapDump, legacyOutput.resolve("converted.hprof"))
                    SnapshotSourceExports.copyRaw(rust, rustOutput.resolve("raw.hprof"))
                    SnapshotSourceExports.copyConverted(rust, rustOutput.resolve("converted.hprof"))
                    assertContentEquals(Files.readAllBytes(raw), Files.readAllBytes(rustOutput.resolve("raw.hprof")))
                    assertContentEquals(Files.readAllBytes(converted), Files.readAllBytes(rustOutput.resolve("converted.hprof")))
                    exports.exportClassHistogramCsv(legacy.histogram, legacyOutput.resolve("histogram.csv"))
                    rustBackend.exportHistogram(rust.histogram, rustOutput.resolve("histogram.csv"))
                    exports.exportClassInstancesCsv(legacyHeap.heapDump, "a.Node", legacyOutput.resolve("instances.csv"))
                    SnapshotSourceExports.instances(rust, "a.Node", rustOutput.resolve("instances.csv"))
                    assertEquals(509, Files.readAllLines(rustOutput.resolve("instances.csv")).size)
                    val summary = HeapSnapshotSummary("parity", sourceFileDigest = sha256(raw))
                    exports.exportHeapSnapshotJson(legacyHeap.heapDump, legacy.histogram, legacyOutput.resolve("snapshot.json"), summary)
                    SnapshotSourceExports.json(rust, rustOutput.resolve("snapshot.json"), summary)
                    val context = HeapExportContext(snapshotId = "parity", exportedAt = Instant.EPOCH)
                    exports.exportInvestigationReportMarkdown(
                        legacyHeap.heapDump,
                        legacy.histogram,
                        null,
                        legacyOutput.resolve("report.md"),
                        summary,
                        context,
                    )
                    SnapshotSourceExports.report(rust, null, rustOutput.resolve("report.md"), summary, context)
                    for (name in listOf("raw.hprof", "converted.hprof", "histogram.csv", "instances.csv", "snapshot.json", "report.md")) {
                        assertContentEquals(
                            Files.readAllBytes(legacyOutput.resolve(name)),
                            Files.readAllBytes(rustOutput.resolve(name)),
                            "export $name",
                        )
                    }
                    val originalRaw = Files.readAllBytes(raw)
                    val originalConverted = Files.readAllBytes(converted)
                    Files.writeString(raw, "external raw source overwritten")
                    Files.writeString(converted, "external analysis source overwritten")
                    SnapshotSourceExports.copyRaw(rust, rustOutput.resolve("pinned-raw.hprof"))
                    SnapshotSourceExports.copyConverted(rust, rustOutput.resolve("pinned-converted.hprof"))
                    assertContentEquals(originalRaw, Files.readAllBytes(rustOutput.resolve("pinned-raw.hprof")))
                    assertContentEquals(originalConverted, Files.readAllBytes(rustOutput.resolve("pinned-converted.hprof")))
                    assertEquals(sha256(rustOutput.resolve("pinned-raw.hprof")), rust.sourceFileDigest)
                    assertNotNull(rust.source.query.detailOf(RustHprofFixture.NODE))
                }
            }
        }

    @Test
    fun `committed SDK converted fixture has equal source queries metrics and findings`() =
        runBlocking {
            val root = createTempDirectory("rust-parity-sdk-converted")
            val file = committedConvertedFixture()
            assertEquals(174L, Files.size(file))
            assertEquals("7d6727771e253c5f9445fff0f2497074fd1de91ee4977beb06608165c156ed66", sha256(file))
            pair(root, file) { legacy, rust ->
                assertHistogram(legacy.histogram, rust.histogram)
                assertEquals(legacy.source.metadata, rust.source.metadata)
                assertEquals(legacy.perHeapClasses, rust.perHeapClasses)
                assertEquals(legacy.source.findings(), rust.source.findings())
                legacy.histogram.classes.forEach { stats ->
                    val expected = allInstances(legacy.source.query, listOf(stats.className))
                    assertEquals(expected, allInstances(rust.source.query, listOf(stats.className)))
                    expected.forEach { row ->
                        assertEquals(legacy.source.query.identity(row.objectId), rust.source.query.identity(row.objectId))
                        assertEquals(legacy.source.query.detailOf(row.objectId), rust.source.query.detailOf(row.objectId))
                    }
                }
            }
        }

    private fun backend(
        root: Path,
        engine: HprofEngine,
        metricsAnalyzer: HprofClassMetricsAnalyzer = HprofClassMetricsAnalyzer { null },
    ): DesktopMemoryProfilerBackend {
        val executable =
            Path.of(assertNotNull(System.getProperty("aps.hprofEngine.path"), "Cargo test prerequisite must set aps.hprofEngine.path"))
        assertTrue(Files.isRegularFile(executable), "Rust sidecar was not built: $executable")
        assertTrue(Files.isExecutable(executable), "Rust sidecar is not executable: $executable")
        return DesktopMemoryProfilerBackend(
            root,
            hprofEngine = engine,
            hprofClassMetricsAnalyzer = metricsAnalyzer,
            rustExecutable = { Path.of(System.getProperty("aps.hprofEngine.path")) },
        )
    }

    private suspend fun importSnapshot(
        backend: DesktopMemoryProfilerBackend,
        file: Path,
    ): LoadedSnapshot = success(backend.importHprof(file))

    private suspend fun reloadMapping(
        backend: DesktopMemoryProfilerBackend,
        file: Path,
    ): LoadedSnapshot = assertNotNull(success(backend.importMapping(file)), "Mapping reload must return the loaded snapshot")

    private fun <T> success(result: MemoryBackendResult<T>): T =
        when (result) {
            is MemoryBackendResult.Success -> result.value
            is MemoryBackendResult.Failure -> error("${result.title}: ${result.detail}")
        }

    private suspend fun pair(
        root: Path,
        file: Path,
        check: (LoadedSnapshot, LoadedSnapshot) -> Unit,
    ) {
        importSnapshot(backend(root.resolve("legacy"), HprofEngine.LEGACY), file).use { legacy ->
            importSnapshot(backend(root.resolve("rust"), HprofEngine.RUST), file).use { rust -> check(legacy, rust) }
        }
    }

    private fun withRichFixtures(check: suspend (Path, Path) -> Unit) =
        runBlocking {
            for (android in listOf(false, true)) {
                for (idSize in listOf(4, 8)) {
                    val root = createTempDirectory("rust-parity-${if (android) "android" else "standard"}-$idSize")
                    check(root, RustHprofFixture.write(root.resolve("rich.hprof"), idSize, android))
                }
            }
        }

    private fun allInstances(
        query: HeapSnapshotQuery,
        names: List<String>,
        heap: String? = null,
        ordinaryOnly: Boolean = false,
    ): List<InstanceQueryRow> {
        val first = query.instancesPage(names, heap, 0, 2, ordinaryOnly)
        val rows = first.rows.toMutableList()
        assertEquals(0, first.offset)
        while (rows.size < first.total) {
            val page = query.instancesPage(names, heap, rows.size, 2, ordinaryOnly)
            assertEquals(first.total, page.total)
            assertEquals(rows.size, page.offset)
            assertTrue(page.rows.isNotEmpty(), "Instance page must make progress")
            rows += page.rows
        }
        assertEquals(first.total, rows.size)
        assertTrue(query.instancesPage(names, heap, first.total, 2, ordinaryOnly).rows.isEmpty())
        return rows
    }

    private fun allDominators(
        source: HeapSnapshotSource,
        parent: Long?,
    ): List<MemoryDominatorRow> {
        val first = source.dominatorsPage(0, 2, parent)
        val rows = first.rows.toMutableList()
        while (rows.size < first.total) {
            val page = source.dominatorsPage(rows.size, 2, parent)
            assertEquals(first.total, page.total)
            assertTrue(page.rows.isNotEmpty(), "Dominator page must make progress")
            rows += page.rows
        }
        assertEquals(first.total, rows.size)
        assertTrue(source.dominatorsPage(first.total, 2, parent).rows.isEmpty())
        return rows
    }

    private fun dominatorTree(source: HeapSnapshotSource): List<MemoryDominatorRow> {
        val pending = ArrayDeque(allDominators(source, null))
        val rows = linkedMapOf<Long, MemoryDominatorRow>()
        while (pending.isNotEmpty()) {
            val row = pending.removeFirst()
            assertNull(rows.put(row.objectId, row), "Dominator object ${row.objectId} visited twice")
            pending.addAll(allDominators(source, row.objectId))
        }
        return rows.values.toList()
    }

    private fun assertHistogram(
        expected: HeapHistogram,
        actual: HeapHistogram,
    ) {
        assertEquals(expected.summary.objectCount, actual.summary.objectCount)
        assertEquals(expected.summary.classCount, actual.summary.classCount)
        assertEquals(expected.summary.shallowSize, actual.summary.shallowSize)
        assertEquals(expected.summary.heapSegments.sortedBy { it.name }, actual.summary.heapSegments.sortedBy { it.name })
        assertEquals(expected.classes.sortedBy { it.className }, actual.classes.sortedBy { it.className })
    }

    private fun coalescingMapping(name: String): String =
        "$name -> a.Node:\n$name -> b.Node:\nexample.ScreenActivity -> q.Screen:\nexample.ScreenFragment -> q.Fragment:\n"

    private fun managedSourceFiles(dataRoot: Path): Set<Path> {
        val directory = dataRoot.resolve("hprof-sources")
        return if (Files.isDirectory(directory)) {
            Files.list(directory).use { files ->
                files
                    .filter {
                        val name = it.fileName.toString()
                        name.endsWith(".hprof") || name.endsWith(".tmp")
                    }.toList()
                    .toSet()
            }
        } else {
            emptySet()
        }
    }

    private fun classMetrics(raw: LoadedSnapshot): List<HprofClassMetric> =
        raw.perHeapClasses
            .toSortedMap()
            .flatMap { (heap, classes) ->
                classes.sortedBy { it.className }.map { heap to it }
            }.mapIndexed { index, (heap, stats) ->
                val seed = index + 1L
                HprofClassMetric(
                    heap.lowercase(),
                    stats.className,
                    stats.instanceCount.toLong(),
                    stats.shallowSize,
                    nativeSize = seed * 100,
                    reachableShallowSize = seed * 10,
                    reachableNativeSize = seed * 20,
                )
            }.also { assertTrue(it.isNotEmpty(), "The raw fixture must supply class metrics") }

    private fun assertEnrichedMetrics(
        snapshot: LoadedSnapshot,
        metrics: List<HprofClassMetric>,
        mappedNames: Map<String, String> = emptyMap(),
    ) {
        val classMapping = ProguardMapping(mappedNames)
        assertEquals(
            metrics.map { classMapping.originalClassName(it.className) }.toSet(),
            snapshot.histogram.classes
                .map { it.className }
                .toSet(),
        )
        assertEquals(
            metrics.map { it.heapName.lowercase() }.toSet(),
            snapshot.perHeapClasses.keys
                .map { it.lowercase() }
                .toSet(),
        )

        fun assertMetrics(
            stats: ClassStats,
            rows: List<HprofClassMetric>,
        ) {
            assertTrue(rows.isNotEmpty(), "Every displayed class must be backed by validated raw rows")
            assertEquals(rows.sumOf { it.nativeSize }, stats.nativeSize, "${stats.className} native")
            assertEquals(rows.sumOf { it.reachableShallowSize }, stats.reachableShallowSize, "${stats.className} reachable shallow")
            assertEquals(rows.sumOf { it.reachableNativeSize }, stats.reachableNativeSize, "${stats.className} reachable native")
        }
        snapshot.perHeapClasses.forEach { (heap, classes) ->
            classes.forEach { stats ->
                assertMetrics(
                    stats,
                    metrics.filter {
                        it.heapName.equals(heap, ignoreCase = true) &&
                            classMapping.originalClassName(it.className) == stats.className
                    },
                )
            }
        }
        snapshot.histogram.classes.forEach { stats ->
            assertMetrics(stats, metrics.filter { classMapping.originalClassName(it.className) == stats.className })
        }
    }

    private fun assertUnknownNativeMetrics(snapshot: LoadedSnapshot) {
        (snapshot.histogram.classes + snapshot.perHeapClasses.values.flatten()).forEach { stats ->
            assertNull(stats.nativeSize, "Rejected metrics must leave ${stats.className} native size unknown")
            assertNull(stats.reachableShallowSize, "Rejected metrics must leave ${stats.className} reachable shallow unknown")
            assertNull(stats.reachableNativeSize, "Rejected metrics must leave ${stats.className} reachable native unknown")
        }
    }

    private fun committedConvertedFixture(): Path {
        val relative = Path.of("parser-hprof/src/test/resources/hprof/android-converted-sample.hprof")
        val candidates =
            generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
                .flatMap { root ->
                    sequenceOf(
                        root.resolve(relative),
                        root.resolve("memory-profiler").resolve(relative),
                        root.resolve("desktop-viewer/memory-profiler").resolve(relative),
                    )
                }
        return assertNotNull(candidates.firstOrNull { Files.isRegularFile(it) }, "Committed SDK-converted fixture not found")
    }

    private fun sha256(path: Path): String =
        java.util.HexFormat
            .of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))
}
