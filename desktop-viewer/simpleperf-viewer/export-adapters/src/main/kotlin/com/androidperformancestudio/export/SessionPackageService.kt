@file:Suppress("TooManyFunctions")

package com.androidperformancestudio.export

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

data class SessionPackageExportResult(
    val archive: Path,
    val fileCount: Int,
)

data class SessionPackageImportResult(
    val sessionDirectory: Path,
    val verifiedFiles: Int,
)

class SessionPackageException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

class SessionPackageService(
    private val maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    init {
        require(maxEntryBytes > 0) { "maxEntryBytes must be positive" }
        require(maxTotalBytes > 0) { "maxTotalBytes must be positive" }
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    fun export(
        sessionDirectory: Path,
        destinationArchive: Path,
    ): SessionPackageExportResult {
        require(Files.isDirectory(sessionDirectory)) { "Session directory does not exist: $sessionDirectory" }
        val files = sessionFiles(sessionDirectory, destinationArchive)
        val manifest = files.associate { relativePath(sessionDirectory, it) to it.sha256() }
        destinationArchive.parent?.createDirectories()
        val temporary = destinationArchive.resolveSibling(".${destinationArchive.fileName}.${UUID.randomUUID()}.tmp")
        try {
            ZipOutputStream(BufferedOutputStream(Files.newOutputStream(temporary))).use { zip ->
                files.forEach { file -> zip.addFile(relativePath(sessionDirectory, file), file) }
                zip.addBytes(MANIFEST_ENTRY, manifest.serializeManifest())
            }
            Files.move(temporary, destinationArchive, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return SessionPackageExportResult(destinationArchive, files.size)
    }

    fun import(
        archive: Path,
        destinationRoot: Path,
    ): SessionPackageImportResult {
        require(archive.isRegularFile()) { "Session package does not exist: $archive" }
        destinationRoot.createDirectories()
        val finalDirectory = uniqueDestination(destinationRoot, archive.sessionName())
        val temporary = destinationRoot.resolve(".${finalDirectory.name}.${UUID.randomUUID()}.import")
        temporary.createDirectories()
        try {
            val manifest = extractAndReadManifest(archive, temporary)
            verifyManifest(temporary, manifest)
            Files.move(temporary, finalDirectory)
            return SessionPackageImportResult(finalDirectory, manifest.size)
        } catch (exception: SessionPackageException) {
            temporary.deleteRecursively()
            throw exception
        } catch (exception: IOException) {
            temporary.deleteRecursively()
            throw SessionPackageException("Failed to import session package: ${exception.message}", exception)
        }
    }

    @Suppress("NestedBlockDepth", "ThrowsCount")
    private fun extractAndReadManifest(
        archive: Path,
        temporary: Path,
    ): Map<String, String> {
        var manifest: Map<String, String>? = null
        var totalBytes = 0L
        var entryCount = 0
        val entryNames = mutableSetOf<String>()
        ZipFile(archive.toFile()).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entryNames.add(entry.name)) {
                    throw SessionPackageException("Duplicate session package entry: ${entry.name}")
                }
                entryCount += 1
                if (entryCount > maxEntries) throw SessionPackageException("Session package contains too many entries")
                val output = safeOutputPath(temporary, entry.name)
                if (entry.isDirectory) {
                    output.createDirectories()
                } else if (entry.name == MANIFEST_ENTRY) {
                    val bytes =
                        zip.getInputStream(entry).use {
                            it.readBounded(maxEntryBytes, maxTotalBytes - totalBytes)
                        }
                    totalBytes += bytes.size
                    manifest = bytes.toString(StandardCharsets.UTF_8).parseManifest()
                } else {
                    output.parent.createDirectories()
                    BufferedInputStream(zip.getInputStream(entry)).use { input ->
                        BufferedOutputStream(Files.newOutputStream(output)).use { fileOutput ->
                            totalBytes += input.copyBounded(fileOutput, maxEntryBytes, maxTotalBytes - totalBytes)
                        }
                    }
                }
            }
        }
        return manifest ?: throw SessionPackageException("Session package manifest is missing")
    }

    private fun verifyManifest(
        directory: Path,
        manifest: Map<String, String>,
    ) {
        manifest.forEach { (relative, expectedHash) ->
            val file = safeOutputPath(directory, relative)
            if (!file.isRegularFile() || file.sha256() != expectedHash) {
                throw SessionPackageException("Session package checksum mismatch: $relative")
            }
        }
        val extracted =
            sessionFiles(directory, directory.resolve("unused"))
                .map { relativePath(directory, it) }
                .toSet()
        if (extracted != manifest.keys) {
            throw SessionPackageException("Session package file list does not match manifest")
        }
    }
}

