package com.androidperformancestudio.memory.capture

import com.androidperformancestudio.model.StudioResult
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Opt-in API 31+ device smoke test; an active debuggable process is required. */
class LocalSampledArtCaptureTest {
    @Test
    fun `captures a raw ART sampling trace from the selected device`() =
        runBlocking {
            val serial = System.getenv("APS_ART_CAPTURE_SERIAL") ?: return@runBlocking
            val pid = System.getenv("APS_ART_CAPTURE_PID")?.toIntOrNull() ?: return@runBlocking
            val packageName = System.getenv("APS_ART_CAPTURE_PACKAGE") ?: return@runBlocking
            val adb = System.getenv("APS_ART_CAPTURE_ADB")?.let(Path::of) ?: Path.of("adb")
            val root = System.getenv("APS_ART_CAPTURE_ROOT")?.let(Path::of) ?: return@runBlocking
            val sessionId = "art-live-${System.currentTimeMillis()}"
            val request =
                SampledArtAllocationCaptureRequest(
                    sessionId = sessionId,
                    sessionRoot = root,
                    serial = serial,
                    pid = pid,
                    packageName = packageName,
                    durationMs = 3_000L,
                )

            val result = SampledArtAllocationCaptureSession(adb).capture(request)

            val captured = assertIs<StudioResult.Success<SampledArtAllocationCaptureResult>>(result).value
            assertTrue(Files.size(captured.traceFile) > 0L)
            println("ART_SAMPLED_TRACE path=${captured.traceFile} bytes=${Files.size(captured.traceFile)}")
        }
}
