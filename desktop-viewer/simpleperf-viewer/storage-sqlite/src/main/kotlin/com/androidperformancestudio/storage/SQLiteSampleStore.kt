package com.androidperformancestudio.storage

import com.androidperformancestudio.model.CanonicalProfileRecord
import com.androidperformancestudio.model.NormalizedProfileRecord
import com.androidperformancestudio.model.NormalizedSample
import com.androidperformancestudio.model.ProfileExecutionType
import com.androidperformancestudio.model.ProfileFrame
import com.androidperformancestudio.model.ProfileSample
import com.androidperformancestudio.profileanalysis.CallStackDirection
import org.sqlite.ProgressHandler
import org.sqlite.SQLiteConnection
import java.io.Closeable
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

internal fun interface ConnectionProvider {
    fun connect(url: String): Connection
}

@Suppress("TooManyFunctions")
class SQLiteSampleStore private constructor(
    internal val connection: Connection,
) : Closeable {
    fun schemaVersion(): Int = connection.singleInt("PRAGMA user_version")

    fun importSamples(
        samples: Sequence<ProfileSample>,
        batchSize: Int = DEFAULT_BATCH_SIZE,
    ): SampleImportResult {
        val result =
            importRecords(
                samples.map { sample ->
                    NormalizedProfileRecord.Sample(
                        NormalizedSample(
                            timestampNanos = sample.timestampNanos,
                            processId = sample.processId,
                            threadId = sample.threadId,
                            threadName = "<unknown-thread:${sample.threadId}>",
                            eventType = sample.eventType,
                            eventCount = sample.eventCount,
                            frames =
                                listOf(
                                    ProfileFrame(
                                        virtualAddress = 0,
                                        fileId = LEGACY_FILE_ID,
                                        symbolId = LEGACY_SYMBOL_ID,
                                        filePath = LEGACY_FILE_PATH,
                                        symbolName = sample.symbolName,
                                        executionType = ProfileExecutionType.NATIVE,
                                    ),
                                ),
                            unwindError = null,
                        ),
                    )
                },
                batchSize,
            )
        return SampleImportResult(result.importedSamples, result.committedBatches)
    }

    fun importRecords(
        records: Sequence<NormalizedProfileRecord>,
        batchSize: Int = DEFAULT_BATCH_SIZE,
    ): ProfileImportResult {
        beginRecordImport(batchSize).use { writer ->
            records.forEach(writer::add)
            return writer.finish()
        }
    }

    fun importCanonicalRecords(
        records: Sequence<CanonicalProfileRecord>,
        batchSize: Int = DEFAULT_BATCH_SIZE,
    ): ProfileImportResult {
        beginRecordImport(batchSize).use { writer ->
            records.forEach(writer::addCanonical)
            return writer.finish()
        }
    }

    fun beginRecordImport(batchSize: Int = DEFAULT_BATCH_SIZE): SQLiteProfileRecordWriter {
        require(batchSize > 0) { "batchSize must be positive" }
        check(connection.autoCommit) { "A record import is already active" }
        connection.autoCommit = false
        return try {
            SQLiteProfileRecordWriter(connection, batchSize)
        } catch (failure: SQLException) {
            try {
                connection.rollback()
            } finally {
                connection.autoCommit = true
            }
            throw failure
        }
    }

    fun sampleCount(query: ProfileQuery = ProfileQuery()): Long = SQLiteProfileQueries.sampleCount(connection, query)

    fun frameCount(): Long = connection.singleLong("SELECT COUNT(*) FROM frame")

    fun callsiteCount(): Long = connection.singleLong("SELECT COUNT(*) FROM callsite")

    fun journalMode(): String = connection.singleString("PRAGMA journal_mode")

    fun checkpointWal() {
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").use { result ->
                check(result.next())
                check(result.getInt(1) == 0) { "SQLite WAL checkpoint was busy" }
            }
        }
    }

    fun interrupt() {
        val sqliteConnection =
            connection as? SQLiteConnection
                ?: throw SQLException("SQLite interruption requires an SQLiteConnection")
        sqliteConnection.database.interrupt()
    }

    fun installCancellationHandler(cancellationRequested: () -> Boolean): AutoCloseable {
        ProgressHandler.setHandler(
            connection,
            CANCELLATION_PROGRESS_INTERVAL,
            object : ProgressHandler() {
                override fun progress(): Int = if (cancellationRequested()) 1 else 0
            },
        )
        return AutoCloseable { ProgressHandler.clearHandler(connection) }
    }

    fun threads(query: ProfileQuery = ProfileQuery()): List<ThreadSummary> = queryThreads(connection, query)

    fun forEachStoredSample(action: (StoredProfileSample) -> Unit) {
        SQLiteStoredProfileQueries.forEachSample(connection, action)
    }

    fun topFunctions(
        query: ProfileQuery = ProfileQuery(),
        limit: Int,
        search: String = "",
        sort: TopFunctionSort = TopFunctionSort.INCLUSIVE_WEIGHT,
        descending: Boolean = true,
    ): List<TopFunction> =
        SQLiteProfileQueries.topFunctions(
            connection,
            query,
            TopFunctionOptions(limit, search, sort, descending),
        )

    fun topSymbols(limit: Int): List<SymbolWeight> = topFunctions(limit = limit).weights()

    fun dataQuality(): DataQualitySummary = SQLiteProfileQueries.dataQuality(connection)

    fun overview(query: ProfileQuery = ProfileQuery()): ProfileOverview = queryOverview(connection, query)

    fun timelineBuckets(
        query: ProfileQuery = ProfileQuery(),
        bucketCount: Int,
    ): List<TimelineBucket> = SQLiteProfileQueries.timelineBuckets(connection, query, bucketCount)

    fun threadTimelineTracks(
        query: ProfileQuery = ProfileQuery(),
        bucketCount: Int,
    ): List<ThreadTimelineTrack> = SQLiteProfileQueries.threadTimelineTracks(connection, query, bucketCount)

    fun callTree(
        query: ProfileQuery = ProfileQuery(),
        direction: CallStackDirection,
    ): List<CallTreeNode> = SQLiteCallTreeQueries.aggregate(connection, query, direction)

    fun projectCore(query: ProfileQuery = ProfileQuery()): ProfileProjectionSnapshot =
        SQLiteProfileProjectionQueries.project(
            store = this,
            query = query,
        )

    fun projectCore(request: ProfileProjectionRequest): ProfileProjectionSnapshot =
        SQLiteProfileProjectionQueries.project(
            store = this,
            request = request,
        )

    override fun close() {
        connection.close()
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 10_000
        private const val LEGACY_FILE_ID = -1
        private const val LEGACY_SYMBOL_ID = -1
        private const val LEGACY_FILE_PATH = "<legacy>"

        fun open(databasePath: Path): SQLiteSampleStore {
            Class.forName("org.sqlite.JDBC")
            databasePath
                .toAbsolutePath()
                .parent
                ?.toFile()
                ?.mkdirs()
            return open(databasePath, DEFAULT_CONNECTION_PROVIDER)
        }

        fun openReadOnly(databasePath: Path): SQLiteSampleStore {
            Class.forName("org.sqlite.JDBC")
            return openReadOnlyExpected(databasePath, null, DEFAULT_CONNECTION_PROVIDER)
        }

        fun schemaVersion(databasePath: Path): Int = openReadOnly(databasePath).use(SQLiteSampleStore::schemaVersion)

        fun openV2(databasePath: Path): SQLiteSampleStore {
            Class.forName("org.sqlite.JDBC")
            return openV2(databasePath, DEFAULT_CONNECTION_PROVIDER)
        }

        fun openReadOnlyExpected(
            databasePath: Path,
            expectedVersion: Int,
        ): SQLiteSampleStore {
            Class.forName("org.sqlite.JDBC")
            return openReadOnlyExpected(databasePath, expectedVersion, DEFAULT_CONNECTION_PROVIDER)
        }

        internal fun open(
            databasePath: Path,
            provider: ConnectionProvider,
        ): SQLiteSampleStore =
            createStore(provider.connect("jdbc:sqlite:${databasePath.toAbsolutePath()}")) { connection ->
                configure(connection)
                SQLiteSchema.migrate(connection)
            }

        internal fun openV2(
            databasePath: Path,
            provider: ConnectionProvider,
        ): SQLiteSampleStore =
            createStore(provider.connect(writableJdbcUrl(databasePath))) { connection ->
                requireSchemaVersion(connection, EXPECTED_WRITABLE_VERSION)
                configure(connection)
            }

        internal fun openReadOnlyExpected(
            databasePath: Path,
            expectedVersion: Int,
            provider: ConnectionProvider,
        ): SQLiteSampleStore = openReadOnlyExpected(databasePath, expectedVersion as Int?, provider)

        fun createStableSnapshot(
            databasePath: Path,
            snapshotPath: Path,
            expectedVersion: Int,
            verifySource: () -> Unit,
        ) {
            Class.forName("org.sqlite.JDBC")
            val connection = DEFAULT_CONNECTION_PROVIDER.connect(writableJdbcUrl(databasePath))
            var transactionStarted = false
            try {
                requireSchemaVersion(connection, expectedVersion)
                requireRollbackJournal(connection)
                val sqliteConnection = requireSQLiteConnection(connection)
                val result =
                    sqliteConnection.database.backup(
                        "main",
                        snapshotPath.toAbsolutePath().toString(),
                        null,
                    )
                requireSuccessfulBackup(result)
                connection.createStatement().use { it.execute("BEGIN EXCLUSIVE") }
                transactionStarted = true
                requireSchemaVersion(connection, expectedVersion)
                verifySource()
            } finally {
                if (transactionStarted) {
                    runCatching { connection.createStatement().use { it.execute("ROLLBACK") } }
                }
                closeQuietly(connection)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun createStore(
            connection: Connection,
            setup: (Connection) -> Unit,
        ): SQLiteSampleStore =
            try {
                setup(connection)
                SQLiteSampleStore(connection)
            } catch (failure: Throwable) {
                closeQuietly(connection)
                throw failure
            }

        private fun openReadOnlyExpected(
            databasePath: Path,
            expectedVersion: Int?,
            provider: ConnectionProvider,
        ): SQLiteSampleStore =
            createStore(provider.connect(readOnlyJdbcUrl(databasePath))) { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA query_only=ON")
                    statement.execute("PRAGMA foreign_keys=ON")
                    statement.execute("PRAGMA temp_store=MEMORY")
                }
                expectedVersion?.let { requireSchemaVersion(connection, it) }
            }

        private fun requireSchemaVersion(
            connection: Connection,
            expectedVersion: Int,
        ) {
            val actual = connection.singleInt("PRAGMA user_version")
            if (actual != expectedVersion) {
                throw SQLException("Expected schema version $expectedVersion but found $actual")
            }
        }

        private fun requireRollbackJournal(connection: Connection) {
            if (connection.singleString("PRAGMA journal_mode").equals("wal", ignoreCase = true)) {
                throw SQLException("WAL-mode source cannot be replaced safely")
            }
        }

        private fun requireSQLiteConnection(connection: Connection): SQLiteConnection =
            connection as? SQLiteConnection
                ?: throw SQLException("SQLite online backup requires an SQLiteConnection")

        private fun requireSuccessfulBackup(result: Int) {
            if (result != 0) throw SQLException("SQLite online backup failed with result $result")
        }

        private fun writableJdbcUrl(databasePath: Path): String =
            "jdbc:sqlite:${databasePath.toAbsolutePath().toUri().toASCIIString()}?mode=rw"

        private fun readOnlyJdbcUrl(databasePath: Path): String =
            "jdbc:sqlite:${databasePath.toAbsolutePath().toUri().toASCIIString()}?mode=ro"

        @Suppress("TooGenericExceptionCaught")
        private fun closeQuietly(connection: Connection) {
            try {
                connection.close()
            } catch (_: Throwable) {
                // Preserve the setup failure; there is no usable store to return.
            }
        }

        private fun configure(connection: Connection) {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys=ON")
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA synchronous=NORMAL")
                statement.execute("PRAGMA temp_store=MEMORY")
            }
        }

        private val DEFAULT_CONNECTION_PROVIDER = ConnectionProvider(DriverManager::getConnection)
        private const val EXPECTED_WRITABLE_VERSION = 2
        private const val CANCELLATION_PROGRESS_INTERVAL = 4_096
    }
}

internal fun Connection.singleLong(sql: String): Long =
    createStatement().use { statement ->
        statement.executeQuery(sql).use { result ->
            check(result.next())
            result.getLong(1)
        }
    }

internal fun Connection.singleInt(sql: String): Int = singleLong(sql).toInt()

internal fun Connection.singleString(sql: String): String =
    createStatement().use { statement ->
        statement.executeQuery(sql).use { result ->
            check(result.next())
            result.getString(1)
        }
    }

private fun queryThreads(
    connection: Connection,
    query: ProfileQuery,
): List<ThreadSummary> = SQLiteProfileQueries.threads(connection, query)

private fun queryOverview(
    connection: Connection,
    query: ProfileQuery,
): ProfileOverview = SQLiteProfileQueries.overview(connection, query)

private fun List<TopFunction>.weights(): List<SymbolWeight> = map { SymbolWeight(it.symbolName, it.inclusiveWeight) }
