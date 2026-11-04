@file:Suppress("TooManyFunctions", "MagicNumber", "SwallowedException", "MaxLineLength")

package com.androidperformancestudio.memory.export

import com.androidperformancestudio.memory.model.HeapDiff
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapExportContext
import com.androidperformancestudio.memory.model.HeapExportInstance
import com.androidperformancestudio.memory.model.HeapExportMetadata
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
                        instance.shallowSize
                            .takeIf { instance.shallowSizeKnown }
                            ?.toString()
                            .orEmpty(),
                        heapDump.objectRetainedSizes[instance.objectId]?.toString().orEmpty(),
                    )
                }
        }

    fun exportClassInstancesCsv(
        instances: Sequence<HeapExportInstance>,
        outputFile: Path,
    ): MemoryExportResult =
        atomicWrite(outputFile) { writer ->
            writer.writeRow("objectId", "className", "shallowSizeBytes", "retainedSizeBytes")
            instances.forEach { instance ->
                writer.writeRow(
                    "0x${java.lang.Long.toHexString(instance.objectId)}",
                    instance.className,
                    instance.shallowSize?.toString().orEmpty(),
                    instance.retainedSize?.toString().orEmpty(),
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
                "beforeRetainedSizeBytes",
                "afterRetainedSizeBytes",
                "retainedSizeDeltaBytes",
                "beforeSnapshotId",
                "afterSnapshotId",
                "beforeSourceHash",
                "afterSourceHash",
                "beforeMappingHash",
                "afterMappingHash",
                "analysisVersion",
                "limitation",
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
                    entry.beforeRetainedSize?.toString().orEmpty(),
                    entry.afterRetainedSize?.toString().orEmpty(),
                    entry.retainedSizeDelta?.toString().orEmpty(),
                    diff.beforeSnapshot?.id.orEmpty(),
                    diff.afterSnapshot?.id.orEmpty(),
                    diff.beforeSnapshot?.sourceFileDigest.orEmpty(),
                    diff.afterSnapshot?.sourceFileDigest.orEmpty(),
                    diff.beforeSnapshot?.mappingDigest.orEmpty(),
                    diff.afterSnapshot?.mappingDigest.orEmpty(),
                    ANALYSIS_VERSION,
                    "class-name matching only; no cross-dump object identity; ClassLoader identity unavailable",
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
                    appendLine("  \"analysisVersion\": ${ANALYSIS_VERSION.jsonString()},")
                    appendLine("  \"snapshotId\": ${investigation.snapshotSummary?.id.jsonString()},")
                    appendLine(
                        "  \"sourceFileDigest\": ${investigation.snapshotSummary?.sourceFileDigest.jsonString()},",
                    )
                    appendLine("  \"mappingDigest\": ${investigation.snapshotSummary?.mappingDigest.jsonString()},")
                    appendLine(
                        "  \"limitation\": \"Object identity is local to this snapshot; retention is not proof of a leak.\",",
                    )
                    appendLine("  \"objectId\": ${investigation.objectId},")
                    appendLine("  \"className\": ${investigation.className.jsonString()},")
                    appendLine(
                        "  \"shallowSize\": ${investigation.shallowSize.takeIf {
                            investigation.shallowSizeKnown
                        } ?: "null"},",
                    )
                    appendLine("  \"nativeSize\": ${investigation.nativeSize ?: "null"},")
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
    ): MemoryExportResult =
        exportHeapSnapshotJson(
            heapDump.toExportMetadata(),
            histogram,
            outputFile,
            snapshotSummary,
            exportContext,
        )

    fun exportHeapSnapshotJson(
        metadata: HeapExportMetadata,
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
                    appendLine(
                        "  \"limitation\": \"Point-in-time heap evidence; no allocation timeline or cross-dump object identity. Class retained sizes may overlap.\",",
                    )
                    appendLine("  \"exportedAt\": ${exportContext?.exportedAt?.toString().jsonString()},")
                    appendLine("  \"filters\": ${exportContext?.filters?.toJsonObject() ?: "{}"},")
                    appendLine(
                        "  \"selectedObjectIds\": " +
                            "${exportContext?.selectedObjectIds?.joinToString(prefix = "[", postfix = "]") ?: "[]"},",
                    )
                    appendLine("  \"format\": ${metadata.format.jsonString()},")
                    appendLine("  \"idSize\": ${metadata.idSize},")
                    appendLine("  \"timestampMillis\": ${metadata.timestampMillis},")
                    appendLine("  \"classCount\": ${histogram.summary.classCount},")
                    appendLine("  \"objectCount\": ${histogram.summary.objectCount},")
                    appendLine("  \"shallowSizeBytes\": ${histogram.summary.shallowSize},")
                    appendLine("  \"retainedSizeBytes\": $retainedSize,")
                    appendLine("  \"instanceCount\": ${metadata.instanceCount},")
                    appendLine("  \"objectArrayCount\": ${metadata.objectArrayCount},")
                    appendLine("  \"primitiveArrayCount\": ${metadata.primitiveArrayCount},")
                    appendLine("  \"gcRootCount\": ${metadata.gcRootCount},")
                    appendLine(
                        "  \"warnings\": ${metadata.warnings.joinToString(
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
    ): MemoryExportResult =
        exportInvestigationReportMarkdown(
            heapDump.toExportMetadata(),
            histogram,
            diff,
            outputFile,
            snapshotSummary,
            exportContext,
        )

    @Suppress("LongParameterList")
    fun exportInvestigationReportMarkdown(
        metadata: HeapExportMetadata,
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
                "- Selected object IDs: `${exportContext?.selectedObjectIds?.joinToString().orEmpty().ifBlank {
                    "none"
                }}`",
            )
            if (exportContext?.filters?.isNotEmpty() == true) {
                writer.appendLine(
                    "- Filters: `${exportContext.filters.entries.joinToString { "${it.key}=${it.value}" }}`",
                )
            }
            writer.appendLine("- Format: `${metadata.format}`")
            writer.appendLine("- ID size: `${metadata.idSize}`")
            writer.appendLine("- Timestamp: `${metadata.timestampMillis}`")
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
            if (metadata.warnings.isNotEmpty()) {
                writer.appendLine()
                writer.appendLine("## Warnings")
                metadata.warnings.forEach { warning -> writer.appendLine("- ${warning.message}") }
            }
            diff?.let { writer.writeClassDiff(it) }
        }
    }

    private fun HeapDump.toExportMetadata(): HeapExportMetadata =
        HeapExportMetadata(
            format = format,
            idSize = idSize,
            timestampMillis = timestampMillis,
            instanceCount = instances.size,
            objectArrayCount = objectArrays.size,
            primitiveArrayCount = primitiveArrays.size,
            gcRootCount = gcRoots.size,
            warnings = warnings,
        )

    private fun BufferedWriter.writeClassDiff(diff: HeapDiff) {
        appendLine()
        appendLine("## Class-level heap diff")
        listOf("A" to diff.beforeSnapshot, "B" to diff.afterSnapshot).forEach { (label, snapshot) ->
            appendLine(
                "- Snapshot $label: `${snapshot?.id ?: "unknown"}`; " +
                    "source `${snapshot?.sourceFileDigest ?: "unknown"}`; mapping `${snapshot?.mappingDigest ?: "not loaded"}`",
            )
        }
        appendLine()
        appendLine("The diff is class-level; it does not establish stable object identity across snapshots.")
        appendLine()
        appendLine("| Class | Before | After | Delta |")
        appendLine("| --- | ---: | ---: | ---: |")
        diff.entries.take(50).forEach { entry ->
            val escaped = entry.className.replace("|", "\\|")
            appendLine("| $escaped | ${entry.beforeCount} | ${entry.afterCount} | ${entry.countDelta} |")
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
