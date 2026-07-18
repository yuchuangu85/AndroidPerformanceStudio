@file:Suppress("MagicNumber")

package com.androidperformancestudio.storage

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LargeMarkerProjectionTest {
    @Test
    fun `hundred thousand markers keep narrow range queries bounded`() {
        val database = Files.createTempFile("aps-large-marker-", ".sqlite")
        try {
            SQLiteSampleStore.open(database).use { store ->
                store.connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO profile_source(source_id, kind, clock_domain) " +
                            "VALUES ('large', 'SIMPLEPERF', 'monotonic')",
                    )
                }
                store.insertMarkers()
                val rangeStart = TARGET_INDEX * MARKER_SPACING_NANOS
                lateinit var snapshot: MarkerProjectionSnapshot

                val elapsedMillis =
                    measureTimeMillis {
                        snapshot =
                            SQLiteMarkerProjectionQueries.load(
                                store.connection,
                                ProfileQuery(rangeStart, rangeStart + QUERY_DURATION_NANOS),
                                "",
                            )
                    }

                assertEquals(11, snapshot.markers.size)
                assertTrue(snapshot.markers.any { it.startNanos < rangeStart && it.endNanosExclusive > rangeStart })
                assertTrue(
                    snapshot.markers.all {
                        it.endNanosExclusive > rangeStart && it.startNanos < rangeStart + QUERY_DURATION_NANOS
                    },
                )
                assertTrue(snapshot.markers.all { it.payloadJson.startsWith("not-json-") })
                assertTrue(elapsedMillis <= TIMEOUT_MILLIS, "Marker query took ${elapsedMillis}ms")
            }
        } finally {
            database.deleteIfExists()
        }
    }

    private fun SQLiteSampleStore.insertMarkers() {
        connection.autoCommit = false
        connection
            .prepareStatement(
                "INSERT INTO profile_marker(" +
                    "source_id, thread_row_id, start_nanos, start_clock_domain, start_error_bound_nanos, " +
                    "end_nanos, end_clock_domain, end_error_bound_nanos, schema_name, name, payload_json" +
                    ") VALUES ('large', NULL, ?, 'monotonic', 0, ?, ?, ?, 'trace', ?, ?)",
            ).use { statement ->
                repeat(MARKER_COUNT) { index ->
                    val start = index * MARKER_SPACING_NANOS
                    statement.setLong(1, start)
                    if (index % 2 == 0) {
                        statement.setLong(2, start + INTERVAL_DURATION_NANOS)
                        statement.setString(3, "monotonic")
                        statement.setLong(4, 0)
                    } else {
                        statement.setNull(2, java.sql.Types.BIGINT)
                        statement.setNull(3, java.sql.Types.VARCHAR)
                        statement.setNull(4, java.sql.Types.BIGINT)
                    }
                    statement.setString(5, "marker-$index")
                    statement.setString(6, "not-json-$index")
                    statement.addBatch()
                    if (index % BATCH_SIZE == BATCH_SIZE - 1) statement.executeBatch()
                }
                statement.executeBatch()
            }
        connection.commit()
        connection.autoCommit = true
    }

    private companion object {
        const val MARKER_COUNT = 100_000
        const val TARGET_INDEX = 50_001L
        const val MARKER_SPACING_NANOS = 1_000_000L
        const val INTERVAL_DURATION_NANOS = 1_500_000L
        const val QUERY_DURATION_NANOS = 10_000_000L
        const val BATCH_SIZE = 1_000
        const val TIMEOUT_MILLIS = 10_000L
    }
}
