@file:Suppress("LongMethod", "MaxLineLength")

package com.androidperformancestudio.memory.export

import com.androidperformancestudio.memory.model.BitmapDumpComparison
import com.androidperformancestudio.memory.model.BitmapDumpImage
import com.androidperformancestudio.memory.model.BitmapDumpSession
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Suppress("TooManyFunctions")
class BitmapDumpExportAdapters {
    fun writeSessionArtifacts(session: BitmapDumpSession) {
        val directory = session.hprofFile.parent
        exportManifestJson(session, directory.resolve("manifest.json"))
        exportManifestCsv(session, directory.resolve("manifest.csv"))
        exportSummaryJson(session, directory.resolve("summary.json"))
        exportGalleryHtml(session, directory.resolve("gallery.html"))
    }

    fun exportImage(
        image: BitmapDumpImage,
        output: Path,
    ): MemoryExportResult {
        output.parent?.let(Files::createDirectories)
        Files.copy(image.file, output, StandardCopyOption.REPLACE_EXISTING)
        return MemoryExportResult(output)
    }

    fun exportSessionZip(
        session: BitmapDumpSession,
        output: Path,
    ): MemoryExportResult {
        output.parent?.let(Files::createDirectories)
        ZipOutputStream(Files.newOutputStream(output)).use { zip ->
            val root = session.hprofFile.parent
            Files.walk(root).use { paths ->
                paths.filter(Files::isRegularFile).filter { it != output }.sorted().forEach { file ->
                    val entryName = root.relativize(file).joinToString("/") { it.toString() }
                    zip.putNextEntry(ZipEntry(entryName))
                    Files.copy(file, zip)
                    zip.closeEntry()
                }
            }
        }
        return MemoryExportResult(output)
    }

    fun exportManifestCsv(
        session: BitmapDumpSession,
        output: Path,
    ): MemoryExportResult {
        output.parent?.let(Files::createDirectories)
        Files.newBufferedWriter(output).use { writer ->
            writer.writeCsv(
                "recordIndex",
                "arrayObjectId",
                "file",
                "width",
                "height",
                "pngBytes",
                "estimatedMemoryBytes",
                "sha256",
                "duplicateCount",
            )
            session.images.forEach { image ->
                writer.writeCsv(
                    image.recordIndex.toString(),
                    image.arrayObjectId.toString(),
                    image.file.fileName.toString(),
                    image.width.toString(),
                    image.height.toString(),
                    image.pngBytes.toString(),
                    image.estimatedMemoryBytes.toString(),
                    image.sha256,
                    image.duplicateCount.toString(),
                )
            }
        }
        return MemoryExportResult(output)
    }

    fun exportManifestJson(
        session: BitmapDumpSession,
        output: Path,
    ): MemoryExportResult =
        writeText(
            output,
            session.images.joinToString(prefix = "[\n", postfix = "\n]\n", separator = ",\n") { image ->
                """  {"record_index":${image.recordIndex},"array_object_id":${image.arrayObjectId},"file":${json(
                    image.file.fileName.toString(),
                )},"width":${image.width},"height":${image.height},"png_bytes":${image.pngBytes},"estimated_bitmap_bytes":${image.estimatedMemoryBytes},"sha256":${json(
                    image.sha256,
                )},"duplicate_count":${image.duplicateCount}}"""
            },
        )

    fun exportSummaryJson(
        session: BitmapDumpSession,
        output: Path,
    ): MemoryExportResult {
        val summary = session.summary
        val memory = session.memorySnapshot
        return writeText(
            output,
            """
            {
              "session_id": ${json(session.id)},
              "package": ${json(session.packageName)},
              "timestamp": ${json(session.capturedAt.toString())},
              "device_serial": ${json(session.deviceSerial)},
              "sdk": ${session.sdkLevel},
              "pid": ${session.pid},
              "hprof": ${json(session.hprofFile.fileName.toString())},
              "hprof_bytes": ${Files.size(session.hprofFile)},
              "hprof_bitmap_count": ${summary.recordedBitmapCount},
              "discovered_bitmap_count": ${summary.discoveredBitmapCount},
              "exported_image_count": ${summary.exportedImageCount},
              "image_count": ${summary.exportedImageCount},
              "unique_image_count": ${summary.uniqueImageCount},
              "duplicate_group_count": ${summary.duplicateGroupCount},
              "png_bytes": ${summary.totalPngBytes},
              "estimated_bitmap_bytes": ${summary.estimatedBitmapBytes},
              "native_heap_pss_bytes": ${memory?.nativeHeapPssBytes ?: "null"},
              "java_heap_pss_bytes": ${memory?.javaHeapPssBytes ?: "null"},
              "total_pss_bytes": ${memory?.totalPssBytes ?: "null"},
              "bitmap_native_ratio_percent": ${summary.bitmapNativeHeapRatioPercent?.formatJson() ?: "null"},
              "gallery": "gallery.html"
            }
            """.trimIndent() + "\n",
        )
    }

