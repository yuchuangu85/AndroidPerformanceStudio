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
import kotlin.test.assertContains
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

    @Test
    fun `export categories use frame resources without misclassifying Kotlin method names`() {
        val session = Files.createTempDirectory("aps-gecko-category-export-")
        val database = session.resolve("profile.sqlite")
        val cases =
            listOf(
                Triple("androidx.kotlin.run", "[JIT app cache]", 7),
                Triple("app.something.work", "[JIT app cache]", 7),
                Triple("app.kallsyms.render", "boot.art", 0),
                Triple("androidx.kotlin.run", "/data/app/package.ko.something/base.vdex", 3),
                Triple("work", "/system/framework/boot.oat", 4),
                Triple("__schedule", "[kernel.kallsyms]", 5),
                Triple("__schedule idle", "/system/module.ko", 5),
                Triple("__schedule_work", "/system/module.ko", 1),
                Triple("nativeWork", "/system/lib64/libart.so", 2),
            )
        SQLiteSampleStore.open(database).use { store ->
            store.importRecords(
                cases.asSequence().mapIndexed { index, (symbol, resource, _) ->
                    sample(
                        timestampNanos = (index + 1) * 1_000_000L,
                        frames = listOf(frame(symbol, resource, ProfileExecutionType.NATIVE, index + 1)),
                        eventCount = 7,
                    )
                },
            )
        }
        val destination = session.resolve("exported.json.gz")

        val result = GeckoProfileExportService().export(session, destination)
        val json = gunzip(destination)

        assertFalse(result.copiedOriginal)
        assertEquals(cases.size.toLong(), result.sampleCount)
        val expectedFrames =
            cases
                .mapIndexed { index, (_, _, category) ->
                    "[$index,false,0,null,null,null,null,$category,0]"
                }.joinToString(",")
        assertContains(json, "\"data\":[$expectedFrames]")
        cases.forEach { (symbol, resource, _) -> assertContains(json, "$symbol (in $resource)") }
        val expectedSamples = cases.indices.joinToString(",") { index -> "[$index,${index + 1}.0,0]" }
        assertContains(json, "\"data\":[$expectedSamples]")
        SQLiteSampleStore.openReadOnly(database).use { store ->
            assertEquals(cases.size.toLong(), store.sampleCount())
            assertEquals(cases.size * 7L, store.overview().totalEventWeight)
        }
    }

    @Test
    fun `keeps unknown symbols in one binary at different addresses as distinct frames`() {
        val session = Files.createTempDirectory("aps-gecko-unknown-export-")
        val database = session.resolve("profile.sqlite")
        val first =
            frame("<unknown-symbol>", "/data/app/libunknown.so", ProfileExecutionType.NATIVE, 1)
                .copy(symbolId = -1, virtualAddress = 0x1010)
        val second = first.copy(virtualAddress = 0x2020)
        SQLiteSampleStore.open(database).use { store ->
            store.importRecords(
                sequenceOf(
                    sample(1_500_000, listOf(first), eventCount = 7),
                    sample(2_500_000, listOf(second), eventCount = 11),
                ),
            )
        }

        val destination = session.resolve("exported.json.gz")
        val result = GeckoProfileExportService().export(session, destination)
        val json = gunzip(destination)

        assertFalse(result.copiedOriginal)
        assertEquals(2L, result.sampleCount)
        assertContains(json, "libunknown.so+0x1010 (in /data/app/libunknown.so)")
        assertContains(json, "libunknown.so+0x2020 (in /data/app/libunknown.so)")
        assertFalse(json.contains("<unknown-symbol>"))
        assertContains(json, "\"data\":[[0,false,0,null,null,null,null,2,0],[1,false,0,null,null,null,null,2,0]]")
        assertContains(json, "\"data\":[[null,0,0],[null,1,0]]")
        assertContains(json, "\"data\":[[0,1.5,0],[1,2.5,0]]")
        SQLiteSampleStore.openReadOnly(database).use { store ->
            assertEquals(2L, store.sampleCount())
            assertEquals(18L, store.overview().totalEventWeight)
        }
    }

    @Test
    fun `merges known symbols with identical locations across source ids`() {
        val session = Files.createTempDirectory("aps-gecko-known-export-")
        val first = frame("renderFrame", "/system/lib64/libui.so", ProfileExecutionType.NATIVE, 1)
        val second = first.copy(fileId = 2, symbolId = 2, virtualAddress = 0x2020)
        SQLiteSampleStore.open(session.resolve("profile.sqlite")).use { store ->
            store.importRecords(
                sequenceOf(
                    sample(1_500_000, listOf(first)),
                    sample(2_500_000, listOf(second)),
                ),
            )
        }

        val destination = session.resolve("exported.json.gz")
        val result = GeckoProfileExportService().export(session, destination)
        val json = gunzip(destination)

        assertFalse(result.copiedOriginal)
        assertEquals(2L, result.sampleCount)
        assertContains(json, "\"stringTable\":[\"renderFrame (in /system/lib64/libui.so)\"]")
        assertContains(json, "\"data\":[[0,false,0,null,null,null,null,2,0]]")
        assertContains(json, "\"data\":[[null,0,0]]")
        assertContains(json, "\"data\":[[0,1.5,0],[0,2.5,0]]")
    }

    private fun sample(
        timestampNanos: Long,
        frames: List<ProfileFrame>,
        eventCount: Long = 1,
    ): NormalizedProfileRecord =
        NormalizedProfileRecord.Sample(
            NormalizedSample(
                timestampNanos = timestampNanos,
                processId = 100,
                threadId = 101,
                threadName = "RenderThread",
                eventType = "cpu-cycles",
                eventCount = eventCount,
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
