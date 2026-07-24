package com.androidperformancestudio.frame.storage

import com.androidperformancestudio.frame.model.ExpectedDurationSource
import com.androidperformancestudio.frame.model.FrameCaptureSession
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class SqliteFrameSessionStoreTest {
    @Test
    fun `round trips session frames and state tags`() {
        val database = createTempDirectory("frame-profiler").resolve("frames.db")
        val session =
            FrameCaptureSession(
                id = "session-1",
                source = FrameSource.GFXINFO,
                startedAt = Instant.ofEpochMilli(1234L),
                packageName = "com.example",
                importedFile = "framestats.txt",
            )
        val frame =
            FrameSample(
                frameId = 7L,
                sessionId = session.id,
                source = FrameSource.GFXINFO,
                packageName = "com.example",
                intendedVsyncNs = 100L,
                frameCompletedNs = 120L,
                expectedDurationNs = 16L,
                expectedDurationSource = ExpectedDurationSource.PLATFORM_DEADLINE,
                totalDurationNs = 20L,
                states = mapOf("interaction" to "scroll"),
            )

        SqliteFrameSessionStore.open(database).use { store -> store.save(session, listOf(frame)) }

        SqliteFrameSessionStore.open(database).use { store ->
            assertEquals(session, store.findSession(session.id))
            assertEquals(frame, store.loadFrames(session.id).single())
        }
    }
}
