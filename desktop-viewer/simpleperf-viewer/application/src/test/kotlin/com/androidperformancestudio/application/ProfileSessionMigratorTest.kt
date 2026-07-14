@file:Suppress("MaxLineLength")

package com.androidperformancestudio.application

import com.androidperformancestudio.storage.ProfileProjectionRequest
import com.androidperformancestudio.storage.ProfileTrackKind
import com.androidperformancestudio.storage.SQLiteSampleStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass")
class ProfileSessionMigratorTest {
    @Test
    fun `successful migration replaces only a copied database and records immutable backup hash`() {
        val session = versionOneSession()
        val original = session.resolve(PROFILE_DATABASE)
        val beforeHash = sha256(original)

        val prepared = ProfileSessionMigrator().prepare(session)

        assertEquals(ProfileSessionMode.READ_WRITE_V2, prepared.mode)
        assertEquals(2, prepared.schemaVersion)
        assertEquals(original, prepared.database)
        assertEquals(original, prepared.originalDatabase)
        assertEquals(2, userVersion(original))
        val backup = session.resolve(PROFILE_BACKUP)
        assertTrue(Files.size(backup) > 0)
        assertFalse(Files.isWritable(backup))
        assertEquals(1, userVersion(backup))
        assertEquals(2, sampleCount(backup))
        assertEquals(
            sha256(backup),
            migrationProperties(session)[PROFILE_BACKUP_SHA256],
        )
        assertEquals(beforeHash, migrationProperties(session)[PROFILE_SOURCE_SHA256])
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
        assertEquals(2, prepared.schemaVersion)
        assertEquals(before, sha256(original))
        assertFalse(session.resolve(PROFILE_BACKUP).exists())
        assertFalse(session.resolve(MIGRATION_PROPERTIES).exists())
        assertNoMigrationScratchFiles(session)
    }

    @Test
    fun `mismatched preexisting backup is preserved but migration is refused`() {
        val session = versionOneSession()
        val original = session.resolve(PROFILE_DATABASE)
        val before = sha256(original)
        val backup = session.resolve(PROFILE_BACKUP)
        val sentinel = "preexisting immutable evidence".encodeToByteArray()
        backup.writeBytes(sentinel)

        val prepared = ProfileSessionMigrator().prepare(session)

        assertEquals(ProfileSessionMode.LEGACY_READ_ONLY, prepared.mode)
        assertEquals(1, prepared.schemaVersion)
        assertEquals(before, sha256(original))
        assertContentEquals(sentinel, Files.readAllBytes(backup))
        assertFalse(session.resolve(MIGRATION_PROPERTIES).exists())
    }

