package com.androidperformancestudio.desktop

import java.nio.file.Path
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class CaptureArchiveUiStateTest {
    @Test
    fun `operation state preserves progress result and error context`() {
        val working = CaptureArchiveUiState.Working(CaptureArchiveOperation.IMPORT)
        val success = CaptureArchiveUiState.Success(
            operation = CaptureArchiveOperation.EXPORT,
            path = Path.of("/tmp/capture.apinspect"),
            rawArtifactsIncluded = false,
        )
        val failure = CaptureArchiveUiState.Failure(
            operation = CaptureArchiveOperation.IMPORT_SCREENSHOT,
            message = "invalid screenshot",
        )

        assertEquals(CaptureArchiveOperation.IMPORT, working.operation)
        assertEquals(CaptureArchiveOperation.EXPORT, success.operation)
        assertEquals(Path.of("/tmp/capture.apinspect"), success.path)
        assertFalse(success.rawArtifactsIncluded)
        assertEquals(CaptureArchiveOperation.IMPORT_SCREENSHOT, failure.operation)
        assertEquals("invalid screenshot", failure.message)
        assertSame(CaptureArchiveUiState.Idle, CaptureArchiveUiState.Idle)
    }

    @Test
    fun `default export name sanitizes package and includes capture time`() {
        assertEquals(
            "com.androidperformancestudio_sample-19700101-000001.apinspect",
            captureArchiveDefaultFileName(
                packageName = "com.androidperformancestudio/sample",
                capturedAtEpochMillis = 1_000L,
                zoneId = ZoneOffset.UTC,
            ),
        )
    }
}
