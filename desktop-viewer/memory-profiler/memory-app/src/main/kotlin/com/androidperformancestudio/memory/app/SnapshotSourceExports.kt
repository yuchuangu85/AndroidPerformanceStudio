package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.export.MemoryExportAdapters
import com.androidperformancestudio.memory.model.HeapDiff
import com.androidperformancestudio.memory.model.HeapExportContext
import com.androidperformancestudio.memory.model.HeapExportInstance
import com.androidperformancestudio.memory.model.HeapExportMetadata
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal object SnapshotSourceExports {
    private const val EXPORT_PAGE_SIZE = 500
    private val exports = MemoryExportAdapters()

    fun copyRaw(
        snapshot: LoadedSnapshot,
        output: Path,
    ) = copySource(snapshot.rawHprofFile, output, "raw")

    fun copyConverted(
        snapshot: LoadedSnapshot,
        output: Path,
    ) = copySource(snapshot.convertedHprofFile, output, "converted")

    private fun copySource(
        source: Path?,
        output: Path,
        label: String,
    ) {
        requireNotNull(source) { "The snapshot has no $label HPROF source" }
        output.parent?.let(Files::createDirectories)
        Files.copy(source, output, StandardCopyOption.REPLACE_EXISTING)
    }

    fun instances(
        snapshot: LoadedSnapshot,
        className: String,
        output: Path,
    ) {
        val instances =
            sequence {
                var offset = 0
                do {
                    val page =
                        snapshot.source.query.instancesPage(
                            listOf(className),
                            null,
                            offset,
                            EXPORT_PAGE_SIZE,
                            ordinaryOnly = true,
                        )
                    check(page.rows.isNotEmpty() || offset >= page.total) { "Incomplete Rust instance export page" }
                    page.rows.forEach { row ->
                        yield(
                            HeapExportInstance(
                                row.objectId,
                                row.className ?: className,
                                row.shallowSize.takeIf { row.shallowSizeKnown },
                                row.retainedSize,
                            ),
                        )
                    }
                    offset += page.rows.size
                } while (offset < page.total)
            }
        exports.exportClassInstancesCsv(instances, output)
    }

    fun json(
        snapshot: LoadedSnapshot,
        output: Path,
        summary: HeapSnapshotSummary?,
    ) {
        exports.exportHeapSnapshotJson(snapshot.exportMetadata(), snapshot.histogram, output, summary)
    }

    fun report(
        snapshot: LoadedSnapshot,
        diff: HeapDiff?,
        output: Path,
        summary: HeapSnapshotSummary?,
        context: HeapExportContext?,
    ) {
        exports.exportInvestigationReportMarkdown(
            snapshot.exportMetadata(),
            snapshot.histogram,
            diff,
            output,
            summary,
            context,
        )
    }

    private fun LoadedSnapshot.exportMetadata(): HeapExportMetadata {
        val metadata = source.metadata
        return HeapExportMetadata(
            metadata.format,
            metadata.idSize,
            metadata.timestampMillis,
            metadata.instanceCount,
            metadata.objectArrayCount,
            metadata.primitiveArrayCount,
            metadata.rootCount,
            metadata.warnings,
        )
    }
}
