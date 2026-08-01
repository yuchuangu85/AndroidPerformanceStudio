package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.hprof.BitmapDumpParseResult
import com.androidperformancestudio.memory.model.BitmapContentChange
import com.androidperformancestudio.memory.model.BitmapDumpComparison
import com.androidperformancestudio.memory.model.BitmapDumpImage
import com.androidperformancestudio.memory.model.BitmapDumpSession
import com.androidperformancestudio.memory.model.BitmapDumpSummary
import com.androidperformancestudio.memory.model.ProcessMemorySnapshot
import java.nio.file.Path
import java.time.Instant

data class BitmapDumpAnalysisRequest(
    val id: String,
    val packageName: String,
    val pid: Int,
    val deviceSerial: String,
    val sdkLevel: Int,
    val capturedAt: Instant,
    val hprofFile: Path,
    val imagesDirectory: Path,
    val parsed: BitmapDumpParseResult,
    val memorySnapshot: ProcessMemorySnapshot?,
)

class BitmapDumpAnalyzer {
    fun analyze(request: BitmapDumpAnalysisRequest): BitmapDumpSession {
        val counts =
            request.parsed.images
                .groupingBy(BitmapDumpImage::sha256)
                .eachCount()
        val images = request.parsed.images.map { image -> image.copy(duplicateCount = counts.getValue(image.sha256)) }
        val estimatedBytes = images.sumOf(BitmapDumpImage::estimatedMemoryBytes)
        val nativeBytes = request.memorySnapshot?.nativeHeapPssBytes?.takeIf { it > 0L }
        return BitmapDumpSession(
            id = request.id,
            packageName = request.packageName,
            pid = request.pid,
            deviceSerial = request.deviceSerial,
            sdkLevel = request.sdkLevel,
            capturedAt = request.capturedAt,
            hprofFile = request.hprofFile,
            imagesDirectory = request.imagesDirectory,
            images = images,
            memorySnapshot = request.memorySnapshot,
            summary =
                BitmapDumpSummary(
                    recordedBitmapCount = request.parsed.recordedBitmapCount,
                    discoveredBitmapCount = request.parsed.discoveredBitmapCount,
                    exportedImageCount = images.size,
                    uniqueImageCount = counts.size,
                    duplicateGroupCount = counts.values.count { it > 1 },
                    totalPngBytes = images.sumOf(BitmapDumpImage::pngBytes),
                    estimatedBitmapBytes = estimatedBytes,
                    bitmapNativeHeapRatioPercent =
                        nativeBytes?.let { estimatedBytes.toDouble() / it * PERCENT_MULTIPLIER },
                ),
        )
    }

    fun compare(
        before: BitmapDumpSession,
        after: BitmapDumpSession,
    ): BitmapDumpComparison {
        val beforeContent = before.images.contentCounts()
        val afterContent = after.images.contentCounts()
        val changes =
            (beforeContent.keys + afterContent.keys)
                .mapNotNull { sha ->
                    val old = beforeContent[sha]
                    val new = afterContent[sha]
                    val beforeCount = old?.count ?: 0
                    val afterCount = new?.count ?: 0
                    if (beforeCount == afterCount) return@mapNotNull null
                    val image = new?.sample ?: old?.sample ?: return@mapNotNull null
                    BitmapContentChange(
                        sha256 = sha,
                        width = image.width,
                        height = image.height,
                        beforeCount = beforeCount,
                        afterCount = afterCount,
                    )
                }.sortedWith(
                    compareByDescending<BitmapContentChange> { kotlin.math.abs(it.countDelta) }
                        .thenBy { it.sha256 },
                )
        return BitmapDumpComparison(
            before = before.summary,
            after = after.summary,
            added = changes.filter { it.beforeCount == 0 },
            removed = changes.filter { it.afterCount == 0 },
            changedDuplicateCounts = changes.filter { it.beforeCount > 0 && it.afterCount > 0 },
        )
    }

    private fun List<BitmapDumpImage>.contentCounts(): Map<String, ContentCount> =
        groupBy(BitmapDumpImage::sha256).mapValues { (_, images) -> ContentCount(images.size, images.first()) }

    private data class ContentCount(
        val count: Int,
        val sample: BitmapDumpImage,
    )

    private companion object {
        const val PERCENT_MULTIPLIER = 100.0
    }
}
