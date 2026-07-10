package dev.agentperf.desktop

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class CaptureArchiveCodec(
    private val json: Json = defaultArchiveJson(),
    private val moveIntoPlace: (Path, Path) -> Unit = ::moveReplacingArchive,
    private val limits: CaptureArchiveLimits = CaptureArchiveLimits(),
) {
    fun write(
        target: Path,
        metadata: CaptureArchiveMetadata,
        payload: CaptureArchivePayload,
    ): CaptureArchiveWriteResult {
        val parent = target.toAbsolutePath().parent
            ?: throw IllegalArgumentException("Archive target must have a parent directory")
        require(Files.isDirectory(parent)) {
            "Archive target directory does not exist: $parent"
        }
        val content = payload.toEntries()
        validateEntrySizes(content)
        val manifest = CaptureArchiveManifest(
            format = CAPTURE_ARCHIVE_FORMAT,
            archiveVersion = CAPTURE_ARCHIVE_VERSION,
            producerVersion = metadata.producerVersion,
            packageName = metadata.packageName,
            capturedAtEpochMillis = metadata.capturedAtEpochMillis,
            protocolMajor = metadata.protocolMajor,
            protocolMinor = metadata.protocolMinor,
            entries = content.map { (path, bytes) ->
                CaptureArchiveManifestEntry(
                    path = path,
                    size = bytes.size.toLong(),
                    sha256 = sha256(bytes),
                    required = path in REQUIRED_PATHS,
                )
            },
        )
        val manifestBytes = json.encodeToString(manifest)
            .toByteArray(StandardCharsets.UTF_8)
        require(manifestBytes.size <= MAX_MANIFEST_BYTES) {
            "Archive manifest is too large"
        }
        val temporary = Files.createTempFile(parent, ".agentperf-capture-", ".tmp")
        try {
            ZipOutputStream(Files.newOutputStream(temporary)).use { output ->
                output.writeEntry(CaptureArchivePaths.MANIFEST, manifestBytes)
                content.forEach { (path, bytes) -> output.writeEntry(path, bytes) }
            }
            moveIntoPlace(temporary, target)
            return CaptureArchiveWriteResult(
                path = target,
                rawArtifactsIncluded = content.containsKey(CaptureArchivePaths.RAW_ZIP),
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun read(source: Path): CaptureArchiveDocument {
        if (!Files.isRegularFile(source)) {
            throw CaptureArchiveFormatException("Archive is not a regular file")
        }
        if (Files.size(source) > limits.maxArchiveBytes) {
            throw CaptureArchiveFormatException("Archive file is too large")
        }
        return try {
            ZipFile(source.toFile()).use(::readDocument)
        } catch (error: CaptureArchiveFormatException) {
            throw error
        } catch (error: ZipException) {
            throw CaptureArchiveFormatException(
                "Archive ZIP data is invalid: ${error.message}",
            )
        }
    }

    private fun readDocument(zip: ZipFile): CaptureArchiveDocument {
        val zipEntries = zip.entries().asSequence().toList()
        if (zipEntries.isEmpty() || zipEntries.size > MAX_ENTRY_COUNT) {
            throw CaptureArchiveFormatException("Archive entry count is out of bounds")
        }
        zipEntries.forEach { entry ->
            if (entry.isDirectory) {
                throw CaptureArchiveFormatException(
                    "Archive contains a directory entry: ${entry.name}",
                )
            }
            validateSafePath(entry.name)
        }
        val actualNames = zipEntries.map { it.name }
        if (actualNames.toSet().size != actualNames.size) {
            throw CaptureArchiveFormatException("Archive contains duplicate entries")
        }
        val manifestEntry = zipEntries.singleOrNull {
            it.name == CaptureArchivePaths.MANIFEST
        } ?: throw CaptureArchiveFormatException("Archive manifest is missing")
        val manifest = zip.getInputStream(manifestEntry).use { input ->
            val bytes = readBounded(
                input = input,
                maximum = MAX_MANIFEST_BYTES,
                label = "Archive manifest",
            )
            try {
                json.decodeFromString<CaptureArchiveManifest>(
                    bytes.toString(StandardCharsets.UTF_8),
                )
            } catch (error: Throwable) {
                throw CaptureArchiveFormatException(
                    "Archive manifest is invalid: ${error.message}",
                )
            }
        }
        validateManifest(manifest)

        val declaredNames = manifest.entries.map { it.path }
        if (declaredNames.toSet().size != declaredNames.size) {
            throw CaptureArchiveFormatException(
                "Archive manifest contains duplicate paths",
            )
        }
        manifest.entries.forEach { validateSafePath(it.path) }
        val expectedNames = declaredNames.toSet() + CaptureArchivePaths.MANIFEST
        if (actualNames.toSet() != expectedNames) {
            throw CaptureArchiveFormatException(
                "Archive entries do not match the manifest",
            )
        }

        var totalBytes = 0L
        val knownContent = mutableMapOf<String, ByteArray>()
        manifest.entries.forEach { declared ->
            val maximum = maximumEntryBytes(declared)
            if (declared.size < 0 || declared.size > maximum) {
                throw CaptureArchiveFormatException(
                    "Archive entry size is out of bounds: ${declared.path}",
                )
            }
            totalBytes += declared.size
            if (totalBytes > limits.maxTotalUncompressedBytes) {
                throw CaptureArchiveFormatException(
                    "Archive uncompressed content is too large",
                )
            }
            val entry = zip.getEntry(declared.path)
                ?: throw CaptureArchiveFormatException(
                    "Archive entry is missing: ${declared.path}",
                )
            val bytes = zip.getInputStream(entry).use { input ->
                readBounded(input, maximum.toInt(), declared.path)
            }
            if (bytes.size.toLong() != declared.size ||
                sha256(bytes) != declared.sha256
            ) {
                throw CaptureArchiveFormatException(
                    "Archive entry failed integrity validation: ${declared.path}",
                )
            }
            if (declared.path in KNOWN_CONTENT_PATHS) {
                knownContent[declared.path] = bytes
            }
        }
        return manifest.toDocument(knownContent)
    }

    private fun validateManifest(manifest: CaptureArchiveManifest) {
        if (manifest.format != CAPTURE_ARCHIVE_FORMAT) {
            throw CaptureArchiveFormatException("Unsupported archive format")
        }
        if (manifest.archiveVersion != CAPTURE_ARCHIVE_VERSION) {
            throw CaptureArchiveFormatException(
                "Unsupported archive version ${manifest.archiveVersion}",
            )
        }
        REQUIRED_PATHS.forEach { requiredPath ->
            val declared = manifest.entries.singleOrNull { it.path == requiredPath }
                ?: throw CaptureArchiveFormatException(
                    "Archive entry is missing: $requiredPath",
                )
            if (!declared.required) {
                throw CaptureArchiveFormatException(
                    "Archive required entry is not marked required: $requiredPath",
                )
            }
        }
        manifest.entries
            .filter { it.path !in KNOWN_CONTENT_PATHS && it.required }
            .firstOrNull()
            ?.let {
                throw CaptureArchiveFormatException(
                    "Archive requires an unsupported entry: ${it.path}",
                )
            }
        val rawZip = manifest.entries.any { it.path == CaptureArchivePaths.RAW_ZIP }
        val rawText = manifest.entries.any { it.path == CaptureArchivePaths.RAW_TEXT }
        if (rawZip != rawText) {
            throw CaptureArchiveFormatException(
                "Archive raw Visible Window Views files are incomplete",
            )
        }
    }

    private fun CaptureArchiveManifest.toDocument(
        content: Map<String, ByteArray>,
    ): CaptureArchiveDocument {
        val snapshot = content.getValue(CaptureArchivePaths.SNAPSHOT)
        val screenshot = content.getValue(CaptureArchivePaths.SCREENSHOT)
        val rawZip = content[CaptureArchivePaths.RAW_ZIP]
        val rawText = content[CaptureArchivePaths.RAW_TEXT]
        val analysisReport = content[CaptureArchivePaths.ANALYSIS_REPORT]
        val timelineHistory = content[CaptureArchivePaths.TIMELINE_HISTORY]
        return CaptureArchiveDocument(
            metadata = CaptureArchiveMetadata(
                producerVersion = producerVersion,
                packageName = packageName,
                capturedAtEpochMillis = capturedAtEpochMillis,
                protocolMajor = protocolMajor,
                protocolMinor = protocolMinor,
            ),
            payload = CaptureArchivePayload(
                snapshotJson = snapshot.toString(StandardCharsets.UTF_8),
                screenshotPng = screenshot,
                rawArtifacts = rawZip?.let {
                    CaptureRawArtifacts(
                        zip = it,
                        text = rawText!!.toString(StandardCharsets.UTF_8),
                    )
                },
                analysisReportJson = analysisReport?.toString(StandardCharsets.UTF_8),
                timelineHistoryJson = timelineHistory?.toString(StandardCharsets.UTF_8),
            ),
        )
    }

    private fun CaptureArchivePayload.toEntries(): LinkedHashMap<String, ByteArray> {
        val required = linkedMapOf(
            CaptureArchivePaths.SNAPSHOT to
                snapshotJson.toByteArray(StandardCharsets.UTF_8),
            CaptureArchivePaths.SCREENSHOT to screenshotPng,
        )
        val analysisEntries = analysisReportJson?.let { report ->
            linkedMapOf(
                CaptureArchivePaths.ANALYSIS_REPORT to report.toByteArray(StandardCharsets.UTF_8),
            )
        }
        val timelineEntries = timelineHistoryJson?.let { history ->
            linkedMapOf(
                CaptureArchivePaths.TIMELINE_HISTORY to history.toByteArray(StandardCharsets.UTF_8),
            )
        }
        val rawEntries = rawArtifacts?.let { raw ->
            linkedMapOf(
                CaptureArchivePaths.RAW_ZIP to raw.zip,
                CaptureArchivePaths.RAW_TEXT to
                    raw.text.toByteArray(StandardCharsets.UTF_8),
            )
        }
        return required.apply {
            if (analysisEntries != null && optionalEntriesFit(required, analysisEntries)) {
                putAll(analysisEntries)
            }
            if (timelineEntries != null && optionalEntriesFit(this, timelineEntries)) {
                putAll(timelineEntries)
            }
            if (rawEntries != null && optionalEntriesFit(this, rawEntries)) {
                putAll(rawEntries)
            }
        }
    }

    private fun optionalEntriesFit(
        required: Map<String, ByteArray>,
        optional: Map<String, ByteArray>,
    ): Boolean {
        val entriesFit = optional.all { (path, bytes) ->
            bytes.size.toLong() <= maximumEntryBytes(
                CaptureArchiveManifestEntry(
                    path = path,
                    size = bytes.size.toLong(),
                    sha256 = "",
                    required = false,
                ),
            )
        }
        if (!entriesFit) return false

        val total = (required.values + optional.values).sumOf { it.size.toLong() }
        return total <= limits.maxTotalUncompressedBytes
    }

    private fun validateEntrySizes(content: Map<String, ByteArray>) {
        var total = 0L
        content.forEach { (path, bytes) ->
            val maximum = maximumEntryBytes(
                CaptureArchiveManifestEntry(
                    path = path,
                    size = bytes.size.toLong(),
                    sha256 = "",
                    required = path in REQUIRED_PATHS,
                ),
            )
            require(bytes.size.toLong() <= maximum) {
                "Archive entry is too large: $path"
            }
            total += bytes.size
        }
        require(total <= limits.maxTotalUncompressedBytes) {
            "Archive uncompressed content is too large"
        }
    }

    private fun maximumEntryBytes(entry: CaptureArchiveManifestEntry): Long =
        when (entry.path) {
            CaptureArchivePaths.SNAPSHOT -> limits.maxSnapshotBytes.toLong()
            CaptureArchivePaths.SCREENSHOT -> MAX_SCREENSHOT_BYTES.toLong()
            CaptureArchivePaths.RAW_ZIP -> MAX_RAW_ZIP_BYTES.toLong()
            CaptureArchivePaths.RAW_TEXT -> MAX_RAW_TEXT_BYTES.toLong()
            CaptureArchivePaths.ANALYSIS_REPORT -> MAX_ANALYSIS_REPORT_BYTES.toLong()
            CaptureArchivePaths.TIMELINE_HISTORY -> MAX_TIMELINE_HISTORY_BYTES.toLong()
            else -> MAX_UNKNOWN_OPTIONAL_BYTES.toLong()
        }

    private fun validateSafePath(path: String) {
        val segments = path.split('/')
        if (path.isBlank() ||
            path.startsWith('/') ||
            path.contains('\\') ||
            segments.any { it.isBlank() || it == "." || it == ".." }
        ) {
            throw CaptureArchiveFormatException("Archive entry path is unsafe: $path")
        }
    }

    private fun readBounded(
        input: InputStream,
        maximum: Int,
        label: String,
    ): ByteArray {
        val output = ByteArrayOutputStream(minOf(maximum, DEFAULT_BUFFER_SIZE))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maximum) {
                throw CaptureArchiveFormatException("$label is too large")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun ZipOutputStream.writeEntry(
        path: String,
        bytes: ByteArray,
    ) {
        putNextEntry(ZipEntry(path))
        write(bytes)
        closeEntry()
    }

    private companion object {
        const val MAX_ENTRY_COUNT = 16
        const val MAX_MANIFEST_BYTES = 256 * 1024
        const val MAX_SCREENSHOT_BYTES = 32 * 1024 * 1024
        const val MAX_RAW_ZIP_BYTES = 32 * 1024 * 1024
        const val MAX_RAW_TEXT_BYTES = 8 * 1024 * 1024
        const val MAX_ANALYSIS_REPORT_BYTES = 4 * 1024 * 1024
        const val MAX_TIMELINE_HISTORY_BYTES = 4 * 1024 * 1024
        const val MAX_UNKNOWN_OPTIONAL_BYTES = 1024 * 1024
        val REQUIRED_PATHS = setOf(
            CaptureArchivePaths.SNAPSHOT,
            CaptureArchivePaths.SCREENSHOT,
        )
        val KNOWN_CONTENT_PATHS = REQUIRED_PATHS + setOf(
            CaptureArchivePaths.RAW_ZIP,
            CaptureArchivePaths.RAW_TEXT,
            CaptureArchivePaths.ANALYSIS_REPORT,
            CaptureArchivePaths.TIMELINE_HISTORY,
        )
    }
}

internal fun defaultArchiveJson(): Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    prettyPrint = true
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

private fun moveReplacingArchive(
    source: Path,
    target: Path,
) {
    try {
        Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target, REPLACE_EXISTING)
    }
}
