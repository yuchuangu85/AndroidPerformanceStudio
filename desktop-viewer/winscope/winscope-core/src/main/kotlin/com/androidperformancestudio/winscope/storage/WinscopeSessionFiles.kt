package com.androidperformancestudio.winscope.storage

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.winscope.model.WinscopeAnnotation
import com.androidperformancestudio.winscope.model.WinscopeCompleteness
import com.androidperformancestudio.winscope.model.WinscopeLimitation
import com.androidperformancestudio.winscope.model.WinscopeSession
import com.androidperformancestudio.winscope.model.WinscopeSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.extension
import kotlin.io.path.name

class WinscopeSessionFiles(
    private val root: Path = Path.of(System.getProperty("user.home"), ".android-performance-studio", "winscope-sessions"),
) {
    init {
        Files.createDirectories(root)
    }

    fun import(path: Path): StudioResult<WinscopeSession> =
        try {
            require(Files.isRegularFile(path)) { "Evidence file does not exist" }
            if (isZip(path)) importZip(path) else StudioResult.Success(rawSession(path))
        } catch (error: Exception) {
            failure("WINSCOPE_IMPORT_FAILED", error.message ?: "Unable to import Winscope evidence", error)
        }

    fun export(
        session: WinscopeSession,
        destination: Path,
        sensitiveEvidenceConfirmed: Boolean,
    ): StudioResult<Path> = exportArchive(session, destination, sensitiveEvidenceConfirmed, includeManifest = true)

    fun exportOriginal(
        session: WinscopeSession,
        destination: Path,
        sensitiveEvidenceConfirmed: Boolean,
    ): StudioResult<Path> = exportArchive(session, destination, sensitiveEvidenceConfirmed, includeManifest = false)

    private fun exportArchive(
        session: WinscopeSession,
        destination: Path,
        sensitiveEvidenceConfirmed: Boolean,
        includeManifest: Boolean,
    ): StudioResult<Path> =
        try {
            if (session.sensitive && !sensitiveEvidenceConfirmed) {
                return failure("WINSCOPE_EXPORT_CONFIRMATION_REQUIRED", "Sensitive Winscope evidence requires explicit export confirmation")
            }
            destination.parent?.let(Files::createDirectories)
            ZipOutputStream(BufferedOutputStream(Files.newOutputStream(destination))).use { zip ->
                if (includeManifest) {
                    zip.writeFile("trace/${session.traceFile.name}", session.traceFile)
                    session.recordingFile?.takeIf(Files::isRegularFile)?.let { zip.writeFile("media/${it.name}", it) }
                    session.screenshotFile?.takeIf(Files::isRegularFile)?.let { zip.writeFile("media/${it.name}", it) }
                    zip.writeText("manifest.json", MANIFEST_JSON.encodeToString(Manifest.from(session)))
                } else {
                    val traceName = session.traceFile.name + if (session.traceFile.extension.isEmpty()) ".perfetto-trace" else ""
                    zip.writeFile(traceName, session.traceFile)
                    (session.recordingFile?.takeIf(Files::isRegularFile) ?: session.screenshotFile?.takeIf(Files::isRegularFile))
                        ?.let { zip.writeFile(it.name, it) }
                }
            }
            StudioResult.Success(destination)
        } catch (error: Exception) {
            failure("WINSCOPE_EXPORT_FAILED", error.message ?: "Unable to export Winscope evidence", error)
        }

    fun delete(session: WinscopeSession): StudioResult<Unit> =
        try {
            if (session.managedFiles) {
                val directory =
                    session.traceFile
                        .toAbsolutePath()
                        .normalize()
                        .parent
                val managedRoot = root.toAbsolutePath().normalize()
                when {
                    directory == managedRoot ->
                        listOfNotNull(session.traceFile, session.recordingFile, session.screenshotFile).forEach(Files::deleteIfExists)
                    directory != null && directory.startsWith(managedRoot) -> directory.deleteRecursively()
                }
            }
            StudioResult.Success(Unit)
        } catch (error: Exception) {
            failure("WINSCOPE_SESSION_DELETE_FAILED", error.message ?: "Unable to delete Winscope session", error)
        }

    private fun rawSession(path: Path): WinscopeSession {
        require(Files.size(path) in 1..MAX_TRACE_BYTES) { "Trace is empty or exceeds the 1 GB limit" }
        require(isTrace(path)) { "File content is not a Perfetto trace" }
        return WinscopeSession(
            id = UUID.randomUUID().toString(),
            traceFile = path.toAbsolutePath().normalize(),
            capturedAt = Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis()),
            completeness = WinscopeCompleteness.UNKNOWN,
            managedFiles = false,
        )
    }

    private fun importZip(path: Path): StudioResult<WinscopeSession> {
        val id = UUID.randomUUID().toString()
        val directory = root.resolve(id)
        Files.createDirectories(directory)
        try {
            val discarded = mutableListOf<String>()
            val extracted = mutableListOf<Path>()
            ZipFile(path.toFile()).use { zip ->
                val entries =
                    zip
                        .entries()
                        .asSequence()
                        .filterNot(ZipEntry::isDirectory)
                        .toList()
                require(entries.size <= MAX_ZIP_ENTRIES) { "ZIP contains too many entries" }
                var total = 0L
                entries.forEach { entry ->
                    require(entry.size < 0 || entry.size <= MAX_ENTRY_BYTES) { "ZIP entry is too large: ${entry.name}" }
                    val target = safeTarget(directory, entry.name)
                    Files.createDirectories(target.parent)
                    zip.getInputStream(entry).use { input ->
                        BufferedInputStream(input).use { source ->
                            BufferedOutputStream(Files.newOutputStream(target)).use { sink ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var entryBytes = 0L
                                while (true) {
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    entryBytes += count
                                    total += count
                                    require(entryBytes <= MAX_ENTRY_BYTES && total <= MAX_ZIP_BYTES) { "ZIP exceeds extraction limits" }
                                    sink.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                    extracted.add(target)
                }
            }
            val traces = extracted.filter(::isTrace).sortedByDescending(Files::size)
            require(traces.isNotEmpty()) { "ZIP does not contain a supported Perfetto trace" }
            discarded += traces.drop(1).map { "Discarded lower-priority trace: ${directory.relativize(it)}" }
            val recordings = extracted.filter { it.extension.equals("mp4", true) }.sortedByDescending(Files::size)
            val screenshots = extracted.filter { it.extension.lowercase() in setOf("png", "jpg", "jpeg") }.sortedByDescending(Files::size)
            discarded += recordings.drop(1).map { "Discarded lower-priority recording: ${directory.relativize(it)}" }
            discarded += screenshots.drop(1).map { "Discarded lower-priority screenshot: ${directory.relativize(it)}" }
            val manifest = extracted.firstOrNull { it.name.equals("manifest.json", true) }?.let(::readManifest)
            val limitations = manifest?.limitationsModel.orEmpty() + discarded.map { WinscopeLimitation(null, "ZIP_ENTRY_DISCARDED", it) }
            val recording = recordings.firstOrNull()
            val screenshot = screenshots.firstOrNull().takeIf { recording == null }
            return StudioResult.Success(
                WinscopeSession(
                    id = id,
                    traceFile = traces.first(),
                    recordingFile = recording,
                    screenshotFile = screenshot,
                    capturedAt = manifest?.capturedAt?.let(Instant::parse) ?: Instant.now(),
                    requestedSources = manifest?.requestedSources,
                    availableSources = manifest?.availableSources.orEmpty(),
                    limitations = limitations,
                    completeness = manifest?.completeness ?: WinscopeCompleteness.UNKNOWN,
                    sensitive = manifest?.sensitive ?: (recording != null || screenshot != null),
                    managedFiles = true,
                    isDump = manifest?.isDump ?: false,
                    annotations = manifest?.annotations.orEmpty(),
                ),
            )
        } catch (error: Exception) {
            directory.deleteRecursively()
            return failure("WINSCOPE_ZIP_IMPORT_FAILED", error.message ?: "Unable to import Winscope ZIP", error)
        }
    }

    private fun readManifest(path: Path): Manifest? =
        runCatching { MANIFEST_JSON.decodeFromString<Manifest>(Files.readString(path)) }.getOrNull()

    companion object {
        private const val MAX_TRACE_BYTES = 1_073_741_824L
        private const val MAX_ENTRY_BYTES = 1_073_741_824L
        private const val MAX_ZIP_BYTES = 2_147_483_648L
        private const val MAX_ZIP_ENTRIES = 2_000
        private const val TRACE_PACKET_TAG = 0x0A

        private fun isZip(path: Path): Boolean =
            Files.newInputStream(path).use { input ->
                val signature = ByteArray(4)
                input.readFully(signature) &&
                    signature[0] == 'P'.code.toByte() &&
                    signature[1] == 'K'.code.toByte() &&
                    signature[2].toInt() in setOf(3, 5, 7) &&
                    signature[3].toInt() in setOf(4, 6, 8)
            }

        internal fun safeTarget(
            root: Path,
            entryName: String,
        ): Path {
            require(entryName.isNotBlank() && '\u0000' !in entryName) { "ZIP contains an invalid entry name" }
            val normalized = root.resolve(entryName.replace('\\', '/')).normalize()
            require(normalized.startsWith(root.normalize())) { "ZIP entry escapes its extraction directory" }
            return normalized
        }

        private fun isTrace(path: Path): Boolean =
            Files.isRegularFile(path) &&
                Files.size(path) in 1..MAX_TRACE_BYTES &&
                Files.newInputStream(path).use { it.read() == TRACE_PACKET_TAG }
    }
}

private fun InputStream.readFully(bytes: ByteArray): Boolean {
    var offset = 0
    while (offset < bytes.size) {
        val count = read(bytes, offset, bytes.size - offset)
        if (count < 0) return false
        offset += count
    }
    return true
}

@Serializable
private val MANIFEST_JSON =
    Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

@Serializable
private data class Manifest(
    val formatVersion: Int = 1,
    val capturedAt: String,
    val requestedSources: Set<WinscopeSource>? = null,
    val availableSources: Set<WinscopeSource> = emptySet(),
    val limitations: List<WinscopeLimitationDto> = emptyList(),
    val completeness: WinscopeCompleteness = WinscopeCompleteness.UNKNOWN,
    val sensitive: Boolean = false,
    val isDump: Boolean = false,
    val annotations: List<WinscopeAnnotation> = emptyList(),
) {
    companion object {
        fun from(session: WinscopeSession): Manifest =
            Manifest(
                capturedAt = session.capturedAt.toString(),
                requestedSources = session.requestedSources,
                availableSources = session.availableSources,
                limitations = session.limitations.map(WinscopeLimitationDto::from),
                completeness = session.completeness,
                sensitive = session.sensitive,
                isDump = session.isDump,
                annotations = session.annotations,
            )
    }
}

@Serializable
private data class WinscopeLimitationDto(
    val source: WinscopeSource? = null,
    val code: String,
    val message: String,
) {
    companion object {
        fun from(value: WinscopeLimitation): WinscopeLimitationDto = WinscopeLimitationDto(value.source, value.code, value.message)
    }
}

private val List<WinscopeLimitationDto>.asLimitations: List<WinscopeLimitation>
    get() = map { WinscopeLimitation(it.source, it.code, it.message) }

private val Manifest.limitationsModel: List<WinscopeLimitation>
    get() = limitations.asLimitations

private fun ZipOutputStream.writeFile(
    name: String,
    path: Path,
) {
    putNextEntry(ZipEntry(name))
    Files.copy(path, this)
    closeEntry()
}

private fun ZipOutputStream.writeText(
    name: String,
    value: String,
) {
    putNextEntry(ZipEntry(name))
    write(value.toByteArray())
    closeEntry()
}

private fun Path.deleteRecursively() {
    if (!Files.exists(this)) return
    Files.walk(this).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}

private fun <T> failure(
    code: String,
    message: String,
    cause: Throwable? = null,
): StudioResult<T> = StudioResult.Failure(StudioError(ErrorCategory.DATA_VALIDATION, code, message, cause))
