package com.androidperformancestudio.memory.app

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
import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.contracts.CaptureArtifactJson
import com.androidperformancestudio.contracts.DeviceIdentityPseudonymizer
import com.androidperformancestudio.contracts.DeviceTargetIdentity
import com.androidperformancestudio.contracts.ProcessIdentity
import com.androidperformancestudio.memory.analysis.JavaHeapCapabilities
import com.androidperformancestudio.memory.analysis.JavaHeapTraceProcessorAdapter
import com.androidperformancestudio.memory.analysis.NativeHeapTraceProcessorAdapter
import com.androidperformancestudio.memory.model.NativeHeapAnalysis
import com.androidperformancestudio.memory.model.NativeHeapCapabilities
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.perfetto.TraceAnalysisContexts
import com.androidperformancestudio.platform.perfetto.TraceProcessorTool
import com.androidperformancestudio.platform.perfetto.TraceProcessorToolResolver
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

internal sealed interface NativeHeapProcessingResult {
    data class Success(
        val analysis: NativeHeapAnalysis,
        val availableCapabilities: Set<com.androidperformancestudio.contracts.CapabilityId>,
        val tool: TraceProcessorTool,
    ) : NativeHeapProcessingResult

    data class Unavailable(
        val reason: String,
    ) : NativeHeapProcessingResult

    data class Failure(
        val reason: String,
    ) : NativeHeapProcessingResult
}

internal fun interface NativeHeapArtifactAnalyzer {
    suspend fun analyze(
        file: Path,
        artifact: CaptureArtifact,
    ): NativeHeapProcessingResult
}

internal class PerfettoNativeHeapArtifactAnalyzer(
    private val resolver: TraceProcessorToolResolver = TraceProcessorToolResolver(),
    private val adapter: NativeHeapTraceProcessorAdapter = NativeHeapTraceProcessorAdapter(),
) : NativeHeapArtifactAnalyzer {
    @Suppress("ReturnCount")
    override suspend fun analyze(
        file: Path,
        artifact: CaptureArtifact,
    ): NativeHeapProcessingResult {
        val tool =
            when (val resolved = resolver.resolve()) {
                is StudioResult.Success -> resolved.value
                is StudioResult.Failure -> {
                    val reason = "${resolved.error.code}: ${resolved.error.message}"
                    return if (resolved.error.code in FALLBACK_TOOL_ERRORS) {
                        NativeHeapProcessingResult.Unavailable(reason)
                    } else {
                        NativeHeapProcessingResult.Failure(reason)
                    }
                }
            }
        val context =
            when (val opened = TraceAnalysisContexts(tool).open(artifact, file)) {
                is StudioResult.Success -> opened.value
                is StudioResult.Failure -> {
                    val reason = "${opened.error.code}: ${opened.error.message}"
                    return if (opened.error.code == "TRACE_PROCESSOR_START_FAILED") {
                        NativeHeapProcessingResult.Unavailable(reason)
                    } else {
                        NativeHeapProcessingResult.Failure(reason)
                    }
                }
            }
        return try {
            val allocations =
                when (val queried = context.query(adapter.allocationQuery)) {
                    is StudioResult.Success -> queried.value
                    is StudioResult.Failure -> return NativeHeapProcessingResult.Failure(
                        "${queried.error.code}: ${queried.error.message}",
                    )
                }
            val stacks =
                when (val queried = context.query(adapter.callStackQuery)) {
                    is StudioResult.Success -> queried.value
                    is StudioResult.Failure -> return NativeHeapProcessingResult.Failure(
                        "${queried.error.code}: ${queried.error.message}",
                    )
                }
            val mapped = adapter.map(allocations, stacks)
            NativeHeapProcessingResult.Success(mapped.analysis, mapped.availableCapabilities, tool)
        } finally {
            context.close()
        }
    }

    private companion object {
        val FALLBACK_TOOL_ERRORS: Set<String> =
            setOf(
                "TRACE_PROCESSOR_HOST_UNSUPPORTED",
                "TRACE_PROCESSOR_NOT_FOUND",
                "TRACE_PROCESSOR_NOT_EXECUTABLE",
                "TRACE_PROCESSOR_CHECKSUM_MISMATCH",
                "TRACE_PROCESSOR_OVERRIDE_INVALID",
                "TRACE_PROCESSOR_OVERRIDE_PROBE_FAILED",
                "TRACE_PROCESSOR_INCOMPATIBLE",
            )
    }
}

internal sealed interface JavaHeapProcessingResult {
    data class Success(
        val heapDump: com.androidperformancestudio.memory.model.HeapDump,
        val availableCapabilities: Set<com.androidperformancestudio.contracts.CapabilityId>,
        val tool: TraceProcessorTool,
    ) : JavaHeapProcessingResult

    data class Unavailable(
        val reason: String,
    ) : JavaHeapProcessingResult

    data class Failure(
        val reason: String,
    ) : JavaHeapProcessingResult
}

