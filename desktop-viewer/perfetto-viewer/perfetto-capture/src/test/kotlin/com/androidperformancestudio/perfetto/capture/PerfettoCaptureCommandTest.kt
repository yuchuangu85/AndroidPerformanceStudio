package com.androidperformancestudio.perfetto.capture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerfettoCaptureCommandTest {
    @Test
    fun `capture writes traces to the Android Perfetto trace directory`() {
        val source =
            Files.readString(
                Path.of(
                    "src/main/kotlin/com/androidperformancestudio/perfetto/capture/PerfettoCaptureSession.kt",
                ),
            )

        assertTrue(source.contains("/data/misc/perfetto-traces/aps-perfetto-trace.pftrace"))
        assertFalse(source.contains("/data/local/tmp/aps-perfetto-trace.pftrace"))
    }
}
