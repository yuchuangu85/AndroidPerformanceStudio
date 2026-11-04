package com.androidperformancestudio.aiplatform.registry

import com.androidperformancestudio.aiplatform.model.ExtensionManifest
import com.androidperformancestudio.aiplatform.model.PlatformJson
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal data class VerifiedBundle(
    val manifest: ExtensionManifest,
    val manifestBytes: ByteArray,
    val files: Map<String, ByteArray>,
    val contentHash: String,
)

internal class BundleValidator(
    private val limits: RegistryLimits,
) {
    fun read(
        directory: Path,
        installed: Boolean = false,
    ): VerifiedBundle {
        rejectSymlinkAncestors(directory)
        registryRequire(Files.isDirectory(directory, NOFOLLOW_LINKS), "E_BUNDLE_DIRECTORY")
        val manifestBytes = readBounded(directory.resolve("manifest.json"), limits.maxManifestBytes)
        val manifest =
            try {
                PlatformJson.decodeManifest(manifestBytes)
            } catch (_: Exception) {
                throw RegistryException("E_MANIFEST_INVALID")
            }
        val actualFiles = linkedSetOf<String>()
        var entryCount = 0
        Files.walkFileTree(
            directory,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    registryRequire(++entryCount <= limits.maxTreeEntries, "E_TREE_LIMIT")
                    registryRequire(!attrs.isSymbolicLink, "E_SYMLINK")
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    registryRequire(++entryCount <= limits.maxTreeEntries, "E_TREE_LIMIT")
                    registryRequire(!attrs.isSymbolicLink, "E_SYMLINK")
                    registryRequire(attrs.isRegularFile, "E_FILE_TYPE")
                    val relative = directory.relativize(file).joinToString("/")
                    registryRequire(
                        relative == "manifest.json" ||
                            relative in manifest.files ||
                            (installed && relative == "installation.json"),
                        "E_UNDECLARED_FILE",
                    )
                    actualFiles += relative
                    return FileVisitResult.CONTINUE
                }
            },
        )
        registryRequire(actualFiles.containsAll(manifest.files.keys), "E_MISSING_FILE")
        var totalBytes = manifestBytes.size.toLong()
        val files =
            manifest.files.toSortedMap().mapValues { (relative, expectedHash) ->
                val maxBytes = if (relative in manifest.scripts) limits.maxScriptBytes else limits.maxFileBytes
                val bytes = readBounded(directory.resolve(relative), maxBytes)
                totalBytes += bytes.size
                registryRequire(totalBytes <= limits.maxPackageBytes, "E_PACKAGE_LIMIT")
                registryRequire(sha256(bytes) == expectedHash, "E_HASH_MISMATCH")
                bytes
            }
        return VerifiedBundle(manifest, manifestBytes, files, packageHash(manifestBytes, files))
    }
}

internal fun readBounded(
    path: Path,
    maxBytes: Int,
): ByteArray {
    rejectSymlinkAncestors(path)
    registryRequire(Files.isRegularFile(path, NOFOLLOW_LINKS), "E_FILE_TYPE")
    registryRequire(Files.size(path) <= maxBytes, "E_FILE_LIMIT")
    val bytes =
        Files.newByteChannel(path, setOf(java.nio.file.StandardOpenOption.READ, NOFOLLOW_LINKS)).use { channel ->
            java.nio.channels.Channels
                .newInputStream(channel)
                .use { it.readNBytes(maxBytes + 1) }
        }
    registryRequire(bytes.size <= maxBytes, "E_FILE_LIMIT")
    return bytes
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun packageHash(
    manifest: ByteArray,
    files: Map<String, ByteArray>,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(manifest)
    files.toSortedMap().forEach { (path, bytes) ->
        digest.update(0.toByte())
        digest.update(path.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