    fun exportGalleryHtml(
        session: BitmapDumpSession,
        output: Path,
    ): MemoryExportResult {
        val cards =
            session.images.joinToString("\n") { image ->
                val file = html("images/${image.file.fileName}")
                """<article class="item" data-sha="${html(
                    image.sha256,
                )}" data-area="${image.width.toLong() * image.height}" data-size="${image.pngBytes}"><a href="$file"><img loading="lazy" src="$file" alt="Bitmap ${image.recordIndex}"></a><div>#${image.recordIndex} · ${image.width}×${image.height} · ${image.pngBytes} B · duplicate ×${image.duplicateCount}</div></article>"""
            }
        return writeText(
            output,
            """
            <!doctype html><html><head><meta charset="utf-8"><title>Bitmap dump</title>
            <style>body{font-family:system-ui;margin:20px;background:#111;color:#eee}.toolbar{display:flex;gap:8px;position:sticky;top:0;background:#111;padding:8px}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:12px}.item{background:#222;padding:10px;border-radius:8px}.item img{width:100%;height:180px;object-fit:contain;background:#333}</style></head>
            <body><h1>${html(
                session.packageName,
            )} Bitmap dump</h1><p>${session.summary.exportedImageCount} images · ${session.summary.uniqueImageCount} unique · ${session.summary.estimatedBitmapBytes} estimated bytes</p>
            <div class="toolbar"><input id="query" placeholder="Search SHA or index"><select id="sort"><option value="index">Index</option><option value="area">Area</option><option value="size">PNG size</option></select><label><input id="duplicates" type="checkbox">Duplicates only</label></div>
            <main id="grid" class="grid">$cards</main>
            <script>const grid=document.getElementById('grid'),items=[...grid.children],q=document.getElementById('query'),sort=document.getElementById('sort'),dupes=document.getElementById('duplicates');function render(){const s=q.value.toLowerCase();const counts={};items.forEach(x=>counts[x.dataset.sha]=(counts[x.dataset.sha]||0)+1);const shown=items.filter(x=>(!s||x.textContent.toLowerCase().includes(s)||x.dataset.sha.includes(s))&&(!dupes.checked||counts[x.dataset.sha]>1));shown.sort((a,b)=>sort.value==='area'?b.dataset.area-a.dataset.area:sort.value==='size'?b.dataset.size-a.dataset.size:0);grid.replaceChildren(...shown)}[q,sort,dupes].forEach(x=>x.addEventListener('input',render));</script>
            </body></html>
            """.trimIndent(),
        )
    }

    fun exportComparisonMarkdown(
        comparison: BitmapDumpComparison,
        output: Path,
    ): MemoryExportResult {
        val changes = comparison.added + comparison.removed + comparison.changedDuplicateCounts
        return writeText(
            output,
            buildString {
                appendLine("# Bitmap dump comparison")
                appendLine()
                appendLine("| Metric | Before | After | Delta |")
                appendLine("|---|---:|---:|---:|")
                metric("Images", comparison.before.exportedImageCount, comparison.after.exportedImageCount)
                metric("Unique images", comparison.before.uniqueImageCount, comparison.after.uniqueImageCount)
                metric("Estimated bytes", comparison.before.estimatedBitmapBytes, comparison.after.estimatedBitmapBytes)
                appendLine()
                appendLine("| SHA-256 | Dimensions | Before | After | Delta |")
                appendLine("|---|---:|---:|---:|---:|")
                changes.forEach { change ->
                    appendLine(
                        "| `${change.sha256}` | ${change.width}×${change.height} | ${change.beforeCount} | ${change.afterCount} | ${change.countDelta.withSign()} |",
                    )
                }
            },
        )
    }

    private fun StringBuilder.metric(
        label: String,
        before: Number,
        after: Number,
    ) {
        val delta = after.toLong() - before.toLong()
        appendLine("| $label | $before | $after | ${delta.withSign()} |")
    }

    private fun Long.withSign(): String = if (this >= 0) "+$this" else toString()

    private fun Int.withSign(): String = if (this >= 0) "+$this" else toString()

    private fun Double.formatJson(): String = String.format(Locale.US, "%.4f", this)

    private fun writeText(
        output: Path,
        text: String,
    ): MemoryExportResult {
        output.parent?.let(Files::createDirectories)
        Files.writeString(output, text)
        return MemoryExportResult(output)
    }

    private fun BufferedWriter.writeCsv(vararg values: String) {
        write(
            values.joinToString(",") { value ->
                if (value.any(CSV_SPECIAL_CHARACTERS::contains)) {
                    "\"${value.replace("\"", "\"\"")}\""
                } else {
                    value
                }
            },
        )
        newLine()
    }

    private fun json(value: String): String =
        buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else ->
                        if (character.code < CONTROL_CHARACTER_LIMIT) {
                            append("\\u%04x".format(character.code))
                        } else {
                            append(character)
                        }
                }
            }
            append('"')
        }

    private fun html(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private companion object {
        val CSV_SPECIAL_CHARACTERS = setOf(',', '"', '\n', '\r')
        const val CONTROL_CHARACTER_LIMIT = 0x20
    }
}
