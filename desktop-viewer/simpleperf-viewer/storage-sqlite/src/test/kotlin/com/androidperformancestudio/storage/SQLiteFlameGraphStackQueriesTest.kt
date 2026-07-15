package com.androidperformancestudio.storage

import com.androidperformancestudio.model.CanonicalProfileRecord
import com.androidperformancestudio.model.ProfileCategory
import com.androidperformancestudio.model.ProfileClockDomain
import com.androidperformancestudio.model.ProfileExecutionType
import com.androidperformancestudio.model.ProfileFrame
import com.androidperformancestudio.model.ProfileProcessFact
import com.androidperformancestudio.model.ProfileProcessKey
import com.androidperformancestudio.model.ProfileSampleFact
import com.androidperformancestudio.model.ProfileSourceFact
import com.androidperformancestudio.model.ProfileSourceId
import com.androidperformancestudio.model.ProfileSourceKind
import com.androidperformancestudio.model.ProfileThreadFact
import com.androidperformancestudio.model.ProfileThreadKey
import com.androidperformancestudio.model.ProfileTimePoint
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FrameImplementation
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals

class SQLiteFlameGraphStackQueriesTest {
    @Test
    fun `loads complete canonical weighted stacks from root to leaf`() =
        withStore { store ->
            store.importCanonicalRecords(canonicalRecords())

            val table = SQLiteFlameGraphStackQueries.load(store.connection, ProfileQuery())
            val canonicalThreadRowId = store.connection.singleLong("SELECT thread_row_id FROM profile_thread")
            val expectedFrameIds =
                store.connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT f.frame_id FROM frame f JOIN symbol sy USING(symbol_id) " +
                                "ORDER BY CASE sy.name WHEN 'runLoop' THEN 0 ELSE 1 END",
                        ).use { result -> buildList { while (result.next()) add(result.getLong(1)) } }
                }
            val expectedFunctionIds =
                store.connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT sy.symbol_id FROM symbol sy " +
                                "ORDER BY CASE sy.name WHEN 'runLoop' THEN 0 ELSE 1 END",
                        ).use { result -> buildList { while (result.next()) add(FlameFunctionId(result.getLong(1))) } }
                }

            assertEquals(2, table.stacks.size)
            assertEquals(2, table.framesById.size)
            table.stacks.forEach { stack ->
                val frames = stack.frameIdsRootToLeaf.map(table::frame)
                assertEquals(expectedFrameIds, stack.frameIdsRootToLeaf)
                assertEquals(listOf("runLoop", "renderFrame"), frames.map { it.symbolName })
                assertEquals(expectedFunctionIds, frames.map { it.functionId })
                assertEquals(listOf(0x10L, 0x20L), frames.map { it.virtualAddress })
                assertEquals(listOf("/system/lib64/libui.so", "/system/lib64/libui.so"), frames.map { it.resource })
                assertEquals("canonical:$canonicalThreadRowId", stack.threadKey)
                assertEquals("Graphics", stack.category)
                assertEquals("Frame", stack.subcategory)
                assertEquals(
                    listOf(FrameImplementation.KERNEL, FrameImplementation.MANAGED),
                    frames.map { it.implementation },
                )
            }
            assertEquals(listOf(1L, 2L), table.stacks.map { it.sampleId })
            assertEquals(listOf(100L, 200L), table.stacks.map { it.timestampNanos })
            assertEquals(listOf(5L, 8L), table.stacks.map { it.weight })
        }

    @Test
    fun `loads version one stacks with legacy thread identity and no category`() {
        val database = legacyDatabase()
        try {
            DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
                val table = SQLiteFlameGraphStackQueries.load(connection, ProfileQuery())
                val stack = table.stacks.single { it.sampleId == 1L }
                val frame = table.frame(stack.frameIdsRootToLeaf.single())

                assertEquals(10L, stack.timestampNanos)
                assertEquals(3L, stack.weight)
                assertEquals("legacy:101", stack.threadKey)
                assertEquals(null, stack.category)
                assertEquals(null, stack.subcategory)
                assertEquals("leaf", frame.symbolName)
                assertEquals("/example/lib.so", frame.resource)
                assertEquals(4096L, frame.virtualAddress)
                assertEquals(FlameFunctionId(1), frame.functionId)
            }
        } finally {
            database.deleteIfExists()
        }
    }

    @Test
    fun `retains canonical samples without callsites as empty weighted stacks`() =
        withStore { store ->
            store.importCanonicalRecords(canonicalEmptyStackRecords())

            val table = SQLiteFlameGraphStackQueries.load(store.connection, ProfileQuery())
            val stack = table.stacks.single()
            val canonicalThreadRowId = store.connection.singleLong("SELECT thread_row_id FROM profile_thread")

            assertEquals(100L, stack.timestampNanos)
            assertEquals(5L, stack.weight)
            assertEquals("canonical:$canonicalThreadRowId", stack.threadKey)
            assertEquals("Graphics", stack.category)
            assertEquals("Frame", stack.subcategory)
            assertEquals(emptyList(), stack.frameIdsRootToLeaf)
            assertEquals(emptyMap(), table.framesById)
        }

    @Test
    fun `retains legacy samples with null or missing callsites as empty weighted stacks`() {
        val database = legacyDatabase()
        try {
            DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
                val table = SQLiteFlameGraphStackQueries.load(connection, ProfileQuery())
                val emptyStacks = table.stacks.filter { it.sampleId == 2L || it.sampleId == 3L }

                assertEquals(listOf(2L, 3L), emptyStacks.map { it.sampleId })
                assertEquals(listOf(20L, 30L), emptyStacks.map { it.timestampNanos })
                assertEquals(listOf(4L, 6L), emptyStacks.map { it.weight })
                assertEquals(listOf("legacy:102", "legacy:103"), emptyStacks.map { it.threadKey })
                assertEquals(listOf(emptyList(), emptyList()), emptyStacks.map { it.frameIdsRootToLeaf })
            }
        } finally {
            database.deleteIfExists()
        }
    }

    @Test
    fun `terminates cyclic legacy callsites without repeating a visited callsite`() {
        val database = legacyDatabase()
        try {
            DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("INSERT INTO callsite VALUES (2, 3, 1)")
                    statement.execute("INSERT INTO callsite VALUES (3, 2, 1)")
                    statement.execute("INSERT INTO sample VALUES (4, 40, 100, 104, 1, 7, 2)")
                }

                val table = SQLiteFlameGraphStackQueries.load(connection, ProfileQuery())
                val stack = table.stacks.single { it.sampleId == 4L }

                assertEquals(7L, stack.weight)
                assertEquals("legacy:104", stack.threadKey)
                assertEquals(listOf(1L, 1L), stack.frameIdsRootToLeaf)
            }
        } finally {
            database.deleteIfExists()
        }
    }

    private fun canonicalRecords(): Sequence<CanonicalProfileRecord> {
        val sourceId = ProfileSourceId("simpleperf")
        val process = ProfileProcessKey(sourceId, 100)
        val thread = ProfileThreadKey(sourceId, process, 101)
        val frames =
            listOf(
                frame(2, "renderFrame", 0x20, ProfileExecutionType.ART),
                frame(1, "runLoop", 0x10, ProfileExecutionType.KERNEL),
            )
        return sequenceOf(
            CanonicalProfileRecord.Source(
                ProfileSourceFact(sourceId, ProfileSourceKind.SIMPLEPERF, clock(), 0, 1_000),
            ),
            CanonicalProfileRecord.Process(ProfileProcessFact(process, "app", null, null)),
            CanonicalProfileRecord.Thread(ProfileThreadFact(thread, "RenderThread", null, null)),
            sample(sourceId, thread, 100, 5, frames),
            sample(sourceId, thread, 200, 8, frames),
        )
    }

    private fun canonicalEmptyStackRecords(): Sequence<CanonicalProfileRecord> {
        val sourceId = ProfileSourceId("simpleperf")
        val process = ProfileProcessKey(sourceId, 100)
        val thread = ProfileThreadKey(sourceId, process, 101)
        return sequenceOf(
            CanonicalProfileRecord.Source(
                ProfileSourceFact(sourceId, ProfileSourceKind.SIMPLEPERF, clock(), 0, 1_000),
            ),
            CanonicalProfileRecord.Process(ProfileProcessFact(process, "app", null, null)),
            CanonicalProfileRecord.Thread(ProfileThreadFact(thread, "RenderThread", null, null)),
            sample(sourceId, thread, 100, 5, emptyList()),
        )
    }

    private fun sample(
        sourceId: ProfileSourceId,
        thread: ProfileThreadKey,
        timestampNanos: Long,
        weight: Long,
        frames: List<ProfileFrame>,
    ) = CanonicalProfileRecord.Sample(
        ProfileSampleFact(
            sourceId = sourceId,
            time = ProfileTimePoint(clock(), timestampNanos),
            thread = thread,
            eventType = "cpu-cycles",
            eventCount = weight,
            cpuCore = null,
            onCpu = true,
            category = ProfileCategory("Graphics", "Frame"),
            frames = frames,
            unwindError = null,
        ),
    )

    private fun frame(
        symbolId: Int,
        symbolName: String,
        address: Long,
        executionType: ProfileExecutionType,
    ) = ProfileFrame(
        virtualAddress = address,
        fileId = 7,
        symbolId = symbolId,
        filePath = "/system/lib64/libui.so",
        symbolName = symbolName,
        executionType = executionType,
    )

    private fun clock() = ProfileClockDomain("monotonic")

    private fun legacyDatabase(): Path {
        Class.forName("org.sqlite.JDBC")
        val database = Files.createTempFile("aps-flame-legacy-", ".sqlite")
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement -> LEGACY_FIXTURE.forEach(statement::execute) }
        }
        return database
    }

    private fun withStore(block: (SQLiteSampleStore) -> Unit) {
        val database = Files.createTempFile("aps-flame-stacks-", ".sqlite")
        try {
            SQLiteSampleStore.open(database).use(block)
        } finally {
            database.deleteIfExists()
            database.resolveSibling(database.fileName.toString() + "-shm").deleteIfExists()
            database.resolveSibling(database.fileName.toString() + "-wal").deleteIfExists()
        }
    }

    private companion object {
        val LEGACY_FIXTURE =
            listOf(
                "CREATE TABLE event (event_id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)",
                "CREATE TABLE file (file_id INTEGER PRIMARY KEY, path TEXT NOT NULL)",
                "CREATE TABLE symbol (symbol_id INTEGER PRIMARY KEY, file_id INTEGER NOT NULL, " +
                    "source_symbol_id INTEGER NOT NULL, name TEXT NOT NULL, mangled_name TEXT)",
                "CREATE TABLE frame (frame_id INTEGER PRIMARY KEY, virtual_address INTEGER NOT NULL, " +
                    "file_id INTEGER NOT NULL, symbol_id INTEGER NOT NULL, execution_type TEXT NOT NULL)",
                "CREATE TABLE callsite (callsite_id INTEGER PRIMARY KEY, parent_id INTEGER, frame_id INTEGER NOT NULL)",
                "CREATE TABLE sample (sample_id INTEGER PRIMARY KEY, timestamp_nanos INTEGER NOT NULL, " +
                    "process_id INTEGER NOT NULL, thread_id INTEGER NOT NULL, event_id INTEGER NOT NULL, " +
                    "event_count INTEGER NOT NULL, leaf_callsite_id INTEGER)",
                "INSERT INTO event VALUES (1, 'cpu-cycles')",
                "INSERT INTO file VALUES (1, '/example/lib.so')",
                "INSERT INTO symbol VALUES (1, 1, 1, 'leaf', NULL)",
                "INSERT INTO frame VALUES (1, 4096, 1, 1, 'NATIVE')",
                "INSERT INTO callsite VALUES (1, NULL, 1)",
                "INSERT INTO sample VALUES (1, 10, 100, 101, 1, 3, 1)",
                "INSERT INTO sample VALUES (2, 20, 100, 102, 1, 4, NULL)",
                "INSERT INTO sample VALUES (3, 30, 100, 103, 1, 6, 999)",
                "PRAGMA user_version=1",
            )
    }
}
