package com.androidperformancestudio.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CaptureArtifactValidationTest {
    private val localDeviceId = DeviceLocalId.fromRawSerial("serial", "application-test-salt".toByteArray())

    @Test
    fun `rejects malformed hashes and capability ids`() {
        assertFailsWith<IllegalArgumentException> { Sha256("A".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { Sha256("a".repeat(63)) }
        assertFailsWith<IllegalArgumentException> { CapabilityId("") }
        assertFailsWith<IllegalArgumentException> { CapabilityId("frame") }
        assertFailsWith<IllegalArgumentException> { CapabilityId("frame-expected") }
        assertFailsWith<IllegalArgumentException> { CapabilityId("Frame.expected_timeline") }
        assertFailsWith<IllegalArgumentException> { DeviceLocalId.fromRawSerial("serial", byteArrayOf()) }
    }

    @Test
    fun `rejects invalid clock mappings`() {
        val bootTime = ClockDomain("android.boottime")
        val monotonic = ClockDomain("android.monotonic")

        assertFailsWith<IllegalArgumentException> {
            ClockMapping(bootTime, bootTime, 1, 1, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ClockMapping(monotonic, bootTime, 10, 20, -1)
        }
        assertFailsWith<IllegalArgumentException> {
            ClockMapping(monotonic, bootTime, 10, 20, 0, validFromSourceNanos = 11)
        }
    }

    @Test
    fun `rejects inconsistent completeness`() {
        val requested = CapabilityId("frame.expected_timeline")

        assertFailsWith<IllegalArgumentException> {
            artifact(
                requested = setOf(requested),
                available = emptySet(),
                completeness = ArtifactCompleteness.COMPLETE,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            artifact(
                requested = setOf(requested),
                available = emptySet(),
                completeness = ArtifactCompleteness.PARTIAL,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            artifact(
                requested = setOf(requested),
                available = setOf(requested),
                completeness = ArtifactCompleteness.UNKNOWN,
            )
        }
    }

    @Test
    fun `partial evidence explains each missing capability`() {
        val expected = CapabilityId("frame.expected_timeline")
        val actual = CapabilityId("frame.actual_timeline")

        val partial =
            artifact(
                requested = setOf(expected, actual),
                available = setOf(expected),
                completeness = ArtifactCompleteness.PARTIAL,
                limitations = listOf(ArtifactLimitation(actual, "MISSING_ACTUAL", "Actual timeline was not recorded.")),
            )

        assertEquals(partial, CaptureArtifactJson.decode(CaptureArtifactJson.encode(partial)))
    }

    @Test
    fun `rejects inconsistent device and process identity privacy`() {
        assertFailsWith<IllegalArgumentException> {
            artifact(
                requested = null,
                available = emptySet(),
                completeness = ArtifactCompleteness.UNKNOWN,
            ).copy(device = DeviceTargetIdentity(localId = localDeviceId, rawSerial = "ABC123"))
        }
        assertFailsWith<IllegalArgumentException> {
            artifact(
                requested = null,
                available = emptySet(),
                completeness = ArtifactCompleteness.UNKNOWN,
            ).copy(
                device = DeviceTargetIdentity(localId = localDeviceId),
                process =
                    ProcessIdentity(
                        pid = 42,
                        deviceLocalId = DeviceLocalId.fromRawSerial("different", "application-test-salt".toByteArray()),
                    ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProcessIdentity(
                pid = 42,
                deviceLocalId = localDeviceId,
                startedAt = ArtifactTimePoint(ClockDomain("android.boottime"), 1),
                strength = ProcessIdentityStrength.WEAK,
            )
        }
    }

    private fun artifact(
        requested: Set<CapabilityId>?,
        available: Set<CapabilityId>,
        completeness: ArtifactCompleteness,
        limitations: List<ArtifactLimitation> = emptyList(),
    ): CaptureArtifact =
        CaptureArtifact(
            id = ArtifactId("artifact-validation"),
            kind = ArtifactKind("perfetto.trace"),
            location = ArtifactLocation("capture.trace"),
            sha256 = Sha256("e".repeat(64)),
            provenance =
                ArtifactProvenance(
                    acquisition =
                        ArtifactAcquisition(
                            kind = ArtifactAcquisitionKind.IMPORT,
                            application = "android-performance-studio",
                            performedAtEpochMillis = 1,
                        ),
                ),
            requestedCapabilities = requested,
            availableCapabilities = available,
            completeness = completeness,
            limitations = limitations,
        )
}
