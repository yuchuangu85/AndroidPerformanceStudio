@file:Suppress("TooManyFunctions")

package com.androidperformancestudio.application

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
import com.androidperformancestudio.contracts.CaptureArtifactJson
import com.androidperformancestudio.contracts.Sha256
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.NormalizedProfileRecord
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.parser.HostSimpleperf
import com.androidperformancestudio.parser.HostSimpleperfLocator
import com.androidperformancestudio.parser.SimpleperfConversionRequest
import com.androidperformancestudio.parser.SimpleperfConversionResult
import com.androidperformancestudio.parser.SimpleperfProfileNormalizer
import com.androidperformancestudio.parser.SimpleperfReadSummary
import com.androidperformancestudio.parser.SimpleperfRecordReader
import com.androidperformancestudio.parser.SimpleperfReportConverter
import com.androidperformancestudio.platform.toolchain.HostCancellationSignal
import com.androidperformancestudio.storage.DataQualitySummary
import com.androidperformancestudio.storage.ProfileImportResult
import com.androidperformancestudio.storage.SQLiteSampleStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.SQLException
import java.util.concurrent.CancellationException
import kotlin.io.path.isRegularFile

enum class OfflineProfileFormat {
    PERF_DATA,
    SIMPLEPERF_PROTOBUF,
    GECKO_PROFILE_JSON_GZIP,
}

data class OfflineImportRequest(
    val sessionId: String,
    val sessionRoot: Path,
    val input: Path,
    val format: OfflineProfileFormat,
    val symbolDirectory: Path? = null,
    val proguardMapping: Path? = null,
    val batchSize: Int = SQLiteSampleStore.DEFAULT_BATCH_SIZE,
) {
    init {
        require(SESSION_ID.matches(sessionId)) {
            "sessionId must contain only letters, numbers, dot, dash, or underscore"
        }
        require(batchSize > 0) { "batchSize must be positive" }
    }

    companion object {
        private val SESSION_ID = Regex("[A-Za-z0-9._-]+")
    }
}

data class OfflineImportResult(
    val sessionDirectory: Path,
    val perfData: Path?,
    val protobufTrace: Path?,
    val geckoProfile: Path?,
    val database: Path,
    val hostSimpleperf: HostSimpleperf?,
    val readSummary: SimpleperfReadSummary,
    val profileImport: ProfileImportResult,
    val quality: DataQualitySummary,
    val artifact: CaptureArtifact? = null,
)

fun interface HostSimpleperfResolver {
    suspend fun locate(cancellationSignal: HostCancellationSignal): StudioResult<HostSimpleperf>
}

fun interface PerfDataConverter {
    suspend fun convert(
        hostSimpleperf: HostSimpleperf,
        request: SimpleperfConversionRequest,
        cancellationSignal: HostCancellationSignal,
    ): StudioResult<SimpleperfConversionResult>
}

