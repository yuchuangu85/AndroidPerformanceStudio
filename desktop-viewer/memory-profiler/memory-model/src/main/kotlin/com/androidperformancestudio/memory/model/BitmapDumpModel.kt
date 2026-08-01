package com.androidperformancestudio.memory.model

import java.nio.file.Path
import java.time.Instant

data class BitmapDumpSession(
    val id: String,
    val packageName: String,
    val pid: Int,
    val deviceSerial: String,
    val sdkLevel: Int,
    val capturedAt: Instant,
    val hprofFile: Path,
    val imagesDirectory: Path,
    val images: List<BitmapDumpImage>,
    val memorySnapshot: ProcessMemorySnapshot? = null,
    val summary: BitmapDumpSummary,
)

data class BitmapDumpImage(
    val recordIndex: Int,
    val arrayObjectId: Long,
    val file: Path,
    val width: Int,
    val height: Int,
    val pngBytes: Long,
    val estimatedMemoryBytes: Long,
    val sha256: String,
    val duplicateCount: Int = 1,
)

data class ProcessMemorySnapshot(
    val totalPssBytes: Long,
    val javaHeapPssBytes: Long,
    val nativeHeapPssBytes: Long,
)

data class BitmapDumpSummary(
    val recordedBitmapCount: Int,
    val discoveredBitmapCount: Int,
    val exportedImageCount: Int,
    val uniqueImageCount: Int,
    val duplicateGroupCount: Int,
    val totalPngBytes: Long,
    val estimatedBitmapBytes: Long,
    val bitmapNativeHeapRatioPercent: Double? = null,
)

data class BitmapDumpComparison(
    val before: BitmapDumpSummary,
    val after: BitmapDumpSummary,
    val added: List<BitmapContentChange>,
    val removed: List<BitmapContentChange>,
    val changedDuplicateCounts: List<BitmapContentChange>,
)

data class BitmapContentChange(
    val sha256: String,
    val width: Int,
    val height: Int,
    val beforeCount: Int,
    val afterCount: Int,
) {
    val countDelta: Int
        get() = afterCount - beforeCount
}
