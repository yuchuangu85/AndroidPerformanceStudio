@file:Suppress("LongMethod", "LongParameterList")

package com.androidperformancestudio.storage

import java.nio.file.Files
import java.sql.DriverManager
import java.sql.Types
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SQLiteMarkerProjectionQueriesTest {
    @Test
    fun `point markers use one nanosecond exclusive extent`() {
        val snapshot = queryMarkers(pointMarker(start = 30), ProfileQuery())

        assertEquals(30, snapshot.markers.single().startNanos)
        assertEquals(31, snapshot.markers.single().endNanosExclusive)
        assertFalse(snapshot.markers.single().interval)
    }

    @Test
    fun `point marker at maximum timestamp does not overflow`() {
        val marker = queryMarkers(pointMarker(Long.MAX_VALUE), ProfileQuery()).markers.single()

        assertEquals(Long.MAX_VALUE, marker.startNanos)
        assertEquals(Long.MAX_VALUE, marker.endNanosExclusive)
    }

    @Test
    fun `interval markers are selected by overlap`() {
        val snapshot = queryMarkers(intervalMarker(start = 10, end = 40), ProfileQuery(20, 30))

        assertEquals("draw", snapshot.markers.single().name)
        assertTrue(snapshot.markers.single().interval)
    }

    @Test
    fun `point and interval boundaries preserve half open range semantics`() {
        val snapshot =
            queryMarkers(
                listOf(
                    pointMarker(start = 20, name = "point-at-start"),
                    pointMarker(start = 30, name = "point-at-end"),
                    intervalMarker(start = 10, end = 20, name = "interval-ending-at-start"),
                    intervalMarker(start = 10, end = 21, name = "interval-overlap"),
                    intervalMarker(start = 30, end = 40, name = "interval-at-end"),
                ),
                ProfileQuery(20, 30),
            )

        assertEquals(listOf("interval-overlap", "point-at-start"), snapshot.markers.map { it.name })
    }

    @Test
    fun `range without matching markers reports empty range`() {
        assertEquals(
            MarkerEmptyReason.RANGE_EMPTY,
            queryMarkers(pointMarker(30), ProfileQuery(40, 50)).emptyReason,
        )
    }

    @Test
    fun `marker search without matches reports filtered empty`() {
        assertEquals(
            MarkerEmptyReason.FILTERED_EMPTY,
            queryMarkers(pointMarker(30), ProfileQuery(), "missing").emptyReason,
        )
    }

    @Test
    fun `empty canonical profile reports profile empty`() {
        assertEquals(MarkerEmptyReason.PROFILE_EMPTY, queryMarkers(emptyList(), ProfileQuery()).emptyReason)
    }

    @Test
    fun `marker search is case insensitive across fields and escapes like metacharacters`() {
        val markers =
            listOf(
                pointMarker(10, name = "Frame%_\\READY"),
                pointMarker(20, name = "Frame-any-ready"),
                pointMarker(30, schema = "SCHEMA%_\\MATCH", name = "schema"),
                pointMarker(40, name = "payload", payloadJson = "{\"label\":\"PAYLOAD%_\\MATCH\"}"),
            )

        assertEquals(
            listOf("Frame%_\\READY"),
            queryMarkers(markers, ProfileQuery(), "%_\\ready").markers.map { it.name },
        )
        assertEquals(
            listOf("schema"),
            queryMarkers(markers, ProfileQuery(), "schema%_\\match").markers.map { it.name },
        )
        assertEquals(
            listOf("payload"),
            queryMarkers(markers, ProfileQuery(), "payload%_\\match").markers.map { it.name },
        )
    }

    @Test
    fun `markers are ordered and lanes use stable source process thread grouping`() {
        val snapshot =
            queryMarkers(
                listOf(
                    pointMarker(30, sourceId = "b", processId = 3, threadId = 4, threadName = "Worker"),
                    pointMarker(10, sourceId = "a", processId = null, threadId = null, threadName = null),
                    pointMarker(20, sourceId = "a", processId = 1, threadId = 2, threadName = "Main"),
                    pointMarker(10, sourceId = "a", processId = 1, threadId = 2, threadName = "Main"),
                ),
                ProfileQuery(),
            )

        assertEquals(listOf(10L, 10L, 20L, 30L), snapshot.markers.map { it.startNanos })
        assertEquals(
            listOf("markers:a:global", "markers:a:1:2", "markers:b:3:4"),
            snapshot.lanes.map { it.key },
        )
        val mainIds = snapshot.markers.filter { it.threadId == 2 }.map { it.id }
        assertEquals(mainIds, snapshot.lanes.single { it.key == "markers:a:1:2" }.markerIds)
    }

    @Test
    fun `thread selection excludes global and other thread markers`() {
        val snapshot =
            queryMarkers(
                listOf(
                    pointMarker(10, processId = null, threadId = null, threadName = null),
                    pointMarker(20, processId = 1, threadId = 2, threadName = "selected"),
                    pointMarker(30, processId = 1, threadId = 3, threadName = "other"),
                ),
                ProfileQuery(threadIds = setOf(2)),
            )

        assertEquals(listOf(2), snapshot.markers.map { it.threadId })
    }

    @Test
    fun `in range markers excluded only by thread selection report filtered empty`() {
        val snapshot =
            queryMarkers(
                pointMarker(20, processId = 1, threadId = 2, threadName = "other"),
                ProfileQuery(startNanosInclusive = 10, endNanosExclusive = 30, threadIds = setOf(3)),
            )

        assertEquals(MarkerEmptyReason.FILTERED_EMPTY, snapshot.emptyReason)
    }

    @Test
    fun `query snapshots expose defensive immutable marker and lane lists`() {
        val snapshot = queryMarkers(pointMarker(30), ProfileQuery())

        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (snapshot.markers as MutableList<MarkerProjectionRow>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (snapshot.lanes as MutableList<MarkerLane>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (snapshot.lanes.single().markerIds as MutableList<ProfileMarkerId>).clear()
        }
    }

    @Test
    fun `legacy database reports markers not collected`() {
        val snapshot = queryLegacyMarkers()

        assertEquals(MarkerAvailability.NOT_COLLECTED, snapshot.availability)
        assertNull(snapshot.emptyReason)
        assertTrue(snapshot.markers.isEmpty())
        assertTrue(snapshot.lanes.isEmpty())
    }

    private fun queryMarkers(
        marker: MarkerSeed,
        query: ProfileQuery,
        search: String = "",
    ): MarkerProjectionSnapshot = queryMarkers(listOf(marker), query, search)

    private fun queryMarkers(
        markers: List<MarkerSeed>,
        query: ProfileQuery,
        search: String = "",
    ): MarkerProjectionSnapshot {
        val database = Files.createTempFile("aps-marker-projection-", ".sqlite")
        return try {
            SQLiteSampleStore.open(database).use { store ->
                markers.forEach { marker -> store.connection.insert(marker) }
                SQLiteMarkerProjectionQueries.load(store.connection, query, search)
            }
        } finally {
            database.deleteIfExists()
        }
    }

    private fun queryLegacyMarkers(): MarkerProjectionSnapshot {
        Class.forName("org.sqlite.JDBC")
        val database = Files.createTempFile("aps-marker-legacy-", ".sqlite")
        return try {
            DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement -> statement.execute("PRAGMA user_version=1") }
                SQLiteMarkerProjectionQueries.load(connection, ProfileQuery(), "")
            }
        } finally {
            database.deleteIfExists()
        }
    }

    private fun java.sql.Connection.insert(marker: MarkerSeed) {
        prepareStatement(
            "INSERT OR IGNORE INTO profile_source(source_id, kind, clock_domain) VALUES (?, 'SIMPLEPERF', 'monotonic')",
        ).use { statement ->
            statement.setString(1, marker.sourceId)
            statement.executeUpdate()
        }
        val threadRowId =
            if (marker.threadId == null) {
                null
            } else {
                requireNotNull(marker.processId)
                prepareStatement(
                    "INSERT OR IGNORE INTO profile_process(source_id, process_id, name) VALUES (?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, marker.sourceId)
                    statement.setInt(2, marker.processId)
                    statement.setString(3, "Process ${marker.processId}")
                    statement.executeUpdate()
                }
                val processRowId =
                    prepareStatement(
                        "SELECT process_row_id FROM profile_process WHERE source_id=? AND process_id=?",
                    ).use { statement ->
                        statement.setString(1, marker.sourceId)
                        statement.setInt(2, marker.processId)
                        statement.executeQuery().use { result ->
                            check(result.next())
                            result.getLong(1)
                        }
                    }
                prepareStatement(
                    "INSERT OR IGNORE INTO profile_thread(source_id, process_row_id, thread_id, name) " +
                        "VALUES (?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, marker.sourceId)
                    statement.setLong(2, processRowId)
                    statement.setInt(3, marker.threadId)
                    statement.setString(4, requireNotNull(marker.threadName))
                    statement.executeUpdate()
                }
                prepareStatement(
                    "SELECT thread_row_id FROM profile_thread " +
                        "WHERE source_id=? AND process_row_id=? AND thread_id=?",
                ).use { statement ->
                    statement.setString(1, marker.sourceId)
                    statement.setLong(2, processRowId)
                    statement.setInt(3, marker.threadId)
                    statement.executeQuery().use { result ->
                        check(result.next())
                        result.getLong(1)
                    }
                }
            }
        prepareStatement(
            "INSERT INTO profile_marker(source_id, thread_row_id, start_nanos, start_clock_domain, " +
                "start_error_bound_nanos, end_nanos, end_clock_domain, end_error_bound_nanos, " +
                "schema_name, name, payload_json) VALUES (?, ?, ?, 'monotonic', 0, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, marker.sourceId)
            if (threadRowId == null) statement.setNull(2, Types.BIGINT) else statement.setLong(2, threadRowId)
            statement.setLong(3, marker.startNanos)
            if (marker.endNanos == null) {
                statement.setNull(4, Types.BIGINT)
                statement.setNull(5, Types.VARCHAR)
                statement.setNull(6, Types.BIGINT)
            } else {
                statement.setLong(4, marker.endNanos)
                statement.setString(5, "monotonic")
                statement.setLong(6, 0)
            }
            statement.setString(7, marker.schema)
            statement.setString(8, marker.name)
            statement.setString(9, marker.payloadJson)
            statement.executeUpdate()
        }
    }

    private fun pointMarker(
        start: Long,
        sourceId: String = "simpleperf",
        processId: Int? = 100,
        threadId: Int? = 101,
        threadName: String? = "RenderThread",
        schema: String = "trace_event",
        name: String = "draw",
        payloadJson: String = "{}",
    ): MarkerSeed = MarkerSeed(sourceId, processId, threadId, threadName, start, null, schema, name, payloadJson)

    private fun intervalMarker(
        start: Long,
        end: Long,
        sourceId: String = "simpleperf",
        processId: Int? = 100,
        threadId: Int? = 101,
        threadName: String? = "RenderThread",
        schema: String = "trace_event",
        name: String = "draw",
        payloadJson: String = "{}",
    ): MarkerSeed = MarkerSeed(sourceId, processId, threadId, threadName, start, end, schema, name, payloadJson)

    private data class MarkerSeed(
        val sourceId: String,
        val processId: Int?,
        val threadId: Int?,
        val threadName: String?,
        val startNanos: Long,
        val endNanos: Long?,
        val schema: String,
        val name: String,
        val payloadJson: String,
    )
}
