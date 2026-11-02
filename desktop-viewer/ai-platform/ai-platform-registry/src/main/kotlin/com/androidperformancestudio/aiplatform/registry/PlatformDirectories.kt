package com.androidperformancestudio.aiplatform.registry

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Application-owned paths; callers can supply an isolated profile root without touching user data. */
public class PlatformDirectories(
    root: Path = Path.of(System.getProperty("user.home"), ".android-performance-studio"),
) {
    public val root: Path = root.toAbsolutePath().normalize()
    public val agents: Path = this.root.resolve("agents")
    public val skills: Path = this.root.resolve("skills")
    public val conversations: Path = this.root.resolve("conversations")
    public val artifacts: Path = this.root.resolve("artifacts")
    public val providerState: Path = this.root.resolve("provider-state")
    public val audit: Path = this.root.resolve("audit")

    public fun initialize() {
        ensurePrivateDirectory(root)
        listOf(agents, skills).forEach { directory ->
            ensurePrivateDirectory(directory)
            ensurePrivateDirectory(directory.resolve("builtin"))
            ensurePrivateDirectory(directory.resolve("imported"))
        }
        listOf(conversations, artifacts, providerState, audit).forEach(::ensurePrivateDirectory)
    }

    internal fun ensurePrivateDirectory(path: Path) {
        val absolute = path.toAbsolutePath().normalize()
        registryRequire(absolute.startsWith(root), "E_MANAGED_PATH")
        var current = absolute.root
        for (part in absolute) {
            current = current.resolve(part)
            if (Files.exists(current, NOFOLLOW_LINKS)) {
                registryRequire(!Files.isSymbolicLink(current), "E_SYMLINK")
                registryRequire(Files.isDirectory(current, NOFOLLOW_LINKS), "E_DIRECTORY")
            } else {
                registryRequire(current.startsWith(root), "E_ROOT_PARENT")
                try {
                    Files.createDirectory(current, PosixFilePermissions.asFileAttribute(directoryPermissions))
                } catch (_: UnsupportedOperationException) {
                    Files.createDirectory(current)
                }
            }
            if (current.startsWith(root)) privateDirectory(current)
        }
    }

    internal fun checkManagedPath(path: Path) {
        registryRequire(path.toAbsolutePath().normalize().startsWith(root), "E_MANAGED_PATH")
        rejectSymlinkAncestors(path)
    }
}

public class RegistryException(
    public val code: String,
) : IllegalArgumentException(code)

internal fun registryRequire(
    condition: Boolean,
    code: String,
) {
    if (!condition) throw RegistryException(code)
}

/** Do not canonicalize through a symlink and then mistake its target for an authorized path. */
internal fun rejectSymlinkAncestors(path: Path) {
    val absolute = path.toAbsolutePath().normalize()
    var current = absolute.root
    for (part in absolute) {
        current = current.resolve(part)
        registryRequire(!Files.isSymbolicLink(current), "E_SYMLINK")
    }
}

internal val directoryPermissions = PosixFilePermissions.fromString("rwx------")
internal val filePermissions = PosixFilePermissions.fromString("rw-------")

internal fun privateDirectory(path: Path) {
    if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(path, directoryPermissions)
    }
}

internal fun privateFile(path: Path) {
    if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(path, filePermissions)
    }
}
