package com.androidperformancestudio.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureArtifactJsonTest {
    @Test
    fun `round trips the complete capture artifact envelope`() {
        val bootTime = ClockDomain("android.boottime")
        val monotonic = ClockDomain("android.monotonic")
        val expectedTimeline = CapabilityId("frame.expected_timeline")
        val actualTimeline = CapabilityId("frame.actual_timeline")
        val deviceId = DeviceLocalId.fromRawSerial("device-8c71", "application-test-salt".toByteArray())
        val artifact =
            CaptureArtifact(
                id = ArtifactId("artifact-42"),
                kind = ArtifactKind("perfetto.trace"),
                location = ArtifactLocation("captures/frame-42.perfetto-trace"),
                sha256 = Sha256("a".repeat(64)),
                format = ArtifactFormat("perfetto-trace", "57.2"),
                provenance =
                    ArtifactProvenance(
                        producer = ArtifactProducer.Known("perfetto", "57.2", Sha256("b".repeat(64))),
                        acquisition =
                            ArtifactAcquisition(
                                kind = ArtifactAcquisitionKind.CAPTURE,
                                application = "android-performance-studio",
                                applicationVersion = "0.4.1",
                                performedAtEpochMillis = 1_723_145_678_000,
                            ),
                    ),
                capturedAt = ArtifactTimePoint(bootTime, 1_000_000),
                device =
                    DeviceTargetIdentity(
                        localId = deviceId,
                        manufacturer = "Google",
                        model = "Pixel 8",
                        buildFingerprint = "google/shiba/shiba:15/AP4A/user/release-keys",
                    ),
                process =
                    ProcessIdentity(
                        pid = 8123,
                        deviceLocalId = deviceId,
                        processName = "example.app",
                        packageName = "example.app",
                        startedAt = ArtifactTimePoint(monotonic, 900_000),
                    ),
                clockDomains = setOf(bootTime, monotonic),
                clockMappings =
                    setOf(
                        ClockMapping(
                            source = monotonic,
                            target = bootTime,
                            sourceReferenceNanos = 900_000,
                            targetReferenceNanos = 950_000,
                            errorBoundNanos = 2_000,
                            validFromSourceNanos = 800_000,
                            validToSourceNanos = 1_200_000,
                        ),
                    ),
                requestedCapabilities = setOf(expectedTimeline, actualTimeline),
                availableCapabilities = setOf(expectedTimeline, actualTimeline),
                completeness = ArtifactCompleteness.COMPLETE,
                warnings = listOf("Device symbols were resolved from the packaged symbol cache."),
            )

        val encoded = CaptureArtifactJson.encode(artifact)
        val decoded = CaptureArtifactJson.decode(encoded)

        assertEquals(artifact, decoded)
        assertEquals(ProcessIdentityStrength.STRONG, decoded.process?.strength)
        assertTrue(encoded.contains("\"strength\":\"STRONG\""))
        assertFalse(encoded.contains("rawSerial", ignoreCase = true))
        assertFalse(encoded.contains("device-8c71"))
    }

    @Test
    fun `round trips imported evidence with unknown producer and weak process identity`() {
        val artifact =
            CaptureArtifact(
                id = ArtifactId("import-1"),
                kind = ArtifactKind("heap.hprof"),
                location = ArtifactLocation("imports/heap.hprof"),
                sha256 = Sha256("c".repeat(64)),
                provenance =
                    ArtifactProvenance(
                        acquisition =
                            ArtifactAcquisition(
                                kind = ArtifactAcquisitionKind.IMPORT,
                                application = "android-performance-studio",
                                performedAtEpochMillis = 1_723_145_678_000,
                            ),
                    ),
                process = ProcessIdentity(pid = 42, packageName = "example.app"),
            )

        val encoded = CaptureArtifactJson.encode(artifact)
        val decoded = CaptureArtifactJson.decode(encoded)

        assertEquals(ArtifactProducer.Unknown, decoded.provenance.producer)
        assertEquals(ProcessIdentityStrength.WEAK, decoded.process?.strength)
        assertFalse(encoded.contains("rawSerial", ignoreCase = true))
    }

    @Test
    fun `exports a raw serial only with explicit sensitive identity preservation`() {
        val rawSerial = "ABC123"
        val artifact =
            minimalArtifact().copy(
                device =
                    DeviceTargetIdentity(
                        localId = DeviceLocalId.fromRawSerial(rawSerial, "application-test-salt".toByteArray()),
                        rawSerial = rawSerial,
                    ),
                privacy = ArtifactPrivacy(containsSensitiveIdentity = true, redactions = emptySet()),
            )

        val encoded = CaptureArtifactJson.encode(artifact)

        assertEquals(artifact, CaptureArtifactJson.decode(encoded))
        assertEquals(rawSerial, CaptureArtifactJson.decode(encoded).device?.rawSerial)
    }

    @Test
    fun `rejects a future contract version`() {
        val encoded = CaptureArtifactJson.encode(minimalArtifact()).replace("\"contractVersion\":1", "\"contractVersion\":2")

        assertFailsWith<IllegalArgumentException> { CaptureArtifactJson.decode(encoded) }
    }

    private fun minimalArtifact(): CaptureArtifact =
        CaptureArtifact(
            id = ArtifactId("artifact-1"),
            kind = ArtifactKind("perfetto.trace"),
            location = ArtifactLocation("capture.trace"),
            sha256 = Sha256("d".repeat(64)),
            provenance =
                ArtifactProvenance(
                    acquisition =
                        ArtifactAcquisition(
                            kind = ArtifactAcquisitionKind.IMPORT,
                            application = "android-performance-studio",
                            performedAtEpochMillis = 1,
                        ),
                ),
        )
}