internal fun interface JavaHeapArtifactAnalyzer {
    suspend fun analyze(
        file: Path,
        artifact: CaptureArtifact,
    ): JavaHeapProcessingResult
}

internal class PerfettoJavaHeapArtifactAnalyzer(
    private val resolver: TraceProcessorToolResolver = TraceProcessorToolResolver(),
    private val adapter: JavaHeapTraceProcessorAdapter = JavaHeapTraceProcessorAdapter(),
) : JavaHeapArtifactAnalyzer {
    @Suppress("ReturnCount")
    override suspend fun analyze(
        file: Path,
        artifact: CaptureArtifact,
    ): JavaHeapProcessingResult {
        val tool =
            when (val resolved = resolver.resolve()) {
                is StudioResult.Success -> resolved.value
                is StudioResult.Failure -> {
                    val reason = "${resolved.error.code}: ${resolved.error.message}"
                    return if (resolved.error.code in FALLBACK_TOOL_ERRORS) {
                        JavaHeapProcessingResult.Unavailable(reason)
                    } else {
                        JavaHeapProcessingResult.Failure(reason)
                    }
                }
            }
        val context =
            when (val opened = TraceAnalysisContexts(tool).open(artifact, file)) {
                is StudioResult.Success -> opened.value
                is StudioResult.Failure -> {
                    val reason = "${opened.error.code}: ${opened.error.message}"
                    return if (opened.error.code == "TRACE_PROCESSOR_START_FAILED") {
                        JavaHeapProcessingResult.Unavailable(reason)
                    } else {
                        JavaHeapProcessingResult.Failure(reason)
                    }
                }
            }
        return try {
            val classes =
                when (val result = context.query(adapter.classQuery)) {
                    is StudioResult.Success -> result.value
                    is StudioResult.Failure ->
                        return JavaHeapProcessingResult.Failure("${result.error.code}: ${result.error.message}")
                }
            val objects =
                when (val result = context.query(adapter.objectQuery)) {
                    is StudioResult.Success -> result.value
                    is StudioResult.Failure ->
                        return JavaHeapProcessingResult.Failure("${result.error.code}: ${result.error.message}")
                }
            val references =
                when (val result = context.query(adapter.referenceQuery)) {
                    is StudioResult.Success -> result.value
                    is StudioResult.Failure ->
                        return JavaHeapProcessingResult.Failure("${result.error.code}: ${result.error.message}")
                }
            val mapped = adapter.map(classes, objects, references)
            JavaHeapProcessingResult.Success(mapped.heapDump, mapped.availableCapabilities, tool)
        } finally {
            context.close()
        }
    }

    private companion object {
        val FALLBACK_TOOL_ERRORS: Set<String> =
            setOf(
                "TRACE_PROCESSOR_HOST_UNSUPPORTED",
                "TRACE_PROCESSOR_NOT_FOUND",
                "TRACE_PROCESSOR_NOT_EXECUTABLE",
                "TRACE_PROCESSOR_CHECKSUM_MISMATCH",
                "TRACE_PROCESSOR_OVERRIDE_INVALID",
                "TRACE_PROCESSOR_OVERRIDE_PROBE_FAILED",
                "TRACE_PROCESSOR_INCOMPATIBLE",
            )
    }
}

