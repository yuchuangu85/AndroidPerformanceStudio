package com.androidperformancestudio.startup.storage

import com.androidperformancestudio.startup.model.CompilationMode
import com.androidperformancestudio.startup.model.PlatformLaunchMetrics
import com.androidperformancestudio.startup.model.StartupCompilationEvidence
import com.androidperformancestudio.startup.model.StartupProfileSource
import com.androidperformancestudio.startup.model.StartupRawEvidence
import com.androidperformancestudio.startup.model.StartupRun
import com.androidperformancestudio.startup.model.StartupSession
import com.androidperformancestudio.startup.model.StartupType
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SqliteStartupSessionStoreTest {
    @Test
    fun `persists session and run atomically`() {
        val file = Files.createTempDirectory("startup-store").resolve("startup.db")
        val session =
            StartupSession(
                "session",
                "device",
                "dev.sample",
                "dev.sample/.MainActivity",
                StartupType.COLD,
                CompilationMode.CURRENT,
                0,
                1,
                Instant.EPOCH,
            )
        val run =
            StartupRun(
                "run",
                session.id,
                1,
                StartupType.COLD,
                StartupType.COLD,
                PlatformLaunchMetrics(totalTimeMs = 100),
                rawEvidence = StartupRawEvidence("raw"),
                compilationEvidence =
                    StartupCompilationEvidence(
                        requestedMode = CompilationMode.SPEED_PROFILE,
                        verified = true,
                        profileSource = StartupProfileSource.BASELINE_PROFILE_PLUGIN,
                        profileSourceDeclared = true,
                        baselineProfileArtifact = "baseline-prof.txt#abc",
                    ),
            )

        SqliteStartupSessionStore.open(file).use { store ->
            store.save(session, listOf(run))
            assertEquals(1, store.listSessions().single().runCount)
        }
        DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery("SELECT baseline_profile_artifact FROM startup_runs WHERE id = 'run'")
                    .use { rows ->
                        assertEquals(true, rows.next())
                        assertEquals("baseline-prof.txt#abc", rows.getString(1))
                    }
            }
        }
    }

    @Test
    fun `adds nullable evidence columns to an existing database`() {
        val file = Files.createTempDirectory("startup-store-migration").resolve("startup.db")
        DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE startup_runs (id TEXT PRIMARY KEY)") }
        }

        SqliteStartupSessionStore.open(file).close()

        DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info(startup_runs)").use { rows ->
                    val columns = buildSet { while (rows.next()) add(rows.getString("name")) }
                    assertEquals(true, "compiler_filter_after" in columns)
                    assertEquals(true, "agent_first_frame_source" in columns)
                    assertEquals(true, "profile_source" in columns)
                    assertEquals(true, "trace_file" in columns)
                    assertEquals(true, "baseline_profile_artifact" in columns)
                }
            }
        }
    }
}
