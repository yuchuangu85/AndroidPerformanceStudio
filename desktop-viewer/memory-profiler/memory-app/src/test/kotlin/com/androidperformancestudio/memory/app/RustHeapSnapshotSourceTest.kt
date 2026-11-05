@file:Suppress("MagicNumber", "MaxLineLength")

package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.analysis.FieldValue
import com.androidperformancestudio.memory.analysis.InstanceQueryDetail
import com.androidperformancestudio.memory.analysis.InstanceQueryRow
import com.androidperformancestudio.memory.analysis.ProguardMapping
import com.androidperformancestudio.memory.model.ActivityLeakEntry
import com.androidperformancestudio.memory.model.BitmapInstanceStats
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapHistogram
import com.androidperformancestudio.memory.model.HeapSegment
import com.androidperformancestudio.memory.model.HeapSummary
import com.androidperformancestudio.memory.model.HeapValueKind
import com.androidperformancestudio.memory.model.LeakSuspect
import com.androidperformancestudio.memory.model.MemoryWarning
import com.androidperformancestudio.memory.model.ObjectReference
import com.androidperformancestudio.memory.presentation.MemoryDominatorRow
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RustHeapSnapshotSourceTest {
    @Test
    fun `open forwards opt in disk graph separately from persistent parser cache`() {
        val child = replies()
        val graphDirectory = Path.of("/private/managed data/hprof-graph-rust")
        val cacheDirectory = Path.of("/private/managed data/hprof-index-rust")
        RustHeapSnapshotSource
            .open(
                RustHprofProcess(child, TEST_DIGEST),
                Path.of("/managed/heap.hprof"),
                cacheDirectory = cacheDirectory,
                graphStorage = "disk",
                graphDirectory = graphDirectory,
            ).use {
                val args = child.requests().single()["args"] as Map<*, *>
                assertEquals("disk", args["graphStorage"])
                assertEquals(graphDirectory.toString(), args["graphDir"])
                assertEquals(cacheDirectory.toString(), args["cacheDir"])
                assertEquals(TEST_DIGEST, args["sourceDigest"])
            }
        assertFalse(child.isAlive)
    }

    @Test
    fun `invalid disk graph configuration closes child without falling back`() {
        for ((storage, directory) in listOf("disk" to null, "invalid" to Path.of("/managed/graph"))) {
            val child = replies()
            assertFailsWith<IllegalArgumentException> {
                RustHeapSnapshotSource.open(
                    RustHprofProcess(child, TEST_DIGEST),
                    Path.of("/managed/heap.hprof"),
                    graphStorage = storage,
                    graphDirectory = directory,
                )
            }
            assertFalse(child.isAlive)
            assertTrue(child.requests().none { it["op"] == "open" })
        }
    }

    @Test
    fun `open forwards configured cache directory without changing snapshot metadata`() {
        val child = replies()
        val directory = Path.of("/private/cache path/hprof-index-rust")
        RustHeapSnapshotSource
            .open(
                RustHprofProcess(child, TEST_DIGEST),
                Path.of("/managed/heap.hprof"),
                cacheDirectory = directory,
            ).use {
                val args = child.requests().single()["args"] as Map<*, *>
                assertEquals(directory.toString(), args["cacheDir"])
                assertEquals(TEST_DIGEST, args["sourceDigest"])
            }
    }

    @Test
    fun `open sends source and mapping via wire and decodes metadata exactly`() {
        val child = replies()
        val path = Path.of("/managed/heap path; 'not a shell'.hprof")
        val mapping = ProguardMapping(mapOf("a.b" to "example.Activity"))
        RustHeapSnapshotSource.open(RustHprofProcess(child, TEST_DIGEST), path, mapping).use { source ->
            assertEquals(
                HeapSourceMetadata(
                    "JAVA PROFILE 1.0.3",
                    8,
                    123456789L,
                    2,
                    3,
                    4,
                    5,
                    6,
                    listOf(MemoryWarning("partial record", 99), MemoryWarning("unknown offset", null)),
                    listOf("App", "Zygote"),
                ),
                source.metadata,
            )
            val request = child.requests().single()
            assertEquals("open", request["op"])
            assertEquals(
                mapOf("path" to path.toString(), "sourceDigest" to TEST_DIGEST, "mapping" to mapping.obfuscatedToOriginal),
                request["args"],
            )
        }
        assertFalse(child.isAlive)
    }

    @Test
    @Suppress("LongMethod")
    fun `mapped and raw histogram share complete decoder including TP evidence`() {
        val payload =
            mapOf(
                "summary" to
                    mapOf(
                        "objectCount" to 3,
                        "shallowSize" to 48,
                        "classCount" to 2,
                        "heapSegments" to listOf(mapOf("name" to "App", "sizeBytes" to 48, "objectCount" to 3)),
                    ),
                "classes" to
                    listOf(
                        mapOf(
                            "className" to "example.Activity",
                            "instanceCount" to 2,
                            "shallowSize" to 32,
                            "retainedSize" to 200,
                            "obfuscatedClassName" to "a.b",
                            "hierarchyDepth" to 3,
                            "nativeSize" to 64,
                            "moduleName" to "feature",
                            "allocations" to 5,
                            "deallocations" to 2,
                            "allocationsSize" to 80,
                            "deallocationsSize" to 32,
                            "shallowSizeChange" to -16,
                            "allocationMethod" to "newActivity",
                            "allocationCallstack" to listOf("entry", "newActivity"),
                            "totalCount" to 7,
                            "heapNames" to listOf("App", "Zygote"),
                            "reachableShallowSize" to 16,
                            "reachableNativeSize" to 33,
                        ),
                        mapOf(
                            "className" to "other.Type",
                            "instanceCount" to 1,
                            "shallowSize" to 16,
                            "retainedSize" to null,
                            "obfuscatedClassName" to null,
                            "hierarchyDepth" to null,
                            "nativeSize" to null,
                            "heapNames" to emptyList<String>(),
                        ),
                    ),
            )
        val expected =
            HeapHistogram(
                HeapSummary(3, 48, 2, listOf(HeapSegment("App", 48, 3))),
                listOf(
                    ClassStats(
                        className = "example.Activity",
                        instanceCount = 2,
                        shallowSize = 32,
                        retainedSize = 200,
                        obfuscatedClassName = "a.b",
                        hierarchyDepth = 3,
                        nativeSize = 64,
                        moduleName = "feature",
                        allocations = 5,
                        deallocations = 2,
                        allocationsSize = 80,
                        deallocationsSize = 32,
                        shallowSizeChange = -16,
                        allocationMethod = "newActivity",
                        allocationCallstack = listOf("entry", "newActivity"),
                        totalCount = 7,
                        heapNames = setOf("App", "Zygote"),
                        reachableShallowSize = 16,
                        reachableNativeSize = 33,
                    ),
                    ClassStats("other.Type", 1, 16),
                ),
            )
        val child = replies(payload, payload)
        open(child).use { source ->
            assertEquals(expected, source.histogram("App"))
            assertEquals(expected, source.rawHistogram())
            assertEquals(listOf("open", "histogram", "rawHistogram"), child.requests().map { it["op"] })
            assertEquals(mapOf("heapName" to "App"), child.requests()[1]["args"])
            assertEquals(mapOf("heapName" to null), child.requests()[2]["args"])
        }
    }

    @Test
    fun `anchored instances decode actual offset and preserve unknown evidence`() {
        val child =
            replies(
                mapOf(
                    "total" to 30,
                    "offset" to 17,
                    "rows" to
                        listOf(
                            instancePayload + mapOf("objectId" to Long.MIN_VALUE, "index" to 18),
                            instancePayload +
                                mapOf(
                                    "objectId" to -1L,
                                    "index" to 19,
                                    "retainedSize" to 100,
                                    "depth" to 2,
                                    "reachable" to true,
                                    "nativeSize" to 200,
                                    "shallowSizeKnown" to true,
                                    "className" to "java.lang.Class",
                                    "representedClassName" to "example.Type",
                                ),
                        ),
                ),
            )
        open(child).use { source ->
            val page = source.query.instancesPage(listOf("example.Type"), "App", 0, 2, true, Long.MIN_VALUE)
            assertEquals(30, page.total)
            assertEquals(17, page.offset)
            assertEquals(
                listOf(
                    InstanceQueryRow(Long.MIN_VALUE, 18, 16, null, null, false, null, false, null, null),
                    InstanceQueryRow(-1, 19, 16, 100, 2, true, 200, true, "java.lang.Class", "example.Type"),
                ),
                page.rows,
            )
            assertEquals(
                mapOf(
                    "classNames" to listOf("example.Type"),
                    "heapName" to "App",
                    "offset" to 0L,
                    "limit" to 2L,
                    "ordinaryOnly" to true,
                    "anchorObjectId" to Long.MIN_VALUE,
                ),
                child.requests()[1]["args"],
            )
        }
    }

    @Test
    fun `detail decodes every field and reference without losing object identity`() {
        val child = replies(detailPayload)
        open(child).use { source ->
            val result = source.query.detailOf(Long.MIN_VALUE, 20, 10)
            val expectedField =
                FieldValue(
                    "item",
                    "a preview",
                    -1L,
                    "java.lang.String",
                    9,
                    HeapValueKind.STRING,
                    16,
                    32,
                    48,
                    64,
                    "represented.Type",
                    "preview",
                    7,
                )
            val expected =
                InstanceQueryDetail(
                    objectId = Long.MIN_VALUE,
                    className = "example.Type[]",
                    shallowSize = 128,
                    retainedSize = 256,
                    depth = 8,
                    isArray = true,
                    elementCount = 300,
                    arrayPageSize = 10,
                    fields = listOf(expectedField),
                    referenceChain = listOf(ObjectReference("root", -1, "java.lang.String")),
                    references = listOf(expectedField.copy(name = "inbound", valueKind = HeapValueKind.OBJECT)),
                    shallowSizeKnown = false,
                    nativeSize = 512,
                    valueKind = HeapValueKind.ARRAY,
                    representedClassName = "example.Type",
                    stringPreview = "object preview",
                    retainedNativeSize = 1024,
                )
            assertEquals(expected, result)
            assertEquals(
                mapOf("objectId" to Long.MIN_VALUE, "arrayStart" to 20L, "arrayLimit" to 10L),
                child.requests()[1]["args"],
            )
        }
    }

    @Test
    fun `detail null metrics stay null and all value kinds decode`() {
        val fields =
            HeapValueKind.entries.map { kind ->
                mapOf(
                    "name" to kind.name,
                    "displayValue" to "unknown",
                    "targetObjectId" to null,
                    "targetClassName" to null,
                    "depth" to null,
                    "valueKind" to kind.name,
                    "shallowSize" to null,
                    "retainedSize" to null,
                    "nativeSize" to null,
                    "retainedNativeSize" to null,
                    "representedClassName" to null,
                    "stringPreview" to null,
                    "childCount" to null,
                )
            }
        val payload =
            detailPayload +
                mapOf(
                    "retainedSize" to null,
                    "depth" to null,
                    "elementCount" to null,
                    "nativeSize" to null,
                    "retainedNativeSize" to null,
                    "representedClassName" to null,
                    "stringPreview" to null,
                    "fields" to fields,
                    "references" to emptyList<Any?>(),
                    "referenceChain" to emptyList<Any?>(),
                )
        open(replies(payload)).use { source ->
            val detail = assertNotNull(source.query.detailOf(Long.MIN_VALUE))
            assertNull(detail.retainedSize)
            assertNull(detail.depth)
            assertNull(detail.elementCount)
            assertNull(detail.nativeSize)
            assertNull(detail.retainedNativeSize)
            assertNull(detail.representedClassName)
            assertNull(detail.stringPreview)
            assertEquals(HeapValueKind.entries, detail.fields.map { it.valueKind })
            detail.fields.forEach { field ->
                assertEquals(FieldValue(field.name, "unknown", null, null, valueKind = field.valueKind), field)
            }
        }
    }

    @Test
    fun `identity missing object and lazy identity helpers use remote query`() {
        val identity =
            mapOf(
                "className" to "java.lang.Class",
                "groupingClassName" to "example.Type",
                "heapName" to "App",
                "shallowSize" to 16,
                "kind" to "CLASS",
            )
        val child = replies(null, null, identity, identity, identity)
        open(child).use { source ->
            assertNull(source.query.identity(99))
            assertNull(source.query.detailOf(99))
            assertEquals(HeapObjectIdentity("java.lang.Class", "example.Type", "App", 16, "CLASS"), source.query.identity(-1))
            assertEquals("example.Type", source.query.groupingClassNameOf(-1))
            assertEquals("App", source.query.heapNameOf(-1))
            assertEquals(6, child.requests().size)
        }
    }

    @Test
    fun `dominator queries pass root or parent and decode all row fields`() {
        val root =
            mapOf(
                "objectId" to Long.MIN_VALUE,
                "className" to "example.Root",
                "shallowSize" to 16,
                "retainedSize" to 32,
                "depth" to 1,
                "parentObjectId" to null,
            )
        val nested = root + mapOf("objectId" to -1L, "depth" to 2, "parentObjectId" to Long.MIN_VALUE)
        val child = replies(mapOf("total" to 1, "rows" to listOf(root)), mapOf("total" to 2, "rows" to listOf(nested)))
        open(child).use { source ->
            assertEquals(
                HeapDominatorPage(1, listOf(MemoryDominatorRow(Long.MIN_VALUE, "example.Root", 16, 32, 1, null))),
                source.dominatorsPage(0, 4),
            )
            assertEquals(
                HeapDominatorPage(2, listOf(MemoryDominatorRow(-1, "example.Root", 16, 32, 2, Long.MIN_VALUE))),
                source.dominatorsPage(1, 4, Long.MIN_VALUE),
            )
            assertEquals(mapOf("offset" to 0L, "limit" to 4L, "parentObjectId" to null), child.requests()[1]["args"])
            assertEquals(mapOf("offset" to 1L, "limit" to 4L, "parentObjectId" to Long.MIN_VALUE), child.requests()[2]["args"])
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `findings decode leak bitmap activity and lifecycle models exactly`() {
        val chain = listOf(referencePayload)
        val payload =
            mapOf(
                "leakSuspects" to
                    listOf(
                        mapOf(
                            "className" to "example.Activity",
                            "reason" to "destroyed",
                            "retainedSize" to null,
                            "instanceCount" to 2,
                            "referenceChain" to chain,
                            "confidence" to 0,
                            "requiresManualVerification" to true,
                            "activityOrFragmentLeak" to true,
                        ),
                    ),
                "bitmapInstances" to
                    listOf(
                        mapOf(
                            "objectId" to Long.MIN_VALUE,
                            "width" to null,
                            "height" to 30,
                            "retainedSize" to 16,
                            "referenceChain" to chain,
                            "estimatedPixelBytes" to 1200,
                            "javaSizeBytes" to 16,
                            "nativeSizeBytes" to null,
                            "className" to "android.graphics.Bitmap",
                            "bitmapId" to -1L,
                            "bitmapSourceId" to null,
                        ),
                    ),
                "activityLeaks" to
                    listOf(
                        mapOf(
                            "className" to "example.Activity",
                            "liveInstanceCount" to 3,
                            "destroyedInstanceCount" to 2,
                            "retainedSize" to null,
                            "referenceChain" to chain,
                        ),
                    ),
                "lifecycleLeakObjects" to listOf(mapOf("objectId" to -1L, "className" to "example.Activity", "heapName" to null)),
            )
        val expectedChain = listOf(ObjectReference("root", -1, "java.lang.String"))
        open(replies(payload)).use { source ->
            assertEquals(
                HeapFindings(
                    listOf(LeakSuspect("example.Activity", "destroyed", null, 2, expectedChain, 0f, true, true)),
                    listOf(
                        BitmapInstanceStats(
                            Long.MIN_VALUE,
                            null,
                            30,
                            16,
                            expectedChain,
                            1200,
                            16,
                            null,
                            "android.graphics.Bitmap",
                            -1,
                            null,
                        ),
                    ),
                    listOf(ActivityLeakEntry("example.Activity", 3, 2, null, expectedChain)),
                    listOf(HeapLifecycleObject(-1, "example.Activity", null)),
                ),
                source.findings(),
            )
        }
    }

    @Test
    fun `open failure always closes process including corrupt metadata`() {
        listOf(
            wireFrame(response(metadataPayload + mapOf("idSize" to 16))),
            wireFrame(response(metadataPayload + mapOf("classCount" to Int.MAX_VALUE.toLong() + 1))),
            wireFrame(response(metadataPayload) + mapOf("sourceDigest" to "not-the-source")),
            byteArrayOf(),
        ).forEach { frames ->
            val child = FakeRustChild(frames)
            assertFailsWith<IOException> { open(child) }
            assertFalse(child.isAlive)
            assertEquals(1, child.destroyCount)
        }
    }

    @Test
    fun `malformed query payload invalidates handle rather than inventing defaults`() {
        val corruptRows =
            listOf(
                instancePayload + mapOf("nativeSize" to "unknown"),
                instancePayload + mapOf("depth" to Long.MAX_VALUE),
                instancePayload + mapOf("reachable" to 1),
                instancePayload - "shallowSize",
            )
        corruptRows.forEach { row ->
            val child = replies(mapOf("total" to 1, "offset" to 0, "rows" to listOf(row)))
            open(child).use { source ->
                assertFailsWith<IOException> { source.query.instancesPage(emptyList(), null, 0, 10) }
                assertFalse(child.isAlive)
                assertFailsWith<IOException> { source.findings() }
            }
        }
    }

    @Test
    fun `invalid detail id enum and oversized pages fail explicitly`() {
        listOf(
            detailPayload + mapOf("objectId" to 1),
            detailPayload + mapOf("valueKind" to "FUTURE_VALUE"),
        ).forEach { payload ->
            val child = replies(payload)
            open(child).use { source ->
                assertFailsWith<IOException> { source.query.detailOf(Long.MIN_VALUE) }
                assertFalse(child.isAlive)
            }
        }
        val child = replies(mapOf("total" to 2, "offset" to 0, "rows" to listOf(instancePayload, instancePayload)))
        open(child).use { source ->
            assertFailsWith<IOException> { source.query.instancesPage(emptyList(), null, 0, 1) }
            assertFalse(child.isAlive)
        }
    }

    @Test
    fun `invalid local pagination never reaches sidecar and close forbids queries`() {
        val child = replies()
        val source = open(child)
        try {
            assertFailsWith<IllegalArgumentException> { source.query.instancesPage(emptyList(), null, -1, 1) }
            assertFailsWith<IllegalArgumentException> { source.query.instancesPage(emptyList(), null, 0, 4097) }
            assertFailsWith<IllegalArgumentException> { source.dominatorsPage(0, 0) }
            assertFailsWith<IllegalArgumentException> { source.query.detailOf(1, -1) }
            assertFailsWith<IllegalArgumentException> { source.query.detailOf(1, 0, 201) }
            assertEquals(1, child.requests().size)
            assertTrue(child.isAlive)
        } finally {
            source.close()
        }
        source.close()
        assertFailsWith<IOException> { source.rawHistogram() }
        assertFailsWith<IOException> { source.query.identity(1) }
        assertEquals(1, child.destroyCount)
    }

    @Test
    fun `factory validates source before attempting executable`() {
        val directory = Files.createTempDirectory("rust source validation ")
        try {
            assertFailsWith<IOException> {
                RustHeapSnapshotSource.open(directory.resolve("missing-engine"), directory.resolve("missing.hprof"), TEST_DIGEST)
            }
            assertFailsWith<IllegalArgumentException> {
                RustHeapSnapshotSource.open(directory.resolve("missing-engine"), directory, TEST_DIGEST)
            }
        } finally {
            Files.deleteIfExists(directory)
        }
    }

    private fun open(child: FakeRustChild): RustHeapSnapshotSource =
        RustHeapSnapshotSource.open(RustHprofProcess(child, TEST_DIGEST), Path.of("/managed/heap.hprof"))

    private fun replies(vararg payloads: Any?): FakeRustChild =
        FakeRustChild(
            wireFrame(response(metadataPayload)) +
                payloads
                    .mapIndexed { index, payload -> wireFrame(response(payload, index + 2L)) }
                    .fold(byteArrayOf()) { frames, frame -> frames + frame },
        )

    private val metadataPayload =
        mapOf(
            "format" to "JAVA PROFILE 1.0.3",
            "idSize" to 8,
            "timestampMillis" to 123456789L,
            "classCount" to 2,
            "instanceCount" to 3,
            "objectArrayCount" to 4,
            "primitiveArrayCount" to 5,
            "rootCount" to 6,
            "warnings" to
                listOf(mapOf("message" to "partial record", "offset" to 99), mapOf("message" to "unknown offset", "offset" to null)),
            "heaps" to listOf("App", "Zygote"),
        )

    private val instancePayload =
        mapOf(
            "objectId" to 1,
            "index" to 1,
            "shallowSize" to 16,
            "retainedSize" to null,
            "depth" to null,
            "reachable" to false,
            "nativeSize" to null,
            "shallowSizeKnown" to false,
            "className" to null,
            "representedClassName" to null,
        )

    private val referencePayload = mapOf("fieldName" to "root", "targetObjectId" to -1L, "targetClassName" to "java.lang.String")

    private val fieldPayload =
        mapOf(
            "name" to "item",
            "displayValue" to "a preview",
            "targetObjectId" to -1L,
            "targetClassName" to "java.lang.String",
            "depth" to 9,
            "valueKind" to "STRING",
            "shallowSize" to 16,
            "retainedSize" to 32,
            "nativeSize" to 48,
            "retainedNativeSize" to 64,
            "representedClassName" to "represented.Type",
            "stringPreview" to "preview",
            "childCount" to 7,
        )

    private val detailPayload =
        mapOf(
            "objectId" to Long.MIN_VALUE,
            "className" to "example.Type[]",
            "shallowSize" to 128,
            "retainedSize" to 256,
            "depth" to 8,
            "isArray" to true,
            "elementCount" to 300,
            "arrayPageSize" to 10,
            "fields" to listOf(fieldPayload),
            "referenceChain" to listOf(referencePayload),
            "references" to listOf(fieldPayload + mapOf("name" to "inbound", "valueKind" to "OBJECT")),
            "shallowSizeKnown" to false,
            "nativeSize" to 512,
            "valueKind" to "ARRAY",
            "representedClassName" to "example.Type",
            "stringPreview" to "object preview",
            "retainedNativeSize" to 1024,
        )
}