    @Test
    fun `artifact appearing at publication is never overwritten`() {
        val session = versionOneSession()
        val original = session.resolve(PROFILE_DATABASE)
        val before = Files.readAllBytes(original)
        val sentinel = "racing publisher evidence".encodeToByteArray()
        val publisher =
            ArtifactPublisher { source, target ->
                if (target.fileName.toString() == PROFILE_BACKUP) {
                    Files.write(target, sentinel, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                }
                ArtifactPublisher.HARD_LINK.publish(source, target)
            }
        val migrator =
            ProfileSessionMigrator(
                CandidateDatabaseMigrator.default(),
                artifactPublisher = publisher,
            )

        val prepared = migrator.prepare(session)

        assertEquals(ProfileSessionMode.LEGACY_READ_ONLY, prepared.mode)
        assertContentEquals(before, Files.readAllBytes(original))
        assertContentEquals(sentinel, Files.readAllBytes(session.resolve(PROFILE_BACKUP)))
        assertFalse(session.resolve(MIGRATION_PROPERTIES).exists())
    }

    @Test
    fun `unsupported no overwrite publication refuses migration`() {
        val session = versionOneSession()
        val original = session.resolve(PROFILE_DATABASE)
        val before = Files.readAllBytes(original)
        val migrator =
            ProfileSessionMigrator(
                CandidateDatabaseMigrator.default(),
                artifactPublisher = ArtifactPublisher { _, _ -> throw UnsupportedOperationException("no hard links") },
            )

        val prepared = migrator.prepare(session)

        assertEquals(ProfileSessionMode.LEGACY_READ_ONLY, prepared.mode)
        assertContentEquals(before, Files.readAllBytes(original))
        assertFalse(session.resolve(PROFILE_BACKUP).exists())
        assertFalse(session.resolve(MIGRATION_PROPERTIES).exists())
        assertNoMigrationScratchFiles(session)
    }

    @Test
    fun `foreign backup replacement after publication is retained and never legitimized`() {
        val session = versionOneSession()
        val original = session.resolve(PROFILE_DATABASE)
        val before = Files.readAllBytes(original)
        val foreign = "foreign backup replacement".encodeToByteArray()
        val migrator =
            ProfileSessionMigrator(
                CandidateDatabaseMigrator.default(),
                MigrationCheckpoint { point ->
                    if (point == ProfileMigrationCheckpoint.AFTER_BACKUP_PUBLISHED) {
                        replacePublicArtifact(session.resolve(PROFILE_BACKUP), foreign)
                    }
                },
            )

        val prepared = migrator.prepare(session)

        assertEquals(ProfileSessionMode.LEGACY_READ_ONLY, prepared.mode)
        assertContentEquals(before, Files.readAllBytes(original))
        assertEquals(1, userVersion(original))
        val backup = session.resolve(PROFILE_BACKUP)
        assertContentEquals(foreign, Files.readAllBytes(backup))
        assertTrue(migrationProperties(session)[PROFILE_BACKUP_SHA256] != sha256(backup))
        assertNoMigrationScratchFiles(session)
    }

    @Test
    fun `foreign metadata replacement after publication is retained and blocks commit`() {
        val session = versionOneSession()
        val original = session.resolve(PROFILE_DATABASE)
        val before = Files.readAllBytes(original)
        val foreign = "foreign metadata replacement".encodeToByteArray()
        val migrator =
            ProfileSessionMigrator(
                CandidateDatabaseMigrator.default(),
                MigrationCheckpoint { point ->
                    if (point == ProfileMigrationCheckpoint.AFTER_METADATA_PUBLISHED) {
                        replacePublicArtifact(session.resolve(MIGRATION_PROPERTIES), foreign)
                    }
                },
            )

        val prepared = migrator.prepare(session)

        assertEquals(ProfileSessionMode.LEGACY_READ_ONLY, prepared.mode)
        assertContentEquals(before, Files.readAllBytes(original))
        assertEquals(1, userVersion(original))
        assertContentEquals(foreign, Files.readAllBytes(session.resolve(MIGRATION_PROPERTIES)))
        assertTrue(session.resolve(PROFILE_BACKUP).exists())
        assertNoMigrationScratchFiles(session)
    }

    @Test
    fun `mismatched migration metadata refuses backup reuse and preserves source`() {
        val session = versionOneSession()
        val original = session.resolve(PROFILE_DATABASE)
        val originalBytes = Files.readAllBytes(original)
        ProfileSessionMigrator().prepare(session)
        Files.write(original, originalBytes)
        deleteSidecars(original)
        session.resolve(MIGRATION_PROPERTIES).toFile().setWritable(true)
        Files.writeString(
            session.resolve(MIGRATION_PROPERTIES),
            "$PROFILE_BACKUP_SHA256=${sha256(session.resolve(PROFILE_BACKUP))}\n" +
                "$PROFILE_SOURCE_SHA256=${"0".repeat(64)}\n",
        )

        val prepared = ProfileSessionMigrator().prepare(session)

        assertEquals(ProfileSessionMode.LEGACY_READ_ONLY, prepared.mode)
        assertEquals(1, userVersion(original))
        assertContentEquals(originalBytes, Files.readAllBytes(original))
    }

    @Test
    fun `self consistent metadata cannot authorize an unrelated v1 backup`() {
        val session = versionOneSession()
        val original = session.resolve(PROFILE_DATABASE)
        val before = Files.readAllBytes(original)
        val unrelatedSession = versionOneSession()
        val unrelated = unrelatedSession.resolve(PROFILE_DATABASE)
        DriverManager.getConnection("jdbc:sqlite:${unrelated.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA application_id=991") }
        }
        val backup = session.resolve(PROFILE_BACKUP)
        Files.copy(unrelated, backup)
        val properties = session.resolve(MIGRATION_PROPERTIES)
        Files.writeString(
            properties,
            "$PROFILE_BACKUP_SHA256=${sha256(backup)}\n" +
                "$PROFILE_SOURCE_SHA256=${sha256(original)}\n" +
                "$PROFILE_SOURCE_SCHEMA=1\n",
        )
        assertTrue(backup.toFile().setReadOnly())
        assertTrue(properties.toFile().setReadOnly())

