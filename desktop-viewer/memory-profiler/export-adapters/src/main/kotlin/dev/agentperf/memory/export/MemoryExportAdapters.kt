package dev.agentperf.memory.export

import dev.agentperf.memory.model.HeapDump
import dev.agentperf.memory.model.HeapHistogram
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class MemoryExportResult(
    val outputFile: Path,
)

class MissingMemoryExportSourceException(
    message: String,
) : IllegalArgumentException(message)

class MemoryExportAdapters {
    fun copyRawHprof(
        heapDump: HeapDump,
        outputFile: Path,
    ): MemoryExportResult = copyHprof(heapDump.rawHprofFile, outputFile, "raw HPROF")

    fun copyConvertedHprof(
        heapDump: HeapDump,
        outputFile: Path,
    ): MemoryExportResult = copyHprof(heapDump.convertedHprofFile, outputFile, "converted HPROF")

    fun exportClassHistogramCsv(
        histogram: HeapHistogram,
        outputFile: Path,
    ): MemoryExportResult {
        outputFile.parent?.let(Files::createDirectories)
        Files.newBufferedWriter(outputFile).use { writer ->
            writer.writeRow("className", "instanceCount", "shallowSizeBytes", "retainedSizeBytes")
            histogram.classes.forEach { stats ->
                writer.writeRow(
                    stats.className,
                    stats.instanceCount.toString(),
                    stats.shallowSize.toString(),
                    stats.retainedSize?.toString().orEmpty(),
                )
            }
        }
        return MemoryExportResult(outputFile)
    }

    private fun copyHprof(
        source: Path?,
        outputFile: Path,
        sourceLabel: String,
    ): MemoryExportResult {
        val hprofFile =
            source
                ?: throw MissingMemoryExportSourceException(
                    "Heap dump has no $sourceLabel file to export",
                )
        outputFile.parent?.let(Files::createDirectories)
        Files.copy(hprofFile, outputFile, StandardCopyOption.REPLACE_EXISTING)
        return MemoryExportResult(outputFile)
    }

    private fun BufferedWriter.writeRow(vararg values: String) {
        write(values.joinToString(separator = ",", transform = ::escapeCsv))
        newLine()
    }

    private fun escapeCsv(value: String): String {
        val needsQuoting =
            value.any { character ->
                character == ',' || character == '"' || character == '\n' || character == '\r'
            }
        if (!needsQuoting) return value
        return buildString {
            append('"')
            value.forEach { character ->
                if (character == '"') append("\"\"") else append(character)
            }
            append('"')
        }
    }
}
