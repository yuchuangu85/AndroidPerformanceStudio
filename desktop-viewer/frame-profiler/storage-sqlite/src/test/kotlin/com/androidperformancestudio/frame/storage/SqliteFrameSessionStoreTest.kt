package com.androidperformancestudio.frame.storage

import com.androidperformancestudio.contracts.DeviceIdentityPseudonymizer
import com.androidperformancestudio.frame.model.ExpectedDurationSource
import com.androidperformancestudio.frame.model.FrameCaptureSession
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import com.androidperformancestudio.frame.model.FrameSourceCapabilities
import com.androidperformancestudio.frame.model.FrameStages
import com.androidperformancestudio.frame.model.JankType
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
                source = FrameSource.FRAME_METRICS,
                startedAt = Instant.ofEpochMilli(1234L),
                packageName = "com.example",
                deviceSerial = "device",
                deviceApiLevel = 35,
                agentProtocol = "1",
                sourceCapabilities = FrameSourceCapabilities(true, true, true, true, true),
                observedRefreshRatesHz = setOf(60.0, 120.0),
                importedFile = "framestats.txt",
                importedFileSha256 = "abc123",
                importedAt = Instant.ofEpochMilli(1200L),
                provenanceComplete = false,
                provenanceWarnings = listOf("missing device, serial", "sidecar unavailable"),
                perfettoTraceFile = "frame.perfetto-trace",
            )
        val frame =
            FrameSample(
                frameId = 7L,
                sessionId = session.id,
                source = FrameSource.FRAME_METRICS,
                packageName = "com.example",
                processId = 42,
                activityName = "com.example.MainActivity",
                windowId = "window:1",
                intendedVsyncNs = 100L,
                actualVsyncNs = 101L,
                frameCompletedNs = 120L,
                presentNs = 121L,
                expectedDurationNs = 16L,
                expectedDurationSource = ExpectedDurationSource.PLATFORM_DEADLINE,
                refreshRateHz = 120.0,
                frameTimelineVsyncId = 99L,
                totalDurationNs = 20L,
                stages = FrameStages(inputNs = 2L, drawNs = 8L, gpuNs = 4L),
                platformJank = true,
                platformJankTypes = setOf(JankType.PLATFORM_REPORTED),
                platformJankRuleId = "jank-rule",
                platformJankRuleVersion = "1.2.3",
                eligibleForJank = false,
                droppedBeforeSample = 3L,
                layoutSnapshotId = "layout-1",
                states = mapOf("interaction" to "scroll"),
            )

        SqliteFrameSessionStore.open(database).use { store -> store.save(session, listOf(frame)) }

        SqliteFrameSessionStore.open(database).use { store ->
            val persistedSession =
                session.copy(
                    deviceSerial = DeviceIdentityPseudonymizer().localId(requireNotNull(session.deviceSerial)).value,
                )
            assertEquals(persistedSession, store.findSession(session.id))
            assertEquals(frame, store.loadFrames(session.id).single())
        }
    }
}
