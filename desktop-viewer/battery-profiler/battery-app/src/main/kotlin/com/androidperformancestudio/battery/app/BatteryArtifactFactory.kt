package com.androidperformancestudio.battery.app

import com.androidperformancestudio.battery.model.BatteryCapabilities
import com.androidperformancestudio.battery.model.BatteryCapabilityLevel
import com.androidperformancestudio.battery.model.BatterySession
import com.androidperformancestudio.battery.model.batteryDeviceLocalId
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
import com.androidperformancestudio.contracts.DeviceTargetIdentity
import com.androidperformancestudio.contracts.Sha256
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Creates privacy-safe envelopes for persisted battery evidence. */
internal class BatteryArtifactFactory(
    private val now: () -> Instant = Instant::now,
) {
    fun forSession(
        session: BatterySession,
        status: String,
        warnings: List<String> = emptyList(),
        evidenceMaterial: String? = null,
    ): CaptureArtifact {
        val requested = requestedCapabilities() + CAPTURE_COMPLETED
        val available =
            availableCapabilities(session.capabilities) +
                if (status == "COMPLETED") setOf(CAPTURE_COMPLETED) else emptySet()
        val missing = requested - available
        val interrupted = status == "INTERRUPTED" || status == "RUNNING"
        val limitations =
            buildList {
                missing.forEach {
                    add(
                        ArtifactLimitation(
                            it,
                            "capability-unavailable",
                            "Battery producer did not provide this capability.",
                        ),
                    )
                }
                if (interrupted) {
                    add(
                        ArtifactLimitation(
                            code = "interrupted",
                            message = "Battery experiment did not reach a normal completed state.",
                        ),
                    )
                }
            } + warnings.map { ArtifactLimitation(code = "warning", message = it) }
        val complete = !interrupted && missing.isEmpty()
        return CaptureArtifact(
            id = ArtifactId("battery-${UUID.randomUUID()}"),
            kind = ArtifactKind("battery.evidence"),
            location = ArtifactLocation("battery://session/${session.id}"),
            sha256 = digest(evidenceMaterial ?: (session.id + session.createdAt + session.packageName + session.uid)),
            format = ArtifactFormat("battery-stats", "session-v1"),
            provenance =
                ArtifactProvenance(
                    producer = ArtifactProducer.Known("Android BatteryStats"),
                    acquisition = acquisition(ArtifactAcquisitionKind.CAPTURE),
                ),
            device = DeviceTargetIdentity(batteryDeviceLocalId(session.deviceSerial)),
            requestedCapabilities = requested,
            availableCapabilities = available,
            completeness = if (complete) ArtifactCompleteness.COMPLETE else ArtifactCompleteness.PARTIAL,
            limitations = limitations,
        )
    }

    fun imported(input: Path): CaptureArtifact {
        val hash = ArtifactFileEvidence.sha256(input)
        return CaptureArtifact(
            id = ArtifactId("battery-${UUID.randomUUID()}"),
            kind = ArtifactKind("battery.evidence"),
            location = ArtifactLocation(input.toAbsolutePath().normalize().toString()),
            sha256 = hash,
            format = ArtifactFormat("battery-analysis-json", "v1"),
            provenance =
                ArtifactProvenance(
                    producer = ArtifactProducer.Unknown,
                    acquisition = acquisition(ArtifactAcquisitionKind.IMPORT),
                ),
        )
    }

    private fun requestedCapabilities(): Set<CapabilityId> = setOf(CHECKIN, HISTORY, ENERGY, UID_ATTRIBUTION)

    private fun availableCapabilities(capabilities: BatteryCapabilities): Set<CapabilityId> =
        buildSet {
            if (capabilities.checkin) add(CHECKIN)
            if (capabilities.history) add(HISTORY)
            if (capabilities.energy) add(ENERGY)
            if (capabilities.level != BatteryCapabilityLevel.UNAVAILABLE) add(UID_ATTRIBUTION)
        }

    private fun digest(value: String): Sha256 =
        Sha256(
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray())),
        )

    private fun acquisition(kind: ArtifactAcquisitionKind): ArtifactAcquisition =
        ArtifactAcquisition(
            kind,
            "Android Performance Studio",
            performedAtEpochMillis = now().toEpochMilli(),
        )

    companion object {
        val CHECKIN = CapabilityId("battery.checkin")
        val HISTORY = CapabilityId("battery.history")
        val ENERGY = CapabilityId("battery.energy")
        val UID_ATTRIBUTION = CapabilityId("battery.uid_attribution")
        val CAPTURE_COMPLETED = CapabilityId("battery.capture_completed")
    }
}
