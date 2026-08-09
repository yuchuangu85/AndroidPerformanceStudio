package com.androidperformancestudio.methodrecording.app

import com.androidperformancestudio.contracts.ArtifactAcquisitionKind
import com.androidperformancestudio.contracts.ArtifactCompleteness
import com.androidperformancestudio.contracts.ArtifactProducer
import com.androidperformancestudio.contracts.CaptureArtifactJson
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MethodRecordingArtifactFactoryTest {
    @Test
    fun `import keeps producer and capture intent unknown`() {
        val trace = Files.createTempFile("method-import", ".trace").also { Files.writeString(it, "trace") }

        val artifact = MethodRecordingArtifactFactory { Instant.EPOCH }.imported(trace)

        assertEquals(ArtifactAcquisitionKind.IMPORT, artifact.provenance.acquisition.kind)
        assertEquals(ArtifactProducer.Unknown, artifact.provenance.producer)
        assertEquals(ArtifactCompleteness.UNKNOWN, artifact.completeness)
        assertEquals(null, artifact.requestedCapabilities)
    }

    @Test
    fun `capture warning produces privacy safe PARTIAL evidence with a capability limitation`() {
        val trace = Files.createTempFile("method-capture", ".trace").also { Files.writeString(it, "trace") }

        val artifact =
            MethodRecordingArtifactFactory { Instant.EPOCH }.captured(
                file = trace,
                serial = "raw-device-serial",
                pid = 42,
                packageName = "dev.example.app",
                warningMessages = listOf("Recording stopped before the normal completion marker."),
            )

        assertEquals(ArtifactCompleteness.PARTIAL, artifact.completeness)
        assertFalse(MethodRecordingArtifactFactory.METHOD_TIMELINE in artifact.availableCapabilities)
        assertEquals(MethodRecordingArtifactFactory.METHOD_TIMELINE, artifact.limitations.single().capability)
        assertTrue(artifact.device?.rawSerial == null)
        assertFalse(CaptureArtifactJson.encode(artifact).contains("raw-device-serial"))
    }
}
