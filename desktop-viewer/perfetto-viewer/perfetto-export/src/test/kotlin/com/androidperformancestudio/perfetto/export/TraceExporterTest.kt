package com.androidperformancestudio.perfetto.export

import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.perfetto.model.PerfettoCaptureConfig
import com.androidperformancestudio.perfetto.model.PerfettoTraceTemplate
import com.androidperformancestudio.perfetto.model.TraceSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TraceExporterTest {
    @Test
    fun `exports valid json and unchanged trace for special character metadata`() {
        val root = Files.createTempDirectory("perfetto-export-test")
        val trace = root.resolve("source.pftrace")
        val traceBytes = byteArrayOf(1, 2, 3, 4)
        Files.write(trace, traceBytes)
        val session =
            TraceSession(
                id = "session,\"one\"",
                traceFile = trace,
                captureConfig =
                    PerfettoCaptureConfig(
                        template = PerfettoTraceTemplate.CUSTOM,
                        targetPackage = "com.example:{debug}",
                        additionalCategories = listOf("gfx", "sched/sched_switch"),
                        customConfigText = "buffers {\n size_kb: 4096\n}",
                    ),
                deviceSerial = "serial=1",
                deviceModel = "Pixel\nTest",
                androidSdk = 36,
                capturedAt = Instant.parse("2026-07-25T01:02:03Z"),
                durationNanos = 10,
                fileSizeBytes = traceBytes.size.toLong(),
                notes = "jank, binder: \"slow\"",
                isProtected = true,
            )
        val output = root.resolve("session.zip")

        assertIs<StudioResult.Success<java.nio.file.Path>>(TraceExporter().exportSessionPackage(session, output))

        ZipFile(output.toFile()).use { zip ->
            val jsonText = zip.getInputStream(zip.getEntry("session.json")).bufferedReader().readText()
            val json = Json.parseToJsonElement(jsonText).jsonObject
            assertEquals(session.id, json.getValue("id").jsonPrimitive.content)
            assertEquals(session.notes, json.getValue("notes").jsonPrimitive.content)
            assertEquals(
                session.captureConfig.customConfigText,
                json
                    .getValue("config")
                    .jsonObject
                    .getValue("customConfigText")
                    .jsonPrimitive.content,
            )
            assertContentEquals(traceBytes, zip.getInputStream(zip.getEntry("trace.pftrace")).readBytes())
        }
    }
}
