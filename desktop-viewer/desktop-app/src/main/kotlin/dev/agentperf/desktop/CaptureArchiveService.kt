package dev.agentperf.desktop

import dev.agentperf.analysis.AiAnalysisReport
import dev.agentperf.analysis.AnalysisReport
import dev.agentperf.application.TimelineFrame
import dev.agentperf.protocol.CaptureFrameCodec
import dev.agentperf.protocol.LayoutSnapshot
import dev.agentperf.protocol.ProtocolCodec
import dev.agentperf.protocol.normalizedToCurrentProtocol
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

internal data class ImportedCapture(
    val snapshot: LayoutSnapshot,
    val screenshotPng: ByteArray?,
    val rawArtifacts: CaptureRawArtifacts?,
    val analysis: AnalysisReport?,
    val aiAnalysis: AiAnalysisReport?,
    val timelineFrames: List<TimelineFrame>,
)

internal data class ImportedScreenshot(
    val png: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
)

internal class CaptureArchiveService(
    private val archiveCodec: CaptureArchiveCodec,
    private val protocolCodec: ProtocolCodec,
    private val analysisReportJson: AnalysisReportJson = AnalysisReportJson(),
    private val aiAnalysisReportJson: AiAnalysisReportJson = AiAnalysisReportJson(),
    private val timelineHistoryJson: TimelineHistoryJson = TimelineHistoryJson(),
) {
    fun export(
        target: Path,
        producerVersion: String,
        snapshot: LayoutSnapshot,
        screenshotPng: ByteArray?,
        rawArtifacts: CaptureRawArtifacts?,
        analysis: AnalysisReport? = null,
        aiAnalysis: AiAnalysisReport? = null,
        timelineFrames: List<TimelineFrame> = emptyList(),
    ): CaptureArchiveWriteResult {
        screenshotPng?.let(::validatePng)
        val exportSnapshot = snapshot.normalizedToCurrentProtocol()
        return archiveCodec.write(
            target = target,
            metadata = CaptureArchiveMetadata(
                producerVersion = producerVersion,
                packageName = exportSnapshot.packageName,
                capturedAtEpochMillis = exportSnapshot.capturedAtEpochMillis,
                protocolMajor = exportSnapshot.protocolVersion.major,
                protocolMinor = exportSnapshot.protocolVersion.minor,
            ),
            payload = CaptureArchivePayload(
                snapshotJson = protocolCodec.encodeSnapshot(exportSnapshot),
                screenshotPng = screenshotPng,
                rawArtifacts = rawArtifacts,
                analysisReportJson = analysis?.let(analysisReportJson::encode),
                aiAnalysisReportJson = aiAnalysis?.let(aiAnalysisReportJson::encode),
                timelineHistoryJson = timelineFrames.takeIf { it.isNotEmpty() }?.let(timelineHistoryJson::encode),
            ),
        )
    }

    fun importScreenshot(
        source: Path,
        expectedWidthPx: Int,
        expectedHeightPx: Int,
    ): ImportedScreenshot {
        if (!Files.isRegularFile(source)) {
            throw CaptureArchiveFormatException("Screenshot is not a regular file")
        }
        if (Files.size(source) > CaptureFrameCodec.MAX_SCREENSHOT_BYTES) {
            throw CaptureArchiveFormatException("Screenshot file is too large")
        }
        val bytes = Files.readAllBytes(source)
        if (bytes.size > CaptureFrameCodec.MAX_SCREENSHOT_BYTES) {
            throw CaptureArchiveFormatException("Screenshot file is too large")
        }
        val imported = decodeStandaloneScreenshot(bytes)
        if (imported.widthPx != expectedWidthPx || imported.heightPx != expectedHeightPx) {
            throw CaptureArchiveFormatException(
                "Screenshot dimensions ${imported.widthPx}x${imported.heightPx} " +
                    "do not match layout dimensions ${expectedWidthPx}x${expectedHeightPx}",
            )
        }
        return imported
    }

    fun import(source: Path): ImportedCapture {
        val document = archiveCodec.read(source)
        val snapshot = protocolCodec.decodeSnapshot(document.payload.snapshotJson)
        document.payload.screenshotPng?.let(::validatePng)
        if (snapshot.packageName != document.metadata.packageName) {
            throw CaptureArchiveFormatException(
                "Archive package name does not match its snapshot",
            )
        }
        if (snapshot.capturedAtEpochMillis != document.metadata.capturedAtEpochMillis) {
            throw CaptureArchiveFormatException(
                "Archive capture time does not match its snapshot",
            )
        }
        if (snapshot.protocolVersion.major != document.metadata.protocolMajor ||
            snapshot.protocolVersion.minor != document.metadata.protocolMinor
        ) {
            throw CaptureArchiveFormatException(
                "Archive protocol version does not match its snapshot",
            )
        }
        return ImportedCapture(
            snapshot = snapshot,
            screenshotPng = document.payload.screenshotPng,
            rawArtifacts = document.payload.rawArtifacts,
            analysis = document.payload.analysisReportJson?.let(analysisReportJson::decode),
            aiAnalysis = document.payload.aiAnalysisReportJson?.let(aiAnalysisReportJson::decode),
            timelineFrames = document.payload.timelineHistoryJson?.let(timelineHistoryJson::decode).orEmpty()
                .map { frame ->
                    if (frame.capturedAtEpochMillis == snapshot.capturedAtEpochMillis) {
                        frame.copy(snapshot = snapshot, screenshotPng = document.payload.screenshotPng)
                    } else {
                        frame
                    }
                },
        )
    }

    private fun validatePng(bytes: ByteArray): ImageDimensions {
        val headerDimensions = pngHeaderDimensions(bytes)
            ?: throw CaptureArchiveFormatException("Screenshot is not a valid PNG")
        checkDimensionsWithinBounds(headerDimensions)
        val decodedDimensions = decodeImageDimensions(bytes, invalidMessage = "Screenshot is not a valid PNG")
        if (decodedDimensions != headerDimensions) {
            throw CaptureArchiveFormatException("Screenshot PNG dimensions are inconsistent")
        }
        return decodedDimensions
    }

    private fun decodeStandaloneScreenshot(bytes: ByteArray): ImportedScreenshot {
        pngHeaderDimensions(bytes)?.let(::checkDimensionsWithinBounds)
        val decoded = decodeImage(bytes, invalidMessage = "Screenshot is not a valid image")
        try {
            checkDimensionsWithinBounds(decoded.dimensions)
            val png = if (bytes.hasPngSignature()) {
                bytes
            } else {
                val data = decoded.image.encodeToData(EncodedImageFormat.PNG, 100)
                    ?: throw CaptureArchiveFormatException("Screenshot could not be converted to PNG")
                data.use { it.bytes }
            }
            if (png.size > CaptureFrameCodec.MAX_SCREENSHOT_BYTES) {
                throw CaptureArchiveFormatException("Screenshot file is too large")
            }
            validatePng(png)
            return ImportedScreenshot(
                png = png,
                widthPx = decoded.dimensions.widthPx,
                heightPx = decoded.dimensions.heightPx,
            )
        } finally {
            decoded.close()
        }
    }

    private fun decodeImageDimensions(
        bytes: ByteArray,
        invalidMessage: String,
    ): ImageDimensions = decodeImage(bytes, invalidMessage).use { decoded ->
        decoded.dimensions
    }

    private fun decodeImage(
        bytes: ByteArray,
        invalidMessage: String,
    ): DecodedImage = try {
        val image = Image.makeFromEncoded(bytes)
        val dimensions = ImageDimensions(widthPx = image.width, heightPx = image.height)
        if (dimensions.widthPx <= 0 || dimensions.heightPx <= 0) {
            image.close()
            throw CaptureArchiveFormatException(invalidMessage)
        }
        DecodedImage(image = image, dimensions = dimensions)
    } catch (error: CaptureArchiveFormatException) {
        throw error
    } catch (_: Exception) {
        throw CaptureArchiveFormatException(invalidMessage)
    }

    private fun checkDimensionsWithinBounds(dimensions: ImageDimensions) {
        val pixelCount = dimensions.widthPx.toLong() * dimensions.heightPx.toLong()
        if (dimensions.widthPx > MAX_SCREENSHOT_DIMENSION_PX ||
            dimensions.heightPx > MAX_SCREENSHOT_DIMENSION_PX ||
            pixelCount > MAX_SCREENSHOT_PIXELS
        ) {
            throw CaptureArchiveFormatException("Screenshot dimensions are too large")
        }
    }

    private fun pngHeaderDimensions(bytes: ByteArray): ImageDimensions? {
        if (!bytes.hasPngSignature()) return null
        val validHeader = bytes.size >= PNG_HEADER_BYTES &&
            bytes.copyOfRange(12, 16).contentEquals(PNG_IHDR)
        if (!validHeader) {
            throw CaptureArchiveFormatException("Screenshot is not a valid PNG")
        }
        val width = readBigEndianInt(bytes, 16)
        val height = readBigEndianInt(bytes, 20)
        if (width <= 0 || height <= 0) {
            throw CaptureArchiveFormatException("Screenshot is not a valid PNG")
        }
        return ImageDimensions(widthPx = width, heightPx = height)
    }

    private fun ByteArray.hasPngSignature(): Boolean =
        size >= PNG_SIGNATURE.size && copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)

    private fun readBigEndianInt(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private data class ImageDimensions(
        val widthPx: Int,
        val heightPx: Int,
    )

    private data class DecodedImage(
        val image: Image,
        val dimensions: ImageDimensions,
    ) : AutoCloseable {
        override fun close() {
            image.close()
        }
    }

    private companion object {
        const val PNG_HEADER_BYTES = 24
        const val MAX_SCREENSHOT_DIMENSION_PX = 16_384
        const val MAX_SCREENSHOT_PIXELS = 64L * 1024L * 1024L
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a,
        )
        val PNG_IHDR = byteArrayOf(0x49, 0x48, 0x44, 0x52)
    }
}