        val prepared = ProfileSessionMigrator().prepare(session)

        assertEquals(ProfileSessionMode.LEGACY_READ_ONLY, prepared.mode)
        assertContentEquals(before, Files.readAllBytes(original))
        assertEquals(1, userVersion(original))
        assertEquals(991, applicationId(backup))
    }

    @Test
    fun `validated backup and metadata can resume an interrupted v1 migration`() {
        val session = versionOneSession()
        val original = session.resolve(PROFILE_DATABASE)
        val originalBytes = Files.readAllBytes(original)
        val first = ProfileSessionMigrator().prepare(session)
        assertEquals(ProfileSessionMode.READ_WRITE_V2, first.mode)
        Files.write(original, originalBytes)
        deleteSidecars(original)

        val resumed = ProfileSessionMigrator().prepare(session)

        assertEquals(ProfileSessionMode.READ_WRITE_V2, resumed.mode)
        assertEquals(2, userVersion(original))
        assertFalse(Files.isWritable(session.resolve(PROFILE_BACKUP)))
    }

    @Test
    fun `source WAL causes conservative refusal without changing database or sidecars`() {
        val session = versionOneSession()
        val original = session.resolve(PROFILE_DATABASE)
        val connection = DriverManager.getConnection("jdbc:sqlite:${original.toAbsolutePath()}")
        try {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("INSERT INTO sample VALUES (3, 30, 100, 101, 1, 7, 1, 0, 0, NULL, NULL, NULL)")
            }
            val wal = original.resolveSibling("$PROFILE_DATABASE-wal")
            val shm = original.resolveSibling("$PROFILE_DATABASE-shm")
            assertTrue(wal.exists())
            val databaseHash = sha256(original)
            val walHash = sha256(wal)
            val shmHash = sha256(shm)

            val prepared = ProfileSessionMigrator().prepare(session)

            assertEquals(ProfileSessionMode.LEGACY_READ_ONLY, prepared.mode)
            assertEquals(databaseHash, sha256(original))
            assertEquals(walHash, sha256(wal))
            assertEquals(shmHash, sha256(shm))
            assertFalse(session.resolve(PROFILE_BACKUP).exists())
        } finally {
            connection.close()
        }
    }

    @Test
    fun `source rollback journal causes conservative refusal`() {
        val session = versionOneSession()
        val original = session.resolve(PROFILE_DATABASE)
        val journal = original.resolveSibling("$PROFILE_DATABASE-journal")
        journal.writeBytes(byteArrayOf(1, 2, 3))
        val before = sha256(original)

        val prepared = ProfileSessionMigrator().prepare(session)

        assertEquals(ProfileSessionMode.LEGACY_READ_ONLY, prepared.mode)
        assertEquals(before, sha256(original))
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(journal))
    }

    @Test
    fun `concurrent source replacement before final move is detected and never overwritten`() {
        val session = versionOneSession()
        val original = session.resolve(PROFILE_DATABASE)
        val replacementSession = versionOneSession()
        val replacement = replacementSession.resolve(PROFILE_DATABASE)
        DriverManager.getConnection("jdbc:sqlite:${replacement.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA application_id=991") }
        }
        val migrator =
            ProfileSessionMigrator(
                CandidateDatabaseMigrator.default(),
                MigrationCheckpoint { point ->
                    if (point == ProfileMigrationCheckpoint.BEFORE_FINAL_MOVE) {
                        Files.copy(replacement, original, StandardCopyOption.REPLACE_EXISTING)
                    }
                },
            )

        val prepared = migrator.prepare(session)

        assertEquals(ProfileSessionMode.LEGACY_READ_ONLY, prepared.mode)
        assertEquals(1, userVersion(original))
        assertEquals(991, applicationId(original))
    }

    @Test
    fun `sqlite snapshot connection is closed before commit handoff`() {
        val session = versionOneSession()
        var handoffObserved = false
        val migrator =
            ProfileSessionMigrator(
                CandidateDatabaseMigrator.default(),
                MigrationCheckpoint { point ->
                    if (point == ProfileMigrationCheckpoint.AFTER_SQLITE_HANDOFF) {
                        DriverManager.getConnection("jdbc:sqlite:${session.resolve(PROFILE_DATABASE)}").use { connection ->
                            connection.createStatement().use { statement ->
                                statement.execute("BEGIN EXCLUSIVE")
                                statement.execute("ROLLBACK")
                            }
                        }
                        handoffObserved = true
                    }
                },
            )

        val prepared = migrator.prepare(session)

        assertTrue(handoffObserved)
        assertEquals(ProfileSessionMode.READ_WRITE_V2, prepared.mode)
    }

    @Test
    fun `supported writer mutation during sqlite to file lock handoff is never overwritten`() {
        val session = versionOneSession()
        val original = session.resolve(PROFILE_DATABASE)
        val migrator =
            ProfileSessionMigrator(
                CandidateDatabaseMigrator.default(),
                MigrationCheckpoint { point ->
                    if (point == ProfileMigrationCheckpoint.AFTER_SQLITE_HANDOFF) {
                        DriverManager.getConnection("jdbc:sqlite:${original.toAbsolutePath()}").use { connection ->
                            connection.createStatement().use { statement ->
                                statement.execute("PRAGMA application_id=991")
                            }
                        }
                    }
                },
            )

        val prepared = migrator.prepare(session)

        assertEquals(ProfileSessionMode.LEGACY_READ_ONLY, prepared.mode)
        assertEquals(1, userVersion(original))
        assertEquals(991, applicationId(original))
    }

    @Test
    fun `commit channel uses default share delete options and remains locked through replacement`() {
        val session = versionOneSession()
        val original = session.resolve(PROFILE_DATABASE)
        var observedOptions: Set<OpenOption>? = null
        var lockObserved = false
        val channelProvider =
            CommitChannelProvider { path, options ->
                observedOptions = options
                FileChannel.open(path, options)
            }
        val mover =
            FinalDatabaseMover { candidate, source ->
                FileChannel.open(source, StandardOpenOption.READ, StandardOpenOption.WRITE).use { second ->
                    assertFailsWith<OverlappingFileLockException> { second.tryLock() }
                }
                lockObserved = true
                FinalDatabaseMover.ATOMIC.replace(candidate, source)
            }
        val migrator =
            ProfileSessionMigrator(
                CandidateDatabaseMigrator.default(),
                finalDatabaseMover = mover,
                commitChannelProvider = channelProvider,
            )

        val prepared = migrator.prepare(session)

        assertEquals(ProfileSessionMode.READ_WRITE_V2, prepared.mode)
        assertEquals(setOf(StandardOpenOption.READ, StandardOpenOption.WRITE), observedOptions)
        assertTrue(lockObserved)
        assertEquals(2, userVersion(original))
    }

    @Test
    fun `commit lock acquisition failure refuses migration without source mutation`() {
        val session = versionOneSession()
        val original = session.resolve(PROFILE_DATABASE)
        val before = Files.readAllBytes(original)
        val migrator =
            ProfileSessionMigrator(
                CandidateDatabaseMigrator.default(),
                commitChannelProvider = CommitChannelProvider { _, _ -> throw IOException("injected lock failure") },
            )

        val prepared = migrator.prepare(session)

        assertEquals(ProfileSessionMode.LEGACY_READ_ONLY, prepared.mode)
        assertContentEquals(before, Files.readAllBytes(original))
        assertEquals(1, userVersion(original))
    }

    @Test
    fun `failed migration preserves original bytes and published evidence while cleaning scratch files`() {
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
        assertPublishedEvidence(session, sha256(original))
        assertNoMigrationScratchFiles(session)
    }

    @Test
    fun `cleanup continues across independent scratch deletion failures`() {
        val session = versionOneSession()
        val stuckCandidate = session.resolve("profile.sqlite.migrating")
        Files.createDirectory(stuckCandidate)
        stuckCandidate.resolve("child").writeBytes(byteArrayOf(1))
        stuckCandidate.resolveSibling("profile.sqlite.migrating-wal").writeBytes(byteArrayOf(2))
        session.resolve("profile.v1.sqlite.creating").writeBytes(byteArrayOf(3))
        session.resolve("migration.properties.creating").writeBytes(byteArrayOf(4))

        val prepared = ProfileSessionMigrator().prepare(session)

        assertEquals(ProfileSessionMode.LEGACY_READ_ONLY, prepared.mode)
        assertFalse(session.resolve("profile.sqlite.migrating-wal").exists())
        assertFalse(session.resolve("profile.v1.sqlite.creating").exists())
        assertFalse(session.resolve("migration.properties.creating").exists())
    }

    @Test
    fun `failures at precommit checkpoints never replace original`() {
        ProfileMigrationCheckpoint.entries
            .filter { it != ProfileMigrationCheckpoint.AFTER_FINAL_MOVE }
            .forEach { failurePoint ->
                val session = versionOneSession()
                val original = session.resolve(PROFILE_DATABASE)
                val before = Files.readAllBytes(original)
                val migrator =
                    ProfileSessionMigrator(
                        CandidateDatabaseMigrator.default(),
                        MigrationCheckpoint { point ->
                            if (point == failurePoint) throw IOException("injected $failurePoint")
                        },
                    )

                val prepared = migrator.prepare(session)

                assertEquals(ProfileSessionMode.LEGACY_READ_ONLY, prepared.mode, failurePoint.name)
                assertContentEquals(before, Files.readAllBytes(original), failurePoint.name)
                assertEquals(1, userVersion(original), failurePoint.name)
                when (failurePoint) {
                    ProfileMigrationCheckpoint.AFTER_SQLITE_HANDOFF -> {
                        assertFalse(session.resolve(PROFILE_BACKUP).exists(), failurePoint.name)
                        assertFalse(session.resolve(MIGRATION_PROPERTIES).exists(), failurePoint.name)
                    }

                    ProfileMigrationCheckpoint.AFTER_BACKUP_PUBLISHED -> {
                        val backup = session.resolve(PROFILE_BACKUP)
                        assertTrue(backup.exists(), failurePoint.name)
                        assertFalse(Files.isWritable(backup), failurePoint.name)
                        assertEquals(1, userVersion(backup), failurePoint.name)
                        assertFalse(session.resolve(MIGRATION_PROPERTIES).exists(), failurePoint.name)
                    }

                    ProfileMigrationCheckpoint.AFTER_METADATA_PUBLISHED,
                    ProfileMigrationCheckpoint.BEFORE_FINAL_MOVE,
                    -> assertPublishedEvidence(session, sha256(original))

                    ProfileMigrationCheckpoint.AFTER_FINAL_MOVE -> error("filtered above")
                }
                assertNoMigrationScratchFiles(session)
            }
    }

    @Test
    fun `final move failure preserves original and retains newly published evidence`() {
        val session = versionOneSession()
        val original = session.resolve(PROFILE_DATABASE)
        val before = Files.readAllBytes(original)
        val migrator =
            ProfileSessionMigrator(
                CandidateDatabaseMigrator.default(),
                finalDatabaseMover =
                    FinalDatabaseMover { _, _ ->
                        throw IOException("injected final move failure")
                    },
            )

        val prepared = migrator.prepare(session)

        assertEquals(ProfileSessionMode.LEGACY_READ_ONLY, prepared.mode)
        assertContentEquals(before, Files.readAllBytes(original))
        assertEquals(1, userVersion(original))
        assertPublishedEvidence(session, sha256(original))
        assertNoMigrationScratchFiles(session)
    }

    @Test
    fun `postcommit checkpoint failure cannot downgrade a completed atomic move`() {
        val session = versionOneSession()
        val migrator =
            ProfileSessionMigrator(
                CandidateDatabaseMigrator.default(),
                MigrationCheckpoint { point ->
                    if (point == ProfileMigrationCheckpoint.AFTER_FINAL_MOVE) {
                        throw IOException("injected postcommit failure")
                    }
                },
            )

        val prepared = migrator.prepare(session)

        assertEquals(ProfileSessionMode.READ_WRITE_V2, prepared.mode)
        assertEquals(2, userVersion(session.resolve(PROFILE_DATABASE)))
    }

    @Test
    fun `newer schema stays read only through actual workspace load`() =
        runTest {
            val session = versionOneSession()
            val original = session.resolve(PROFILE_DATABASE)
            SQLiteSampleStore
                .open(original)
                .use { }
            DriverManager.getConnection("jdbc:sqlite:${original.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA user_version=3")
                    statement.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                    statement.execute("PRAGMA journal_mode=DELETE")
                }
            }
            deleteSidecars(original)
            val before = sha256(original)
            val controller =
                ProfileWorkspaceController(
                    backgroundScope,
                    sqliteProjectionLoader(UnconfinedTestDispatcher(testScheduler)),
                )

            controller.openSession(session, ProfileProjectionRequest(timelineBucketCount = 2, topFunctionLimit = 10))
            runCurrent()

            assertEquals(ProfileSessionMode.LEGACY_READ_ONLY, controller.state.value.sessionMode)
            assertEquals(
                3,
                controller.state.value.preparedSession
                    ?.schemaVersion,
            )
            assertIs<ProfileWorkspaceLoadState.Ready>(controller.state.value.loadState)
            assertEquals(before, sha256(original))
            assertFalse(original.resolveSibling("$PROFILE_DATABASE-wal").exists())
            assertFalse(original.resolveSibling("$PROFILE_DATABASE-shm").exists())
        }

    @Test
    fun `prepared writable v2 fails closed if database is replaced with v1 before load`() =
        runTest {
            val v2Session = versionOneSession()
            val v2 = v2Session.resolve(PROFILE_DATABASE)
            SQLiteSampleStore
                .open(v2)
                .use { }
            val prepared = ProfileSessionMigrator().prepare(v2Session)
            val v1Session = versionOneSession()
            val v1 = v1Session.resolve(PROFILE_DATABASE)
            val v1Bytes = Files.readAllBytes(v1)
            Files.copy(v1, v2, StandardCopyOption.REPLACE_EXISTING)

            assertFailsWith<ProfileProjectionLoadException> {
                sqliteProjectionLoader(UnconfinedTestDispatcher(testScheduler))
                    .load(prepared, ProfileProjectionRequest(timelineBucketCount = 2))
            }
            assertContentEquals(v1Bytes, Files.readAllBytes(v2))
            assertEquals(1, userVersion(v2))
        }

    @Test
    fun `missing database and symlink database are never migrated`() {
        val missingSession = Files.createTempDirectory("aps-missing-session-")
        val missing = ProfileSessionMigrator().prepare(missingSession)
        assertEquals(ProfileSessionMode.LEGACY_READ_ONLY, missing.mode)
        assertEquals(null, missing.schemaVersion)
        assertFalse(missingSession.resolve(PROFILE_DATABASE).exists())

        val targetSession = versionOneSession()
        val target = targetSession.resolve(PROFILE_DATABASE)
        val before = sha256(target)
        val linkSession = Files.createTempDirectory("aps-link-session-")
        Files.createSymbolicLink(linkSession.resolve(PROFILE_DATABASE), target)

        val linked = ProfileSessionMigrator().prepare(linkSession)

        assertEquals(ProfileSessionMode.LEGACY_READ_ONLY, linked.mode)
        assertEquals(null, linked.schemaVersion)
        assertEquals(before, sha256(target))
        assertFalse(linkSession.resolve(PROFILE_BACKUP).exists())
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

    private fun assertPublishedEvidence(
        session: Path,
        expectedSourceHash: String,
    ) {
        val backup = session.resolve(PROFILE_BACKUP)
        val properties = session.resolve(MIGRATION_PROPERTIES)
        assertTrue(backup.exists())
        assertTrue(properties.exists())
        assertFalse(Files.isWritable(backup))
        assertFalse(Files.isWritable(properties))
        assertEquals(1, userVersion(backup))
        val metadata = migrationProperties(session)
        assertEquals(expectedSourceHash, metadata[PROFILE_SOURCE_SHA256])
        assertEquals("1", metadata[PROFILE_SOURCE_SCHEMA])
        assertEquals(sha256(backup), metadata[PROFILE_BACKUP_SHA256])
    }

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

    private fun applicationId(database: Path): Int =
        DriverManager.getConnection("jdbc:sqlite:file:${database.toAbsolutePath()}?mode=ro").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA application_id").use { result ->
                    check(result.next())
                    result.getInt(1)
                }
            }
        }

    private fun sampleCount(database: Path): Int =
        DriverManager.getConnection("jdbc:sqlite:file:${database.toAbsolutePath()}?mode=ro").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM sample").use { result ->
                    check(result.next())
                    result.getInt(1)
                }
            }
        }

    private fun deleteSidecars(database: Path) {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            Files.deleteIfExists(database.resolveSibling(database.fileName.toString() + suffix))
        }
    }

    private fun replacePublicArtifact(
        target: Path,
        bytes: ByteArray,
    ) {
        assertTrue(target.toFile().setWritable(true))
        Files.delete(target)
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
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
        const val PROFILE_SOURCE_SHA256 = "profile.sqlite.source.sha256"
        const val PROFILE_SOURCE_SCHEMA = "profile.sqlite.source.schema"

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
