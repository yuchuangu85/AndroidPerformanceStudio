package com.androidperformancestudio.desktop

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CaptureArchiveFileChooserTest {
    @Test
    fun `export path preserves a supported extension`() {
        assertEquals(
            Path.of("/tmp/capture.apinspect"),
            normalizeCaptureArchiveExportPath(
                Path.of("/tmp/capture.apinspect"),
                "zip",
            ),
        )
        assertEquals(
            Path.of("/tmp/capture.zip"),
            normalizeCaptureArchiveExportPath(
                Path.of("/tmp/capture.zip"),
                "apinspect",
            ),
        )
    }

    @Test
    fun `export path appends the selected extension when absent`() {
        assertEquals(
            Path.of("/tmp/capture.apinspect"),
            normalizeCaptureArchiveExportPath(
                Path.of("/tmp/capture"),
                "apinspect",
            ),
        )
        assertEquals(
            Path.of("/tmp/capture.zip"),
            normalizeCaptureArchiveExportPath(
                Path.of("/tmp/capture"),
                "zip",
            ),
        )
    }
}
