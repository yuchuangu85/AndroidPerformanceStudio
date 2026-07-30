package com.androidperformancestudio.desktop

import com.androidperformancestudio.analysis.AnalysisReport
import com.androidperformancestudio.analysis.Finding
import com.androidperformancestudio.analysis.LayoutMetrics
import com.androidperformancestudio.analysis.Severity
import com.androidperformancestudio.application.TimelineChangeType
import com.androidperformancestudio.application.TimelineDiff
import com.androidperformancestudio.application.TimelineFrame
import com.androidperformancestudio.application.TimelineNodeChange
import com.androidperformancestudio.fixtures.SampleSnapshots
import com.androidperformancestudio.protocol.CURRENT_PROTOCOL_VERSION
import com.androidperformancestudio.protocol.CaptureFrameCodec
import com.androidperformancestudio.protocol.LEGACY_WINDOW_ID
import com.androidperformancestudio.protocol.ProtocolCodec
import com.androidperformancestudio.protocol.ProtocolVersion
import com.androidperformancestudio.protocol.ViewAttributes
import com.androidperformancestudio.protocol.ViewNode
import com.androidperformancestudio.protocol.normalizedToCurrentProtocol
import com.androidperformancestudio.protocol.UnsupportedProtocolVersionException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CaptureArchiveServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val protocolCodec = ProtocolCodec(supportedMajor = 1)
    private val service = CaptureArchiveService(
        archiveCodec = CaptureArchiveCodec(),
        protocolCodec = protocolCodec,
    )

    @Test
    fun `service export then import restores snapshot screenshot and raw files`() {
        val path = tempDir.resolve("round-trip.apinspect")
        val snapshot = SampleSnapshots.dashboard
        val raw = CaptureRawArtifacts(
            zip = byteArrayOf(0x50, 0x4b, 0x03, 0x04),
            text = "raw text",
        )

        service.export(path, "0.1.2", snapshot, ONE_PIXEL_PNG, raw)
        val imported = service.import(path)

        assertEquals(snapshot.normalizedToCurrentProtocol(), imported.snapshot)
        assertArrayEquals(ONE_PIXEL_PNG, imported.screenshotPng)
        assertArrayEquals(raw.zip, requireNotNull(imported.rawArtifacts).zip)
        assertEquals(raw.text, requireNotNull(imported.rawArtifacts).text)
    }

    @Test
    fun `exports legacy snapshots with the current protocol version`() {
        val path = tempDir.resolve("legacy-upgraded.apinspect")
        val legacySnapshot = SampleSnapshots.dashboard.copy(
            protocolVersion = ProtocolVersion(1, 0),
            windows = emptyList(),
            defaultWindowId = null,
        )

        service.export(path, "0.1.3", legacySnapshot, ONE_PIXEL_PNG, rawArtifacts = null)

        val document = CaptureArchiveCodec().read(path)
        val exported = protocolCodec.decodeSnapshot(document.payload.snapshotJson)
        assertEquals(CURRENT_PROTOCOL_VERSION.major, document.metadata.protocolMajor)
        assertEquals(CURRENT_PROTOCOL_VERSION.minor, document.metadata.protocolMinor)
        assertEquals(CURRENT_PROTOCOL_VERSION, exported.protocolVersion)
        assertEquals(listOf(LEGACY_WINDOW_ID), exported.windows.map { it.id })
        assertEquals(LEGACY_WINDOW_ID, exported.defaultWindowId)
    }

    @Test
    fun `exports complex legacy snapshot after normalization exceeds transport limit`() {
        val path = tempDir.resolve("large-normalized-snapshot.apinspect")
        val largeProperty = "x".repeat(CaptureFrameCodec.MAX_SNAPSHOT_BYTES / 2)
        val root = (SampleSnapshots.dashboard.root as ViewNode).copy(
            attributes = ViewAttributes(
                rawProperties = mapOf("large-property" to largeProperty),
            ),
        )
        val snapshot = SampleSnapshots.dashboard.copy(root = root)

        service.export(path, "0.1.3", snapshot, ONE_PIXEL_PNG, rawArtifacts = null)

        val archivedJson = CaptureArchiveCodec().read(path).payload.snapshotJson
        assertTrue(
            archivedJson.toByteArray().size > CaptureFrameCodec.MAX_SNAPSHOT_BYTES,
        )
        val imported = service.import(path)
        assertEquals(
            largeProperty,
            (imported.snapshot.root as ViewNode).attributes.rawProperties["large-property"],
        )
    }

    @Test
    fun `service export then import restores persisted analysis report`() {
        val path = tempDir.resolve("report.apinspect")
        val report = AnalysisReport(
            metrics = LayoutMetrics(nodeCount = 5, maxDepth = 3, widestLevel = 2),
            findings = listOf(
                Finding(
                    ruleId = "layout.deep-hierarchy",
                    severity = Severity.WARNING,
                    nodeId = "root",
                    message = "deep",
                    arguments = mapOf("depth" to "12"),
                ),
            ),
        )

        service.export(path, "0.2.0", SampleSnapshots.dashboard, ONE_PIXEL_PNG, rawArtifacts = null, analysis = report)
        val imported = service.import(path)

        assertEquals(report, imported.analysis)
    }


    @Test
    fun `service export then import restores persisted ai analysis report`() {
        val path = tempDir.resolve("ai-report.apinspect")
        val report = com.androidperformancestudio.analysis.AiAnalysisReport(
            model = "gpt-test",
            summary = "summary",
            findings = listOf(
                com.androidperformancestudio.analysis.AiFinding(
                    ruleId = "ai.layout",
                    severity = Severity.WARNING,
                    nodeId = "root",
                    title = "AI layout risk",
                    message = "Possible risky layout",
                    recommendation = "Simplify hierarchy",
                    confidence = 0.75f,
                ),
            ),
        )

        service.export(
            target = path,
            producerVersion = "0.2.5",
            snapshot = SampleSnapshots.dashboard,
            screenshotPng = ONE_PIXEL_PNG,
            rawArtifacts = null,
            aiAnalysis = report,
        )
        val imported = service.import(path)

        assertEquals(report, imported.aiAnalysis)
    }


    @Test
    fun `service export then import restores timeline history summaries`() {
        val path = tempDir.resolve("timeline.apinspect")
        val frame = TimelineFrame(
            index = 1,
            snapshot = SampleSnapshots.dashboard,
            screenshotPng = ONE_PIXEL_PNG,
            diffFromPrevious = TimelineDiff(
                previousCapturedAtEpochMillis = 1_000,
                currentCapturedAtEpochMillis = SampleSnapshots.dashboard.capturedAtEpochMillis,
                addedNodes = 1,
                removedNodes = 0,
                boundsChangedNodes = 2,
                changes = listOf(
                    TimelineNodeChange(
                        type = TimelineChangeType.CHANGED,
                        windowId = "window:main",
                        nodeId = "title",
                        nodeKey = "window:main:title",
                        className = "TextView",
                        changedProperties = listOf("bounds", "text"),
                    ),
                ),
            ),
        )

        service.export(
            target = path,
            producerVersion = "0.3.0",
            snapshot = SampleSnapshots.dashboard,
            screenshotPng = ONE_PIXEL_PNG,
            rawArtifacts = null,
            timelineFrames = listOf(frame),
        )
        val imported = service.import(path)

        assertEquals(1, imported.timelineFrames.size)
        val restored = imported.timelineFrames.single()
        assertEquals(1, restored.index)
        assertEquals(SampleSnapshots.dashboard.capturedAtEpochMillis, restored.capturedAtEpochMillis)
        assertEquals(1, restored.diffFromPrevious?.addedNodes)
        assertEquals(listOf("bounds", "text"), restored.diffFromPrevious?.changes?.single()?.changedProperties)
    }


    @Test
    fun `service export then import supports a layout-only capture without screenshot`() {
        val path = tempDir.resolve("layout-only.apinspect")
        val snapshot = SampleSnapshots.dashboard

        service.export(path, "0.3.1", snapshot, screenshotPng = null, rawArtifacts = null)
        val imported = service.import(path)

        assertEquals(snapshot.normalizedToCurrentProtocol(), imported.snapshot)
        assertEquals(null, imported.screenshotPng)
    }

    @Test
    fun `service imports a standalone jpeg screenshot as png bytes`() {
        val path = tempDir.resolve("manual.jpg")
        Files.write(path, encodedImage(ONE_PIXEL_PNG, org.jetbrains.skia.EncodedImageFormat.JPEG))

        val imported = service.importScreenshot(
            source = path,
            expectedWidthPx = 1,
            expectedHeightPx = 1,
        )

        assertArrayEquals(PNG_SIGNATURE, imported.png.copyOf(PNG_SIGNATURE.size))
        assertEquals(1, imported.widthPx)
        assertEquals(1, imported.heightPx)
    }

    @Test
    fun `service imports a standalone screenshot png`() {
        val path = tempDir.resolve("manual.png")
        Files.write(path, ONE_PIXEL_PNG)

        val imported = service.importScreenshot(
            source = path,
            expectedWidthPx = 1,
            expectedHeightPx = 1,
        )

        assertArrayEquals(ONE_PIXEL_PNG, imported.png)
        assertEquals(1, imported.widthPx)
        assertEquals(1, imported.heightPx)
    }

    @Test
    fun `standalone imported screenshot is exported as archive screenshot`() {
        val screenshotPath = tempDir.resolve("manual.png")
        val archivePath = tempDir.resolve("manual-image.apinspect")
        Files.write(screenshotPath, TWO_BY_ONE_PNG)
        val screenshot = service.importScreenshot(
            source = screenshotPath,
            expectedWidthPx = 2,
            expectedHeightPx = 1,
        )

        service.export(
            target = archivePath,
            producerVersion = "0.3.0",
            snapshot = SampleSnapshots.dashboard,
            screenshotPng = screenshot.png,
            rawArtifacts = null,
        )

        val importedArchive = service.import(archivePath)
        assertArrayEquals(TWO_BY_ONE_PNG, importedArchive.screenshotPng)
    }

    @Test
    fun `service rejects an invalid standalone screenshot`() {
        val path = tempDir.resolve("manual.png")
        Files.write(path, byteArrayOf(1, 2, 3))

        assertThrows(CaptureArchiveFormatException::class.java) {
            service.importScreenshot(
                source = path,
                expectedWidthPx = 1,
                expectedHeightPx = 1,
            )
        }
    }

    @Test
    fun `service rejects a truncated png with a valid header`() {
        val path = tempDir.resolve("truncated.png")
        Files.write(path, pngHeader(widthPx = 1, heightPx = 1))

        assertThrows(CaptureArchiveFormatException::class.java) {
            service.importScreenshot(
                source = path,
                expectedWidthPx = 1,
                expectedHeightPx = 1,
            )
        }
    }

    @Test
    fun `service rejects a screenshot whose dimensions do not match the layout`() {
        val path = tempDir.resolve("resized.png")
        Files.write(path, TWO_BY_ONE_PNG)

        val error = assertThrows(CaptureArchiveFormatException::class.java) {
            service.importScreenshot(
                source = path,
                expectedWidthPx = 1,
                expectedHeightPx = 1,
            )
        }

        assertTrue(error.message.orEmpty().contains("2x1"))
        assertTrue(error.message.orEmpty().contains("1x1"))
    }

    @Test
    fun `service rejects png dimensions that exceed the decoded pixel limit`() {
        val path = tempDir.resolve("pixel-bomb.png")
        Files.write(path, pngHeader(widthPx = 9_000, heightPx = 9_000))

        val error = assertThrows(CaptureArchiveFormatException::class.java) {
            service.importScreenshot(
                source = path,
                expectedWidthPx = 9_000,
                expectedHeightPx = 9_000,
            )
        }

        assertTrue(error.message.orEmpty().contains("dimensions"))
    }

    @Test
    fun `service rejects an invalid screenshot`() {
        val path = tempDir.resolve("invalid-png.apinspect")
        CaptureArchiveCodec().write(
            path,
            metadata(SampleSnapshots.dashboard.protocolVersion),
            CaptureArchivePayload(
                snapshotJson = protocolCodec.encodeSnapshot(SampleSnapshots.dashboard),
                screenshotPng = byteArrayOf(1, 2, 3),
            ),
        )

        assertThrows(CaptureArchiveFormatException::class.java) {
            service.import(path)
        }
    }

    @Test
    fun `service retains protocol compatibility checks`() {
        val path = tempDir.resolve("unsupported-protocol.apinspect")
        val unsupported = SampleSnapshots.dashboard.copy(
            protocolVersion = ProtocolVersion(2, 0),
        )
        CaptureArchiveCodec().write(
            path,
            metadata(unsupported.protocolVersion),
            CaptureArchivePayload(
                snapshotJson = protocolCodec.encodeSnapshot(unsupported),
                screenshotPng = ONE_PIXEL_PNG,
            ),
        )

        assertThrows(UnsupportedProtocolVersionException::class.java) {
            service.import(path)
        }
    }

    private fun metadata(version: ProtocolVersion) = CaptureArchiveMetadata(
        producerVersion = "0.1.2",
        packageName = SampleSnapshots.dashboard.packageName,
        capturedAtEpochMillis = SampleSnapshots.dashboard.capturedAtEpochMillis,
        protocolMajor = version.major,
        protocolMinor = version.minor,
    )

    private companion object {
        fun encodedImage(source: ByteArray, format: EncodedImageFormat): ByteArray =
            Image.makeFromEncoded(source).use { image ->
                requireNotNull(image.encodeToData(format, 100)).use { data -> data.bytes }
            }

        fun pngHeader(widthPx: Int, heightPx: Int): ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
            (widthPx ushr 24).toByte(),
            (widthPx ushr 16).toByte(),
            (widthPx ushr 8).toByte(),
            widthPx.toByte(),
            (heightPx ushr 24).toByte(),
            (heightPx ushr 16).toByte(),
            (heightPx ushr 8).toByte(),
            heightPx.toByte(),
        )

        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )

        val ONE_PIXEL_PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
        val TWO_BY_ONE_PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAADklEQVR4nGP4z8DwH4QBEfcD/ePF9e8AAAAASUVORK5CYII=",
        )
    }
}
