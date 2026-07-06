package dev.agentperf.desktop

import java.nio.file.Path
import kotlinx.serialization.Serializable

internal const val CAPTURE_ARCHIVE_FORMAT = "agentperf-inspector-capture"
internal const val CAPTURE_ARCHIVE_VERSION = 1

internal object CaptureArchivePaths {
    const val MANIFEST = "manifest.json"
    const val SNAPSHOT = "capture/layout-snapshot.json"
    const val SCREENSHOT = "capture/screenshot.png"
    const val RAW_ZIP = "raw/visible-window-views.zip"
    const val RAW_TEXT = "raw/visible-window-views.txt"
}

internal data class CaptureRawArtifacts(
    val zip: ByteArray,
    val text: String,
)

internal data class CaptureArchivePayload(
    val snapshotJson: String,
    val screenshotPng: ByteArray,
    val rawArtifacts: CaptureRawArtifacts? = null,
)

internal data class CaptureArchiveMetadata(
    val producerVersion: String,
    val packageName: String,
    val capturedAtEpochMillis: Long,
    val protocolMajor: Int,
    val protocolMinor: Int,
)

internal data class CaptureArchiveDocument(
    val metadata: CaptureArchiveMetadata,
    val payload: CaptureArchivePayload,
)

internal data class CaptureArchiveWriteResult(
    val path: Path,
    val rawArtifactsIncluded: Boolean,
)

internal class CaptureArchiveFormatException(message: String) :
    IllegalArgumentException(message)

@Serializable
internal data class CaptureArchiveManifest(
    val format: String,
    val archiveVersion: Int,
    val producerVersion: String,
    val packageName: String,
    val capturedAtEpochMillis: Long,
    val protocolMajor: Int,
    val protocolMinor: Int,
    val entries: List<CaptureArchiveManifestEntry>,
)

@Serializable
internal data class CaptureArchiveManifestEntry(
    val path: String,
    val size: Long,
    val sha256: String,
    val required: Boolean,
)
