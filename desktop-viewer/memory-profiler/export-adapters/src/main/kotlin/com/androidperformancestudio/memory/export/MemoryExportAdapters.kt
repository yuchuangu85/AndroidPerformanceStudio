@file:Suppress("TooManyFunctions", "MagicNumber", "SwallowedException", "MaxLineLength")

package com.androidperformancestudio.memory.export

import com.androidperformancestudio.memory.model.HeapDiff
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapExportContext
import com.androidperformancestudio.memory.model.HeapHistogram
import com.androidperformancestudio.memory.model.HeapObjectFieldEvidence
import com.androidperformancestudio.memory.model.HeapObjectInvestigation
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

private const val ANALYSIS_VERSION = "offline-hprof-viewer-v1"

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
    ): MemoryExportResult =
        atomicWrite(outputFile) { writer ->
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

    fun exportClassInstancesCsv(
        heapDump: HeapDump,
        className: String,
        outputFile: Path,
    ): MemoryExportResult =
        atomicWrite(outputFile) { writer ->
            writer.writeRow("objectId", "className", "shallowSizeBytes", "retainedSizeBytes")
            heapDump.instances
                .filter { it.className == className }
                .forEach { instance ->
                    writer.writeRow(
                        "0x${java.lang.Long.toHexString(instance.objectId)}",
                        instance.className,
                        instance.shallowSize.toString(),
                        heapDump.objectRetainedSizes[instance.objectId]?.toString().orEmpty(),
                    )
                }
        }

    fun exportHeapDiffCsv(
        diff: HeapDiff,
        outputFile: Path,
    ): MemoryExportResult =
        atomicWrite(outputFile) { writer ->
            writer.writeRow(
                "className",
                "beforeCount",
                "afterCount",
                "countDelta",
                "beforeShallowSizeBytes",
                "afterShallowSizeBytes",
                "shallowSizeDeltaBytes",
            )
            diff.entries.forEach { entry ->
                writer.writeRow(
                    entry.className,
                    entry.beforeCount.toString(),
                    entry.afterCount.toString(),
                    entry.countDelta.toString(),
                    entry.beforeShallowSize.toString(),
                    entry.afterShallowSize.toString(),
                    entry.shallowSizeDelta.toString(),
                )
            }
        }

    fun exportObjectInvestigationJson(
        investigation: HeapObjectInvestigation,
        outputFile: Path,
    ): MemoryExportResult =
        atomicWrite(outputFile) { writer ->
            writer.write(
                buildString {
                    appendLine("{")
                    appendLine("  \"objectId\": ${investigation.objectId},")
                    appendLine("  \"className\": ${investigation.className.jsonString()},")
                    appendLine("  \"shallowSize\": ${investigation.shallowSize},")
                    appendLine("  \"retainedSize\": ${investigation.retainedSize ?: "null"},")
                    appendLine("  \"depth\": ${investigation.depth ?: "null"},")
                    appendLine("  \"fields\": ${investigation.fields.toFieldJsonArray()},")
                    appendLine("  \"references\": ${investigation.references.toFieldJsonArray()},")
                    appendLine("  \"referenceChain\": ${investigation.referenceChain.toReferenceJsonArray()},")
                    appendLine("}")
                },
            )
        }

    fun exportHeapSnapshotJson(
        heapDump: HeapDump,
        histogram: HeapHistogram,
        outputFile: Path,
        snapshotSummary: HeapSnapshotSummary? = null,
        exportContext: HeapExportContext? = null,
    ): MemoryExportResult {
        val retainedSize = histogram.classes.mapNotNull { it.retainedSize }.sum()
        return atomicWrite(outputFile) { writer ->
            writer.write(
                buildString {
                    appendLine("{")
                    appendLine("  \"analysisVersion\": ${ANALYSIS_VERSION.jsonString()},")
                    appendLine("  \"sourceFileDigest\": ${snapshotSummary?.sourceFileDigest.jsonString()},")
                    appendLine("  \"mappingDigest\": ${snapshotSummary?.mappingDigest.jsonString()},")
                    appendLine("  \"exportedAt\": ${exportContext?.exportedAt?.toString().jsonString()},")
                    appendLine("  \"filters\": ${exportContext?.filters?.toJsonObject() ?: "{}"},")
                    appendLine(
                        "  \"selectedObjectIds\": " +
                            "${exportContext?.selectedObjectIds?.joinToString(prefix = "[", postfix = "]") ?: "[]"},",
                    )
                    appendLine("  \"format\": ${heapDump.format.jsonString()},")
                    appendLine("  \"idSize\": ${heapDump.idSize},")
                    appendLine("  \"timestampMillis\": ${heapDump.timestampMillis},")
                    appendLine("  \"classCount\": ${histogram.summary.classCount},")
                    appendLine("  \"objectCount\": ${histogram.summary.objectCount},")
                    appendLine("  \"shallowSizeBytes\": ${histogram.summary.shallowSize},")
                    appendLine("  \"retainedSizeBytes\": $retainedSize,")
                    appendLine("  \"instanceCount\": ${heapDump.instances.size},")
                    appendLine("  \"objectArrayCount\": ${heapDump.objectArrays.size},")
                    appendLine("  \"primitiveArrayCount\": ${heapDump.primitiveArrays.size},")
                    appendLine("  \"gcRootCount\": ${heapDump.gcRoots.size},")
                    appendLine(
                        "  \"warnings\": ${heapDump.warnings.joinToString(
                            prefix = "[",
                            postfix = "]",
                            transform = { it.message.jsonString() },
                        )}",
                    )
                    appendLine("}")
                },
            )
        }
    }

    @Suppress("LongParameterList")
    fun exportInvestigationReportMarkdown(
        heapDump: HeapDump,
        histogram: HeapHistogram,
        diff: HeapDiff?,
        outputFile: Path,
        snapshotSummary: HeapSnapshotSummary? = null,
        exportContext: HeapExportContext? = null,
    ): MemoryExportResult {
        val retainedSize = histogram.classes.mapNotNull { it.retainedSize }.sum()
        return atomicWrite(outputFile) { writer ->
            writer.appendLine("# Memory Profiler Investigation Report")
            writer.appendLine()
            writer.appendLine("- Analysis version: `$ANALYSIS_VERSION`")
            writer.appendLine("- Source digest: `${snapshotSummary?.sourceFileDigest ?: "unknown"}`")
            writer.appendLine("- Mapping digest: `${snapshotSummary?.mappingDigest ?: "not loaded"}`")
            writer.appendLine("- Exported at: `${exportContext?.exportedAt ?: java.time.Instant.now()}`")
            writer.appendLine("- Snapshot ID: `${exportContext?.snapshotId ?: snapshotSummary?.id ?: "unknown"}`")
            writer.appendLine(
                "- Selected object IDs: `${exportContext?.selectedObjectIds?.joinToString().orEmpty().ifBlank { "none" }}`",
            )
            if (exportContext?.filters?.isNotEmpty() == true) {
                writer.appendLine(
                    "- Filters: `${exportContext.filters.entries.joinToString { "${it.key}=${it.value}" }}`",
                )
            }
            writer.appendLine("- Format: `${heapDump.format}`")
            writer.appendLine("- ID size: `${heapDump.idSize}`")
            writer.appendLine("- Timestamp: `${heapDump.timestampMillis}`")
            writer.appendLine("- Classes: `${histogram.summary.classCount}`")
            writer.appendLine("- Objects: `${histogram.summary.objectCount}`")
            writer.appendLine("- Shallow size: `${histogram.summary.shallowSize}` bytes")
            writer.appendLine("- Retained size: `$retainedSize` bytes")
            writer.appendLine()
            writer.appendLine("## Evidence limitations")
            writer.appendLine()
            writer.appendLine(
                "This report describes one heap-dump evidence boundary. " +
                    "Object IDs are not stable across snapshots, and retained paths are evidence of retention " +
                    "rather than proof of a leak.",
            )
            if (heapDump.warnings.isNotEmpty()) {
                writer.appendLine()
                writer.appendLine("## Warnings")
                heapDump.warnings.forEach { warning -> writer.appendLine("- ${warning.message}") }
            }
            diff?.let {
                writer.appendLine()
                writer.appendLine("## Class-level heap diff")
                writer.appendLine()
                writer.appendLine(
                    "The diff is class-level; it does not establish stable object identity " +
                        "across snapshots.",
                )
                writer.appendLine()
                writer.appendLine("| Class | Before | After | Delta |")
                writer.appendLine("| --- | ---: | ---: | ---: |")
                it.entries.take(50).forEach { entry ->
                    val escapedClassName = entry.className.replace("|", "\\|")
                    writer.appendLine(
                        "| $escapedClassName | ${entry.beforeCount} | ${entry.afterCount} | " +
                            "${entry.countDelta} |",
                    )
                }
            }
        }
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

    private fun fieldJson(
        name: String,
        displayValue: String,
        targetObjectId: Long?,
        targetClassName: String?,
    ): String =
        "{\"name\":${name.jsonString()},\"displayValue\":${displayValue.jsonString()}," +
            "\"targetObjectId\":${targetObjectId ?: "null"},\"targetClassName\":${targetClassName.jsonString()}}"

    private fun Map<String, String>.toJsonObject(): String =
        entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "${key.jsonString()}:${value.jsonString()}"
        }

    private fun List<com.androidperformancestudio.memory.model.ObjectReference>.toReferenceJsonArray(): String =
        joinToString(prefix = "[", postfix = "]") { reference ->
            "{\"fieldName\":${reference.fieldName.jsonString()}," +
                "\"targetObjectId\":${reference.targetObjectId}," +
                "\"targetClassName\":${reference.targetClassName.jsonString()}}"
        }

    private fun List<HeapObjectFieldEvidence>.toFieldJsonArray(): String =
        joinToString(prefix = "[", postfix = "]") { field ->
            fieldJson(field.name, field.displayValue, field.targetObjectId, field.targetClassName)
        }

    private fun atomicWrite(
        outputFile: Path,
        write: (BufferedWriter) -> Unit,
    ): MemoryExportResult {
        outputFile.parent?.let(Files::createDirectories)
        val parent = outputFile.parent ?: Path.of(".")
        val temporary = parent.resolve(".${outputFile.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.newBufferedWriter(temporary).use(write)
            Files.move(temporary, outputFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (exception: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, outputFile, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return MemoryExportResult(outputFile)
    }

    private fun String?.jsonString(): String =
        if (this == null) {
            "null"
        } else {
            buildString {
                append('"')
                for (character in this@jsonString) {
                    when (character) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(character)
                    }
                }
                append('"')
            }
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
