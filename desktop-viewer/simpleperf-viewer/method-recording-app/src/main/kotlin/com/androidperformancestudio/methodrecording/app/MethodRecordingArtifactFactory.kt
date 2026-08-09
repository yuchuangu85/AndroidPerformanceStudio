package com.androidperformancestudio.methodrecording.app

import com.androidperformancestudio.contracts.ArtifactAcquisition
import com.androidperformancestudio.contracts.ArtifactAcquisitionKind
import com.androidperformancestudio.contracts.ArtifactCompleteness
import com.androidperformancestudio.contracts.ArtifactFileEvidence
import com.androidperformancestudio.contracts.ArtifactFormat
import com.androidperformancestudio.contracts.ArtifactId
import com.androidperformancestudio.contracts.ArtifactKind
import com.androidperformancestudio.contracts.ArtifactLimitation
import com.androidperformancestudio.contracts.ArtifactLocation
import com.androidperformancestudio.contracts.ArtifactProducer
import com.androidperformancestudio.contracts.ArtifactProvenance
import com.androidperformancestudio.contracts.CapabilityId
import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.contracts.ClockDomain
import com.androidperformancestudio.contracts.DeviceIdentityPseudonymizer
import com.androidperformancestudio.contracts.DeviceTargetIdentity
import com.androidperformancestudio.contracts.ProcessIdentity
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** Builds the versioned envelope for ART method trace evidence. */
internal class MethodRecordingArtifactFactory(
    private val deviceIdentity: DeviceIdentityPseudonymizer = DeviceIdentityPseudonymizer(),
    private val now: () -> Instant = Instant::now,
) {
    @Suppress("MaxLineLength")
    fun imported(file: Path): CaptureArtifact = base(file, ArtifactAcquisitionKind.IMPORT, serial = null, pid = null, packageName = null)

    fun captured(
        file: Path,
        serial: String,
        pid: Int,
        packageName: String,
        warningMessages: List<String>,
    ): CaptureArtifact {
        val deviceId = deviceIdentity.localId(serial)
        val limitations =
            warningMessages.map {
                ArtifactLimitation(capability = METHOD_TIMELINE, code = "capture-warning", message = it)
            }
        val requested = setOf(METHOD_TIMELINE)
        val complete = limitations.isEmpty()
        return base(file, ArtifactAcquisitionKind.CAPTURE, serial, pid, packageName).copy(
            device = DeviceTargetIdentity(localId = deviceId),
            process = ProcessIdentity(pid = pid, deviceLocalId = deviceId, packageName = packageName),
            requestedCapabilities = requested,
            availableCapabilities = if (complete) requested else emptySet(),
            completeness = if (complete) ArtifactCompleteness.COMPLETE else ArtifactCompleteness.PARTIAL,
            limitations = limitations.ifEmpty { emptyList() },
        )
    }

    private fun base(
        file: Path,
        kind: ArtifactAcquisitionKind,
        serial: String?,
        pid: Int?,
        packageName: String?,
    ): CaptureArtifact {
        val hash = ArtifactFileEvidence.sha256(file)
        val deviceId = serial?.let(deviceIdentity::localId)
        return CaptureArtifact(
            id = ArtifactId("method-${UUID.randomUUID()}"),
            kind = ArtifactKind("method.recording"),
            location = ArtifactLocation(file.toAbsolutePath().normalize().toString()),
            sha256 = hash,
            format = ArtifactFormat("art-method-trace", "v4"),
            provenance =
                ArtifactProvenance(
                    producer =
                        if (kind ==
                            ArtifactAcquisitionKind.CAPTURE
                        ) {
                            ArtifactProducer.Known("Android ART")
                        } else {
                            ArtifactProducer.Unknown
                        },
                    acquisition =
                        ArtifactAcquisition(
                            kind = kind,
                            application = "Android Performance Studio",
                            performedAtEpochMillis = now().toEpochMilli(),
                        ),
                ),
            device = deviceId?.let { DeviceTargetIdentity(localId = it) },
            process =
                if (pid != null &&
                    deviceId != null
                ) {
                    ProcessIdentity(pid, deviceLocalId = deviceId, packageName = packageName)
                } else {
                    null
                },
            clockDomains = setOf(ClockDomain("art.monotonic")),
            requestedCapabilities = null,
            availableCapabilities = emptySet(),
            completeness = ArtifactCompleteness.UNKNOWN,
        )
    }

    companion object {
        val METHOD_TIMELINE = CapabilityId("method.timeline")
    }
}
