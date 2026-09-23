package com.androidperformancestudio.perfetto.app

import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.perfetto.model.PerfettoArtifactFactory
import com.androidperformancestudio.perfetto.model.PerfettoCaptureConfig
import com.androidperformancestudio.perfetto.model.PerfettoTraceTemplate
import com.androidperformancestudio.perfetto.model.TraceSession
import com.androidperformancestudio.perfetto.storage.TraceSessionStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class PerfettoRecentTraceReaderTest {
    @TempDir lateinit var root: Path

    @Test
    fun `missing session index returns empty without creating storage`() =
        runBlocking {
            val directory = root.resolve("missing")

            assertEquals(emptyList(), PerfettoRecentTraceReader(directory).listRecent(5))
            assertIs<StudioResult.Success<List<PerfettoRecentTrace>>>(PerfettoRecentTraceReader(directory).listRecentResult(5))
            assertFalse(Files.exists(directory))
        }

    @Test
    fun `malformed index does not block dashboard or get rewritten`() =
        runBlocking {
            val directory = Files.createDirectory(root.resolve("malformed"))
            val index = Files.writeString(directory.resolve("sessions.json"), "{not valid json")

            assertEquals(emptyList(), PerfettoRecentTraceReader(directory).listRecent(5))
            assertIs<StudioResult.Failure>(PerfettoRecentTraceReader(directory).listRecentResult(5))
            assertEquals("{not valid json", Files.readString(index))
        }

    @Test
    fun `reader projects only existing trace files and leaves index unchanged`() =
        runBlocking {
            val directory = root.resolve("index")
            val existing = Files.writeString(root.resolve("trace.perfetto-trace"), "trace")
            val missing = root.resolve("deleted.perfetto-trace")
            val store = TraceSessionStore(directory)
            val artifact = PerfettoArtifactFactory(ByteArray(32) { 1 }).imported("artifact-1", existing, Instant.EPOCH)

            fun session(
                id: String,
                trace: Path,
                savedArtifact: CaptureArtifact? = null,
            ): TraceSession =
                TraceSession(
                    id = id,
                    traceFile = trace,
                    captureConfig =
                        PerfettoCaptureConfig(
                            template = PerfettoTraceTemplate.APP_PERFORMANCE,
                            targetPackage = "com.example.app",
                        ),
                    deviceSerial = "emulator-5554",
                    deviceModel = "Pixel",
                    androidSdk = 35,
                    capturedAt = Instant.parse("2026-09-01T01:02:03Z"),
                    durationNanos = 1000,
                    fileSizeBytes = 5,
                    artifact = savedArtifact,
                )
            store.save(session("available", existing, artifact))
            store.save(session("deleted", missing))
            val index = directory.resolve("sessions.json")
            val before = Files.readString(index)

            val items = PerfettoRecentTraceReader(directory).listRecent(5)

            assertEquals(listOf("available"), items.map(PerfettoRecentTrace::sessionId))
            assertEquals(listOf("available"), PerfettoRecentTraceReader(directory).listRecent(1).map(PerfettoRecentTrace::sessionId))
            assertEquals(existing, items.single().traceFile)
            assertEquals("com.example.app", items.single().packageName)
            assertEquals(before, Files.readString(index))
            val storedSessions = assertIs<StudioResult.Success<List<TraceSession>>>(store.listRecent()).value
            assertEquals(artifact, storedArtifactForTrace(storedSessions, existing))
        }
}
