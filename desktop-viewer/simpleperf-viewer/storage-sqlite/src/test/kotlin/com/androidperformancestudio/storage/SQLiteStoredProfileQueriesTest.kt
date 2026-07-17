package com.androidperformancestudio.storage

import com.androidperformancestudio.model.NormalizedProfileRecord
import com.androidperformancestudio.model.NormalizedSample
import com.androidperformancestudio.model.ProfileExecutionType
import com.androidperformancestudio.model.ProfileFrame
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SQLiteStoredProfileQueriesTest {
    @Test
    fun `streams samples by thread and time with root to leaf frames`() {
        val database = Files.createTempFile("aps-stored-profile-", ".sqlite")
        SQLiteSampleStore.open(database).use { store ->
            store.importRecords(
                sequenceOf(
                    sample(20, 101, listOf(frame(2, "leaf"), frame(1, "root"))),
                    sample(10, 101, emptyList()),
                    sample(15, 202, listOf(frame(3, "worker"))),
                ),
            )

            val samples = mutableListOf<StoredProfileSample>()
            store.forEachStoredSample(samples::add)

            assertEquals(listOf(10L, 20L, 15L), samples.map { it.timestampNanos })
            assertEquals(listOf("RenderThread", "RenderThread", "worker"), samples.map { it.thread.name })
            assertEquals(emptyList(), samples[0].framesRootToLeaf)
            assertEquals(listOf("root", "leaf"), samples[1].framesRootToLeaf.map { it.symbolName })
            assertEquals(listOf("worker"), samples[2].framesRootToLeaf.map { it.symbolName })
        }
    }

    private fun sample(
        timestamp: Long,
        threadId: Int,
        frames: List<ProfileFrame>,
    ): NormalizedProfileRecord =
        NormalizedProfileRecord.Sample(
            NormalizedSample(
                timestampNanos = timestamp,
                processId = if (threadId == 101) 100 else 200,
                threadId = threadId,
                threadName = if (threadId == 101) "RenderThread" else "worker",
                eventType = "cpu-cycles",
                eventCount = 1,
                frames = frames,
                unwindError = null,
            ),
        )

    private fun frame(
        id: Int,
        name: String,
    ): ProfileFrame =
        ProfileFrame(
            virtualAddress = id.toLong(),
            fileId = 7,
            symbolId = id,
            filePath = "/system/lib64/libui.so",
            symbolName = name,
            executionType = ProfileExecutionType.NATIVE,
        )
}
