package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.hprof.BitmapDumpParseResult
import com.androidperformancestudio.memory.model.BitmapDumpImage
import com.androidperformancestudio.memory.model.ProcessMemorySnapshot
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class BitmapDumpAnalyzerTest {
    private val analyzer = BitmapDumpAnalyzer()

    @Test
    fun `analysis retains duplicate records and calculates native heap ratio`() {
        val parsed =
            BitmapDumpParseResult(
                recordedBitmapCount = 3,
                discoveredBitmapCount = 4,
                images = listOf(image(1, "same"), image(2, "same"), image(3, "other")),
            )

        val session =
            analyzer.analyze(
                BitmapDumpAnalysisRequest(
                    id = "one",
                    packageName = "com.example",
                    pid = 42,
                    deviceSerial = "serial",
                    sdkLevel = 35,
                    capturedAt = Instant.EPOCH,
                    hprofFile = Path.of("one.hprof"),
                    imagesDirectory = Path.of("images"),
                    parsed = parsed,
                    memorySnapshot = ProcessMemorySnapshot(10_000, 5_000, 800),
                ),
            )

        assertEquals(3, session.summary.exportedImageCount)
        assertEquals(2, session.summary.uniqueImageCount)
        assertEquals(1, session.summary.duplicateGroupCount)
        assertEquals(12L, session.summary.estimatedBitmapBytes)
        assertEquals(1.5, session.summary.bitmapNativeHeapRatioPercent)
        assertEquals(listOf(2, 2, 1), session.images.map { it.duplicateCount })
    }

    @Test
    fun `comparison uses hash multiplicity instead of unique hash set`() {
        val before = session("before", listOf(image(1, "same")))
        val after = session("after", listOf(image(1, "same"), image(2, "same"), image(3, "new")))

        val comparison = analyzer.compare(before, after)

        assertEquals(1, comparison.added.single().afterCount)
        assertEquals(1, comparison.changedDuplicateCounts.single().beforeCount)
        assertEquals(2, comparison.changedDuplicateCounts.single().afterCount)
    }

    private fun session(
        id: String,
        images: List<BitmapDumpImage>,
    ) = analyzer.analyze(
        BitmapDumpAnalysisRequest(
            id = id,
            packageName = "com.example",
            pid = 42,
            deviceSerial = "serial",
            sdkLevel = 35,
            capturedAt = Instant.EPOCH,
            hprofFile = Path.of("$id.hprof"),
            imagesDirectory = Path.of("images"),
            parsed = BitmapDumpParseResult(images.size, images.size, images),
            memorySnapshot = null,
        ),
    )

    private fun image(
        index: Int,
        hash: String,
    ) = BitmapDumpImage(
        recordIndex = index,
        arrayObjectId = index.toLong(),
        file = Path.of("$index.png"),
        width = 1,
        height = 1,
        pngBytes = 32,
        estimatedMemoryBytes = 4,
        sha256 = hash,
    )
}
