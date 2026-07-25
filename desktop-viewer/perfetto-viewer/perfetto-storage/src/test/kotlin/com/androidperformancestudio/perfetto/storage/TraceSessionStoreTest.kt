package com.androidperformancestudio.perfetto.storage

import com.androidperformancestudio.perfetto.model.PerfettoCaptureConfig
import com.androidperformancestudio.perfetto.model.PerfettoTraceTemplate
import com.androidperformancestudio.perfetto.model.TraceSession
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TraceSessionStoreTest {
    @Test
    fun `round trips paths and text containing json delimiters`() {
        val root = Files.createTempDirectory("perfetto-session-store-test")
        val trace = root.resolve("trace,with{json}\"quotes.pftrace")
        val session =
            TraceSession(
                id = "session-1",
                traceFile = trace,
                captureConfig =
                    PerfettoCaptureConfig(
                        template = PerfettoTraceTemplate.APP_PERFORMANCE,
                        targetPackage = "com.example.app,debug",
                        durationSeconds = 15,
                        bufferSizeKb = 4096,
                        additionalCategories = listOf("gfx", "sched/sched_process_exit"),
                        customConfigText = "buffers {\n size_kb: 4096\n}",
                    ),
                deviceSerial = "emulator-5554",
                deviceModel = "Pixel {test}",
                androidSdk = 35,
                capturedAt = Instant.parse("2026-07-25T01:02:03Z"),
                durationNanos = 15_000_000_000,
                fileSizeBytes = 42,
                notes = "binder, \"jank\"",
                isProtected = true,
            )
        val store = TraceSessionStore(root.resolve("index"))

        assertIs<com.androidperformancestudio.model.StudioResult.Success<Unit>>(store.save(session))
        val listed = assertIs<com.androidperformancestudio.model.StudioResult.Success<List<TraceSession>>>(store.listRecent()).value
        assertEquals(session, listed.single())

        assertIs<com.androidperformancestudio.model.StudioResult.Success<Unit>>(store.delete(session.id))
        assertEquals(
            emptyList(),
            assertIs<com.androidperformancestudio.model.StudioResult.Success<List<TraceSession>>>(store.listRecent()).value,
        )
    }
}
