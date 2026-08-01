package com.androidperformancestudio.memory.export

import com.androidperformancestudio.memory.model.BitmapDumpImage
import com.androidperformancestudio.memory.model.BitmapDumpSession
import com.androidperformancestudio.memory.model.BitmapDumpSummary
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class BitmapDumpExportAdaptersTest {
    @Test
    fun `writes compatible manifests gallery summary and session zip`() {
        val root = createTempDirectory("bitmap-export")
        val images = root.resolve("images")
        Files.createDirectories(images)
        val image = images.resolve("0001_1x1.png")
        Files.write(image, byteArrayOf(1, 2, 3))
        val hprof = root.resolve("bitmap.raw.hprof")
        Files.write(hprof, byteArrayOf(4, 5, 6))
        val session = sampleSession(root, hprof, image)
        val exports = BitmapDumpExportAdapters()

        exports.writeSessionArtifacts(session)
        val zip = root.resolve("export.zip")
        exports.exportSessionZip(session, zip)

        assertContains(Files.readString(root.resolve("summary.json")), "\"image_count\": 1")
        assertContains(Files.readString(root.resolve("manifest.json")), "\"sha256\":\"abc\"")
        assertContains(Files.readString(root.resolve("gallery.html")), "alt=\"Bitmap 1\"")
        ZipFile(zip.toFile()).use { archive ->
            assertTrue(archive.getEntry("bitmap.raw.hprof") != null)
            assertTrue(archive.getEntry("images/0001_1x1.png") != null)
            assertTrue(archive.getEntry("summary.json") != null)
            assertTrue(archive.getEntry("export.zip") == null)
        }
    }

    private fun sampleSession(
        root: java.nio.file.Path,
        hprof: java.nio.file.Path,
        image: java.nio.file.Path,
    ) = BitmapDumpSession(
        id = "session",
        packageName = "com.example",
        pid = 42,
        deviceSerial = "serial",
        sdkLevel = 35,
        capturedAt = Instant.EPOCH,
        hprofFile = hprof,
        imagesDirectory = root.resolve("images"),
        images =
            listOf(
                BitmapDumpImage(
                    recordIndex = 1,
                    arrayObjectId = 2,
                    file = image,
                    width = 1,
                    height = 1,
                    pngBytes = 3,
                    estimatedMemoryBytes = 4,
                    sha256 = "abc",
                ),
            ),
        summary = BitmapDumpSummary(1, 1, 1, 1, 0, 3, 4),
    )
}