class OfflineProfileImporter(
    private val hostSimpleperfResolver: HostSimpleperfResolver,
    private val perfDataConverter: PerfDataConverter,
    private val recordReader: SimpleperfRecordReader = SimpleperfRecordReader(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val geckoProfileReader = GeckoProfileReader()
    constructor(
        locator: HostSimpleperfLocator,
        converter: SimpleperfReportConverter,
    ) : this(
        hostSimpleperfResolver = HostSimpleperfResolver { signal -> locator.locate(signal) },
        perfDataConverter = PerfDataConverter { host, request, signal -> converter.convert(host, request, signal) },
    )

    suspend fun import(
        request: OfflineImportRequest,
        cancellationSignal: HostCancellationSignal = HostCancellationSignal(),
    ): StudioResult<OfflineImportResult> =
        runImport {
            validate(request)?.let { return@runImport it }
            importValidated(request, cancellationSignal)
        }

    suspend fun importCapturedSession(
        sessionDirectory: Path,
        batchSize: Int = SQLiteSampleStore.DEFAULT_BATCH_SIZE,
        cancellationSignal: HostCancellationSignal = HostCancellationSignal(),
    ): StudioResult<OfflineImportResult> =
        runImport {
            val session = sessionDirectory.toAbsolutePath().normalize()
            val perfData = session.resolve("perf.data")
            if (!Files.isDirectory(session) || !perfData.isRegularFile()) {
                return@runImport failure(
                    ErrorCategory.IO,
                    "CAPTURED_SESSION_PERF_DATA_NOT_FOUND",
                    "Captured session does not contain perf.data",
                )
            }
            val artifactFile = session.resolve("capture-artifact.json")
            if (artifactFile.isRegularFile()) {
                val artifact = runCatching { CaptureArtifactJson.decode(Files.readString(artifactFile)) }.getOrNull()
                if (artifact == null || artifact.sha256 != ArtifactFileEvidence.sha256(perfData)) {
                    return@runImport failure(
                        ErrorCategory.DATA_VALIDATION,
                        "CAPTURED_SESSION_HASH_MISMATCH",
                        "Captured session perf.data no longer matches its Capture Artifact hash",
                    )
                }
            }
            val request =
                OfflineImportRequest(
                    sessionId = session.fileName.toString(),
                    sessionRoot = checkNotNull(session.parent),
                    input = perfData,
                    format = OfflineProfileFormat.PERF_DATA,
                    batchSize = batchSize,
                )
            importPrepared(
                request = request,
                artifacts =
                    ImportArtifacts(
                        sessionDirectory = session,
                        perfData = perfData,
                        protobufTrace = session.resolve("simpleperf.protobuf"),
                        geckoProfile = null,
                        database = session.resolve("profile.sqlite"),
                        mapping = session.resolve("mapping.txt").takeIf(Path::isRegularFile),
                        symbols = session.resolve("symbols").takeIf { Files.isDirectory(it) },
                    ),
                cancellationSignal = cancellationSignal,
            )
        }

    @Suppress("MaxLineLength")
    private suspend fun runImport(block: suspend () -> StudioResult<OfflineImportResult>): StudioResult<OfflineImportResult> =
        withContext(ioDispatcher) {
            try {
                block()
            } catch (_: CancellationException) {
                failure(ErrorCategory.PROCESS_CANCELLED, "OFFLINE_IMPORT_CANCELLED", "Offline import was cancelled")
            } catch (exception: GeckoProfileFormatException) {
                failure(
                    ErrorCategory.DATA_VALIDATION,
                    "GECKO_PROFILE_INVALID",
                    exception.message ?: "Invalid Gecko profile",
                    exception,
                )
            } catch (exception: IOException) {
                failure(
                    ErrorCategory.IO,
                    "OFFLINE_IMPORT_IO_FAILED",
                    "Failed to prepare or import offline profile",
                    exception,
                )
            } catch (exception: SQLException) {
                failure(
                    ErrorCategory.DATA_VALIDATION,
                    "OFFLINE_IMPORT_FAILED",
                    "Failed to parse or index offline profile",
                    exception,
                )
            }
        }

    private suspend fun importValidated(
        request: OfflineImportRequest,
        cancellationSignal: HostCancellationSignal,
    ): StudioResult<OfflineImportResult> {
        ensureActive(cancellationSignal)
        val artifacts = prepareArtifacts(request)
        return importPrepared(request, artifacts, cancellationSignal)
    }

    @Suppress("ReturnCount")
    private suspend fun importPrepared(
        request: OfflineImportRequest,
        artifacts: ImportArtifacts,
        cancellationSignal: HostCancellationSignal,
    ): StudioResult<OfflineImportResult> {
        if (request.format == OfflineProfileFormat.GECKO_PROFILE_JSON_GZIP) {
            return indexGeckoProfile(request, artifacts, cancellationSignal)
        }
        val converted = convertIfNecessary(request, artifacts, cancellationSignal)
        val prepared =
            when (converted) {
                is StudioResult.Failure -> {
                    writeFailure(artifacts.sessionDirectory, request.format, converted.error)
                    return converted
                }
                is StudioResult.Success -> converted.value
            }
        ensureActive(cancellationSignal)
        return indexTrace(request, artifacts, prepared, cancellationSignal)
    }

    private fun prepareArtifacts(request: OfflineImportRequest): ImportArtifacts {
        Files.createDirectories(request.sessionRoot)
        val sessionDirectory = request.sessionRoot.resolve(request.sessionId)
        try {
            Files.createDirectory(sessionDirectory)
        } catch (exception: FileAlreadyExistsException) {
            throw IOException("Session already exists: $sessionDirectory", exception)
        }
        val perfData =
            if (request.format == OfflineProfileFormat.PERF_DATA) {
                copy(request.input, sessionDirectory.resolve("perf.data"))
            } else {
                null
            }
        val protobufTrace = sessionDirectory.resolve("simpleperf.protobuf")
        if (request.format == OfflineProfileFormat.SIMPLEPERF_PROTOBUF) copy(request.input, protobufTrace)
        val geckoProfile =
            if (request.format == OfflineProfileFormat.GECKO_PROFILE_JSON_GZIP) {
                copy(request.input, sessionDirectory.resolve("gecko-profile.json.gz"))
            } else {
                null
            }
        val mapping = request.proguardMapping?.let { copy(it, sessionDirectory.resolve("mapping.txt")) }
        val symbols = request.symbolDirectory?.let { copyDirectory(it, sessionDirectory.resolve("symbols")) }
        return ImportArtifacts(
            sessionDirectory = sessionDirectory,
            perfData = perfData,
            protobufTrace = protobufTrace,
            database = sessionDirectory.resolve("profile.sqlite"),
            geckoProfile = geckoProfile,
            mapping = mapping,
            symbols = symbols,
        )
    }

    @Suppress("ReturnCount")
    private suspend fun convertIfNecessary(
        request: OfflineImportRequest,
        artifacts: ImportArtifacts,
        cancellationSignal: HostCancellationSignal,
    ): StudioResult<PreparedTrace> {
        if (request.format == OfflineProfileFormat.SIMPLEPERF_PROTOBUF) {
            return StudioResult.Success(PreparedTrace(artifacts.protobufTrace, null))
        }
        val host =
            when (val located = hostSimpleperfResolver.locate(cancellationSignal)) {
                is StudioResult.Failure -> return located
                is StudioResult.Success -> located.value
            }
        val conversion =
            SimpleperfConversionRequest(
                perfData = checkNotNull(artifacts.perfData),
                protobufTrace = artifacts.protobufTrace,
                symbolDirectory = artifacts.symbols,
                proguardMapping = artifacts.mapping,
            )
        return when (val result = perfDataConverter.convert(host, conversion, cancellationSignal)) {
            is StudioResult.Failure -> result
            is StudioResult.Success -> StudioResult.Success(PreparedTrace(result.value.protobufTrace, host))
        }
    }

    @Suppress("NestedBlockDepth", "ReturnCount", "LongMethod")
    private fun indexTrace(
        request: OfflineImportRequest,
        artifacts: ImportArtifacts,
        prepared: PreparedTrace,
        cancellationSignal: HostCancellationSignal,
    ): StudioResult<OfflineImportResult> {
        deleteDatabaseArtifacts(artifacts.database)
        val normalizer = SimpleperfProfileNormalizer()
        var readSummary: SimpleperfReadSummary? = null
        var profileImport: ProfileImportResult? = null
        try {
            SQLiteSampleStore.open(artifacts.database).use { store ->
                store.beginRecordImport(request.batchSize).use { writer ->
                    // Simpleperf can emit samples before the File, Thread, and MetaInfo records they reference.
                    val preload =
                        Files.newInputStream(prepared.protobufTrace).buffered().use { input ->
                            recordReader.read(input) { envelope ->
                                ensureActive(cancellationSignal)
                                val normalized = normalizer.normalize(envelope.record)
                                if (normalized.isLookupRecord()) writer.add(normalized)
                            }
                        }
                    when (preload) {
                        is StudioResult.Failure -> {
                            writeFailure(artifacts.sessionDirectory, request.format, preload.error)
                            return preload
                        }
                        is StudioResult.Success -> Unit
                    }
                    val read =
                        Files.newInputStream(prepared.protobufTrace).buffered().use { input ->
                            recordReader.read(input) { envelope ->
                                ensureActive(cancellationSignal)
                                val normalized = normalizer.normalize(envelope.record)
                                if (!normalized.isLookupRecord()) writer.add(normalized)
                            }
                        }
                    when (read) {
                        is StudioResult.Failure -> {
                            writeFailure(artifacts.sessionDirectory, request.format, read.error)
                            return read
                        }
                        is StudioResult.Success -> readSummary = read.value
                    }
                    profileImport = writer.finish()
                }
                val quality = store.dataQuality()
                val result =
                    OfflineImportResult(
                        sessionDirectory = artifacts.sessionDirectory,
                        perfData = artifacts.perfData,
                        protobufTrace = prepared.protobufTrace,
                        geckoProfile = artifacts.geckoProfile,
                        database = artifacts.database,
                        hostSimpleperf = prepared.hostSimpleperf,
                        readSummary = checkNotNull(readSummary),
                        profileImport = checkNotNull(profileImport),
                        quality = quality,
                        artifact = createImportArtifact(request, artifacts, prepared.hostSimpleperf, quality),
                    )
                writeSuccess(request, result)
                writeArtifact(result)
                return StudioResult.Success(result)
            }
        } finally {
            if (readSummary == null || profileImport == null) deleteDatabaseArtifacts(artifacts.database)
        }
    }

    @Suppress("NestedBlockDepth")
    private fun indexGeckoProfile(
        request: OfflineImportRequest,
        artifacts: ImportArtifacts,
        cancellationSignal: HostCancellationSignal,
    ): StudioResult<OfflineImportResult> {
        deleteDatabaseArtifacts(artifacts.database)
        var readSummary: SimpleperfReadSummary? = null
        var profileImport: ProfileImportResult? = null
        try {
            SQLiteSampleStore.open(artifacts.database).use { store ->
                store.beginRecordImport(request.batchSize).use { writer ->
                    val gecko = checkNotNull(artifacts.geckoProfile)
                    val summary =
                        Files.newInputStream(gecko).buffered().use { input ->
                            geckoProfileReader.read(
                                input = input,
                                ensureActive = { ensureActive(cancellationSignal) },
                                onRecord = writer::add,
                            )
                        }
                    readSummary =
                        SimpleperfReadSummary(
                            version = GECKO_PROFILE_VERSION,
                            recordCount = summary.recordCount,
                            bytesRead = summary.decompressedBytes,
                        )
                    profileImport = writer.finish()
                }
                val quality = store.dataQuality()
                val result =
                    OfflineImportResult(
                        sessionDirectory = artifacts.sessionDirectory,
                        perfData = null,
                        protobufTrace = null,
                        geckoProfile = artifacts.geckoProfile,
                        database = artifacts.database,
                        hostSimpleperf = null,
                        readSummary = checkNotNull(readSummary),
                        profileImport = checkNotNull(profileImport),
                        quality = quality,
                        artifact = createImportArtifact(request, artifacts, null, quality),
                    )
                writeSuccess(request, result)
                writeArtifact(result)
                return StudioResult.Success(result)
            }
        } finally {
            if (readSummary == null || profileImport == null) deleteDatabaseArtifacts(artifacts.database)
        }
    }

    private fun validate(request: OfflineImportRequest): StudioResult.Failure? =
        when {
            !request.input.isRegularFile() ->
                failure(ErrorCategory.IO, "OFFLINE_INPUT_NOT_FOUND", "Offline profile input does not exist")
            request.proguardMapping != null && !request.proguardMapping.isRegularFile() ->
                failure(ErrorCategory.IO, "OFFLINE_MAPPING_NOT_FOUND", "Proguard mapping file does not exist")
            request.symbolDirectory != null && !Files.isDirectory(request.symbolDirectory) ->
                failure(ErrorCategory.IO, "OFFLINE_SYMBOL_DIRECTORY_NOT_FOUND", "Symbol directory does not exist")
            else -> null
        }
}

private fun NormalizedProfileRecord.isLookupRecord(): Boolean =
    this is NormalizedProfileRecord.File ||
        this is NormalizedProfileRecord.Thread ||
        this is NormalizedProfileRecord.Metadata

private data class ImportArtifacts(
    val sessionDirectory: Path,
    val perfData: Path?,
    val protobufTrace: Path,
    val geckoProfile: Path?,
    val database: Path,
    val mapping: Path?,
    val symbols: Path?,
)

private data class PreparedTrace(
    val protobufTrace: Path,
    val hostSimpleperf: HostSimpleperf?,
)

private fun ensureActive(signal: HostCancellationSignal) {
    if (signal.isCancelled) throw CancellationException("Offline import cancelled")
}

private fun copy(
    source: Path,
    target: Path,
): Path {
    target.toAbsolutePath().parent?.let(Files::createDirectories)
    return Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
}

private fun copyDirectory(
    source: Path,
    target: Path,
): Path {
    Files.walk(source).use { paths ->
        paths.forEach { path ->
            val relative = source.relativize(path)
            val destination = target.resolve(relative)
            when {
                Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> Files.createDirectories(destination)
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> copy(path, destination)
            }
        }
    }
    return target
}

private fun deleteDatabaseArtifacts(database: Path) {
    Files.deleteIfExists(database)
    Files.deleteIfExists(database.resolveSibling(database.fileName.toString() + "-wal"))
    Files.deleteIfExists(database.resolveSibling(database.fileName.toString() + "-shm"))
}

private fun writeSuccess(
    request: OfflineImportRequest,
    result: OfflineImportResult,
) {
    val host = result.hostSimpleperf
    val lines =
        buildList {
            add("status=completed")
            add("format=${request.format}")
            add("input=${request.input.toAbsolutePath()}")
            result.perfData?.let { add("perfData=${it.fileName}") }
            result.protobufTrace?.let { add("protobufTrace=${it.fileName}") }
            result.geckoProfile?.let { add("geckoProfile=${it.fileName}") }
            add("database=${result.database.fileName}")
            add("recordCount=${result.readSummary.recordCount}")
            add("sampleCount=${result.profileImport.importedSamples}")
            host?.let {
                add("hostSimpleperfVersion=${it.version}")
                add("hostSimpleperfSha256=${it.sha256}")
            }
        }
    Files.writeString(result.sessionDirectory.resolve("import.properties"), lines.joinToString("\n", postfix = "\n"))
}

private fun writeFailure(
    sessionDirectory: Path,
    format: OfflineProfileFormat,
    error: StudioError,
) {
    Files.writeString(
        sessionDirectory.resolve("import.properties"),
        "status=failed\nformat=$format\nerrorCode=${error.code}\nerrorMessage=${error.message}\n",
    )
}

@Suppress("LongMethod")
private fun createImportArtifact(
    request: OfflineImportRequest,
    artifacts: ImportArtifacts,
    hostSimpleperf: HostSimpleperf?,
    quality: DataQualitySummary,
): CaptureArtifact {
    val evidence =
        artifacts.perfData
            ?: artifacts.protobufTrace.takeIf(Path::isRegularFile)
            ?: checkNotNull(artifacts.geckoProfile)
    val existing =
        artifacts.sessionDirectory.resolve("capture-artifact.json").takeIf(Path::isRegularFile)?.let {
            runCatching { CaptureArtifactJson.decode(Files.readString(it)) }.getOrNull()
        }
    val processor =
        hostSimpleperf?.let {
            ArtifactProducer.Known("Host simpleperf", it.version, Sha256(it.sha256))
        }
    val requested = setOf(CPU_SAMPLES, CPU_CALL_STACKS, CPU_THREAD_TIMELINE)
    val available =
        buildSet {
            if (quality.lostSampleCount == 0L && quality.unknownRecords == 0L) add(CPU_SAMPLES)
            if (quality.unwindErrorSamples == 0L && quality.emptyStackSamples == 0L) add(CPU_CALL_STACKS)
            add(CPU_THREAD_TIMELINE)
        }
    val limitations =
        (requested - available).map { capability ->
            ArtifactLimitation(
                capability,
                "profile-data-quality",
                "Imported CPU evidence contains lost, unknown, empty-stack, or unwind-error records.",
            )
        }
    if (existing != null && existing.sha256 == ArtifactFileEvidence.sha256(evidence)) {
        return existing.copy(
            location = ArtifactLocation(evidence.toAbsolutePath().normalize().toString()),
            provenance =
                existing.provenance.copy(
                    processors = existing.provenance.processors + listOfNotNull(processor),
                ),
            availableCapabilities = available,
            limitations = existing.limitations + limitations,
        )
    }
    val hash = ArtifactFileEvidence.sha256(evidence)
    return CaptureArtifact(
        id = ArtifactId("cpu-${java.util.UUID.randomUUID()}"),
        kind = ArtifactKind("cpu.simpleperf"),
        location = ArtifactLocation(evidence.toAbsolutePath().normalize().toString()),
        sha256 = hash,
        format = ArtifactFormat(request.format.artifactFormatName()),
        provenance =
            ArtifactProvenance(
                producer = ArtifactProducer.Unknown,
                acquisition =
                    ArtifactAcquisition(
                        ArtifactAcquisitionKind.IMPORT,
                        "Android Performance Studio",
                        performedAtEpochMillis = System.currentTimeMillis(),
                    ),
                processors = listOfNotNull(processor),
            ),
        availableCapabilities = available,
        completeness = ArtifactCompleteness.UNKNOWN,
        limitations = limitations,
    )
}

private fun OfflineProfileFormat.artifactFormatName(): String =
    when (this) {
        OfflineProfileFormat.PERF_DATA -> "simpleperf-perf-data"
        OfflineProfileFormat.SIMPLEPERF_PROTOBUF -> "simpleperf-protobuf"
        OfflineProfileFormat.GECKO_PROFILE_JSON_GZIP -> "gecko-profile-json-gzip"
    }

private fun writeArtifact(result: OfflineImportResult) {
    result.artifact?.let { artifact ->
        Files.writeString(
            result.sessionDirectory.resolve("capture-artifact.json"),
            CaptureArtifactJson.encode(artifact),
        )
    }
}

private fun failure(
    category: ErrorCategory,
    code: String,
    message: String,
    cause: Throwable? = null,
): StudioResult.Failure = StudioResult.Failure(StudioError(category, code, message, cause))

private const val GECKO_PROFILE_VERSION = 24
private val CPU_SAMPLES = CapabilityId("cpu.samples")
private val CPU_CALL_STACKS = CapabilityId("cpu.call_stacks")
private val CPU_THREAD_TIMELINE = CapabilityId("cpu.thread_timeline")
