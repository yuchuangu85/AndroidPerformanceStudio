package com.androidperformancestudio.perfetto.model

import com.androidperformancestudio.contracts.ArtifactAcquisitionKind
import com.androidperformancestudio.contracts.ArtifactCompleteness
import com.androidperformancestudio.contracts.ArtifactProducer
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class PerfettoArtifactFactoryTest {
    @Test
    fun `capture registers immutable bytes and privacy safe device provenance`() {
        val trace = Files.createTempFile("captured", ".pftrace")
        Files.writeString(trace, "captured trace")
        val artifact =
            PerfettoArtifactFactory(applicationSalt = ByteArray(32) { 3 }).captured(
                id = "capture-1",
                traceFile = trace,
                deviceSerial = "SERIAL-123",
                deviceModel = "Pixel",
                capturedAt = Instant.ofEpochSecond(10),
            )

        assertEquals(ArtifactAcquisitionKind.CAPTURE, artifact.provenance.acquisition.kind)
        assertIs<ArtifactProducer.Known>(artifact.provenance.producer)
        assertEquals(ArtifactCompleteness.COMPLETE, artifact.completeness)
        assertFalse(
            artifact.device
                ?.localId
                ?.value
                .orEmpty()
                .contains("SERIAL-123"),
        )
        assertEquals(null, artifact.device?.rawSerial)
    }

    @Test
    fun `import keeps its producer unknown and its requested capability scope unknown`() {
        val trace = Files.createTempFile("imported", ".pftrace")
        Files.writeString(trace, "imported trace")
        val artifact = PerfettoArtifactFactory(ByteArray(32) { 4 }).imported("import-1", trace, Instant.ofEpochSecond(20))

        assertEquals(ArtifactAcquisitionKind.IMPORT, artifact.provenance.acquisition.kind)
        assertEquals(ArtifactProducer.Unknown, artifact.provenance.producer)
        assertEquals(ArtifactCompleteness.UNKNOWN, artifact.completeness)
        assertEquals(null, artifact.requestedCapabilities)
    }
}
