@file:Suppress("MaxLineLength", "MagicNumber", "TooGenericExceptionCaught")

package com.androidperformancestudio.memory.storage

import com.androidperformancestudio.contracts.DeviceIdentityPseudonymizer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant

data class MemorySessionMetadata(
    val sessionId: String,
    val packageName: String,
    val deviceSerial: String,
    val capturedAt: Instant,
    val rawHprofFile: Path,
    val convertedHprofFile: Path? = null,
    val mappingFile: Path? = null,
    val mappingDigest: String? = null,
    val classCount: Int = 0,
    val objectCount: Int = 0,
    val shallowSizeBytes: Long = 0L,
)

data class MemorySessionFilterPreset(
    val id: String,
    val label: String,
    val heapFilter: String?,
    val classScope: String,
    val leakFilter: String,
    val arrangeBy: String,
    val searchText: String,
    val matchCase: Boolean,
    val useRegex: Boolean,
)

data class MemorySessionUiSettings(
    val visibleColumns: Set<String> = emptySet(),
    val filterPresets: List<MemorySessionFilterPreset> = emptyList(),
)

class SqliteMemorySessionStore private constructor(
    private val connection: Connection,
    private val deviceIdentity: DeviceIdentityPseudonymizer = DeviceIdentityPseudonymizer(),
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
                    mapping_file TEXT,
                    mapping_digest TEXT,
                    class_count INTEGER NOT NULL,
                    object_count INTEGER NOT NULL,
                    shallow_size_bytes INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            addColumnIfMissing(statement, "mapping_file", "TEXT")
            addColumnIfMissing(statement, "mapping_digest", "TEXT")
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
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS memory_session_columns (
                    session_id TEXT NOT NULL,
                    column_name TEXT NOT NULL,
                    visible INTEGER NOT NULL,
                    PRIMARY KEY(session_id, column_name)
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS memory_session_filter_presets (
                    session_id TEXT NOT NULL,
                    preset_id TEXT NOT NULL,
                    label TEXT NOT NULL,
                    heap_filter TEXT,
                    class_scope TEXT NOT NULL,
                    leak_filter TEXT NOT NULL,
                    arrange_by TEXT NOT NULL,
                    search_text TEXT NOT NULL,
                    match_case INTEGER NOT NULL,
                    use_regex INTEGER NOT NULL,
                    PRIMARY KEY(session_id, preset_id)
                )
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
                    mapping_file,
                    mapping_digest,
                    class_count,
                    object_count,
                    shallow_size_bytes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET
                    package_name = excluded.package_name,
                    device_serial = excluded.device_serial,
                    captured_at_epoch_millis = excluded.captured_at_epoch_millis,
                    raw_hprof_file = excluded.raw_hprof_file,
                    converted_hprof_file = excluded.converted_hprof_file,
                    mapping_file = excluded.mapping_file,
                    mapping_digest = excluded.mapping_digest,
                    class_count = excluded.class_count,
                    object_count = excluded.object_count,
                    shallow_size_bytes = excluded.shallow_size_bytes
                """.trimIndent(),
            ).use { statement ->
                statement.setString(SESSION_ID_PARAMETER, metadata.sessionId)
                statement.setString(PACKAGE_NAME_PARAMETER, metadata.packageName)
                statement.setString(
                    DEVICE_SERIAL_PARAMETER,
                    metadata.deviceSerial
                        .takeIf(String::isNotBlank)
                        ?.let(deviceIdentity::localId)
                        ?.value
                        .orEmpty(),
                )
                statement.setLong(CAPTURED_AT_PARAMETER, metadata.capturedAt.toEpochMilli())
                statement.setString(RAW_HPROF_PARAMETER, metadata.rawHprofFile.toString())
                statement.setString(CONVERTED_HPROF_PARAMETER, metadata.convertedHprofFile?.toString())
                statement.setString(MAPPING_FILE_PARAMETER, metadata.mappingFile?.toString())
                statement.setString(MAPPING_DIGEST_PARAMETER, metadata.mappingDigest)
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
                    raw_hprof_file, converted_hprof_file, mapping_file, mapping_digest, class_count, object_count, shallow_size_bytes
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
                    raw_hprof_file, converted_hprof_file, mapping_file, mapping_digest, class_count, object_count, shallow_size_bytes
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

    fun saveUiSettings(
        sessionId: String,
        settings: MemorySessionUiSettings,
    ) {
        connection.autoCommit = false
        try {
            connection.prepareStatement("DELETE FROM memory_session_columns WHERE session_id = ?").use { statement ->
                statement.setString(1, sessionId)
                statement.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM memory_session_filter_presets WHERE session_id = ?").use { statement ->
                statement.setString(1, sessionId)
                statement.executeUpdate()
            }
            connection
                .prepareStatement(
                    "INSERT INTO memory_session_columns(session_id, column_name, visible) VALUES (?, ?, ?)",
                ).use { statement ->
                    settings.visibleColumns.forEach { column ->
                        statement.setString(1, sessionId)
                        statement.setString(2, column)
                        statement.setInt(3, 1)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            connection
                .prepareStatement(
                    """
                    INSERT INTO memory_session_filter_presets(
                        session_id, preset_id, label, heap_filter, class_scope, leak_filter,
                        arrange_by, search_text, match_case, use_regex
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    settings.filterPresets.forEach { preset ->
                        statement.setString(1, sessionId)
                        statement.setString(2, preset.id)
                        statement.setString(3, preset.label)
                        statement.setString(4, preset.heapFilter)
                        statement.setString(5, preset.classScope)
                        statement.setString(6, preset.leakFilter)
                        statement.setString(7, preset.arrangeBy)
                        statement.setString(8, preset.searchText)
                        statement.setInt(9, if (preset.matchCase) 1 else 0)
                        statement.setInt(10, if (preset.useRegex) 1 else 0)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            connection.commit()
        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        } finally {
            connection.autoCommit = true
        }
    }

    fun loadUiSettings(sessionId: String): MemorySessionUiSettings {
        val visibleColumns =
            connection
                .prepareStatement(
                    "SELECT column_name FROM memory_session_columns WHERE session_id = ? ORDER BY column_name",
                ).use { statement ->
                    statement.setString(1, sessionId)
                    statement.executeQuery().use { resultSet ->
                        buildSet {
                            while (resultSet.next()) add(resultSet.getString(1))
                        }
                    }
                }
        val presets =
            connection
                .prepareStatement(
                    """
                    SELECT preset_id, label, heap_filter, class_scope, leak_filter, arrange_by,
                        search_text, match_case, use_regex
                    FROM memory_session_filter_presets
                    WHERE session_id = ? ORDER BY preset_id
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, sessionId)
                    statement.executeQuery().use { resultSet ->
                        buildList {
                            while (resultSet.next()) {
                                add(
                                    MemorySessionFilterPreset(
                                        id = resultSet.getString("preset_id"),
                                        label = resultSet.getString("label"),
                                        heapFilter = resultSet.getString("heap_filter"),
                                        classScope = resultSet.getString("class_scope"),
                                        leakFilter = resultSet.getString("leak_filter"),
                                        arrangeBy = resultSet.getString("arrange_by"),
                                        searchText = resultSet.getString("search_text"),
                                        matchCase = resultSet.getInt("match_case") != 0,
                                        useRegex = resultSet.getInt("use_regex") != 0,
                                    ),
                                )
                            }
                        }
                    }
                }
        return MemorySessionUiSettings(visibleColumns = visibleColumns, filterPresets = presets)
    }

    private fun addColumnIfMissing(
        statement: java.sql.Statement,
        columnName: String,
        definition: String,
    ) {
        try {
            statement.executeUpdate("ALTER TABLE memory_sessions ADD COLUMN $columnName $definition")
        } catch (_: java.sql.SQLException) {
            // Existing databases already contain the column.
        }
    }

    override fun close() {
        connection.close()
    }

    private fun ResultSet.toMetadata(): MemorySessionMetadata =
        MemorySessionMetadata(
            sessionId = getString("session_id"),
            packageName = getString("package_name"),
            deviceSerial = getString("device_serial"),
            capturedAt = Instant.ofEpochMilli(getLong("captured_at_epoch_millis")),
            rawHprofFile = Path.of(getString("raw_hprof_file")),
            convertedHprofFile = getString("converted_hprof_file")?.let(Path::of),
            mappingFile = getString("mapping_file")?.let(Path::of),
            mappingDigest = getString("mapping_digest"),
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
        private const val MAPPING_FILE_PARAMETER = 7
        private const val MAPPING_DIGEST_PARAMETER = 8
        private const val CLASS_COUNT_PARAMETER = 9
        private const val OBJECT_COUNT_PARAMETER = 10
        private const val SHALLOW_SIZE_PARAMETER = 11

        fun open(databaseFile: Path): SqliteMemorySessionStore {
            databaseFile.parent?.let(Files::createDirectories)
            return SqliteMemorySessionStore(DriverManager.getConnection("jdbc:sqlite:$databaseFile"))
        }
    }
}
