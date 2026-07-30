package com.androidperformancestudio.battery.historian

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatteryHistorianAdapterTest {
    @Test
    fun `generates local bugreport without uploading it`() =
        runTest {
            val output = Files.createTempDirectory("battery-historian").resolve("bugreport.zip")
            val adapter =
                BatteryHistorianAdapter(
                    Path.of("adb"),
                    "serial",
                    BugreportCommandRunner { arguments -> Files.writeString(Path.of(arguments.last()), "bugreport") },
                )

            val artifact = adapter.generateBugreport(output)

            assertEquals(output.toAbsolutePath(), artifact.path)
            assertTrue(artifact.sizeBytes > 0)
            assertEquals("http://127.0.0.1:9999/", adapter.historianUploadUri("http://127.0.0.1:9999", artifact))
        }
}