@Suppress("TooManyFunctions")
internal class MemoryCaptureArtifactFactory(
    @Suppress("UNUSED_PARAMETER") dataRoot: Path,
    private val now: () -> Instant = Instant::now,
    private val deviceIdentity: DeviceIdentityPseudonymizer = DeviceIdentityPseudonymizer(),
) {
    fun nativeCapture(
        id: String,
        file: Path,
        rawSerial: String,
        processId: Int,
        packageName: String,
    ): CaptureArtifact {
        val localId = deviceIdentity.localId(rawSerial)
        return baseNativeArtifact(id, file, ArtifactAcquisitionKind.CAPTURE).copy(
            provenance =
                ArtifactProvenance(
                    producer = ArtifactProducer.Known("Android heapprofd"),
                    acquisition = acquisition(ArtifactAcquisitionKind.CAPTURE),
                ),
            device = DeviceTargetIdentity(localId),
            process = ProcessIdentity(processId, localId, processName = packageName, packageName = packageName),
            requestedCapabilities = NativeHeapCapabilities.ALL,
            availableCapabilities = emptySet(),
            // Registered before analysis; completion is finalized only after a successful
            // authoritative query or a deliberately visible wire fallback.
            completeness = ArtifactCompleteness.PARTIAL,
            limitations = NativeHeapCapabilities.ALL.map(::pendingLimitation),
        )
    }

    fun nativeImport(
        id: String,
        file: Path,
    ): CaptureArtifact = baseNativeArtifact(id, file, ArtifactAcquisitionKind.IMPORT)

    fun javaImport(
        id: String,
        file: Path,
    ): CaptureArtifact =
        CaptureArtifact(
            id = ArtifactId(id),
            kind = ArtifactKind("memory.java_heap"),
            location = ArtifactLocation(file.toAbsolutePath().normalize().toString()),
            sha256 = ArtifactFileEvidence.sha256(file),
            format = ArtifactFormat("perfetto", "java_hprof"),
            provenance = ArtifactProvenance(acquisition = acquisition(ArtifactAcquisitionKind.IMPORT)),
        )

    fun javaProcessorResult(
        artifact: CaptureArtifact,
        result: JavaHeapProcessingResult.Success,
    ): CaptureArtifact =
        artifact.copy(
            provenance =
                artifact.provenance.copy(
                    processors =
                        artifact.provenance.processors +
                            ArtifactProducer.Known(
                                "Perfetto Trace Processor",
                                result.tool.version,
                                com.androidperformancestudio.contracts.Sha256(result.tool.sha256),
                            ),
                ),
            availableCapabilities = result.availableCapabilities,
            completeness = ArtifactCompleteness.UNKNOWN,
            requestedCapabilities = null,
        )

    fun javaWireFallback(
        artifact: CaptureArtifact,
        reason: String,
    ): CaptureArtifact =
        artifact.copy(
            availableCapabilities = JavaHeapCapabilities.ALL,
            completeness = ArtifactCompleteness.UNKNOWN,
            requestedCapabilities = null,
            warnings = artifact.warnings + "Perfetto Trace Processor unavailable; explicit wire fallback used: $reason",
        )

    fun processorResult(
        artifact: CaptureArtifact,
        result: NativeHeapProcessingResult.Success,
    ): CaptureArtifact {
        val missing = NativeHeapCapabilities.ALL - result.availableCapabilities
        val requested = artifact.requestedCapabilities
        val completeness =
            when {
                requested == null -> ArtifactCompleteness.UNKNOWN
                missing.isEmpty() -> ArtifactCompleteness.COMPLETE
                else -> ArtifactCompleteness.PARTIAL
            }
        return artifact.copy(
            provenance =
                artifact.provenance.copy(
                    processors =
                        artifact.provenance.processors +
                            ArtifactProducer.Known(
                                name = "Perfetto Trace Processor",
                                version = result.tool.version,
                                sha256 = com.androidperformancestudio.contracts.Sha256(result.tool.sha256),
                            ),
                ),
            availableCapabilities = result.availableCapabilities,
            completeness = completeness,
            limitations =
                if (requested == null) {
                    missing.map(::unavailableLimitation)
                } else {
                    (requested - result.availableCapabilities).map(::unavailableLimitation)
                },
        )
    }

    fun wireFallback(
        artifact: CaptureArtifact,
        reason: String,
    ): CaptureArtifact {
        val available =
            setOf(
                NativeHeapCapabilities.ALLOCATIONS,
                NativeHeapCapabilities.DEALLOCATIONS,
                NativeHeapCapabilities.COUNTS,
            )
        val missing = NativeHeapCapabilities.ALL - available
        return artifact.copy(
            requestedCapabilities = NativeHeapCapabilities.ALL,
            availableCapabilities = available,
            completeness = ArtifactCompleteness.PARTIAL,
            limitations =
                missing.map { capability ->
                    ArtifactLimitation(
                        capability,
                        "TRACE_PROCESSOR_UNAVAILABLE",
                        "${capability.value} is unavailable in the wire fallback: $reason",
                    )
                },
        )
    }

    private fun baseNativeArtifact(
        id: String,
        file: Path,
        kind: ArtifactAcquisitionKind,
    ): CaptureArtifact =
        CaptureArtifact(
            id = ArtifactId(id),
            kind = ArtifactKind("memory.native_heap"),
            location = ArtifactLocation(file.toAbsolutePath().normalize().toString()),
            sha256 = ArtifactFileEvidence.sha256(file),
            format = ArtifactFormat("perfetto", "heapprofd"),
            provenance = ArtifactProvenance(acquisition = acquisition(kind)),
        )

    private fun acquisition(kind: ArtifactAcquisitionKind): ArtifactAcquisition =
        ArtifactAcquisition(kind, "Android Performance Studio", performedAtEpochMillis = now().toEpochMilli())

    private fun pendingLimitation(capability: com.androidperformancestudio.contracts.CapabilityId): ArtifactLimitation =
        ArtifactLimitation(capability, "ANALYSIS_PENDING", "${capability.value} has not been verified yet")

    @Suppress("MaxLineLength")
    private fun unavailableLimitation(capability: com.androidperformancestudio.contracts.CapabilityId): ArtifactLimitation =
        ArtifactLimitation(capability, "EVIDENCE_UNAVAILABLE", "${capability.value} is not present or not provable")
}

internal class MemoryArtifactStore(
    private val root: Path,
) {
    fun write(artifact: CaptureArtifact) {
        Files.createDirectories(root)
        val destination = root.resolve("${artifact.id.value}.capture-artifact.json")
        val temporary = Files.createTempFile(root, artifact.id.value, ".tmp")
        try {
            Files.writeString(temporary, CaptureArtifactJson.encode(artifact))
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
