package dev.agentperf.desktop

import dev.agentperf.fixtures.SampleSnapshots
import dev.agentperf.protocol.ProtocolCodec
import dev.agentperf.protocol.ProtocolVersion
import dev.agentperf.protocol.UnsupportedProtocolVersionException
import java.nio.file.Path
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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

        assertEquals(snapshot, imported.snapshot)
        assertArrayEquals(ONE_PIXEL_PNG, imported.screenshotPng)
        assertArrayEquals(raw.zip, requireNotNull(imported.rawArtifacts).zip)
        assertEquals(raw.text, requireNotNull(imported.rawArtifacts).text)
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
        val ONE_PIXEL_PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
    }
}
