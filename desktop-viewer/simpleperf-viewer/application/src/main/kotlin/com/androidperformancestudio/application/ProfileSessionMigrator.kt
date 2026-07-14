package com.androidperformancestudio.application

import com.androidperformancestudio.storage.SQLiteSampleStore
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.exists

enum class ProfileSessionMode {
    READ_WRITE_V2,
    LEGACY_READ_ONLY,
}

data class PreparedProfileSession(
    val database: Path,
    val originalDatabase: Path,
    val mode: ProfileSessionMode,
)

internal fun interface CandidateDatabaseMigrator {
    fun migrate(candidate: Path)
}

class ProfileSessionMigrator internal constructor(
    private val candidateMigrator: CandidateDatabaseMigrator,
) {
    constructor() : this(
        CandidateDatabaseMigrator { candidate ->
            SQLiteSampleStore.open(candidate).use { store ->
                check(store.schemaVersion() == CURRENT_SCHEMA_VERSION) {
                    "Candidate migration did not reach schema $CURRENT_SCHEMA_VERSION"
                }
                store.checkpointWal()
            }
        },
    )

    fun prepare(sessionDirectory: Path): PreparedProfileSession {
        val session = sessionDirectory.toAbsolutePath().normalize()
        val original = session.resolve(PROFILE_DATABASE)
        val version = runCatching { SQLiteSampleStore.schemaVersion(original) }.getOrNull()
        val mode =
            when (version) {
                CURRENT_SCHEMA_VERSION -> ProfileSessionMode.READ_WRITE_V2
                LEGACY_SCHEMA_VERSION -> migrateLegacy(session, original)
                else -> ProfileSessionMode.LEGACY_READ_ONLY
            }
        return PreparedProfileSession(original, original, mode)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun migrateLegacy(
        session: Path,
        original: Path,
    ): ProfileSessionMode {
        val paths = MigrationPaths(session)
        var createdBackup = false
        var previousProperties: ByteArray? = null
        var publishedProperties = false
        return try {
            previousProperties = paths.properties.takeIf(Files::isRegularFile)?.let(Files::readAllBytes)
            cleanup(paths.candidate, paths.backupCandidate, paths.propertiesCandidate)
            Files.copy(original, paths.candidate, StandardCopyOption.COPY_ATTRIBUTES)
            candidateMigrator.migrate(paths.candidate)
            check(SQLiteSampleStore.schemaVersion(paths.candidate) == CURRENT_SCHEMA_VERSION) {
                "Candidate database schema is not $CURRENT_SCHEMA_VERSION"
            }
            forceFile(paths.candidate)
            cleanupSidecars(paths.candidate)

            if (!paths.backup.exists()) {
                Files.copy(original, paths.backupCandidate, StandardCopyOption.COPY_ATTRIBUTES)
                forceFile(paths.backupCandidate)
                moveNewFileAtomically(paths.backupCandidate, paths.backup)
                createdBackup = true
                check(paths.backup.toFile().setReadOnly()) { "Could not make the legacy backup read-only" }
            }
            writeMigrationProperties(paths.propertiesCandidate, paths.backup)
            forceFile(paths.propertiesCandidate)
            Files.move(
                paths.propertiesCandidate,
                paths.properties,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            publishedProperties = true
            forceDirectory(session)

            Files.move(
                paths.candidate,
                original,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            forceDirectory(session)
            ProfileSessionMode.READ_WRITE_V2
        } catch (_: Exception) {
            cleanupQuietly(paths.candidate, paths.backupCandidate, paths.propertiesCandidate)
            if (createdBackup) deleteQuietly(paths.backup)
            if (publishedProperties) restorePropertiesQuietly(paths.properties, previousProperties)
            ProfileSessionMode.LEGACY_READ_ONLY
        }
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
}

private fun writeMigrationProperties(
    candidate: Path,
    backup: Path,
) {
    Files.writeString(
        candidate,
        "$PROFILE_BACKUP_SHA256=${sha256(backup)}\n",
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
    )
}

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

private fun cleanup(vararg scratchFiles: Path) {
    scratchFiles.forEach { scratch ->
        cleanupSidecars(scratch)
        Files.deleteIfExists(scratch)
    }
}

@Suppress("TooGenericExceptionCaught")
private fun cleanupQuietly(vararg scratchFiles: Path) {
    try {
        cleanup(*scratchFiles)
    } catch (_: Exception) {
        // The primary evidence database remains untouched even if damaged scratch files resist deletion.
    }
}

@Suppress("TooGenericExceptionCaught")
private fun deleteQuietly(path: Path) {
    try {
        Files.deleteIfExists(path)
    } catch (_: Exception) {
        // The backup is valid immutable evidence and is safe to retain after a failed replacement.
    }
}

@Suppress("TooGenericExceptionCaught")
private fun restorePropertiesQuietly(
    properties: Path,
    previous: ByteArray?,
) {
    try {
        if (previous == null) {
            Files.deleteIfExists(properties)
        } else {
            Files.write(properties, previous, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        }
    } catch (_: Exception) {
        // A metadata restoration failure must not cause an in-place fallback or alter profile.sqlite.
    }
}

private fun cleanupSidecars(database: Path) {
    listOf("-wal", "-shm", "-journal").forEach { suffix ->
        Files.deleteIfExists(database.resolveSibling(database.fileName.toString() + suffix))
    }
}

private fun forceFile(path: Path) {
    FileChannel.open(path, StandardOpenOption.WRITE).use { channel -> channel.force(true) }
}

@Suppress("TooGenericExceptionCaught")
private fun forceDirectory(directory: Path) {
    try {
        FileChannel.open(directory, StandardOpenOption.READ).use { channel -> channel.force(true) }
    } catch (_: Exception) {
        // Directory fsync is not supported by every desktop filesystem; atomic same-directory moves still apply.
    }
}

private fun moveNewFileAtomically(
    source: Path,
    target: Path,
) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (unsupported: AtomicMoveNotSupportedException) {
        throw IOException("Atomic migration backup creation is not supported", unsupported)
    }
}

private const val CURRENT_SCHEMA_VERSION = 2
private const val LEGACY_SCHEMA_VERSION = 1
private const val PROFILE_DATABASE = "profile.sqlite"
private const val PROFILE_BACKUP = "profile.v1.sqlite"
private const val PROFILE_BACKUP_SHA256 = "profile.v1.sqlite.sha256"
private const val MIGRATION_PROPERTIES = "migration.properties"
private const val CANDIDATE_DATABASE = "profile.sqlite.migrating"
private const val BACKUP_CANDIDATE = "profile.v1.sqlite.creating"
private const val PROPERTIES_CANDIDATE = "migration.properties.creating"
private const val HASH_BUFFER_SIZE = 64 * 1024