private fun sessionFiles(
    directory: Path,
    destinationArchive: Path,
): List<Path> =
    Files.walk(directory).use { paths ->
        paths
            .peek { path ->
                if (Files.isSymbolicLink(path)) {
                    throw SessionPackageException("Session directory contains a symbolic link: $path")
                }
            }.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .filter { it.toAbsolutePath().normalize() != destinationArchive.toAbsolutePath().normalize() }
            .filter { it.fileName.toString() != MANIFEST_ENTRY }
            .sorted()
            .toList()
    }

private fun ZipOutputStream.addFile(
    name: String,
    file: Path,
) {
    putNextEntry(ZipEntry(name).apply { time = ZIP_EPOCH_MILLIS })
    Files.newInputStream(file).use { it.copyTo(this) }
    closeEntry()
}

private fun ZipOutputStream.addBytes(
    name: String,
    bytes: ByteArray,
) {
    putNextEntry(ZipEntry(name).apply { time = ZIP_EPOCH_MILLIS })
    write(bytes)
    closeEntry()
}

private fun Map<String, String>.serializeManifest(): ByteArray =
    ("schema=1\n" + entries.sortedBy(Map.Entry<String, String>::key).joinToString("") { "${it.value}  ${it.key}\n" })
        .toByteArray(StandardCharsets.UTF_8)

private fun String.parseManifest(): Map<String, String> {
    val lines = lineSequence().filter(String::isNotBlank).toList()
    if (lines.firstOrNull() != "schema=1") throw SessionPackageException("Unsupported session package schema")
    return lines.drop(1).associate { line ->
        val separator = line.indexOf("  ")
        if (separator != SHA_256_HEX_LENGTH) throw SessionPackageException("Invalid session package manifest")
        line.substring(separator + 2) to line.substring(0, separator)
    }
}

private fun safeOutputPath(
    root: Path,
    entryName: String,
): Path {
    if (entryName.isBlank() || entryName.startsWith('/') || entryName.startsWith('\\')) {
        throw SessionPackageException("Unsafe session package entry: $entryName")
    }
    val output = root.resolve(entryName).normalize()
    if (!output.startsWith(root.normalize())) throw SessionPackageException("Unsafe session package entry: $entryName")
    return output
}

private fun Path.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(this).use { input ->
        val buffer = ByteArray(HASH_BUFFER_SIZE)
        var count = input.read(buffer)
        while (count >= 0) {
            if (count > 0) digest.update(buffer, 0, count)
            count = input.read(buffer)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun java.io.InputStream.readBounded(
    maxEntryBytes: Long,
    remainingTotalBytes: Long,
): ByteArray {
    val output = ByteArrayOutputStream()
    copyBounded(output, maxEntryBytes, remainingTotalBytes)
    return output.toByteArray()
}

@Suppress("ThrowsCount")
private fun java.io.InputStream.copyBounded(
    output: java.io.OutputStream,
    maxEntryBytes: Long,
    remainingTotalBytes: Long,
): Long {
    if (remainingTotalBytes < 0) throw SessionPackageException("Session package exceeds total size limit")
    val buffer = ByteArray(HASH_BUFFER_SIZE)
    var written = 0L
    var count = read(buffer)
    while (count >= 0) {
        if (count > 0) {
            written += count
            if (written > maxEntryBytes) throw SessionPackageException("Session package entry exceeds size limit")
            if (written > remainingTotalBytes) throw SessionPackageException("Session package exceeds total size limit")
            output.write(buffer, 0, count)
        }
        count = read(buffer)
    }
    return written
}

private fun relativePath(
    root: Path,
    file: Path,
): String = root.relativize(file).joinToString("/")

private fun Path.sessionName(): String =
    fileName
        .toString()
        .removeSuffix(".apsession.zip")
        .removeSuffix(".zip")
        .ifBlank { "imported-session" }

private fun uniqueDestination(
    root: Path,
    requestedName: String,
): Path {
    var candidate = root.resolve(requestedName)
    var suffix = 1
    while (Files.exists(candidate)) candidate = root.resolve("$requestedName-${suffix++}")
    return candidate
}

private fun Path.deleteRecursively() {
    if (!Files.exists(this)) return
    Files.walk(this).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
}

private const val MANIFEST_ENTRY = "apsession-manifest.txt"
private const val HASH_BUFFER_SIZE = 64 * 1024
private const val SHA_256_HEX_LENGTH = 64
private const val ZIP_EPOCH_MILLIS = 0L
private const val DEFAULT_MAX_ENTRY_BYTES = 4L * 1024 * 1024 * 1024
private const val DEFAULT_MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
private const val DEFAULT_MAX_ENTRIES = 100_000
