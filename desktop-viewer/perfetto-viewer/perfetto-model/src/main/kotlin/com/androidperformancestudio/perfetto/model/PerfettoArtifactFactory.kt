package com.androidperformancestudio.perfetto.model

import com.androidperformancestudio.contracts.ArtifactAcquisition
import com.androidperformancestudio.contracts.ArtifactAcquisitionKind
import com.androidperformancestudio.contracts.ArtifactCompleteness
import com.androidperformancestudio.contracts.ArtifactFileEvidence
import com.androidperformancestudio.contracts.ArtifactFormat
import com.androidperformancestudio.contracts.ArtifactId
import com.androidperformancestudio.contracts.ArtifactKind
import com.androidperformancestudio.contracts.ArtifactLocation
import com.androidperformancestudio.contracts.ArtifactProducer
import com.androidperformancestudio.contracts.ArtifactProvenance
import com.androidperformancestudio.contracts.ArtifactTimePoint
import com.androidperformancestudio.contracts.CapabilityId
import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.contracts.ClockDomain
import com.androidperformancestudio.contracts.DeviceIdentitySaltStore
import com.androidperformancestudio.contracts.DeviceLocalId
import com.androidperformancestudio.contracts.DeviceTargetIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

object CaptureSystemActivitiesCapabilities {
    val RAW_TRACE = CapabilityId("system_activities.raw_trace")
}

class PerfettoArtifactFactory(
    private val applicationSalt: ByteArray = DeviceIdentitySaltStore().loadOrCreate(),
    private val applicationVersion: String = "0.1.0",
) {
    fun captured(
        id: String,
        traceFile: Path,
        deviceSerial: String,
        deviceModel: String,
        capturedAt: Instant,
    ): CaptureArtifact {
        require(Files.size(traceFile) > 0) { "captured Perfetto trace must not be empty" }
        val capability = CaptureSystemActivitiesCapabilities.RAW_TRACE
        val clock = ClockDomain("utc.epoch")
        return CaptureArtifact(
            id = ArtifactId(id),
            kind = PERFETTO_TRACE,
            location = ArtifactLocation(traceFile.toAbsolutePath().normalize().toString()),
            sha256 = ArtifactFileEvidence.sha256(traceFile),
            format = PERFETTO_FORMAT,
            provenance =
                ArtifactProvenance(
                    producer = ArtifactProducer.Known("android-perfetto"),
                    acquisition = acquisition(ArtifactAcquisitionKind.CAPTURE, capturedAt),
                ),
            capturedAt = ArtifactTimePoint(clock, capturedAt.toEpochMilli() * NANOS_PER_MILLISECOND),
            device =
                DeviceTargetIdentity(
                    localId = DeviceLocalId.fromRawSerial(deviceSerial, applicationSalt),
                    model = deviceModel.takeIf(String::isNotBlank),
                ),
            clockDomains = setOf(clock),
            requestedCapabilities = setOf(capability),
            availableCapabilities = setOf(capability),
            completeness = ArtifactCompleteness.COMPLETE,
        )
    }

    fun imported(
        id: String,
        traceFile: Path,
        importedAt: Instant,
    ): CaptureArtifact =
        CaptureArtifact(
            id = ArtifactId(id),
            kind = PERFETTO_TRACE,
            location = ArtifactLocation(traceFile.toAbsolutePath().normalize().toString()),
            sha256 = ArtifactFileEvidence.sha256(traceFile),
            format = PERFETTO_FORMAT,
            provenance = ArtifactProvenance(acquisition = acquisition(ArtifactAcquisitionKind.IMPORT, importedAt)),
            availableCapabilities = setOf(CaptureSystemActivitiesCapabilities.RAW_TRACE),
            completeness = ArtifactCompleteness.UNKNOWN,
        )

    private fun acquisition(
        kind: ArtifactAcquisitionKind,
        at: Instant,
    ): ArtifactAcquisition =
        ArtifactAcquisition(
            kind = kind,
            application = "android-performance-studio",
            applicationVersion = applicationVersion,
            performedAtEpochMillis = at.toEpochMilli(),
        )

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val PERFETTO_TRACE = ArtifactKind("perfetto.trace")
        val PERFETTO_FORMAT = ArtifactFormat("perfetto-trace")
    }
}
