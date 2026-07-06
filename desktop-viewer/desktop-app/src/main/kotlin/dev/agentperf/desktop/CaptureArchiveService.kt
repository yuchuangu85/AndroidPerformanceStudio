package dev.agentperf.desktop

import dev.agentperf.protocol.LayoutSnapshot
import dev.agentperf.protocol.ProtocolCodec
import java.nio.file.Path

internal data class ImportedCapture(
    val snapshot: LayoutSnapshot,
    val screenshotPng: ByteArray,
    val rawArtifacts: CaptureRawArtifacts?,
)

internal class CaptureArchiveService(
    private val archiveCodec: CaptureArchiveCodec,
    private val protocolCodec: ProtocolCodec,
) {
    fun export(
        target: Path,
        producerVersion: String,
        snapshot: LayoutSnapshot,
        screenshotPng: ByteArray,
        rawArtifacts: CaptureRawArtifacts?,
    ): CaptureArchiveWriteResult {
        validatePng(screenshotPng)
        return archiveCodec.write(
            target = target,
            metadata = CaptureArchiveMetadata(
                producerVersion = producerVersion,
                packageName = snapshot.packageName,
                capturedAtEpochMillis = snapshot.capturedAtEpochMillis,
                protocolMajor = snapshot.protocolVersion.major,
                protocolMinor = snapshot.protocolVersion.minor,
            ),
            payload = CaptureArchivePayload(
                snapshotJson = protocolCodec.encodeSnapshot(snapshot),
                screenshotPng = screenshotPng,
                rawArtifacts = rawArtifacts,
            ),
        )
    }

    fun import(source: Path): ImportedCapture {
        val document = archiveCodec.read(source)
        val snapshot = protocolCodec.decodeSnapshot(document.payload.snapshotJson)
        validatePng(document.payload.screenshotPng)
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
        )
    }

    private fun validatePng(bytes: ByteArray) {
        val valid = bytes.size >= PNG_HEADER_BYTES &&
            bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE) &&
            bytes.copyOfRange(12, 16).contentEquals(PNG_IHDR) &&
            readBigEndianInt(bytes, 16) > 0 &&
            readBigEndianInt(bytes, 20) > 0
        if (!valid) {
            throw CaptureArchiveFormatException("Screenshot is not a valid PNG")
        }
    }

    private fun readBigEndianInt(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private companion object {
        const val PNG_HEADER_BYTES = 24
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
