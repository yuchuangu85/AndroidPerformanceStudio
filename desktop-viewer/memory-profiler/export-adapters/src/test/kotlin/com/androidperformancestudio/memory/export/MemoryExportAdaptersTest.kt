package com.androidperformancestudio.memory.export

import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapDiff
import com.androidperformancestudio.memory.model.HeapDiffEntry
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapExportContext
import com.androidperformancestudio.memory.model.HeapExportInstance
import com.androidperformancestudio.memory.model.HeapExportMetadata
import com.androidperformancestudio.memory.model.HeapHistogram
import com.androidperformancestudio.memory.model.HeapInstance
import com.androidperformancestudio.memory.model.HeapObjectArray
import com.androidperformancestudio.memory.model.HeapObjectFieldEvidence
import com.androidperformancestudio.memory.model.HeapObjectInvestigation
import com.androidperformancestudio.memory.model.HeapPrimitiveArray
import com.androidperformancestudio.memory.model.HeapRoot
import com.androidperformancestudio.memory.model.HeapRootKind
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.memory.model.HeapSummary
import com.androidperformancestudio.memory.model.MemoryWarning
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MemoryExportAdaptersTest {
    private val adapters = MemoryExportAdapters()

    @Test
    fun `copies raw hprof to requested output file`() {
        val directory = createTempDirectory("memory-export")
        val raw = directory.resolve("source.raw.hprof")
        val output = directory.resolve("exports/raw.hprof")
        Files.writeString(raw, "raw-bytes")

        val result = adapters.copyRawHprof(HeapDump(rawHprofFile = raw), output)

        assertEquals(output, result.outputFile)
        assertEquals("raw-bytes", Files.readString(output))
    }

    @Test
    fun `copies converted hprof to requested output file`() {
        val directory = createTempDirectory("memory-export")
        val converted = directory.resolve("source.hprof")
        val output = directory.resolve("exports/converted.hprof")
        Files.writeString(converted, "converted-bytes")

        adapters.copyConvertedHprof(HeapDump(convertedHprofFile = converted), output)

        assertEquals("converted-bytes", Files.readString(output))
    }

    @Test
    fun `converted hprof export requires converted file`() {
        assertFailsWith<MissingMemoryExportSourceException> {
            adapters.copyConvertedHprof(HeapDump(), createTempDirectory("memory-export").resolve("converted.hprof"))
        }
    }

    @Test
    fun `exports class diff csv atomically`() {
        val directory = createTempDirectory("memory-diff-export")
        val output = directory.resolve("reports/diff.csv")

        adapters.exportHeapDiffCsv(
            HeapDiff(
                entries =
                    listOf(
                        HeapDiffEntry(
                            "com.example.Item",
                            1,
                            3,
                            2,
                            16,
                            48,
                            32,
                            beforeRetainedSize = 100,
                            afterRetainedSize = 300,
                        ),
                    ),
                beforeSnapshot = HeapSnapshotSummary("A", sourceFileDigest = "hash-a", mappingDigest = "map-a"),
                afterSnapshot = HeapSnapshotSummary("B", sourceFileDigest = "hash-b", mappingDigest = "map-b"),
            ),
            output,
        )

        val lines = Files.readAllLines(output)
        assertTrue(lines.first().contains("beforeRetainedSizeBytes,afterRetainedSizeBytes,retainedSizeDeltaBytes"))
        assertTrue(lines[1].startsWith("com.example.Item,1,3,2,16,48,32,100,300,200,A,B,hash-a,hash-b,map-a,map-b,"))
        assertTrue(lines[1].contains("offline-hprof-viewer-v1"))
        assertTrue(lines[1].contains("no cross-dump object identity"))
    }

    @Test
    fun `exports selected object investigation json`() {
        val output = createTempDirectory("memory-object-export").resolve("object.json")

        adapters.exportObjectInvestigationJson(
            HeapObjectInvestigation(
                objectId = 42L,
                className = "com.example.Root",
                shallowSize = 24L,
                retainedSize = 96L,
                fields = listOf(HeapObjectFieldEvidence("child", "0x2a · com.example.Child", 42L, "com.example.Child")),
            ),
            output,
        )

        val text = Files.readString(output)
        assertTrue(text.contains("\"objectId\": 42"))
        assertTrue(text.contains("\"className\": \"com.example.Root\""))
        assertTrue(text.contains("\"fields\""))
    }

    @Test
    fun `unknown shallow size is not exported as zero for instances or object detail`() {
        val directory = createTempDirectory("memory-unknown-size-export")
        val csv = directory.resolve("instances.csv")
        val json = directory.resolve("object.json")
        val heap =
            HeapDump(
                instances =
                    listOf(
                        HeapInstance(
                            objectId = 42,
                            classObjectId = 100,
                            className = "example.Bitmap",
                            shallowSize = 0,
                            shallowSizeKnown = false,
                        ),
                    ),
            )

        adapters.exportClassInstancesCsv(heap, "example.Bitmap", csv)
        adapters.exportObjectInvestigationJson(
            HeapObjectInvestigation(
                objectId = 42,
                className = "example.Bitmap",
                shallowSize = 0,
                shallowSizeKnown = false,
                nativeSize = 4096,
            ),
            json,
        )

        assertTrue(Files.readString(csv).contains("0x2a,example.Bitmap,,"))
        assertTrue(Files.readString(json).contains("\"shallowSize\": null"))
        assertTrue(Files.readString(json).contains("\"nativeSize\": 4096"))
    }

    @Test
    fun `exports snapshot json and evidence report with limitations`() {
        val directory = createTempDirectory("memory-report-export")
        val json = directory.resolve("reports/snapshot.json")
        val report = directory.resolve("reports/investigation.md")
        val heap = HeapDump(format = "JAVA PROFILE 1.0.3", idSize = 4, timestampMillis = 1234L)
        val histogram =
            HeapHistogram(
                summary =
                    com.androidperformancestudio.memory.model
                        .HeapSummary(objectCount = 2, classCount = 1, shallowSize = 24),
            )

        adapters.exportHeapSnapshotJson(heap, histogram, json)
        adapters.exportInvestigationReportMarkdown(heap, histogram, null, report)

        val jsonText = Files.readString(json)
        val reportText = Files.readString(report)
        assertTrue(jsonText.contains("\"format\": \"JAVA PROFILE 1.0.3\""))
        assertTrue(jsonText.contains("\"objectCount\": 2"))
        assertTrue(reportText.contains("## Evidence limitations"))
        assertTrue(reportText.contains("Object IDs are not stable across snapshots"))
    }

    @Test
    fun `exports class histogram as csv with escaped class names and empty retained size`() {
        val directory = createTempDirectory("memory-export")
        val output = directory.resolve("histogram.csv")
        val histogram =
            HeapHistogram(
                classes =
                    listOf(
                        ClassStats(className = "java.lang.String", instanceCount = 2, shallowSize = 48L),
                        ClassStats(className = "com.example.Comma,Quote\"", instanceCount = 1, shallowSize = 16L),
                    ),
            )

        adapters.exportClassHistogramCsv(histogram, output)

        assertEquals(
            listOf(
                "className,instanceCount,shallowSizeBytes,retainedSizeBytes",
                "java.lang.String,2,48,",
                "\"com.example.Comma,Quote\"\"\",1,16,",
            ),
            Files.readAllLines(output),
        )
    }

    @Test
    fun `metadata snapshot json matches heap export byte for byte`() {
        val directory = createTempDirectory("memory-metadata-export")
        val legacy = directory.resolve("legacy.json")
        val output = directory.resolve("reports/metadata.json")
        val histogram =
            HeapHistogram(
                summary = HeapSummary(objectCount = 6, classCount = 3, shallowSize = 128),
                classes =
                    listOf(
                        ClassStats("example.One", retainedSize = 48),
                        ClassStats("example.Two"),
                        ClassStats("example.Three", retainedSize = 96),
                    ),
            )
        val summary = HeapSnapshotSummary("snapshot", sourceFileDigest = "source-hash", mappingDigest = "mapping-hash")
        val context =
            HeapExportContext(
                sourceFileDigest = "ignored-context-source",
                mappingDigest = "ignored-context-mapping",
                filters = linkedMapOf("class" to "example.\"One\"", "heap" to "app"),
                selectedObjectIds = listOf(42, -1),
                exportedAt = Instant.parse("2026-10-30T00:00:00Z"),
            )

        adapters.exportHeapSnapshotJson(exportHeap(), histogram, legacy, summary, context)
        val result = adapters.exportHeapSnapshotJson(exportMetadata(), histogram, output, summary, context)

        assertEquals(output, result.outputFile)
        assertContentEquals(Files.readAllBytes(legacy), Files.readAllBytes(output))
        assertEquals(
            """
            {
              "analysisVersion": "offline-hprof-viewer-v1",
              "sourceFileDigest": "source-hash",
              "mappingDigest": "mapping-hash",
              "limitation": "Point-in-time heap evidence; no allocation timeline or cross-dump object identity. Class retained sizes may overlap.",
              "exportedAt": "2026-10-30T00:00:00Z",
              "filters": {"class":"example.\"One\"", "heap":"app"},
              "selectedObjectIds": [42, -1],
              "format": "JAVA PROFILE 1.0.3",
              "idSize": 8,
              "timestampMillis": 1234,
              "classCount": 3,
              "objectCount": 6,
              "shallowSizeBytes": 128,
              "retainedSizeBytes": 144,
              "instanceCount": 2,
              "objectArrayCount": 1,
              "primitiveArrayCount": 3,
              "gcRootCount": 4,
              "warnings": ["warning \"quoted\"\\path\nnext\rline\tend", "second warning"]
            }

            """.trimIndent(),
            Files.readString(output),
        )
    }

    @Test
    fun `metadata snapshot json preserves defaults without summary or context`() {
        val directory = createTempDirectory("memory-metadata-defaults")
        val legacy = directory.resolve("legacy.json")
        val output = directory.resolve("metadata.json")
        val metadata = HeapExportMetadata("", 0, 0, 0, 0, 0, 0)

        adapters.exportHeapSnapshotJson(HeapDump(), HeapHistogram(), legacy)
        adapters.exportHeapSnapshotJson(metadata, HeapHistogram(), output)

        assertContentEquals(Files.readAllBytes(legacy), Files.readAllBytes(output))
        val text = Files.readString(output)
        assertTrue(text.contains("\"sourceFileDigest\": null"))
        assertTrue(text.contains("\"mappingDigest\": null"))
        assertTrue(text.contains("\"exportedAt\": null"))
        assertTrue(text.contains("\"filters\": {}"))
        assertTrue(text.contains("\"selectedObjectIds\": []"))
        assertTrue(text.contains("\"retainedSizeBytes\": 0"))
        assertTrue(text.contains("\"warnings\": []"))
    }

    @Test
    fun `metadata investigation report matches heap export with warnings and diff`() {
        val directory = createTempDirectory("memory-metadata-report")
        val legacy = directory.resolve("legacy.md")
        val output = directory.resolve("reports/metadata.md")
        val histogram =
            HeapHistogram(
                summary = HeapSummary(objectCount = 6, classCount = 3, shallowSize = 128),
                classes = listOf(ClassStats("example.Item", retainedSize = 144), ClassStats("example.Unknown")),
            )
        val summary =
            HeapSnapshotSummary("summary-id", sourceFileDigest = "source-hash", mappingDigest = "mapping-hash")
        val context =
            HeapExportContext(
                snapshotId = "context-id",
                filters = linkedMapOf("class" to "example.Item", "heap" to "app"),
                selectedObjectIds = listOf(42, -1),
                exportedAt = Instant.parse("2026-10-30T00:00:00Z"),
            )
        val diff =
            HeapDiff(
                entries = listOf(HeapDiffEntry("example.Item|Nested", 1, 2, 1, 16, 32, 16)),
                beforeSnapshot = HeapSnapshotSummary("A", sourceFileDigest = "hash-a", mappingDigest = "map-a"),
                afterSnapshot = HeapSnapshotSummary("B", sourceFileDigest = "hash-b"),
            )

        adapters.exportInvestigationReportMarkdown(exportHeap(), histogram, diff, legacy, summary, context)
        val result =
            adapters.exportInvestigationReportMarkdown(exportMetadata(), histogram, diff, output, summary, context)

        assertEquals(output, result.outputFile)
        assertContentEquals(Files.readAllBytes(legacy), Files.readAllBytes(output))
        val text = Files.readString(output)
        assertTrue(text.contains("- Source digest: `source-hash`"))
        assertTrue(text.contains("- Mapping digest: `mapping-hash`"))
        assertTrue(text.contains("- Exported at: `2026-10-30T00:00:00Z`"))
        assertTrue(text.contains("- Snapshot ID: `context-id`"))
        assertTrue(text.contains("- Selected object IDs: `42, -1`"))
        assertTrue(text.contains("- Filters: `class=example.Item, heap=app`"))
        assertTrue(text.contains("- Format: `JAVA PROFILE 1.0.3`"))
        assertTrue(text.contains("- ID size: `8`"))
        assertTrue(text.contains("- Timestamp: `1234`"))
        assertTrue(text.contains("- Retained size: `144` bytes"))
        assertTrue(text.contains("## Warnings\n- ${exportMetadata().warnings.first().message}\n- second warning"))
        assertTrue(text.contains("- Snapshot A: `A`; source `hash-a`; mapping `map-a`"))
        assertTrue(text.contains("- Snapshot B: `B`; source `hash-b`; mapping `not loaded`"))
        assertTrue(text.contains("| example.Item\\|Nested | 1 | 2 | 1 |"))
    }

    @Test
    fun `metadata report preserves optional context fallbacks without warnings or diff`() {
        val directory = createTempDirectory("memory-metadata-report-defaults")
        val legacy = directory.resolve("legacy.md")
        val output = directory.resolve("metadata.md")
        val metadata = HeapExportMetadata("", 0, 0, 0, 0, 0, 0)
        val exportedAtPrefix = "- Exported at: `"
        for (summary in listOf(null, HeapSnapshotSummary("summary-id"))) {
            val before = Instant.now()
            adapters.exportInvestigationReportMarkdown(HeapDump(), HeapHistogram(), null, legacy, summary)
            adapters.exportInvestigationReportMarkdown(metadata, HeapHistogram(), null, output, summary)
            val after = Instant.now()
            val legacyLines = Files.readAllLines(legacy)
            val lines = Files.readAllLines(output)

            assertEquals(
                legacyLines.filterNot { it.startsWith(exportedAtPrefix) },
                lines.filterNot { it.startsWith(exportedAtPrefix) },
            )
            val exportedAt =
                Instant.parse(
                    lines
                        .single { it.startsWith(exportedAtPrefix) }
                        .removePrefix(exportedAtPrefix)
                        .removeSuffix("`"),
                )
            assertFalse(exportedAt.isBefore(before))
            assertFalse(exportedAt.isAfter(after))
            assertTrue(lines.contains("- Snapshot ID: `${summary?.id ?: "unknown"}`"))
            assertTrue(lines.contains("- Source digest: `unknown`"))
            assertTrue(lines.contains("- Mapping digest: `not loaded`"))
            assertTrue(lines.contains("- Selected object IDs: `none`"))
            assertFalse(lines.any { it.startsWith("- Filters:") })
            assertFalse(lines.contains("## Warnings"))
            assertFalse(lines.contains("## Class-level heap diff"))
        }
    }

    @Test
    fun `streaming instances match legacy class selection ids sizes and escaping`() {
        val directory = createTempDirectory("memory-streaming-instances")
        val legacy = directory.resolve("legacy.csv")
        val output = directory.resolve("instances.csv")
        val className = "example.Comma,Quote\"\r\n"
        val heap =
            HeapDump(
                instances =
                    listOf(
                        HeapInstance(-1, 100, className, shallowSize = 0),
                        HeapInstance(99, 101, "other.Class", shallowSize = 100),
                        HeapInstance(42, 100, className, shallowSize = 24),
                        HeapInstance(0, 100, className, shallowSize = 7, shallowSizeKnown = false),
                    ),
                objectArrays = listOf(HeapObjectArray(50, 100, className, shallowSize = 32)),
                primitiveArrays = listOf(HeapPrimitiveArray(51, shallowSize = 8)),
                objectRetainedSizes = mapOf(-1L to 0L, 42L to 96L),
            )
        val selected =
            sequenceOf(
                HeapExportInstance(-1, className, 0, 0),
                HeapExportInstance(42, className, 24, 96),
                HeapExportInstance(0, className, null, null),
            ).constrainOnce()

        adapters.exportClassInstancesCsv(heap, className, legacy)
        val result = adapters.exportClassInstancesCsv(selected, output)

        assertEquals(output, result.outputFile)
        assertContentEquals(Files.readAllBytes(legacy), Files.readAllBytes(output))
        val escapedName = "\"example.Comma,Quote\"\"\r\n\""
        val newline = System.lineSeparator()
        assertEquals(
            listOf(
                "objectId,className,shallowSizeBytes,retainedSizeBytes",
                "0xffffffffffffffff,$escapedName,0,0",
                "0x2a,$escapedName,24,96",
                "0x0,$escapedName,,",
                "",
            ).joinToString(newline),
            Files.readString(output),
        )

        adapters.exportClassInstancesCsv(heap, "missing.Class", legacy)
        adapters.exportClassInstancesCsv(emptySequence(), output)
        assertContentEquals(Files.readAllBytes(legacy), Files.readAllBytes(output))
        assertEquals("objectId,className,shallowSizeBytes,retainedSizeBytes$newline", Files.readString(output))
    }

    @Test
    fun `streams caller selected members once inside atomic write`() {
        val directory = createTempDirectory("memory-streaming-selection")
        val output = directory.resolve("instances.csv")
        Files.writeString(output, "previous export")
        val className = "example." + "Member".repeat(4096)
        var consumed = 0
        val members =
            sequence {
                val temporary =
                    Files.list(directory).use { files ->
                        files.filter { it.toString().endsWith(".tmp") }.toList().single()
                    }
                assertEquals("previous export", Files.readString(output))
                yield(HeapExportInstance(42, className, null, 4096))
                assertTrue(Files.size(temporary) > 0, "Rows must be written before requesting subsequent members")
                assertEquals("previous export", Files.readString(output))
                yield(HeapExportInstance(99, "excluded.Class", 24, null))
                yield(HeapExportInstance(-1, "int[]", 0, null))
            }.onEach { consumed++ }
                .filter { it.objectId in setOf(42L, -1L) }
                .constrainOnce()
        assertEquals(0, consumed)

        adapters.exportClassInstancesCsv(members, output)

        assertEquals(3, consumed)
        val newline = System.lineSeparator()
        assertEquals(
            "objectId,className,shallowSizeBytes,retainedSizeBytes$newline" +
                "0x2a,$className,,4096${newline}0xffffffffffffffff,int[],0,$newline",
            Files.readString(output),
        )
        assertEquals(listOf(output), Files.list(directory).use { it.toList() })
    }

    @Test
    fun `streaming failure preserves existing output and deletes temporary file`() {
        val directory = createTempDirectory("memory-streaming-failure")
        val output = directory.resolve("instances.csv")
        Files.writeString(output, "previous export")
        val failure = IllegalStateException("member retrieval failed")
        val members =
            sequence {
                yield(HeapExportInstance(42, "example.Item", 24, null))
                throw failure
            }

        val actual = assertFailsWith<IllegalStateException> { adapters.exportClassInstancesCsv(members, output) }

        assertSame(failure, actual)
        assertEquals("previous export", Files.readString(output))
        assertEquals(listOf(output), Files.list(directory).use { it.toList() })
    }

    private fun exportHeap(): HeapDump =
        HeapDump(
            format = "JAVA PROFILE 1.0.3",
            idSize = 8,
            timestampMillis = 1234,
            instances = listOf(HeapInstance(1, 100), HeapInstance(2, 100)),
            objectArrays = listOf(HeapObjectArray(3, 101)),
            primitiveArrays = List(3) { HeapPrimitiveArray(it.toLong() + 4) },
            gcRoots = List(4) { HeapRoot(it.toLong() + 1, HeapRootKind.UNKNOWN) },
            warnings = exportMetadata().warnings,
        )

    private fun exportMetadata(): HeapExportMetadata =
        HeapExportMetadata(
            format = "JAVA PROFILE 1.0.3",
            idSize = 8,
            timestampMillis = 1234,
            instanceCount = 2,
            objectArrayCount = 1,
            primitiveArrayCount = 3,
            gcRootCount = 4,
            warnings =
                listOf(
                    MemoryWarning("warning \"quoted\"\\path\nnext\rline\tend", offset = 12),
                    MemoryWarning("second warning"),
                ),
        )
}
