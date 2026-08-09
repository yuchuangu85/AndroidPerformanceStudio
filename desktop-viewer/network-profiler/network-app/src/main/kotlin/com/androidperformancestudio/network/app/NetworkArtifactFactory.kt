package com.androidperformancestudio.network.app

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
import com.androidperformancestudio.contracts.ArtifactTimePoint
import com.androidperformancestudio.contracts.CapabilityId
import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.contracts.ClockDomain
import com.androidperformancestudio.contracts.DeviceIdentityPseudonymizer
import com.androidperformancestudio.contracts.DeviceTargetIdentity
import com.androidperformancestudio.contracts.ProcessIdentity
import com.androidperformancestudio.contracts.Sha256
import com.androidperformancestudio.network.model.EvidenceCompleteness
import com.androidperformancestudio.network.model.NetworkCaptureResult
import com.androidperformancestudio.network.model.NetworkSessionStatus
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Capture Artifact metadata for redacted Network Agent evidence and HAR imports. */
internal class NetworkArtifactFactory(
    private val deviceIdentity: DeviceIdentityPseudonymizer = DeviceIdentityPseudonymizer(),
    private val now: () -> Instant = Instant::now,
) {
    fun agent(result: NetworkCaptureResult): CaptureArtifact = build(
        result = result,
        location = "network://session/${result.session.id}",
        hash = digest(result.evidenceMaterial()),
        acquisitionKind = ArtifactAcquisitionKind.CAPTURE,
        producer = ArtifactProducer.Known("Android Performance Studio Network Agent", result.session.sourceFormatVersion),
        format = ArtifactFormat("aps-network-agent-events", result.session.sourceFormatVersion),
    )

    fun har(input: Path, result: NetworkCaptureResult): CaptureArtifact = build(
        result = result,
        location = input.toAbsolutePath().normalize().toString(),
        hash = ArtifactFileEvidence.sha256(input),
        acquisitionKind = ArtifactAcquisitionKind.IMPORT,
        producer = result.session.sourceProducer?.takeIf(String::isNotBlank)?.let { ArtifactProducer.Known(it) } ?: ArtifactProducer.Unknown,
        format = ArtifactFormat("http-archive", result.session.sourceFormatVersion),
    )

    private fun build(
        result: NetworkCaptureResult,
        location: String,
        hash: Sha256,
        acquisitionKind: ArtifactAcquisitionKind,
        producer: ArtifactProducer,
        format: ArtifactFormat,
    ): CaptureArtifact {
        val session = result.session
        val complete = session.status == NetworkSessionStatus.COMPLETE && session.completeness.status == EvidenceCompleteness.COMPLETE
        val available = buildSet {
            if (result.calls.isNotEmpty()) add(HTTP_CALLS)
            if (result.calls.any { call -> call.exchanges.any { it.phases.isNotEmpty() } }) add(PHASE_TIMING)
            add(PRIVACY_REDACTION)
            if (complete) add(COMPLETE_SESSION)
        }
        val requested = setOf(HTTP_CALLS, PHASE_TIMING, PRIVACY_REDACTION, COMPLETE_SESSION)
        val missing = requested - available
        val isImport = acquisitionKind == ArtifactAcquisitionKind.IMPORT
        val sourceClock = ClockDomain("device.monotonic")
        val hostClock = ClockDomain("host.monotonic")
        val deviceId = session.deviceSerial?.takeIf(String::isNotBlank)?.let(deviceIdentity::localId)
        return CaptureArtifact(
            id = ArtifactId("network-${UUID.randomUUID()}"),
            kind = ArtifactKind("network.http"),
            location = ArtifactLocation(location),
            sha256 = hash,
            format = format,
            provenance = ArtifactProvenance(
                producer = producer,
                acquisition = ArtifactAcquisition(acquisitionKind, "Android Performance Studio", performedAtEpochMillis = now().toEpochMilli()),
            ),
            capturedAt = session.sourceTimeOriginNs.takeIf { it >= 0 }?.let { ArtifactTimePoint(sourceClock, it) },
            device = deviceId?.let { DeviceTargetIdentity(it) },
            process = session.coverage.processIds.singleOrNull()?.takeIf { it > 0 }?.let { ProcessIdentity(it, deviceId, packageName = session.packageName) },
            clockDomains = if (session.clockMapping == null) setOf(sourceClock) else setOf(sourceClock, hostClock),
            clockMappings = session.clockMapping?.let {
                setOf(com.androidperformancestudio.contracts.ClockMapping(sourceClock, hostClock, it.sourceMonotonicReferenceNs, it.hostMonotonicReferenceNs, it.errorBoundNs))
            }.orEmpty(),
            requestedCapabilities = if (isImport) null else requested,
            availableCapabilities = available,
            completeness =
                if (isImport) {
                    ArtifactCompleteness.UNKNOWN
                } else if (missing.isEmpty()) {
                    ArtifactCompleteness.COMPLETE
                } else {
                    ArtifactCompleteness.PARTIAL
                },
            limitations = missing.map { capability ->
                val message = if (capability == COMPLETE_SESSION) "Network session is partial or interrupted." else "Network evidence did not provide this capability."
                ArtifactLimitation(capability, "capability-unavailable", message)
            } + session.coverage.knownLimitations.map { ArtifactLimitation(code = "coverage-limitation", message = it) },
            warnings = session.warnings,
        )
    }

    /**
     * The Agent has no standalone raw file, so its immutable, already-redacted domain snapshot is
     * the content boundary. Kotlin data-class rendering includes every constructor field, including
     * coverage, completeness, exchanges, headers, TLS, timings, failures, raw events, and warnings.
     */
    private fun NetworkCaptureResult.evidenceMaterial(): String = toString()

    private fun digest(value: String): Sha256 =
        Sha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray())))

    companion object {
        val HTTP_CALLS = CapabilityId("network.http_calls")
        val PHASE_TIMING = CapabilityId("network.phase_timing")
        val PRIVACY_REDACTION = CapabilityId("network.privacy_redaction")
        val COMPLETE_SESSION = CapabilityId("network.complete_session")
    }
}
