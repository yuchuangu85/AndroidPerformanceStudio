package com.androidperformancestudio.startup.storage

import com.androidperformancestudio.startup.model.CompilationMode
import com.androidperformancestudio.startup.model.PlatformLaunchMetrics
import com.androidperformancestudio.startup.model.StartupRawEvidence
import com.androidperformancestudio.startup.model.StartupRun
import com.androidperformancestudio.startup.model.StartupSession
import com.androidperformancestudio.startup.model.StartupType
import java.nio.file.Files
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
            )

        SqliteStartupSessionStore.open(file).use { store ->
            store.save(session, listOf(run))
            assertEquals(1, store.listSessions().single().runCount)
        }
    }
}
