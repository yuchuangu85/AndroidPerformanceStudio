package com.androidperformancestudio.memory.export

import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapDiff
import com.androidperformancestudio.memory.model.HeapDiffEntry
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapHistogram
import com.androidperformancestudio.memory.model.HeapInstance
import com.androidperformancestudio.memory.model.HeapObjectFieldEvidence
import com.androidperformancestudio.memory.model.HeapObjectInvestigation
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
                        HeapDiffEntry("com.example.Item", 1, 3, 2, 16, 48, 32),
                    ),
            ),
            output,
        )

        assertEquals(
            listOf(
                "className,beforeCount,afterCount,countDelta," +
                    "beforeShallowSizeBytes,afterShallowSizeBytes,shallowSizeDeltaBytes",
                "com.example.Item,1,3,2,16,48,32",
            ),
            Files.readAllLines(output),
        )
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
}
