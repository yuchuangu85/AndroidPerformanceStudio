package com.androidperformancestudio.frame.app

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
import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.contracts.ClockDomain
import com.androidperformancestudio.contracts.DeviceIdentityPseudonymizer
import com.androidperformancestudio.contracts.DeviceTargetIdentity
import com.androidperformancestudio.contracts.ProcessIdentity
import com.androidperformancestudio.frame.analysis.FrameTimelineResult
import com.androidperformancestudio.frame.analysis.FrameTimelineTraceAdapter
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.perfetto.TraceAnalysisContexts
import com.androidperformancestudio.platform.perfetto.TraceProcessorTool
import com.androidperformancestudio.platform.perfetto.TraceProcessorToolResolver
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

internal sealed interface FrameTimelineProcessingResult {
    data class Success(
        val result: FrameTimelineResult,
        val tool: TraceProcessorTool,
    ) : FrameTimelineProcessingResult

    data class Failure(
        val reason: String,
    ) : FrameTimelineProcessingResult
}

internal fun interface FrameTimelineArtifactAnalyzer {
    suspend fun analyze(
        file: Path,
        artifact: CaptureArtifact,
    ): FrameTimelineProcessingResult
}

internal class PerfettoFrameTimelineArtifactAnalyzer(
    private val resolver: TraceProcessorToolResolver = TraceProcessorToolResolver(),
    private val adapter: FrameTimelineTraceAdapter = FrameTimelineTraceAdapter(),
) : FrameTimelineArtifactAnalyzer {
    @Suppress("ReturnCount")
    override suspend fun analyze(
        file: Path,
        artifact: CaptureArtifact,
    ): FrameTimelineProcessingResult {
        val tool =
            when (val resolved = resolver.resolve()) {
                is StudioResult.Success -> resolved.value
                is StudioResult.Failure ->
                    return FrameTimelineProcessingResult.Failure(
                        "${resolved.error.code}: ${resolved.error.message}",
                    )
            }
        val context =
            when (val opened = TraceAnalysisContexts(tool).open(artifact, file)) {
                is StudioResult.Success -> opened.value
                is StudioResult.Failure ->
                    return FrameTimelineProcessingResult.Failure(
                        "${opened.error.code}: ${opened.error.message}",
                    )
            }
        return try {
            when (val queried = context.query(adapter.timelineQuery(artifact.process?.pid))) {
                is StudioResult.Success ->
                    FrameTimelineProcessingResult.Success(adapter.map(queried.value), tool)
                is StudioResult.Failure ->
                    FrameTimelineProcessingResult.Failure(
                        "${queried.error.code}: ${queried.error.message}",
                    )
            }
        } finally {
            context.close()
        }
    }
}

internal class FrameCaptureArtifactFactory(
    private val deviceIdentity: DeviceIdentityPseudonymizer = DeviceIdentityPseudonymizer(),
    private val now: () -> Instant = Instant::now,
) {
    fun imported(file: Path): CaptureArtifact =
        CaptureArtifact(
            id = ArtifactId("frame-${UUID.randomUUID()}"),
            kind = ArtifactKind("frame.timeline"),
            location = ArtifactLocation(file.toAbsolutePath().normalize().toString()),
            sha256 = ArtifactFileEvidence.sha256(file),
            format = ArtifactFormat("perfetto", "frame_timeline"),
            provenance =
                ArtifactProvenance(
                    producer = ArtifactProducer.Unknown,
                    acquisition =
                        ArtifactAcquisition(
                            ArtifactAcquisitionKind.IMPORT,
                            "Android Performance Studio",
                            performedAtEpochMillis = now().toEpochMilli(),
                        ),
                ),
        )

    fun captured(
        file: Path,
        serial: String,
        processId: Int,
        packageName: String,
    ): CaptureArtifact {
        val hash = ArtifactFileEvidence.sha256(file)
        val deviceId = deviceIdentity.localId(serial)
        return CaptureArtifact(
            id = ArtifactId("frame-${UUID.randomUUID()}"),
            kind = ArtifactKind("frame.timeline"),
            location = ArtifactLocation(file.toAbsolutePath().normalize().toString()),
            sha256 = hash,
            format = ArtifactFormat("perfetto", "frame_timeline"),
            provenance =
                ArtifactProvenance(
                    producer = ArtifactProducer.Known("Android Perfetto traced"),
                    acquisition =
                        ArtifactAcquisition(
                            ArtifactAcquisitionKind.CAPTURE,
                            "Android Performance Studio",
                            performedAtEpochMillis = now().toEpochMilli(),
                        ),
                ),
            device = DeviceTargetIdentity(deviceId),
            process = ProcessIdentity(processId, deviceId, packageName = packageName),
            clockDomains = setOf(PERFETTO_TRACE_CLOCK),
        )
    }

    fun analyzed(
        artifact: CaptureArtifact,
        tool: TraceProcessorTool,
        availableCapabilities: Set<com.androidperformancestudio.contracts.CapabilityId>,
    ): CaptureArtifact {
        val isImport = artifact.provenance.acquisition.kind == ArtifactAcquisitionKind.IMPORT
        val missing = FrameTimelineTraceAdapter.FRAME_TIMELINE_CAPABILITIES - availableCapabilities
        return artifact.copy(
            provenance =
                artifact.provenance.copy(
                    processors =
                        artifact.provenance.processors +
                            ArtifactProducer.Known(
                                "Perfetto Trace Processor",
                                tool.version,
                                com.androidperformancestudio.contracts.Sha256(tool.sha256),
                            ),
                ),
            requestedCapabilities = if (isImport) null else FrameTimelineTraceAdapter.FRAME_TIMELINE_CAPABILITIES,
            availableCapabilities = availableCapabilities,
            completeness =
                if (isImport) {
                    ArtifactCompleteness.UNKNOWN
                } else if (missing.isEmpty()) {
                    ArtifactCompleteness.COMPLETE
                } else {
                    ArtifactCompleteness.PARTIAL
                },
            limitations =
                missing.map {
                    com.androidperformancestudio.contracts.ArtifactLimitation(
                        it,
                        "frame-correlation-unavailable",
                        "The trace did not expose an app-to-SurfaceFlinger correlation for this capability.",
                    )
                },
        )
    }

    private companion object {
        val PERFETTO_TRACE_CLOCK = ClockDomain("perfetto.trace_time")
    }
}
