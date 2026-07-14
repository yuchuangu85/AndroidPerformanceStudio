@file:Suppress("MaxLineLength")

package com.androidperformancestudio.application

import com.androidperformancestudio.storage.ProfileProjectionRequest
import com.androidperformancestudio.storage.ProfileTrackKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSessionMigratorTest {
    @Test
    fun `successful migration replaces only a copied database and records immutable backup hash`() {
        val session = versionOneSession()
        val original = session.resolve(PROFILE_DATABASE)
        val before = Files.readAllBytes(original)

        val prepared = ProfileSessionMigrator().prepare(session)

        assertEquals(ProfileSessionMode.READ_WRITE_V2, prepared.mode)
        assertEquals(original, prepared.database)
        assertEquals(original, prepared.originalDatabase)
        assertEquals(2, userVersion(original))
        val backup = session.resolve(PROFILE_BACKUP)
        assertContentEquals(before, Files.readAllBytes(backup))
        assertFalse(Files.isWritable(backup))
        assertEquals(1, userVersion(backup))
        assertEquals(
            sha256(backup),
            migrationProperties(session)[PROFILE_BACKUP_SHA256],
        )
        assertNoMigrationScratchFiles(session)
    }

    @Test
    fun `repeated prepare of migrated session is idempotent and never overwrites backup`() {
        val session = versionOneSession()
        val migrator = ProfileSessionMigrator()
        migrator.prepare(session)
        val database = session.resolve(PROFILE_DATABASE)
        val backup = session.resolve(PROFILE_BACKUP)
        val databaseHash = sha256(database)
        val backupHash = sha256(backup)
        val properties = session.resolve(MIGRATION_PROPERTIES).readText()

        val repeated = migrator.prepare(session)

        assertEquals(ProfileSessionMode.READ_WRITE_V2, repeated.mode)
        assertEquals(databaseHash, sha256(database))
        assertEquals(backupHash, sha256(backup))
        assertEquals(properties, session.resolve(MIGRATION_PROPERTIES).readText())
        assertNoMigrationScratchFiles(session)
    }

    @Test
    fun `current v2 session prepare is a no-op without legacy backup`() {
        val session = versionOneSession()
        val original = session.resolve(PROFILE_DATABASE)
        com.androidperformancestudio.storage.SQLiteSampleStore
            .open(original)
            .use { }
        val before = sha256(original)

        val prepared = ProfileSessionMigrator().prepare(session)

        assertEquals(ProfileSessionMode.READ_WRITE_V2, prepared.mode)
        assertEquals(before, sha256(original))
        assertFalse(session.resolve(PROFILE_BACKUP).exists())
        assertFalse(session.resolve(MIGRATION_PROPERTIES).exists())
        assertNoMigrationScratchFiles(session)
    }

    @Test
    fun `preexisting backup is never overwritten`() {
        val session = versionOneSession()
        val backup = session.resolve(PROFILE_BACKUP)
        val sentinel = "preexisting immutable evidence".encodeToByteArray()
        backup.writeBytes(sentinel)

        val prepared = ProfileSessionMigrator().prepare(session)

        assertEquals(ProfileSessionMode.READ_WRITE_V2, prepared.mode)
        assertContentEquals(sentinel, Files.readAllBytes(backup))
        assertEquals(sha256(backup), migrationProperties(session)[PROFILE_BACKUP_SHA256])
    }

    @Test
    fun `failed migration preserves original bytes cleans candidate sidecars and returns legacy read only`() {
        val session = versionOneSession()
        val original = session.resolve(PROFILE_DATABASE)
        val before = Files.readAllBytes(original)
        val migrator =
            ProfileSessionMigrator(
                CandidateDatabaseMigrator { candidate ->
                    candidate.resolveSibling(candidate.fileName.toString() + "-wal").writeBytes(byteArrayOf(1))
                    candidate.resolveSibling(candidate.fileName.toString() + "-shm").writeBytes(byteArrayOf(2))
                    candidate.resolveSibling(candidate.fileName.toString() + "-journal").writeBytes(byteArrayOf(3))
                    throw IOException("injected candidate migration failure")
                },
            )

        val prepared = migrator.prepare(session)

        assertEquals(ProfileSessionMode.LEGACY_READ_ONLY, prepared.mode)
        assertContentEquals(before, Files.readAllBytes(original))
        assertEquals(1, userVersion(original))
        assertFalse(session.resolve(PROFILE_BACKUP).exists())
        assertFalse(session.resolve(MIGRATION_PROPERTIES).exists())
        assertNoMigrationScratchFiles(session)
    }

    @Test
    fun `workspace failed migration opens actual v1 projection read only without changing evidence`() =
        runTest {
            val session = versionOneSession()
            val original = session.resolve(PROFILE_DATABASE)
            val before = sha256(original)
            val migrator =
                ProfileSessionMigrator(
                    CandidateDatabaseMigrator { throw IOException("injected candidate migration failure") },
                )
            val controller =
                ProfileWorkspaceController(
                    backgroundScope,
                    sqliteProjectionLoader(UnconfinedTestDispatcher(testScheduler)),
                    migrator,
                )

            controller.openSession(
                session,
                ProfileProjectionRequest(timelineBucketCount = 2, topFunctionLimit = 10),
            )
            runCurrent()

            val state = controller.state.value
            assertEquals(ProfileSessionMode.LEGACY_READ_ONLY, state.sessionMode)
            assertIs<ProfileWorkspaceLoadState.Ready>(state.loadState)
            assertEquals(2, state.snapshot?.overview?.sampleCount)
            assertEquals(listOf("main"), state.snapshot?.threads?.map { it.name })
            assertEquals(listOf("leaf"), state.snapshot?.topFunctions?.map { it.symbolName })
            assertTrue(
                state.snapshot
                    ?.tracks
                    .orEmpty()
                    .any { it.kind == ProfileTrackKind.CPU_SAMPLES },
            )
            assertTrue(
                state.snapshot
                    ?.tracks
                    .orEmpty()
                    .any { it.kind == ProfileTrackKind.CONTEXT_SWITCHES },
            )
            assertEquals(before, sha256(original))
            assertEquals(1, userVersion(original))
            assertNoMigrationScratchFiles(session)
        }

    @Test
    fun `report controller remains readable after migration failure without mutating v1 evidence`() =
        runTest {
            val session = versionOneSession()
            val original = session.resolve(PROFILE_DATABASE)
            val before = sha256(original)
            val migrator =
                ProfileSessionMigrator(
                    CandidateDatabaseMigrator { throw IOException("injected candidate migration failure") },
                )
            val workspace =
                ProfileWorkspaceController(
                    backgroundScope,
                    sqliteProjectionLoader(UnconfinedTestDispatcher(testScheduler)),
                    migrator,
                )
            val reportController =
                ReportController(
                    timelineBucketCount = 2,
                    topFunctionLimit = 10,
                    scope = backgroundScope,
                    workspaceController = workspace,
                    sessionSummaryLoader =
                        ReportSessionSummaryLoader { directory ->
                            ReportSessionSummary(directory.fileName.toString(), directory, emptyMap(), emptyList())
                        },
                )

            reportController.openSession(session)
            runCurrent()

            val ready = assertIs<ReportLoadState.Ready>(reportController.state.value.loadState)
            assertEquals(2, ready.report.overview.sampleCount)
            assertEquals(listOf("leaf"), ready.report.topFunctions.map { it.symbolName })
            assertEquals(ProfileSessionMode.LEGACY_READ_ONLY, workspace.state.value.sessionMode)
            assertEquals(before, sha256(original))
            assertEquals(1, userVersion(original))
        }

    private fun versionOneSession(): Path {
        Class.forName("org.sqlite.JDBC")
        val session = Files.createTempDirectory("aps-migration-session-").toAbsolutePath().normalize()
        val database = session.resolve(PROFILE_DATABASE)
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
        }
        return session
    }

    private fun migrationProperties(session: Path): Map<String, String> =
        session
            .resolve(MIGRATION_PROPERTIES)
            .readText()
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith('#') }
            .associate { line -> line.substringBefore('=') to line.substringAfter('=') }

    private fun assertNoMigrationScratchFiles(session: Path) {
        Files.list(session).use { children ->
            assertFalse(
                children.anyMatch { path ->
                    path.fileName.toString().contains(".migrating") ||
                        path.fileName.toString().endsWith(".creating")
                },
                "migration scratch file was not cleaned in $session",
            )
        }
    }

    private fun userVersion(database: Path): Int =
        DriverManager.getConnection("jdbc:sqlite:file:${database.toAbsolutePath()}?mode=ro").use { connection ->
            connection.userVersion()
        }

    private fun sha256(path: Path): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun Connection.userVersion(): Int =
        createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { result ->
                check(result.next())
                result.getInt(1)
            }
        }

    private companion object {
        const val PROFILE_DATABASE = "profile.sqlite"
        const val PROFILE_BACKUP = "profile.v1.sqlite"
        const val MIGRATION_PROPERTIES = "migration.properties"
        const val PROFILE_BACKUP_SHA256 = "profile.v1.sqlite.sha256"

        val VERSION_ONE_STATEMENTS =
            listOf(
                "CREATE TABLE process (process_id INTEGER PRIMARY KEY, name TEXT)",
                "CREATE TABLE thread (thread_id INTEGER PRIMARY KEY, process_id INTEGER NOT NULL, name TEXT NOT NULL)",
                "CREATE TABLE event (event_id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)",
                "CREATE TABLE file (file_id INTEGER PRIMARY KEY, path TEXT NOT NULL)",
                "CREATE TABLE symbol (symbol_id INTEGER PRIMARY KEY, file_id INTEGER NOT NULL, source_symbol_id INTEGER NOT NULL, name TEXT NOT NULL, mangled_name TEXT)",
                "CREATE TABLE frame (frame_id INTEGER PRIMARY KEY, virtual_address INTEGER NOT NULL, file_id INTEGER NOT NULL, symbol_id INTEGER NOT NULL, execution_type TEXT NOT NULL)",
                "CREATE TABLE callsite (callsite_id INTEGER PRIMARY KEY, parent_id INTEGER, frame_id INTEGER NOT NULL)",
                "CREATE TABLE sample (sample_id INTEGER PRIMARY KEY, timestamp_nanos INTEGER NOT NULL, process_id INTEGER NOT NULL, thread_id INTEGER NOT NULL, event_id INTEGER NOT NULL, event_count INTEGER NOT NULL, leaf_callsite_id INTEGER, has_unknown_symbol INTEGER NOT NULL DEFAULT 0, empty_stack INTEGER NOT NULL DEFAULT 0, unwind_error_code TEXT, unwind_raw_code INTEGER, unwind_address INTEGER)",
                "CREATE TABLE lost_situation (lost_id INTEGER PRIMARY KEY, sample_count INTEGER NOT NULL, lost_count INTEGER NOT NULL)",
                "CREATE TABLE unknown_record (unknown_id INTEGER PRIMARY KEY)",
                "CREATE TABLE context_switch (context_switch_id INTEGER PRIMARY KEY, thread_id INTEGER NOT NULL, timestamp_nanos INTEGER NOT NULL, switched_on_cpu INTEGER NOT NULL)",
                "CREATE TABLE profile_metadata (metadata_id INTEGER PRIMARY KEY, event_types TEXT NOT NULL, trace_off_cpu INTEGER NOT NULL)",
                "INSERT INTO process VALUES (100, 'example')",
                "INSERT INTO thread VALUES (101, 100, 'main')",
                "INSERT INTO event VALUES (1, 'cpu-cycles')",
                "INSERT INTO file VALUES (1, '/example/lib.so')",
                "INSERT INTO symbol VALUES (1, 1, 1, 'leaf', NULL)",
                "INSERT INTO frame VALUES (1, 4096, 1, 1, 'NATIVE')",
                "INSERT INTO callsite VALUES (1, NULL, 1)",
                "INSERT INTO sample VALUES (1, 10, 100, 101, 1, 3, 1, 0, 0, NULL, NULL, NULL)",
                "INSERT INTO sample VALUES (2, 20, 100, 101, 1, 5, 1, 0, 0, NULL, NULL, NULL)",
                "INSERT INTO context_switch VALUES (1, 101, 15, 1)",
                "INSERT INTO profile_metadata VALUES (1, 'cpu-cycles', 1)",
                "PRAGMA user_version=1",
            )
    }
}
