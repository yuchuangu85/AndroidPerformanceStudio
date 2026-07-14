@file:Suppress("TooManyFunctions")

package com.androidperformancestudio.application

import com.androidperformancestudio.storage.SQLiteSampleStore
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.isWritable
import kotlin.io.path.readLines

enum class ProfileSessionMode {
    READ_WRITE_V2,
    LEGACY_READ_ONLY,
}

data class PreparedProfileSession(
    val database: Path,
    val originalDatabase: Path,
    val mode: ProfileSessionMode,
    val schemaVersion: Int?,
)

internal fun interface CandidateDatabaseMigrator {
    fun migrate(candidate: Path)

    companion object {
        fun default(): CandidateDatabaseMigrator =
            CandidateDatabaseMigrator { candidate ->
                SQLiteSampleStore.open(candidate).use { store ->
                    check(store.schemaVersion() == CURRENT_SCHEMA_VERSION) {
                        "Candidate migration did not reach schema $CURRENT_SCHEMA_VERSION"
                    }
                    store.checkpointWal()
                }
            }
    }
}

internal enum class ProfileMigrationCheckpoint {
    AFTER_BACKUP_PUBLISHED,
    AFTER_METADATA_PUBLISHED,
    BEFORE_FINAL_MOVE,
    AFTER_FINAL_MOVE,
}

internal fun interface MigrationCheckpoint {
    fun reached(point: ProfileMigrationCheckpoint)

    companion object {
        val NONE = MigrationCheckpoint { }
    }
}

