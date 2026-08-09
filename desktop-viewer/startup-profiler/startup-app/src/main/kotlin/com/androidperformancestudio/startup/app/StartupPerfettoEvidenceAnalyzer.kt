package com.androidperformancestudio.startup.app

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
import com.androidperformancestudio.contracts.DeviceIdentityPseudonymizer
import com.androidperformancestudio.contracts.DeviceTargetIdentity
import com.androidperformancestudio.contracts.ProcessIdentity
import com.androidperformancestudio.contracts.Sha256
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.perfetto.TraceAnalysisContexts
import com.androidperformancestudio.platform.perfetto.TraceProcessorToolResolver
import com.androidperformancestudio.startup.analysis.StartupPerfettoTraceAdapter
import com.androidperformancestudio.startup.model.StartupPerfettoRootCauseEvidence
import com.androidperformancestudio.startup.model.StartupRun
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

internal data class StartupPerfettoProcessingResult(
    val artifact: CaptureArtifact,
    val evidence: StartupPerfettoRootCauseEvidence,
)

internal class StartupPerfettoEvidenceAnalyzer(
    private val resolver: TraceProcessorToolResolver = TraceProcessorToolResolver(),
    private val adapter: StartupPerfettoTraceAdapter = StartupPerfettoTraceAdapter(),
) {
    @Suppress("ReturnCount")
    suspend fun analyze(run: StartupRun): StudioResult<StartupPerfettoProcessingResult> {
        val trace = requireNotNull(run.traceEvidence)
        val file = Path.of(requireNotNull(trace.file))
        require(Files.isRegularFile(file)) { "Startup trace does not exist: $file" }
        val artifact = StartupTraceArtifactFactory().captured(run, file)
        val tool =
            when (val resolved = resolver.resolve()) {
                is StudioResult.Success -> resolved.value
                is StudioResult.Failure -> return resolved
            }
        val analyzedArtifact =
            artifact.copy(
                provenance =
                    artifact.provenance.copy(
                        processors =
                            artifact.provenance.processors +
                                ArtifactProducer.Known(
                                    "Perfetto Trace Processor",
                                    tool.version,
                                    Sha256(tool.sha256),
                                ),
                    ),
            )
        val context =
            when (val opened = TraceAnalysisContexts(tool).open(analyzedArtifact, file)) {
                is StudioResult.Success -> opened.value
                is StudioResult.Failure -> return opened
            }
        return try {
            val scheduling = context.query(adapter.schedulingQuery(run.processIdAfter)).valueOrReturn()
            val binder = context.query(adapter.binderQuery(run.processIdAfter)).valueOrReturn()
            val main = context.query(adapter.mainThreadQuery(run.processIdAfter)).valueOrReturn()
            val frames = context.query(adapter.frameQuery(run.processIdAfter)).valueOrReturn()
            StudioResult.Success(
                StartupPerfettoProcessingResult(
                    analyzedArtifact,
                    adapter.map(scheduling, binder, main, frames, analyzedArtifact.clockMappings.singleOrNull()),
                ),
            )
        } catch (failure: QueryFailure) {
            failure.result
        } finally {
            context.close()
        }
    }

    private fun <T> StudioResult<T>.valueOrReturn(): T =
        when (this) {
            is StudioResult.Success -> value
            is StudioResult.Failure -> throw QueryFailure(this)
        }

    private class QueryFailure(
        val result: StudioResult.Failure,
    ) : RuntimeException()
}

internal class StartupTraceArtifactFactory(
    private val deviceIdentity: DeviceIdentityPseudonymizer = DeviceIdentityPseudonymizer(),
    private val now: () -> Instant = Instant::now,
) {
    fun captured(
        run: StartupRun,
        file: Path,
    ): CaptureArtifact {
        val hash = ArtifactFileEvidence.sha256(file)
        val truncated = run.traceEvidence?.truncated == true
        val completeCapability = CapabilityId("startup.trace_complete")
        val requested = StartupPerfettoTraceAdapter.ALL + completeCapability
        val available = StartupPerfettoTraceAdapter.ALL + if (truncated) emptySet() else setOf(completeCapability)
        val deviceId = run.context?.deviceSerial?.let(deviceIdentity::localId)
        val existing = run.traceEvidence?.artifact?.takeIf { it.sha256 == hash }
        if (existing != null) {
            return existing.copy(
                location = ArtifactLocation(file.toAbsolutePath().normalize().toString()),
                requestedCapabilities = requested,
                availableCapabilities = available,
                completeness = if (truncated) ArtifactCompleteness.PARTIAL else ArtifactCompleteness.COMPLETE,
                limitations =
                    if (truncated) {
                        existing.limitations + truncatedLimitation(completeCapability)
                    } else {
                        existing.limitations
                    },
            )
        }
        return CaptureArtifact(
            id = ArtifactId("startup-${UUID.randomUUID()}"),
            kind = ArtifactKind("startup.perfetto"),
            location = ArtifactLocation(file.toAbsolutePath().normalize().toString()),
            sha256 = hash,
            format = ArtifactFormat("perfetto-trace"),
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
            device = deviceId?.let { DeviceTargetIdentity(it) },
            process =
                run.processIdAfter
                    ?.takeIf { it > 0 }
                    ?.let { ProcessIdentity(it, deviceId, packageName = run.context?.packageName) },
            clockDomains = setOf(StartupPerfettoTraceAdapter.PERFETTO_TRACE_CLOCK),
            requestedCapabilities = requested,
            availableCapabilities = available,
            completeness = if (truncated) ArtifactCompleteness.PARTIAL else ArtifactCompleteness.COMPLETE,
            limitations = if (truncated) listOf(truncatedLimitation(completeCapability)) else emptyList(),
            warnings = run.warnings,
        )
    }

    private fun truncatedLimitation(capability: CapabilityId): ArtifactLimitation =
        ArtifactLimitation(
            capability,
            "trace-truncated",
            "Perfetto capture ended before the requested stop boundary.",
        )
}
