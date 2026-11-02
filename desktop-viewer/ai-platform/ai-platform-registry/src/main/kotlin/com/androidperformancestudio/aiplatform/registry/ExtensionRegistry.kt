package com.androidperformancestudio.aiplatform.registry

import com.androidperformancestudio.aiplatform.model.AuditAction
import com.androidperformancestudio.aiplatform.model.AuditRecord
import com.androidperformancestudio.aiplatform.model.ExtensionKind
import com.androidperformancestudio.aiplatform.model.ExtensionOrigin
import com.androidperformancestudio.aiplatform.model.PlatformJson
import com.androidperformancestudio.aiplatform.model.TrustStatus
import com.androidperformancestudio.aiplatform.model.requireStableId
import kotlinx.serialization.encodeToString
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * Content verification and host-owned trust state, without provider, script or tool execution.
 * Every resolution revalidates content; installation is never an implicit execution grant.
 */
public class ExtensionRegistry(
    private val directories: PlatformDirectories = PlatformDirectories(),
    private val limits: RegistryLimits = RegistryLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val atomicMove: (Path, Path) -> Unit = { source, target ->
        Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
    },
) {
    private val validator = BundleValidator(limits)

    /** Import only; imported manifests cannot choose builtin origin or a reserved builtin identity. */
    public fun install(source: Path): RegistryEntry =
        locked {
            val bundle = validator.read(source.toAbsolutePath().normalize())
            registryRequire(bundle.manifest.id !in BuiltinBundles.reservedIds, "E_RESERVED_ID")
            installVerified(bundle, ExtensionOrigin.IMPORTED)
        }

    /** Explicit host startup hook. Merely constructing a registry performs no installation. */
    public fun installBuiltins(): List<RegistryEntry> =
        locked {
            BuiltinBundles.load(limits).map { installVerified(it, ExtensionOrigin.BUILTIN) }.also { entries ->
                entries.forEach { selectVersionUnlocked(it.key) }
            }
        }

    public fun scan(): RegistryScan = locked { scanUnlocked() }

    /** Remove one exact imported version, never builtin or an inferred/latest identity. */
    public fun deleteImported(key: ExtensionKey) =
        locked {
            registryRequire(key.origin == ExtensionOrigin.IMPORTED, "E_BUILTIN_PROTECTED")
            val entry = readInstalled(key)
            val staging = createStaging()
            try {
                val quarantined = staging.resolve("deleted-package")
                directories.checkManagedPath(entry.directory)
                moveAtomically(entry.directory, quarantined)
                audit(AuditAction.DELETED, key.id, entry.contentHash)
            } finally {
                deleteStaging(staging)
            }
        }

    /** Select a stable ID only when exactly one verified version exists. Never guess newest. */
    public fun resolve(
        kind: ExtensionKind,
        id: String,
    ): RegistryEntry =
        locked {
            requireStableId(id)
            val result = scanUnlocked()
            registryRequire(result.issues.isEmpty(), "E_REGISTRY_INVALID")
            val entries = result.entries.filter { it.key.kind == kind && it.key.id == id }
            registryRequire(entries.isNotEmpty(), "E_EXTENSION_UNAVAILABLE")
            val selection = selectionPath(kind, id)
            if (Files.exists(selection, NOFOLLOW_LINKS)) {
                val selected = PlatformJson.codec.decodeFromString<SelectedVersion>(readBounded(selection, 4096).decodeToString())
                registryRequire(selected.kind == kind && selected.id == id, "E_SELECTION_INVALID")
                val selectedEntry =
                    entries.singleOrNull {
                        it.key.version == selected.version && it.key.origin == selected.origin && it.contentHash == selected.contentHash
                    }
                registryRequire(selectedEntry != null, "E_SELECTED_VERSION_UNAVAILABLE")
                return@locked selectedEntry!!
            }
            registryRequire(entries.size == 1, "E_DUPLICATE_ID")
            entries.single()
        }

    public fun resolve(key: ExtensionKey): RegistryEntry = locked { readInstalled(key) }

    /** An explicit host choice, bound to an immutable version and content hash. */
    public fun selectVersion(key: ExtensionKey): RegistryEntry = locked { selectVersionUnlocked(key) }

    private fun selectVersionUnlocked(key: ExtensionKey): RegistryEntry {
        val entry = readInstalled(key)
        val selected = SelectedVersion(key.kind, key.id, key.version, key.origin, entry.contentHash)
        val path = selectionPath(key.kind, key.id)
        directories.ensurePrivateDirectory(path.parent)
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            val existing = PlatformJson.codec.decodeFromString<SelectedVersion>(readBounded(path, 4096).decodeToString())
            if (existing == selected) return entry
        }
        writeAtomic(path, PlatformJson.codec.encodeToString(selected).toByteArray())
        audit(AuditAction.SELECTED, key.id, entry.contentHash)
        return entry
    }

    /** Trust remains separate from enablement. Revoked packages require a new version, not reset. */
    public fun trust(key: ExtensionKey): RegistryEntry =
        transition(key, AuditAction.TRUSTED) { state ->
            registryRequire(state.trustStatus != TrustStatus.REVOKED, "E_EXTENSION_REVOKED")
            state.copy(trustStatus = TrustStatus.TRUSTED)
        }

    public fun enable(key: ExtensionKey): RegistryEntry =
        transition(key, AuditAction.ENABLED) { state ->
            registryRequire(state.trustStatus == TrustStatus.TRUSTED, "E_EXTENSION_UNTRUSTED")
            state.copy(enabled = true)
        }

    public fun disable(key: ExtensionKey): RegistryEntry =
        transition(key, AuditAction.DISABLED) { state ->
            state.copy(enabled = false)
        }

    public fun revoke(key: ExtensionKey): RegistryEntry =
        transition(key, AuditAction.REVOKED) { state ->
            state.copy(trustStatus = TrustStatus.REVOKED, enabled = false)
        }

    private fun transition(
        key: ExtensionKey,
        action: AuditAction,
        update: (InstallationState) -> InstallationState,
    ): RegistryEntry =
        locked {
            val entry = readInstalled(key)
            val oldState = readState(entry.directory)
            val state = update(oldState)
            if (state != oldState) {
                writeAtomic(entry.directory.resolve("installation.json"), PlatformJson.codec.encodeToString(state).toByteArray())
                audit(action, key.id, state.contentHash)
            }
            readInstalled(key)
        }

    private fun installVerified(
        bundle: VerifiedBundle,
        origin: ExtensionOrigin,
    ): RegistryEntry {
        val manifest = bundle.manifest
        val key = ExtensionKey(manifest.kind, manifest.id, manifest.version, origin)
        val destination = packageDirectory(key)
        directories.checkManagedPath(destination)
        if (Files.exists(destination, NOFOLLOW_LINKS)) {
            val existing = readInstalled(key)
            registryRequire(existing.contentHash == bundle.contentHash, "E_VERSION_CONFLICT")
            return existing
        }
        val state =
            InstallationState(
                id = key.id,
                version = key.version,
                kind = key.kind,
                origin = origin,
                contentHash = bundle.contentHash,
                trustStatus = if (origin == ExtensionOrigin.BUILTIN) TrustStatus.TRUSTED else TrustStatus.UNTRUSTED,
                enabled = origin == ExtensionOrigin.BUILTIN,
            )
        val staging = createStaging()
        try {
            writePrivate(staging.resolve("manifest.json"), bundle.manifestBytes)
            bundle.files.forEach { (relative, bytes) ->
                val target = staging.resolve(relative)
                directories.ensurePrivateDirectory(target.parent)
                writePrivate(target, bytes)
            }
            writePrivate(staging.resolve("installation.json"), PlatformJson.codec.encodeToString(state).toByteArray())
            // Validate the exact staged bytes before publication, not only the source directory.
            registryRequire(validator.read(staging, installed = true).contentHash == bundle.contentHash, "E_HASH_MISMATCH")
            directories.ensurePrivateDirectory(destination.parent)
            directories.checkManagedPath(destination)
            registryRequire(!Files.exists(destination, NOFOLLOW_LINKS), "E_VERSION_CONFLICT")
            moveAtomically(staging, destination)
        } finally {
            deleteStaging(staging)
        }
        audit(AuditAction.INSTALLED, key.id, bundle.contentHash)
        return RegistryEntry(key, manifest, destination, bundle.contentHash, state.trustStatus, state.enabled)
    }

    private fun readInstalled(key: ExtensionKey): RegistryEntry {
        val directory = packageDirectory(key)
        directories.checkManagedPath(directory)
        registryRequire(Files.isDirectory(directory, NOFOLLOW_LINKS), "E_EXTENSION_UNAVAILABLE")
        val bundle = validator.read(directory, installed = true)
        val state = readState(directory)
        registryRequire(
            bundle.manifest.kind == key.kind && bundle.manifest.id == key.id && bundle.manifest.version == key.version,
            "E_PACKAGE_IDENTITY",
        )
        registryRequire(
            state.kind == key.kind && state.id == key.id && state.version == key.version && state.origin == key.origin,
            "E_INSTALLATION_IDENTITY",
        )
        registryRequire(state.contentHash == bundle.contentHash, "E_HASH_MISMATCH")
        var qualifiedBuiltin = true
        if (key.origin == ExtensionOrigin.IMPORTED) {
            registryRequire(key.id !in BuiltinBundles.reservedIds, "E_RESERVED_ID")
        } else {
            val builtin =
                BuiltinBundles.load(limits).singleOrNull {
                    it.manifest.kind == key.kind && it.manifest.id == key.id && it.manifest.version == key.version
                }
            if (builtin == null) {
                // Preserve old versions for history/inspection, without treating unknown old content as executable.
                qualifiedBuiltin = false
            } else {
                registryRequire(builtin.contentHash == bundle.contentHash, "E_BUILTIN_CONTENT")
            }
        }
        return RegistryEntry(key, bundle.manifest, directory, bundle.contentHash, state.trustStatus, state.enabled && qualifiedBuiltin)
    }

    private fun readState(directory: Path): InstallationState {
        val bytes = readBounded(directory.resolve("installation.json"), limits.maxManifestBytes)
        return try {
            PlatformJson.codec.decodeFromString<InstallationState>(bytes.decodeToString(throwOnInvalidSequence = true))
        } catch (_: Exception) {
            throw RegistryException("E_INSTALLATION_INVALID")
        }
    }

    private fun scanUnlocked(): RegistryScan {
        val entries = mutableListOf<RegistryEntry>()
        val issues = mutableListOf<RegistryIssue>()
        var visited = 0

        fun children(
            directory: Path,
            visit: (Path) -> Unit,
        ) {
            directories.checkManagedPath(directory)
            Files.newDirectoryStream(directory).use { stream ->
                for (child in stream) {
                    registryRequire(++visited <= limits.maxInstalledVersions * 3, "E_REGISTRY_LIMIT")
                    try {
                        directories.checkManagedPath(child)
                        registryRequire(Files.isDirectory(child, NOFOLLOW_LINKS), "E_DIRECTORY")
                        visit(child)
                    } catch (failure: Exception) {
                        issues += RegistryIssue((failure as? RegistryException)?.code ?: "E_REGISTRY_INVALID")
                    }
                }
            }
        }
        for (kind in ExtensionKind.entries) {
            for (origin in ExtensionOrigin.entries) {
                val base = kindDirectory(kind).resolve(origin.name.lowercase())
                children(base) { idDirectory ->
                    children(idDirectory) { versionDirectory ->
                        registryRequire(entries.size < limits.maxInstalledVersions, "E_REGISTRY_LIMIT")
                        val key = ExtensionKey(kind, idDirectory.fileName.toString(), versionDirectory.fileName.toString(), origin)
                        entries += readInstalled(key)
                    }
                }
            }
        }
        return RegistryScan(entries, issues)
    }

    private fun packageDirectory(key: ExtensionKey): Path =
        kindDirectory(key.kind).resolve(key.origin.name.lowercase()).resolve(key.id).resolve(key.version)

    private fun kindDirectory(kind: ExtensionKind): Path = if (kind == ExtensionKind.AGENT) directories.agents else directories.skills

    private fun selectionPath(
        kind: ExtensionKind,
        id: String,
    ): Path = directories.providerState.resolve("extension-selection").resolve("${kind.name.lowercase()}-$id.json")

    private fun audit(
        action: AuditAction,
        id: String,
        contentHash: String,
    ) {
        val record = AuditRecord(action = action, subjectId = id, timestampEpochMillis = clock(), contentHash = contentHash)
        val directory = directories.audit.resolve("registry")
        directories.ensurePrivateDirectory(directory)
        writeAtomic(directory.resolve("${UUID.randomUUID()}.json"), PlatformJson.codec.encodeToString(record).toByteArray())
    }

    private fun writeAtomic(
        target: Path,
        bytes: ByteArray,
    ) {
        directories.checkManagedPath(target)
        val staging = createStaging()
        try {
            val temporary = staging.resolve("state.json")
            writePrivate(temporary, bytes)
            directories.checkManagedPath(target)
            moveAtomically(temporary, target)
        } finally {
            deleteStaging(staging)
        }
    }

    private fun createStaging(): Path {
        val stagingRoot = directories.root.resolve(".registry-staging")
        directories.ensurePrivateDirectory(stagingRoot)
        return Files.createTempDirectory(stagingRoot, "package-").also(::privateDirectory)
    }

    private fun moveAtomically(
        source: Path,
        target: Path,
    ) {
        try {
            atomicMove(source, target)
        } catch (_: AtomicMoveNotSupportedException) {
            throw RegistryException("E_ATOMIC_MOVE_UNAVAILABLE")
        }
    }

    @Synchronized
    private fun <T> locked(block: () -> T): T {
        try {
            directories.initialize()
            val lock = directories.providerState.resolve("registry.lock")
            directories.checkManagedPath(lock)
            FileChannel.open(lock, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                privateFile(lock)
                channel.lock().use { return block() }
            }
        } catch (failure: RegistryException) {
            throw failure
        } catch (_: OverlappingFileLockException) {
            // A different registry instance in this JVM owns the same profile: fail closed, retry later.
            throw RegistryException("E_REGISTRY_BUSY")
        } catch (_: Exception) {
            // No absolute paths, raw filesystem messages or user-supplied exception bodies escape.
            throw RegistryException("E_REGISTRY_IO")
        }
    }
}

private fun writePrivate(
    path: Path,
    bytes: ByteArray,
) {
    Files.write(path, bytes, java.nio.file.StandardOpenOption.CREATE_NEW, WRITE)
    privateFile(path)
}

/** Only an exact staging path allocated by this registry is cleaned; never follow links. */
private fun deleteStaging(path: Path) {
    if (!Files.exists(path, NOFOLLOW_LINKS)) return
    Files.walkFileTree(
        path,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                dir: Path,
                exc: java.io.IOException?,
            ): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}