internal fun interface FinalDatabaseMover {
    fun replace(
        candidate: Path,
        original: Path,
    )

    companion object {
        val ATOMIC =
            FinalDatabaseMover { candidate, original ->
                Files.move(
                    candidate,
                    original,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
    }
}

class ProfileSessionMigrator internal constructor(
    private val candidateMigrator: CandidateDatabaseMigrator,
    private val checkpoint: MigrationCheckpoint = MigrationCheckpoint.NONE,
    private val finalDatabaseMover: FinalDatabaseMover = FinalDatabaseMover.ATOMIC,
) {
    constructor() : this(CandidateDatabaseMigrator.default())

    fun prepare(sessionDirectory: Path): PreparedProfileSession {
        val session = sessionDirectory.toAbsolutePath().normalize()
        val original = session.resolve(PROFILE_DATABASE)
        val version = inspectSafeDatabase(session, original)
        val mode =
            when (version) {
                CURRENT_SCHEMA_VERSION -> ProfileSessionMode.READ_WRITE_V2
                LEGACY_SCHEMA_VERSION -> migrateLegacy(session, original)
                else -> ProfileSessionMode.LEGACY_READ_ONLY
            }
        val preparedVersion = if (mode == ProfileSessionMode.READ_WRITE_V2) CURRENT_SCHEMA_VERSION else version
        return PreparedProfileSession(original, original, mode, preparedVersion)
    }

    private fun inspectSafeDatabase(
        session: Path,
        original: Path,
    ): Int? {
        val safePaths =
            !Files.isSymbolicLink(session) &&
                !Files.isSymbolicLink(original) &&
                Files.isDirectory(session, LinkOption.NOFOLLOW_LINKS) &&
                Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)
        val safeJournal = sourceSidecars(original).none(Path::exists)
        return if (safePaths && safeJournal) {
            runCatching { SQLiteSampleStore.schemaVersion(original) }.getOrNull()
        } else {
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun migrateLegacy(
        session: Path,
        original: Path,
    ): ProfileSessionMode {
        val paths = MigrationPaths(session)
        val initialIdentity = runCatching { sourceIdentity(original) }.getOrNull()
        if (initialIdentity == null || sourceSidecars(original).any(Path::exists)) {
            return ProfileSessionMode.LEGACY_READ_ONLY
        }
        var createdBackup = false
        var createdProperties = false
        return try {
            cleanupRequired(paths.scratchFiles)
            SQLiteSampleStore.withExclusiveSnapshot(
                databasePath = original,
                snapshotPath = paths.candidate,
                expectedVersion = LEGACY_SCHEMA_VERSION,
            ) {
                requireStableSource(original, initialIdentity)
                val sourceHash = initialIdentity.sha256
                if (paths.backup.exists() || paths.properties.exists()) {
                    validatePublishedBackup(paths, sourceHash)
                } else {
                    publishBackup(paths)
                    createdBackup = true
                    checkpoint.reached(ProfileMigrationCheckpoint.AFTER_BACKUP_PUBLISHED)
                    publishMetadata(paths, sourceHash)
                    createdProperties = true
                    checkpoint.reached(ProfileMigrationCheckpoint.AFTER_METADATA_PUBLISHED)
                }

                candidateMigrator.migrate(paths.candidate)
                check(SQLiteSampleStore.schemaVersion(paths.candidate) == CURRENT_SCHEMA_VERSION)
                forceFile(paths.candidate)
                cleanupRequired(candidateArtifacts(paths.candidate).drop(1))
                checkpoint.reached(ProfileMigrationCheckpoint.BEFORE_FINAL_MOVE)
                requireStableSource(original, initialIdentity)

                finalDatabaseMover.replace(paths.candidate, original)
                forceDirectory(session)
                runCatching { checkpoint.reached(ProfileMigrationCheckpoint.AFTER_FINAL_MOVE) }
            }
            ProfileSessionMode.READ_WRITE_V2
        } catch (_: Exception) {
            cleanupQuietly(paths.scratchFiles)
            if (createdProperties) deleteQuietly(paths.properties)
            if (createdBackup) deleteQuietly(paths.backup)
            ProfileSessionMode.LEGACY_READ_ONLY
        }
    }

    private fun publishBackup(paths: MigrationPaths) {
        Files.copy(paths.candidate, paths.backupCandidate, StandardCopyOption.COPY_ATTRIBUTES)
        check(SQLiteSampleStore.schemaVersion(paths.backupCandidate) == LEGACY_SCHEMA_VERSION)
        forceFile(paths.backupCandidate)
        check(paths.backupCandidate.toFile().setReadOnly()) { "Could not make legacy backup immutable" }
        check(!paths.backupCandidate.isWritable()) { "Legacy backup candidate remains writable" }
        moveNewFileAtomically(paths.backupCandidate, paths.backup)
        forceDirectory(paths.session)
    }

    private fun publishMetadata(
        paths: MigrationPaths,
        sourceHash: String,
    ) {
        Files.writeString(
            paths.propertiesCandidate,
            "$PROFILE_BACKUP_SHA256=${sha256(paths.backup)}\n" +
                "$PROFILE_SOURCE_SHA256=$sourceHash\n" +
                "$PROFILE_SOURCE_SCHEMA=$LEGACY_SCHEMA_VERSION\n",
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
        forceFile(paths.propertiesCandidate)
        check(paths.propertiesCandidate.toFile().setReadOnly()) { "Could not make migration metadata immutable" }
        moveNewFileAtomically(paths.propertiesCandidate, paths.properties)
        forceDirectory(paths.session)
    }

    private fun validatePublishedBackup(
        paths: MigrationPaths,
        sourceHash: String,
    ) {
        check(
            Files.isRegularFile(paths.backup, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(paths.backup),
        )
        check(
            Files.isRegularFile(paths.properties, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(paths.properties),
        )
        check(!paths.backup.isWritable()) { "Existing legacy backup is writable" }
        check(!paths.properties.isWritable()) { "Existing migration metadata is writable" }
        check(SQLiteSampleStore.schemaVersion(paths.backup) == LEGACY_SCHEMA_VERSION)
        val metadata = readMetadata(paths.properties)
        check(metadata[PROFILE_SOURCE_SCHEMA] == LEGACY_SCHEMA_VERSION.toString())
        check(metadata[PROFILE_SOURCE_SHA256] == sourceHash)
        check(metadata[PROFILE_BACKUP_SHA256] == sha256(paths.backup))
        check(metadata[PROFILE_BACKUP_SHA256] == sha256(paths.candidate))
    }
}

private data class MigrationPaths(
    val session: Path,
) {
    val candidate: Path = session.resolve(CANDIDATE_DATABASE)
    val backup: Path = session.resolve(PROFILE_BACKUP)
    val backupCandidate: Path = session.resolve(BACKUP_CANDIDATE)
    val properties: Path = session.resolve(MIGRATION_PROPERTIES)
    val propertiesCandidate: Path = session.resolve(PROPERTIES_CANDIDATE)
    val scratchFiles: List<Path> = listOf(candidate, backupCandidate, propertiesCandidate)
}

private data class SourceIdentity(
    val fileKey: Any?,
    val size: Long,
    val sha256: String,
)

private fun sourceIdentity(path: Path): SourceIdentity {
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    check(attributes.isRegularFile && !attributes.isSymbolicLink)
    return SourceIdentity(attributes.fileKey(), attributes.size(), sha256(path))
}

private fun requireStableSource(
    original: Path,
    expected: SourceIdentity,
) {
    check(sourceSidecars(original).none(Path::exists)) { "Source database has active or stale sidecars" }
    check(sourceIdentity(original) == expected) { "Source database changed during migration" }
}

private fun sourceSidecars(database: Path): List<Path> = sidecars(database)

private fun candidateArtifacts(database: Path): List<Path> = listOf(database) + sidecars(database)

private fun sidecars(database: Path): List<Path> =
    listOf("-wal", "-shm", "-journal").map { suffix ->
        database.resolveSibling(database.fileName.toString() + suffix)
    }

private fun readMetadata(path: Path): Map<String, String> =
    path
        .readLines()
        .filter { it.isNotBlank() && !it.startsWith('#') && '=' in it }
        .associate { line -> line.substringBefore('=') to line.substringAfter('=') }

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(HASH_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun cleanupRequired(scratchFiles: List<Path>) {
    val failures = deleteIndependently(scratchFiles.flatMap(::candidateArtifacts))
    if (failures.isNotEmpty()) throw IOException("Could not clean migration scratch files", failures.first())
}

private fun cleanupQuietly(scratchFiles: List<Path>) {
    deleteIndependently(scratchFiles.flatMap(::candidateArtifacts))
}

@Suppress("TooGenericExceptionCaught")
private fun deleteIndependently(paths: List<Path>): List<Exception> =
    buildList {
        paths.distinct().forEach { path ->
            try {
                Files.deleteIfExists(path)
            } catch (failure: Exception) {
                add(failure)
            }
        }
    }

private fun deleteQuietly(path: Path) {
    deleteIndependently(listOf(path))
}

private fun forceFile(path: Path) {
    FileChannel.open(path, StandardOpenOption.WRITE).use { channel -> channel.force(true) }
}

@Suppress("TooGenericExceptionCaught")
private fun forceDirectory(directory: Path) {
    try {
        FileChannel.open(directory, StandardOpenOption.READ).use { channel -> channel.force(true) }
    } catch (_: Exception) {
        // Directory fsync is unavailable on some supported desktop filesystems.
    }
}

private fun moveNewFileAtomically(
    source: Path,
    target: Path,
) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (unsupported: AtomicMoveNotSupportedException) {
        throw IOException("Atomic migration artifact publication is not supported", unsupported)
    }
}

private const val CURRENT_SCHEMA_VERSION = 2
private const val LEGACY_SCHEMA_VERSION = 1
private const val PROFILE_DATABASE = "profile.sqlite"
private const val PROFILE_BACKUP = "profile.v1.sqlite"
private const val PROFILE_BACKUP_SHA256 = "profile.v1.sqlite.sha256"
private const val PROFILE_SOURCE_SHA256 = "profile.sqlite.source.sha256"
private const val PROFILE_SOURCE_SCHEMA = "profile.sqlite.source.schema"
private const val MIGRATION_PROPERTIES = "migration.properties"
private const val CANDIDATE_DATABASE = "profile.sqlite.migrating"
private const val BACKUP_CANDIDATE = "profile.v1.sqlite.creating"
private const val PROPERTIES_CANDIDATE = "migration.properties.creating"
private const val HASH_BUFFER_SIZE = 64 * 1024
