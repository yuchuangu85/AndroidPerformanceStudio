package com.androidperformancestudio.desktop

import com.androidperformancestudio.protocol.CaptureFrameCodec
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CaptureArchiveCodecTest {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json {
        encodeDefaults = true
        prettyPrint = true
    }

    @Test
    fun `archive round trip preserves required and optional capture files`() {
        val target = tempDir.resolve("capture.apinspect")
        val input = CaptureArchivePayload(
            snapshotJson = """{"protocolVersion":{"major":1,"minor":0}}""",
            screenshotPng = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47),
            rawArtifacts = CaptureRawArtifacts(
                zip = byteArrayOf(0x50, 0x4b, 0x03, 0x04),
                text = "visible hierarchy",
            ),
        )
        val metadata = CaptureArchiveMetadata(
            producerVersion = "0.1.2",
            packageName = "com.androidperformancestudio.sample",
            capturedAtEpochMillis = 1234L,
            protocolMajor = 1,
            protocolMinor = 0,
        )

        val result = CaptureArchiveCodec().write(target, metadata, input)
        val output = CaptureArchiveCodec().read(target)

        assertEquals(target, result.path)
        assertTrue(result.rawArtifactsIncluded)
        assertEquals(input.snapshotJson, output.payload.snapshotJson)
        assertArrayEquals(input.screenshotPng, output.payload.screenshotPng)
        val inputRaw = requireNotNull(input.rawArtifacts)
        val outputRaw = requireNotNull(output.payload.rawArtifacts)
        assertArrayEquals(inputRaw.zip, outputRaw.zip)
        assertEquals(inputRaw.text, outputRaw.text)
        assertEquals("com.androidperformancestudio.sample", output.metadata.packageName)
        ZipFile(target.toFile()).use { zip ->
            assertEquals(
                setOf(
                    "manifest.json",
                    "capture/layout-snapshot.json",
                    "capture/screenshot.png",
                    "raw/visible-window-views.zip",
                    "raw/visible-window-views.txt",
                ),
                zip.entries().asSequence().map { it.name }.toSet(),
            )
        }
    }


    @Test
    fun `archive round trip supports layout-only captures without screenshot entry`() {
        val target = tempDir.resolve("layout-only.apinspect")
        val input = CaptureArchivePayload(
            snapshotJson = "{}",
            screenshotPng = null,
        )

        CaptureArchiveCodec().write(target, validMetadata(), input)
        val output = CaptureArchiveCodec().read(target)

        assertEquals(input.snapshotJson, output.payload.snapshotJson)
        assertEquals(null, output.payload.screenshotPng)
        ZipFile(target.toFile()).use { zip ->
            assertEquals(
                setOf(
                    "manifest.json",
                    "capture/layout-snapshot.json",
                ),
                zip.entries().asSequence().map { it.name }.toSet(),
            )
        }
    }

    @Test
    fun `archive round trip preserves optional ai analysis report`() {
        val target = tempDir.resolve("ai-report.apinspect")
        val input = CaptureArchivePayload(
            snapshotJson = "{}",
            screenshotPng = null,
            aiAnalysisReportJson = """{"summary":"ai"}""",
        )

        CaptureArchiveCodec().write(target, validMetadata(), input)
        val output = CaptureArchiveCodec().read(target)

        assertEquals(input.aiAnalysisReportJson, output.payload.aiAnalysisReportJson)
        ZipFile(target.toFile()).use { zip ->
            assertTrue(zip.entries().asSequence().any { it.name == CaptureArchivePaths.AI_ANALYSIS_REPORT })
        }
    }


    @Test
    fun `write omits oversized optional raw attachments instead of failing export`() {
        val target = tempDir.resolve("oversized-raw-text.apinspect")
        val oversizedRawText = "x".repeat(8 * 1024 * 1024 + 1)

        val result = CaptureArchiveCodec().write(
            target = target,
            metadata = validMetadata(),
            payload = CaptureArchivePayload(
                snapshotJson = "{}",
                screenshotPng = byteArrayOf(1, 2, 3),
                rawArtifacts = CaptureRawArtifacts(
                    zip = byteArrayOf(0x50, 0x4b, 0x03, 0x04),
                    text = oversizedRawText,
                ),
            ),
        )
        val output = CaptureArchiveCodec().read(target)

        assertEquals(target, result.path)
        assertEquals(false, result.rawArtifactsIncluded)
        assertEquals(null, output.payload.rawArtifacts)
        ZipFile(target.toFile()).use { zip ->
            assertEquals(
                setOf(
                    "manifest.json",
                    "capture/layout-snapshot.json",
                    "capture/screenshot.png",
                ),
                zip.entries().asSequence().map { it.name }.toSet(),
            )
        }
    }

    @Test
    fun `write and read support snapshots larger than the live transport limit`() {
        val target = tempDir.resolve("large-snapshot.apinspect")
        val snapshot = "x".repeat(CaptureFrameCodec.MAX_SNAPSHOT_BYTES + 1)

        CaptureArchiveCodec().write(
            target = target,
            metadata = validMetadata(),
            payload = CaptureArchivePayload(
                snapshotJson = snapshot,
                screenshotPng = byteArrayOf(1, 2, 3),
            ),
        )

        assertEquals(snapshot, CaptureArchiveCodec().read(target).payload.snapshotJson)
    }

    @Test
    fun `configured snapshot multiplier raises the write and read limit`() {
        val target = tempDir.resolve("adjusted-large-snapshot.apinspect")
        val limits = CaptureArchiveLimits(snapshotSizeMultiplier = 2)
        val codec = CaptureArchiveCodec(limits = limits)
        val snapshot = "x".repeat(
            CaptureArchiveLimits.BASE_MAX_SNAPSHOT_SIZE_MIB * 1024 * 1024 + 1,
        )

        codec.write(
            target = target,
            metadata = validMetadata(),
            payload = CaptureArchivePayload(
                snapshotJson = snapshot,
                screenshotPng = byteArrayOf(1, 2, 3),
            ),
        )

        assertEquals(snapshot.length, codec.read(target).payload.snapshotJson.length)
    }

    @Test
    fun `read rejects an archive without a manifest`() {
        val archive = tempDir.resolve("missing-manifest.zip")
        writeZip(
            archive,
            listOf(CaptureArchivePaths.SNAPSHOT to "{}".toByteArray()),
        )

        assertThrows(CaptureArchiveFormatException::class.java) {
            CaptureArchiveCodec().read(archive)
        }
    }

    @Test
    fun `read rejects duplicate manifest paths`() {
        val archive = tempDir.resolve("duplicate-manifest-path.zip")
        val snapshot = "{}".toByteArray()
        val screenshot = byteArrayOf(1, 2, 3)
        val manifest = validManifest(
            entries = listOf(
                manifestEntry(CaptureArchivePaths.SNAPSHOT, snapshot, required = true),
                manifestEntry(CaptureArchivePaths.SNAPSHOT, snapshot, required = true),
                manifestEntry(CaptureArchivePaths.SCREENSHOT, screenshot, required = true),
            ),
        )
        writeZip(
            archive,
            listOf(
                CaptureArchivePaths.MANIFEST to manifestBytes(manifest),
                CaptureArchivePaths.SNAPSHOT to snapshot,
                CaptureArchivePaths.SCREENSHOT to screenshot,
            ),
        )

        assertThrows(CaptureArchiveFormatException::class.java) {
            CaptureArchiveCodec().read(archive)
        }
    }

    @Test
    fun `read rejects traversal and undeclared entries`() {
        val archive = tempDir.resolve("traversal.zip")
        val snapshot = "{}".toByteArray()
        val screenshot = byteArrayOf(1, 2, 3)
        val manifest = validManifest(
            entries = listOf(
                manifestEntry(CaptureArchivePaths.SNAPSHOT, snapshot, required = true),
                manifestEntry(CaptureArchivePaths.SCREENSHOT, screenshot, required = true),
                manifestEntry("../outside.txt", byteArrayOf(9), required = false),
            ),
        )
        writeZip(
            archive,
            listOf(
                CaptureArchivePaths.MANIFEST to manifestBytes(manifest),
                CaptureArchivePaths.SNAPSHOT to snapshot,
                CaptureArchivePaths.SCREENSHOT to screenshot,
                "../outside.txt" to byteArrayOf(9),
            ),
        )

        assertThrows(CaptureArchiveFormatException::class.java) {
            CaptureArchiveCodec().read(archive)
        }

        val undeclared = createValidArchive("undeclared.zip")
        appendEntry(undeclared, "extra.bin", byteArrayOf(4))
        assertThrows(CaptureArchiveFormatException::class.java) {
            CaptureArchiveCodec().read(undeclared)
        }
    }

    @Test
    fun `read rejects checksum mismatch and incomplete raw pair`() {
        val corrupt = tempDir.resolve("corrupt.zip")
        val snapshot = "{}".toByteArray()
        val screenshot = byteArrayOf(1, 2, 3)
        val corruptManifest = validManifest(
            entries = listOf(
                manifestEntry(CaptureArchivePaths.SNAPSHOT, snapshot, required = true),
                manifestEntry(
                    CaptureArchivePaths.SCREENSHOT,
                    byteArrayOf(9, 9, 9),
                    required = true,
                ),
            ),
        )
        writeZip(
            corrupt,
            listOf(
                CaptureArchivePaths.MANIFEST to manifestBytes(corruptManifest),
                CaptureArchivePaths.SNAPSHOT to snapshot,
                CaptureArchivePaths.SCREENSHOT to screenshot,
            ),
        )
        assertThrows(CaptureArchiveFormatException::class.java) {
            CaptureArchiveCodec().read(corrupt)
        }

        val incomplete = tempDir.resolve("incomplete-raw.zip")
        val rawZip = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        val incompleteManifest = validManifest(
            entries = listOf(
                manifestEntry(CaptureArchivePaths.SNAPSHOT, snapshot, required = true),
                manifestEntry(CaptureArchivePaths.SCREENSHOT, screenshot, required = true),
                manifestEntry(CaptureArchivePaths.RAW_ZIP, rawZip, required = false),
            ),
        )
        writeZip(
            incomplete,
            listOf(
                CaptureArchivePaths.MANIFEST to manifestBytes(incompleteManifest),
                CaptureArchivePaths.SNAPSHOT to snapshot,
                CaptureArchivePaths.SCREENSHOT to screenshot,
                CaptureArchivePaths.RAW_ZIP to rawZip,
            ),
        )
        assertThrows(CaptureArchiveFormatException::class.java) {
            CaptureArchiveCodec().read(incomplete)
        }
    }

    @Test
    fun `read rejects snapshot declared above archive limit before reading content`() {
        val archive = tempDir.resolve("oversized.zip")
        val snapshot = byteArrayOf(1)
        val screenshot = byteArrayOf(1)
        val manifest = validManifest(
            entries = listOf(
                manifestEntry(CaptureArchivePaths.SNAPSHOT, snapshot, required = true).copy(
                    size = CaptureArchiveLimits.BASE_MAX_SNAPSHOT_SIZE_MIB.toLong() *
                        1024 * 1024 + 1,
                ),
                manifestEntry(CaptureArchivePaths.SCREENSHOT, screenshot, required = true),
            ),
        )
        writeZip(
            archive,
            listOf(
                CaptureArchivePaths.MANIFEST to manifestBytes(manifest),
                CaptureArchivePaths.SNAPSHOT to snapshot,
                CaptureArchivePaths.SCREENSHOT to screenshot,
            ),
        )

        assertThrows(CaptureArchiveFormatException::class.java) {
            CaptureArchiveCodec().read(archive)
        }
    }

    @Test
    fun `failed replacement preserves an existing target and removes temp files`() {
        val target = tempDir.resolve("capture.apinspect")
        Files.writeString(target, "existing")
        val codec = CaptureArchiveCodec(
            moveIntoPlace = { _, _ -> error("move failed") },
        )

        assertThrows(IllegalStateException::class.java) {
            codec.write(target, validMetadata(), validPayload())
        }

        assertEquals("existing", Files.readString(target))
        assertEquals(
            setOf("capture.apinspect"),
            Files.list(tempDir).use { paths ->
                paths.map { it.fileName.toString() }.toList().toSet()
            },
        )
    }

    private fun createValidArchive(name: String): Path {
        val archive = tempDir.resolve(name)
        CaptureArchiveCodec().write(archive, validMetadata(), validPayload())
        return archive
    }

    private fun appendEntry(
        archive: Path,
        name: String,
        bytes: ByteArray,
    ) {
        val existing = ZipFile(archive.toFile()).use { zip ->
            zip.entries().asSequence().map { entry ->
                entry.name to zip.getInputStream(entry).use { it.readAllBytes() }
            }.toList()
        }
        writeZip(archive, existing + (name to bytes))
    }

    private fun validMetadata() = CaptureArchiveMetadata(
        producerVersion = "0.1.2",
        packageName = "com.androidperformancestudio.sample",
        capturedAtEpochMillis = 1234L,
        protocolMajor = 1,
        protocolMinor = 0,
    )

    private fun validPayload() = CaptureArchivePayload(
        snapshotJson = "{}",
        screenshotPng = byteArrayOf(1, 2, 3),
    )

    private fun validManifest(
        entries: List<CaptureArchiveManifestEntry>,
    ) = CaptureArchiveManifest(
        format = CAPTURE_ARCHIVE_FORMAT,
        archiveVersion = CAPTURE_ARCHIVE_VERSION,
        producerVersion = "0.1.2",
        packageName = "com.androidperformancestudio.sample",
        capturedAtEpochMillis = 1234L,
        protocolMajor = 1,
        protocolMinor = 0,
        entries = entries,
    )

    private fun manifestEntry(
        path: String,
        bytes: ByteArray,
        required: Boolean,
    ) = CaptureArchiveManifestEntry(
        path = path,
        size = bytes.size.toLong(),
        sha256 = sha256(bytes),
        required = required,
    )

    private fun manifestBytes(manifest: CaptureArchiveManifest): ByteArray =
        json.encodeToString(manifest).toByteArray(StandardCharsets.UTF_8)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun writeZip(
        target: Path,
        entries: List<Pair<String, ByteArray>>,
    ) {
        ZipOutputStream(Files.newOutputStream(target)).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }
}
