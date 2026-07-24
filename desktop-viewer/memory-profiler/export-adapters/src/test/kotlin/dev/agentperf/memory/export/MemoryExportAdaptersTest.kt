package dev.agentperf.memory.export

import dev.agentperf.memory.model.ClassStats
import dev.agentperf.memory.model.HeapDump
import dev.agentperf.memory.model.HeapHistogram
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
