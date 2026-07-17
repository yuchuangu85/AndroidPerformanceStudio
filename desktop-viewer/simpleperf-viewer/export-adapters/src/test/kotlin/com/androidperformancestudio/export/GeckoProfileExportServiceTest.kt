package com.androidperformancestudio.export

import com.androidperformancestudio.model.NormalizedProfileRecord
import com.androidperformancestudio.model.NormalizedSample
import com.androidperformancestudio.model.ProfileExecutionType
import com.androidperformancestudio.model.ProfileFrame
import com.androidperformancestudio.storage.SQLiteSampleStore
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeckoProfileExportServiceTest {
    @Test
    fun `exports AOSP Gecko v24 tables and categories from stored samples`() {
        val session = Files.createTempDirectory("aps-gecko-export-")
        val database = session.resolve("profile.sqlite")
        SQLiteSampleStore.open(database).use { store ->
            store.importRecords(
                sequenceOf(
                    sample(
                        timestampNanos = 2_500_000,
                        frames =
                            listOf(
                                frame("renderFrame", "/system/lib64/libui.so", ProfileExecutionType.NATIVE, 2),
                                frame("__schedule", "[kernel.kallsyms]", ProfileExecutionType.KERNEL, 1),
                            ),
                    ),
                    sample(
                        timestampNanos = 1_500_000,
                        frames = listOf(frame("renderFrame", "/system/lib64/libui.so", ProfileExecutionType.NATIVE, 2)),
                    ),
                ),
            )
        }
        val destination = session.resolve("perf_data.json.gz")

        val result = GeckoProfileExportService().export(session, destination)
        val json = gunzip(destination)

        assertFalse(result.copiedOriginal)
        assertEquals(1, result.threadCount)
        assertEquals(2, result.sampleCount)
        assertTrue(json.contains("\"version\":24"))
        assertTrue(json.contains("\"presymbolicated\":true"))
        assertTrue(json.contains("\"samples\":{\"schema\":{\"stack\":0,\"time\":1,\"responsiveness\":2}"))
        assertTrue(json.contains("[0,1.5,0]"))
        assertTrue(json.contains("[2,2.5,0]"))
        assertTrue(json.contains("renderFrame (in /system/lib64/libui.so)"))
        assertTrue(json.contains("__schedule (in [kernel.kallsyms])"))
        assertTrue(json.contains("[1,false,0,null,null,null,null,5,0]"))
        assertTrue(json.endsWith("\"processes\":[],\"pausedRanges\":[]}"))
    }

    @Test
    fun `copies an imported Gecko profile without changing its bytes`() {
        val session = Files.createTempDirectory("aps-gecko-original-")
        val original = session.resolve("gecko-profile.json.gz").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val destination = session.resolve("exported.json.gz")

        val result = GeckoProfileExportService().export(session, destination)

        assertTrue(result.copiedOriginal)
        assertContentEquals(original.readBytes(), destination.readBytes())
    }

    private fun sample(
        timestampNanos: Long,
        frames: List<ProfileFrame>,
    ): NormalizedProfileRecord =
        NormalizedProfileRecord.Sample(
            NormalizedSample(
                timestampNanos = timestampNanos,
                processId = 100,
                threadId = 101,
                threadName = "RenderThread",
                eventType = "cpu-cycles",
                eventCount = 1,
                frames = frames,
                unwindError = null,
            ),
        )

    private fun frame(
        symbol: String,
        file: String,
        executionType: ProfileExecutionType,
        id: Int,
    ): ProfileFrame =
        ProfileFrame(
            virtualAddress = id.toLong(),
            fileId = id,
            symbolId = id,
            filePath = file,
            symbolName = symbol,
            executionType = executionType,
        )

    private fun gunzip(path: java.nio.file.Path): String =
        GZIPInputStream(Files.newInputStream(path)).use { input ->
            input.reader(StandardCharsets.UTF_8).readText()
        }
}
