package com.androidperformancestudio.aiplatform.script

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

internal fun checkPath(path: Path) {
    val normalized = path.toAbsolutePath().normalize()
    var current = normalized.root
    normalized.forEach { component ->
        current = current.resolve(component)
        scriptRequire(!Files.isSymbolicLink(current), "E_SYMLINK")
    }
}

internal fun privateDirectory(path: Path) {
    checkPath(path)
    if (!Files.exists(path, NOFOLLOW_LINKS)) {
        path.parent?.let { parent ->
            if (!Files.exists(parent, NOFOLLOW_LINKS)) privateDirectory(parent)
        }
        try {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } catch (_: UnsupportedOperationException) {
            Files.createDirectory(path)
        }
    }
    scriptRequire(Files.isDirectory(path, NOFOLLOW_LINKS), "E_DIRECTORY")
    if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
    }
}

internal fun privateWrite(
    path: Path,
    bytes: ByteArray,
) {
    checkPath(path)
    Files.write(path, bytes, java.nio.file.StandardOpenOption.CREATE_NEW)
    if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    }
}

internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun safeRead(
    path: Path,
    limit: Int,
): ByteArray {
    checkPath(path)
    scriptRequire(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) <= limit, "E_FILE_LIMIT")
    val bytes =
        Files.newByteChannel(path, setOf(java.nio.file.StandardOpenOption.READ, NOFOLLOW_LINKS)).use { channel ->
            java.nio.channels.Channels
                .newInputStream(channel)
                .use { it.readNBytes(limit + 1) }
        }
    scriptRequire(bytes.size <= limit, "E_FILE_LIMIT")
    return bytes
}
