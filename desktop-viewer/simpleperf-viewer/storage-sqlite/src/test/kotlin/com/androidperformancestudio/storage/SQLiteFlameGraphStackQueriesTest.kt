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
import com.androidperformancestudio.profileanalysis.CallTreeProjector
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FrameImplementation
import com.androidperformancestudio.profileanalysis.matchesStackSearch
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
                listOf(
                    FlameFunctionId.fromSymbol("runLoop", "/system/lib64/libui.so"),
                    FlameFunctionId.fromSymbol("renderFrame", "/system/lib64/librender.so"),
                )

            assertEquals(2, table.stacks.size)
            assertEquals(2, table.framesById.size)
            table.stacks.forEach { stack ->
                val frames = stack.frameIdsRootToLeaf.map(table::frame)
                assertEquals(expectedFrameIds, stack.frameIdsRootToLeaf)
                assertEquals(listOf("runLoop", "renderFrame"), frames.map { it.symbolName })
                assertEquals(expectedFunctionIds, frames.map { it.functionId })
                assertEquals(listOf(0x10L, 0x20L), frames.map { it.virtualAddress })
                assertEquals(
                    listOf("/system/lib64/libui.so", "/system/lib64/librender.so"),
                    frames.map { it.resource },
                )
                assertEquals("canonical:$canonicalThreadRowId", stack.threadKey)
                assertEquals("Graphics", stack.category)
                assertEquals("Frame", stack.subcategory)
                assertEquals(listOf("Graphics", "Graphics"), stack.categoriesRootToLeaf)
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
    fun `canonical loader leaves unavailable source metadata absent without inferring file names`() =
        withStore { store ->
            store.importCanonicalRecords(canonicalRecords())

            val table = SQLiteFlameGraphStackQueries.load(store.connection, ProfileQuery())

            table.framesById.values.forEach { frame ->
                assertNull(frame.sourceFile)
                assertNull(frame.originalSourceFile)
                assertTrue(frame.matchesStackSearch(frame.symbolName.uppercase()))
                assertTrue(frame.matchesStackSearch(frame.resource.uppercase()))
                assertFalse(frame.matchesStackSearch("${frame.symbolName}.kt"))
                assertFalse(frame.matchesStackSearch("${frame.symbolName}.java"))
            }
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
                assertEquals(listOf<String?>(null), stack.categoriesRootToLeaf)
                assertEquals("leaf", frame.symbolName)
                assertEquals("/example/lib.so", frame.resource)
                assertNull(frame.sourceFile)
                assertNull(frame.originalSourceFile)
                assertTrue(frame.matchesStackSearch("LIB.SO"))
                assertFalse(frame.matchesStackSearch("leaf.kt"))
                assertEquals(4096L, frame.virtualAddress)
                assertEquals(FlameFunctionId.fromSymbol("leaf", "/example/lib.so"), frame.functionId)
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

    @Test
    fun `canonical loader skips frames with dangling required relations`() {
        val corruptions =
            listOf(
                "missing frame" to
                    "DELETE FROM frame WHERE frame_id = (" +
                    "SELECT f.frame_id FROM frame f JOIN symbol sy USING(symbol_id) " +
                    "WHERE sy.name = 'renderFrame')",
                "missing symbol" to "DELETE FROM symbol WHERE name = 'renderFrame'",
                "missing file" to "DELETE FROM file WHERE path = '/system/lib64/librender.so'",
            )

        corruptions.forEach { (case, corruption) ->
            withStore { store ->
                store.importCanonicalRecords(canonicalRecords())
                store.connection.corrupt(corruption)

                val table = SQLiteFlameGraphStackQueries.load(store.connection, ProfileQuery())

                assertEquals(listOf(1L, 2L), table.stacks.map { it.sampleId }, case)
                table.stacks.forEach { stack ->
                    assertEquals(
                        listOf("runLoop"),
                        stack.frameIdsRootToLeaf.map(table::frame).map { it.symbolName },
                        case,
                    )
                }
            }
        }
    }

    @Test
    fun `legacy loader skips frames with dangling required relations`() {
        val corruptions =
            listOf(
                "missing frame" to "DELETE FROM frame WHERE frame_id = 2",
                "missing symbol" to "DELETE FROM symbol WHERE symbol_id = 2",
                "missing file" to "DELETE FROM file WHERE file_id = 2",
            )

        corruptions.forEach { (case, corruption) -> assertLegacyDanglingRelation(case, corruption) }
    }

    @Test
    fun `canonical loader retains unresolved events only when event type is unfiltered`() =
        withStore { store ->
            store.importCanonicalRecords(canonicalRecords())
            store.connection.corrupt("DELETE FROM event")

            val unfiltered = SQLiteFlameGraphStackQueries.load(store.connection, ProfileQuery())
            val filtered =
                SQLiteFlameGraphStackQueries.load(
                    store.connection,
                    ProfileQuery(eventTypes = setOf("cpu-cycles")),
                )

            assertEquals(listOf(1L, 2L), unfiltered.stacks.map { it.sampleId })
            assertEquals(listOf(100L, 200L), unfiltered.stacks.map { it.timestampNanos })
            assertEquals(listOf(5L, 8L), unfiltered.stacks.map { it.weight })
            unfiltered.stacks.forEach { stack ->
                assertEquals(
                    listOf("runLoop", "renderFrame"),
                    stack.frameIdsRootToLeaf.map(unfiltered::frame).map { it.symbolName },
                )
            }
            assertEquals(emptyList(), filtered.stacks)
        }

    @Test
    fun `legacy loader retains unresolved events only when event type is unfiltered`() {
        val database = legacyDatabase()
        try {
            DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
                connection.corrupt("DELETE FROM event")

                val unfiltered = SQLiteFlameGraphStackQueries.load(connection, ProfileQuery())
                val filtered =
                    SQLiteFlameGraphStackQueries.load(
                        connection,
                        ProfileQuery(eventTypes = setOf("cpu-cycles")),
                    )

                assertEquals(listOf(1L, 2L, 3L), unfiltered.stacks.map { it.sampleId })
                assertEquals(listOf(10L, 20L, 30L), unfiltered.stacks.map { it.timestampNanos })
                assertEquals(listOf(3L, 4L, 6L), unfiltered.stacks.map { it.weight })
                val populatedStack = unfiltered.stacks.single { it.sampleId == 1L }
                assertEquals(
                    listOf("leaf"),
                    populatedStack.frameIdsRootToLeaf.map(unfiltered::frame).map { it.symbolName },
                )
                assertEquals(emptyList(), filtered.stacks)
            }
        } finally {
            database.deleteIfExists()
        }
    }

    @Test
    fun `display function identity merges duplicate source symbols without merging source frames`() =
        withLegacyConnection { connection ->
            connection.executeAll(LOOP_IDENTITY_FIXTURE)

            val table = SQLiteFlameGraphStackQueries.load(connection, ProfileQuery())
            val first = table.frame(2)
            val second = table.frame(3)
            assertNotEquals(first.frameId, second.frameId)
            assertNotEquals(first.implementation, second.implementation)
            assertEquals(first.functionId, second.functionId)
            assertEquals(
                FlameFunctionId.fromSymbol("android.os.Looper.loopOnce", "/example/boot.oat"),
                first.functionId,
            )

            val nodes = CallTreeProjector.project(table)
            val loop = (0 until nodes.size).single { nodes.frameAt(it)?.symbolName == "android.os.Looper.loopOnce" }
            assertEquals(10L, nodes.inclusiveWeightAt(loop))
            assertEquals(2L, nodes.sampleCountAt(loop))
            assertEquals(2L, nodes.selfSampleCountAt(loop))
            assertEquals(
                2L,
                connection.singleLong("SELECT COUNT(*) FROM symbol WHERE name='android.os.Looper.loopOnce'"),
            )
        }

    @Test
    fun `function and call node ids survive filtering and source symbol renumbering`() =
        withLegacyConnection { connection ->
            connection.executeAll(LOOP_IDENTITY_FIXTURE)
            val all = SQLiteFlameGraphStackQueries.load(connection, ProfileQuery())
            val first =
                SQLiteFlameGraphStackQueries.load(
                    connection,
                    ProfileQuery(startNanosInclusive = 40, endNanosExclusive = 50),
                )
            val second =
                SQLiteFlameGraphStackQueries.load(
                    connection,
                    ProfileQuery(startNanosInclusive = 50, endNanosExclusive = 60),
                )
            val allNodes = CallTreeProjector.project(all)
            val firstNodes = CallTreeProjector.project(first)
            val secondNodes = CallTreeProjector.project(second)
            val loopIndex =
                (0 until allNodes.size).single { allNodes.frameAt(it)?.symbolName == "android.os.Looper.loopOnce" }
            assertEquals(allNodes.nodeIdAt(loopIndex), firstNodes.nodeIdAt(0))
            assertEquals(firstNodes.nodeIdAt(0), secondNodes.nodeIdAt(0))

            connection.executeAll(listOf("UPDATE symbol SET source_symbol_id=source_symbol_id+100"))
            val renumbered =
                SQLiteFlameGraphStackQueries.load(
                    connection,
                    ProfileQuery(startNanosInclusive = 40, endNanosExclusive = 50),
                )
            assertEquals(first.frame(2).functionId, renumbered.frame(2).functionId)
            assertEquals(firstNodes.nodeIdAt(0), CallTreeProjector.project(renumbered).nodeIdAt(0))
        }

    @Test
    fun `unknown addresses sharing one source symbol keep distinct display function identities`() =
        withLegacyConnection { connection ->
            connection.executeAll(
                listOf(
                    "INSERT INTO symbol VALUES (2, 1, -1, '<unknown-symbol>', NULL)",
                    "INSERT INTO frame VALUES (2, 32, 1, 2, 'NATIVE')",
                    "INSERT INTO frame VALUES (3, 48, 1, 2, 'NATIVE')",
                    "INSERT INTO callsite VALUES (2, NULL, 2)",
                    "INSERT INTO callsite VALUES (3, NULL, 3)",
                    "INSERT INTO sample VALUES (4, 40, 100, 104, 1, 9, 2)",
                    "INSERT INTO sample VALUES (5, 50, 100, 104, 1, 1, 3)",
                ),
            )

            val table = SQLiteFlameGraphStackQueries.load(connection, ProfileQuery(startNanosInclusive = 40))
            assertEquals("lib.so+0x20", table.frame(2).symbolName)
            assertEquals("lib.so+0x30", table.frame(3).symbolName)
            assertNotEquals(table.frame(2).functionId, table.frame(3).functionId)
            val nodes = CallTreeProjector.project(table)
            assertEquals(2, nodes.size)
            assertEquals(listOf(9L, 1L), nodes.inclusiveWeights.toList())
            assertEquals(1L, connection.singleLong("SELECT COUNT(*) FROM symbol WHERE source_symbol_id=-1"))
        }

    private fun canonicalRecords(): Sequence<CanonicalProfileRecord> {
        val sourceId = ProfileSourceId("simpleperf")
        val process = ProfileProcessKey(sourceId, 100)
        val thread = ProfileThreadKey(sourceId, process, 101)
        val frames =
            listOf(
                frame(2, "renderFrame", 0x20, ProfileExecutionType.ART, "/system/lib64/librender.so"),
                frame(1, "runLoop", 0x10, ProfileExecutionType.KERNEL, "/system/lib64/libui.so"),
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
        filePath: String,
    ) = ProfileFrame(
        virtualAddress = address,
        fileId = symbolId,
        symbolId = symbolId,
        filePath = filePath,
        symbolName = symbolName,
        executionType = executionType,
    )

    private fun clock() = ProfileClockDomain("monotonic")

    private fun assertLegacyDanglingRelation(
        case: String,
        corruption: String,
    ) {
        val database = legacyDatabase()
        try {
            DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
                connection.executeAll(LEGACY_MALFORMED_LEAF_FIXTURE)
                connection.corrupt(corruption)

                val table = SQLiteFlameGraphStackQueries.load(connection, ProfileQuery())
                val stack = table.stacks.single { it.sampleId == 4L }

                assertEquals(7L, stack.weight, case)
                assertEquals(listOf("leaf"), stack.frameIdsRootToLeaf.map(table::frame).map { it.symbolName }, case)
            }
        } finally {
            database.deleteIfExists()
        }
    }

    private fun java.sql.Connection.executeAll(statements: List<String>) {
        createStatement().use { statement -> statements.forEach(statement::execute) }
    }

    private fun java.sql.Connection.corrupt(sql: String) {
        createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys=OFF")
            statement.execute(sql)
        }
    }

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

    private fun withLegacyConnection(block: (java.sql.Connection) -> Unit) {
        val database = legacyDatabase()
        try {
            DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use(block)
        } finally {
            database.deleteIfExists()
        }
    }

    private companion object {
        val LOOP_IDENTITY_FIXTURE =
            listOf(
                "INSERT INTO file VALUES (2, '/example/boot.oat')",
                "INSERT INTO symbol VALUES (2, 2, 10, 'android.os.Looper.loopOnce', NULL)",
                "INSERT INTO symbol VALUES (3, 2, 11, 'android.os.Looper.loopOnce', NULL)",
                "INSERT INTO frame VALUES (2, 32, 2, 2, 'NATIVE')",
                "INSERT INTO frame VALUES (3, 48, 2, 3, 'ART')",
                "INSERT INTO callsite VALUES (2, NULL, 2)",
                "INSERT INTO callsite VALUES (3, NULL, 3)",
                "INSERT INTO sample VALUES (4, 40, 100, 104, 1, 9, 2)",
                "INSERT INTO sample VALUES (5, 50, 100, 104, 1, 1, 3)",
            )

        val LEGACY_MALFORMED_LEAF_FIXTURE =
            listOf(
                "INSERT INTO file VALUES (2, '/example/leaf.so')",
                "INSERT INTO symbol VALUES (2, 2, 2, 'malformedLeaf', NULL)",
                "INSERT INTO frame VALUES (2, 8192, 2, 2, 'NATIVE')",
                "INSERT INTO callsite VALUES (2, 1, 2)",
                "INSERT INTO sample VALUES (4, 40, 100, 104, 1, 7, 2)",
            )

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
