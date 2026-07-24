package dev.agentperf.memory.storage

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

data class MemorySessionMetadata(
    val sessionId: String,
    val packageName: String,
    val deviceSerial: String,
    val capturedAt: Instant,
    val rawHprofFile: Path,
    val convertedHprofFile: Path? = null,
    val classCount: Int = 0,
    val objectCount: Int = 0,
    val shallowSizeBytes: Long = 0L,
)

class SqliteMemorySessionStore private constructor(
    private val connection: Connection,
) : AutoCloseable {
    init {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS memory_sessions (
                    session_id TEXT PRIMARY KEY NOT NULL,
                    package_name TEXT NOT NULL,
                    device_serial TEXT NOT NULL,
                    captured_at_epoch_millis INTEGER NOT NULL,
                    raw_hprof_file TEXT NOT NULL,
                    converted_hprof_file TEXT,
                    class_count INTEGER NOT NULL,
                    object_count INTEGER NOT NULL,
                    shallow_size_bytes INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS memory_sessions_captured_at_idx
                ON memory_sessions(captured_at_epoch_millis DESC)
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS memory_sessions_package_idx
                ON memory_sessions(package_name)
                """.trimIndent(),
            )
        }
    }

    fun upsert(metadata: MemorySessionMetadata) {
        connection
            .prepareStatement(
                """
                INSERT INTO memory_sessions(
                    session_id,
                    package_name,
                    device_serial,
                    captured_at_epoch_millis,
                    raw_hprof_file,
                    converted_hprof_file,
                    class_count,
                    object_count,
                    shallow_size_bytes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET
                    package_name = excluded.package_name,
                    device_serial = excluded.device_serial,
                    captured_at_epoch_millis = excluded.captured_at_epoch_millis,
                    raw_hprof_file = excluded.raw_hprof_file,
                    converted_hprof_file = excluded.converted_hprof_file,
                    class_count = excluded.class_count,
                    object_count = excluded.object_count,
                    shallow_size_bytes = excluded.shallow_size_bytes
                """.trimIndent(),
            ).use { statement ->
                statement.setString(SESSION_ID_PARAMETER, metadata.sessionId)
                statement.setString(PACKAGE_NAME_PARAMETER, metadata.packageName)
                statement.setString(DEVICE_SERIAL_PARAMETER, metadata.deviceSerial)
                statement.setLong(CAPTURED_AT_PARAMETER, metadata.capturedAt.toEpochMilli())
                statement.setString(RAW_HPROF_PARAMETER, metadata.rawHprofFile.toString())
                statement.setString(CONVERTED_HPROF_PARAMETER, metadata.convertedHprofFile?.toString())
                statement.setInt(CLASS_COUNT_PARAMETER, metadata.classCount)
                statement.setInt(OBJECT_COUNT_PARAMETER, metadata.objectCount)
                statement.setLong(SHALLOW_SIZE_PARAMETER, metadata.shallowSizeBytes)
                statement.executeUpdate()
            }
    }

    fun find(sessionId: String): MemorySessionMetadata? =
        connection
            .prepareStatement(
                """
                SELECT session_id, package_name, device_serial, captured_at_epoch_millis,
                    raw_hprof_file, converted_hprof_file, class_count, object_count, shallow_size_bytes
                FROM memory_sessions
                WHERE session_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.toMetadata() else null
                }
            }

    fun listRecent(limit: Int = DEFAULT_LIMIT): List<MemorySessionMetadata> {
        require(limit > 0) { "limit must be positive" }
        return connection
            .prepareStatement(
                """
                SELECT session_id, package_name, device_serial, captured_at_epoch_millis,
                    raw_hprof_file, converted_hprof_file, class_count, object_count, shallow_size_bytes
                FROM memory_sessions
                ORDER BY captured_at_epoch_millis DESC, session_id ASC
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) add(resultSet.toMetadata())
                    }
                }
            }
    }

    override fun close() {
        connection.close()
    }

    private fun java.sql.ResultSet.toMetadata(): MemorySessionMetadata =
        MemorySessionMetadata(
            sessionId = getString("session_id"),
            packageName = getString("package_name"),
            deviceSerial = getString("device_serial"),
            capturedAt = Instant.ofEpochMilli(getLong("captured_at_epoch_millis")),
            rawHprofFile = Path.of(getString("raw_hprof_file")),
            convertedHprofFile = getString("converted_hprof_file")?.let(Path::of),
            classCount = getInt("class_count"),
            objectCount = getInt("object_count"),
            shallowSizeBytes = getLong("shallow_size_bytes"),
        )

    companion object {
        private const val DEFAULT_LIMIT = 100
        private const val SESSION_ID_PARAMETER = 1
        private const val PACKAGE_NAME_PARAMETER = 2
        private const val DEVICE_SERIAL_PARAMETER = 3
        private const val CAPTURED_AT_PARAMETER = 4
        private const val RAW_HPROF_PARAMETER = 5
        private const val CONVERTED_HPROF_PARAMETER = 6
        private const val CLASS_COUNT_PARAMETER = 7
        private const val OBJECT_COUNT_PARAMETER = 8
        private const val SHALLOW_SIZE_PARAMETER = 9

        fun open(databaseFile: Path): SqliteMemorySessionStore {
            databaseFile.parent?.let(Files::createDirectories)
            return SqliteMemorySessionStore(DriverManager.getConnection("jdbc:sqlite:$databaseFile"))
        }
    }
}
