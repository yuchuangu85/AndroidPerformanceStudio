package com.androidperformancestudio.storage

import com.androidperformancestudio.model.NormalizedProfileRecord
import com.androidperformancestudio.model.NormalizedSample
import com.androidperformancestudio.model.ProfileExecutionType
import com.androidperformancestudio.model.ProfileFile
import com.androidperformancestudio.model.ProfileFrame
import com.androidperformancestudio.model.ProfileMetadata
import com.androidperformancestudio.model.ProfileThread
import com.androidperformancestudio.model.ProfileUnwindError
import com.androidperformancestudio.profileanalysis.CallStackDirection
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SQLiteNormalizedProfileStoreTest {
    @Test
    fun `streams normalized records into bounded transactions and shared callsites`() {
        withStore { store ->
            val records =
                sequence {
                    yield(metadata())
                    yield(file())
                    yield(NormalizedProfileRecord.Thread(ProfileThread(100, 101, "RenderThread")))
                    repeat(2_505) { index ->
                        yield(
                            sample(
                                timestampNanos = index.toLong(),
                                threadId = 101,
                                eventCount = 2,
                                frames =
                                    listOf(
                                        frame(1, "renderFrame", 0x20),
                                        frame(0, "runLoop", 0x10),
                                    ),
                            ),
                        )
                    }
                }

            val result = store.importRecords(records, batchSize = 1_000)

            assertEquals(2_505L, result.importedSamples)
            assertEquals(2_508L, result.importedRecords)
            assertEquals(3, result.committedBatches)
            assertEquals(2L, store.frameCount())
            assertEquals(2L, store.callsiteCount())
            assertEquals(2_505L, store.sampleCount())
        }
    }

    @Test
    fun `queries sample counts threads and weighted top functions with filters`() {
        withStore { store ->
            store.importRecords(
                sequenceOf(
                    metadata(),
                    file(),
                    NormalizedProfileRecord.Thread(ProfileThread(100, 101, "RenderThread")),
                    NormalizedProfileRecord.Thread(ProfileThread(100, 102, "worker")),
                    sample(10, 101, 3, listOf(frame(1, "renderFrame", 0x20), frame(0, "runLoop", 0x10))),
                    sample(20, 101, 5, listOf(frame(1, "renderFrame", 0x20), frame(0, "runLoop", 0x10))),
                    sample(30, 102, 7, listOf(frame(0, "runLoop", 0x10))),
                ),
            )

            assertEquals(3L, store.sampleCount())
            assertEquals(
                listOf(
                    ThreadSummary(100, 101, "RenderThread", 2, 8),
                    ThreadSummary(100, 102, "worker", 1, 7),
                ),
                store.threads(),
            )
            assertEquals(
                listOf(
                    TopFunction("runLoop", "/system/lib64/libui.so", 15, 7, 3, 2),
                    TopFunction("renderFrame", "/system/lib64/libui.so", 8, 8, 2, 1),
                ),
                store.topFunctions(limit = 10),
            )

            val filtered = ProfileQuery(startNanosInclusive = 15, endNanosExclusive = 30, threadIds = setOf(101))
            assertEquals(1L, store.sampleCount(filtered))
            assertEquals(5L, store.topFunctions(filtered, limit = 1).single().inclusiveWeight)
            assertEquals(
                listOf("renderFrame"),
                store
                    .topFunctions(
                        limit = 10,
                        search = "render",
                        sort = TopFunctionSort.EXCLUSIVE_WEIGHT,
                    ).map(TopFunction::symbolName),
            )
            assertEquals(
                listOf("renderFrame", "runLoop"),
                store
                    .topFunctions(
                        limit = 10,
                        sort = TopFunctionSort.FILE_PATH,
                        descending = false,
                    ).map(TopFunction::symbolName),
            )
        }
    }

    @Test
    fun `aggregates overview timeline and forward reverse call trees from sample detail`() {
        withStore { store ->
            store.importRecords(
                sequenceOf(
                    metadata(),
                    file(),
                    NormalizedProfileRecord.Thread(ProfileThread(100, 101, "RenderThread")),
                    NormalizedProfileRecord.Thread(ProfileThread(100, 102, "worker")),
                    sample(10, 101, 3, listOf(frame(1, "renderFrame", 0x20), frame(0, "runLoop", 0x10))),
                    sample(20, 101, 5, listOf(frame(1, "renderFrame", 0x20), frame(0, "runLoop", 0x10))),
                    sample(30, 102, 7, listOf(frame(0, "runLoop", 0x10))),
                ),
            )

            assertEquals(
                ProfileOverview(
                    startNanos = 10,
                    endNanosInclusive = 30,
                    sampleCount = 3,
                    totalEventWeight = 15,
                    processCount = 1,
                    threadCount = 2,
                    eventTypes = listOf("cpu-cycles"),
                ),
                store.overview(),
            )
            assertEquals(
                listOf(
                    TimelineBucket(10, 20, 1, 3),
                    TimelineBucket(20, 30, 1, 5),
                    TimelineBucket(30, 40, 1, 7),
                ),
                store.timelineBuckets(ProfileQuery(startNanosInclusive = 10, endNanosExclusive = 40), 3),
            )

            val forward = store.callTree(direction = CallStackDirection.FORWARD)
            val forwardRoot = forward.single { it.parentId == null }
            val forwardChild = forward.single { it.parentId == forwardRoot.id }
            assertEquals("runLoop", forwardRoot.symbolName)
            assertEquals(15L, forwardRoot.inclusiveWeight)
            assertEquals(7L, forwardRoot.exclusiveWeight)
            assertEquals("renderFrame", forwardChild.symbolName)
            assertEquals(8L, forwardChild.inclusiveWeight)
            assertEquals(8L, forwardChild.exclusiveWeight)

            val reverse = store.callTree(direction = CallStackDirection.INVERTED)
            val reverseRender = reverse.single { it.parentId == null && it.symbolName == "renderFrame" }
            val renderCaller = reverse.single { it.parentId == reverseRender.id }
            assertEquals("runLoop", renderCaller.symbolName)
            assertEquals(8L, renderCaller.inclusiveWeight)
            assertEquals(0L, renderCaller.exclusiveWeight)
            assertTrue(reverse.any { it.parentId == null && it.symbolName == "runLoop" && it.exclusiveWeight == 7L })
        }
    }

    @Test
    fun `builds aligned Firefox timeline buckets for every thread despite thread filter`() {
        withStore { store ->
            store.importRecords(
                sequenceOf(
                    metadata(),
                    file(),
                    NormalizedProfileRecord.Thread(ProfileThread(100, 100, "main")),
                    NormalizedProfileRecord.Thread(ProfileThread(100, 102, "worker")),
                    sample(10, 100, 3, listOf(frame(0, "runLoop", 0x10))),
                    sample(20, 100, 5, listOf(frame(0, "runLoop", 0x10))),
                    sample(30, 102, 7, listOf(frame(0, "runLoop", 0x10))),
                ),
            )

            val tracks =
                store.threadTimelineTracks(
                    ProfileQuery(
                        startNanosInclusive = 10,
                        endNanosExclusive = 40,
                        threadIds = emptySet(),
                    ),
                    bucketCount = 3,
                )

            assertEquals(listOf("legacy:100", "legacy:102"), tracks.map(ThreadTimelineTrack::id))
            assertEquals(listOf(10L, 20L, 30L), tracks.first().buckets.map(TimelineBucket::startNanos))
            assertEquals(listOf(3L, 5L, 0L), tracks.first().buckets.map(TimelineBucket::eventWeight))
            assertEquals(listOf(0L, 0L, 7L), tracks.last().buckets.map(TimelineBucket::eventWeight))
        }
    }

    @Test
    fun `extracts lost unwind unknown symbol empty stack and unknown record quality`() {
        withStore { store ->
            val unknownFrame =
                ProfileFrame(
                    virtualAddress = 0x99,
                    fileId = 42,
                    symbolId = -1,
                    filePath = "<unknown-file:42>",
                    symbolName = "<unknown-symbol>",
                    executionType = ProfileExecutionType.NATIVE,
                )
            store.importRecords(
                sequenceOf(
                    metadata(),
                    NormalizedProfileRecord.Thread(ProfileThread(200, 201, "worker")),
                    sample(
                        timestampNanos = 1,
                        threadId = 201,
                        eventCount = 1,
                        frames = listOf(unknownFrame),
                        unwindError = ProfileUnwindError("ERROR_NOT_ENOUGH_STACK", 12, 0xabcd),
                    ),
                    sample(timestampNanos = 2, threadId = 201, eventCount = 1, frames = emptyList()),
                    NormalizedProfileRecord.Lost(sampleCount = 100, lostCount = 4),
                    NormalizedProfileRecord.Unknown,
                    NormalizedProfileRecord.Unknown,
                ),
            )

            val quality = store.dataQuality()
            assertEquals(2L, quality.sampleCount)
            assertEquals(100L, quality.reportedSampleCount)
            assertEquals(4L, quality.lostSampleCount)
            assertEquals(1L, quality.unwindErrorSamples)
            assertEquals(1L, quality.unknownSymbolSamples)
            assertEquals(1L, quality.emptyStackSamples)
            assertEquals(2L, quality.unknownRecords)
            assertEquals(0.04, quality.lostRate)
            assertTrue(quality.unwindErrors.single().code == "ERROR_NOT_ENOUGH_STACK")
        }
    }

    private fun metadata(): NormalizedProfileRecord.Metadata =
        NormalizedProfileRecord.Metadata(
            ProfileMetadata(
                eventTypes = listOf("cpu-cycles"),
                appPackageName = "com.example.app",
                appType = "profileable",
                androidSdkVersion = "36",
                androidBuildType = "userdebug",
                traceOffCpu = false,
            ),
        )

    private fun file(): NormalizedProfileRecord.File =
        NormalizedProfileRecord.File(
            ProfileFile(
                id = 7,
                path = "/system/lib64/libui.so",
                symbols = listOf("runLoop", "renderFrame"),
                mangledSymbols = listOf("_Z7runLoopv", "_Z11renderFramev"),
            ),
        )

    private fun sample(
        timestampNanos: Long,
        threadId: Int,
        eventCount: Long,
        frames: List<ProfileFrame>,
        unwindError: ProfileUnwindError? = null,
    ): NormalizedProfileRecord.Sample =
        NormalizedProfileRecord.Sample(
            NormalizedSample(
                timestampNanos = timestampNanos,
                processId = if (threadId == 201) 200 else 100,
                threadId = threadId,
                threadName = if (threadId == 101) "RenderThread" else "worker",
                eventType = "cpu-cycles",
                eventCount = eventCount,
                frames = frames,
                unwindError = unwindError,
            ),
        )

    private fun frame(
        symbolId: Int,
        symbolName: String,
        address: Long,
    ): ProfileFrame =
        ProfileFrame(
            virtualAddress = address,
            fileId = 7,
            symbolId = symbolId,
            filePath = "/system/lib64/libui.so",
            symbolName = symbolName,
            executionType = ProfileExecutionType.NATIVE,
        )

    private fun withStore(block: (SQLiteSampleStore) -> Unit) {
        val database = Files.createTempFile("aps-normalized-", ".sqlite")
        try {
            SQLiteSampleStore.open(database).use(block)
        } finally {
            database.deleteIfExists()
            database.resolveSibling(database.fileName.toString() + "-shm").deleteIfExists()
            database.resolveSibling(database.fileName.toString() + "-wal").deleteIfExists()
        }
    }
}
