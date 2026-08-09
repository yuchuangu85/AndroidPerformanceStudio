@file:Suppress("TooGenericExceptionCaught", "MaxLineLength", "MagicNumber")

package com.androidperformancestudio.ai

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

public interface AnalysisSessionRepository {
    public fun saveSession(session: AnalysisSession)

    public fun saveRequest(request: AnalysisRequest)

    public fun saveResult(result: AnalysisResult)

    public fun session(id: AnalysisSessionId): AnalysisSession?

    public fun findings(id: AnalysisSessionId): List<AnalysisFinding>

    public fun evidence(id: AnalysisSessionId): List<AnalysisEvidenceSummary>

    public fun candidates(id: AnalysisSessionId): List<AnalysisCandidateSummary>
}

public data class AnalysisEvidenceSummary(
    val id: String,
    val kind: String,
    val summary: String,
    val payloadHash: String,
)

public data class AnalysisCandidateSummary(
    val id: String,
    val relativePath: String,
    val startLine: Int?,
    val endLine: Int?,
    val resolutionConfidence: String,
    val contentHash: String?,
)

public class SqliteAnalysisSessionRepository(
    databasePath: Path,
) : AnalysisSessionRepository,
    AutoCloseable {
    private val connection: Connection

    init {
        databasePath.parent?.let(Files::createDirectories)
        connection = DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode = WAL")
            statement.execute("PRAGMA foreign_keys = ON")
        }
        createSchema()
        connection.ensureColumn("analysis_session", "provider", "TEXT")
        migrateLegacyFindingPrimaryKey()
    }

    override fun saveSession(session: AnalysisSession) {
        connection.prepareStatement(
            """
            INSERT INTO analysis_session(
              id, origin_profiler, scope_kind, scope_description, model, provider, prompt_version,
              payload_policy_version, source_snapshot_ids, build_evidence_ids, status,
              created_at, parent_session_id, summary, error_message
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET model=excluded.model, provider=excluded.provider, status=excluded.status,
              summary=excluded.summary, error_message=excluded.error_message
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, session.id.value)
            statement.setString(2, session.originProfiler.name)
            statement.setString(3, session.scope.kind.name)
            statement.setString(4, session.scope.description)
            statement.setString(5, session.model)
            statement.setString(6, session.provider)
            statement.setString(7, session.promptVersion)
            statement.setString(8, session.payloadPolicyVersion)
            statement.setString(9, session.sourceSnapshotIds.toJsonArray())
            statement.setString(10, session.buildEvidenceBundleIds.toJsonArray())
            statement.setString(11, session.status.name)
            statement.setString(12, session.createdAt.toString())
            statement.setString(13, session.parentSessionId?.value)
            statement.setString(14, session.summary)
            statement.setString(15, session.errorMessage)
            statement.executeUpdate()
        }
    }

    override fun saveRequest(request: AnalysisRequest) {
        connection.autoCommit = false
        try {
            connection.prepareStatement("DELETE FROM analysis_evidence WHERE session_id = ?").use { statement ->
                statement.setString(1, request.sessionId.value)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO analysis_evidence(session_id, id, kind, summary, payload_hash) VALUES (?, ?, ?, ?, ?)",
            ).use { statement ->
                request.evidence.forEach { evidence ->
                    statement.setString(1, request.sessionId.value)
                    statement.setString(2, evidence.id)
                    statement.setString(3, evidence.kind)
                    statement.setString(4, evidence.summary)
                    statement.setString(5, evidence.structuredPayload.sha256())
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            connection.prepareStatement("DELETE FROM analysis_candidate WHERE session_id = ?").use { statement ->
                statement.setString(1, request.sessionId.value)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO analysis_candidate(
                  session_id, id, relative_path, start_line, end_line, resolution_confidence, content_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                request.sourceCandidates.forEach { candidate ->
                    statement.setString(1, request.sessionId.value)
                    statement.setString(2, candidate.id)
                    statement.setString(3, candidate.relativePath)
                    statement.setObject(4, candidate.startLine)
                    statement.setObject(5, candidate.endLine)
                    statement.setString(6, candidate.resolutionConfidence)
                    statement.setString(7, candidate.contentHash)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            connection.commit()
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    override fun saveResult(result: AnalysisResult) {
        connection.autoCommit = false
        try {
            connection.prepareStatement("DELETE FROM analysis_finding WHERE session_id = ?").use { statement ->
                statement.setString(1, result.sessionId.value)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO analysis_finding(
                  id, session_id, severity, title, explanation, recommendation,
                  analysis_confidence, evidence_ids, candidate_ids
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                result.findings.forEach { finding ->
                    statement.setString(1, finding.id.value)
                    statement.setString(2, result.sessionId.value)
                    statement.setString(3, finding.severity.name)
                    statement.setString(4, finding.title)
                    statement.setString(5, finding.explanation)
                    statement.setString(6, finding.recommendation)
                    statement.setFloat(7, finding.analysisConfidence)
                    statement.setString(8, finding.performanceEvidenceIds.toJsonArray())
                    statement.setString(9, finding.sourceCandidateIds.toJsonArray())
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            connection.prepareStatement(
                "UPDATE analysis_session SET model = ?, status = ?, summary = ? WHERE id = ?",
            ).use { statement ->
                statement.setString(1, result.model)
                statement.setString(2, AnalysisSessionStatus.SUCCEEDED.name)
                statement.setString(3, result.summary)
                statement.setString(4, result.sessionId.value)
                statement.executeUpdate()
            }
            connection.commit()
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    override fun session(id: AnalysisSessionId): AnalysisSession? =
        connection.prepareStatement("SELECT * FROM analysis_session WHERE id = ?").use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                AnalysisSession(
                    id = id,
                    originProfiler = ProfilerKind.valueOf(result.getString("origin_profiler")),
                    scope = AnalysisScope(
                        AnalysisScopeKind.valueOf(result.getString("scope_kind")),
                        result.getString("scope_description"),
                    ),
                    model = result.getString("model"),
                    promptVersion = result.getString("prompt_version"),
                    payloadPolicyVersion = result.getString("payload_policy_version"),
                    sourceSnapshotIds = result.getString("source_snapshot_ids").parseStringArray(),
                    buildEvidenceBundleIds = result.getString("build_evidence_ids").parseStringArray(),
                    status = AnalysisSessionStatus.valueOf(result.getString("status")),
                    createdAt = Instant.parse(result.getString("created_at")),
                    parentSessionId = result.getString("parent_session_id")?.let(::AnalysisSessionId),
                    summary = result.getString("summary"),
                    errorMessage = result.getString("error_message"),
                    provider = result.getString("provider"),
                )
            }
        }

    override fun findings(id: AnalysisSessionId): List<AnalysisFinding> =
        connection.prepareStatement("SELECT * FROM analysis_finding WHERE session_id = ? ORDER BY rowid").use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            AnalysisFinding(
                                id = AnalysisFindingId(result.getString("id")),
                                severity = AnalysisSeverity.valueOf(result.getString("severity")),
                                title = result.getString("title"),
                                explanation = result.getString("explanation"),
                                recommendation = result.getString("recommendation"),
                                analysisConfidence = result.getFloat("analysis_confidence"),
                                performanceEvidenceIds = result.getString("evidence_ids").parseStringArray(),
                                sourceCandidateIds = result.getString("candidate_ids").parseStringArray(),
                            ),
                        )
                    }
                }
            }
        }

    override fun evidence(id: AnalysisSessionId): List<AnalysisEvidenceSummary> =
        connection.prepareStatement(
            "SELECT id, kind, summary, payload_hash FROM analysis_evidence WHERE session_id = ? ORDER BY rowid",
        ).use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            AnalysisEvidenceSummary(
                                id = result.getString("id"),
                                kind = result.getString("kind"),
                                summary = result.getString("summary"),
                                payloadHash = result.getString("payload_hash"),
                            ),
                        )
                    }
                }
            }
        }

    override fun candidates(id: AnalysisSessionId): List<AnalysisCandidateSummary> =
        connection.prepareStatement(
            "SELECT * FROM analysis_candidate WHERE session_id = ? ORDER BY rowid",
        ).use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            AnalysisCandidateSummary(
                                id = result.getString("id"),
                                relativePath = result.getString("relative_path"),
                                startLine = (result.getObject("start_line") as? Number)?.toInt(),
                                endLine = (result.getObject("end_line") as? Number)?.toInt(),
                                resolutionConfidence = result.getString("resolution_confidence"),
                                contentHash = result.getString("content_hash"),
                            ),
                        )
                    }
                }
            }
        }

    override fun close() {
        connection.close()
    }

    private fun createSchema() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS analysis_session(
                  id TEXT PRIMARY KEY, origin_profiler TEXT NOT NULL, scope_kind TEXT NOT NULL,
                  scope_description TEXT NOT NULL, model TEXT, provider TEXT, prompt_version TEXT NOT NULL,
                  payload_policy_version TEXT NOT NULL, source_snapshot_ids TEXT NOT NULL,
                  build_evidence_ids TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL,
                  parent_session_id TEXT, summary TEXT, error_message TEXT
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS analysis_finding(
                  id TEXT NOT NULL, session_id TEXT NOT NULL REFERENCES analysis_session(id) ON DELETE CASCADE,
                  severity TEXT NOT NULL, title TEXT NOT NULL, explanation TEXT NOT NULL,
                  recommendation TEXT NOT NULL, analysis_confidence REAL NOT NULL,
                  evidence_ids TEXT NOT NULL, candidate_ids TEXT NOT NULL,
                  PRIMARY KEY(session_id, id)
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS analysis_evidence(
                  session_id TEXT NOT NULL REFERENCES analysis_session(id) ON DELETE CASCADE,
                  id TEXT NOT NULL, kind TEXT NOT NULL, summary TEXT NOT NULL,
                  payload_hash TEXT NOT NULL,
                  PRIMARY KEY(session_id, id)
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS analysis_candidate(
                  session_id TEXT NOT NULL REFERENCES analysis_session(id) ON DELETE CASCADE,
                  id TEXT NOT NULL, relative_path TEXT NOT NULL, start_line INTEGER, end_line INTEGER,
                  resolution_confidence TEXT NOT NULL, content_hash TEXT,
                  PRIMARY KEY(session_id, id)
                )
                """.trimIndent(),
            )
        }
    }

    private fun migrateLegacyFindingPrimaryKey() {
        val sessionIdIsPartOfPrimaryKey = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(analysis_finding)").use(ResultSet::hasSessionIdPrimaryKey)
        }
        if (sessionIdIsPartOfPrimaryKey) return
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                statement.executeUpdate("ALTER TABLE analysis_finding RENAME TO analysis_finding_legacy")
                statement.executeUpdate(
                    """
                    CREATE TABLE analysis_finding(
                      id TEXT NOT NULL, session_id TEXT NOT NULL REFERENCES analysis_session(id) ON DELETE CASCADE,
                      severity TEXT NOT NULL, title TEXT NOT NULL, explanation TEXT NOT NULL,
                      recommendation TEXT NOT NULL, analysis_confidence REAL NOT NULL,
                      evidence_ids TEXT NOT NULL, candidate_ids TEXT NOT NULL,
                      PRIMARY KEY(session_id, id)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate("INSERT INTO analysis_finding SELECT * FROM analysis_finding_legacy")
                statement.executeUpdate("DROP TABLE analysis_finding_legacy")
            }
            connection.commit()
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }
}

private fun ResultSet.hasSessionIdPrimaryKey(): Boolean {
    while (next()) {
        if (getString("name") == "session_id" && getInt("pk") > 0) return true
    }
    return false
}

private fun ResultSet.hasColumn(columnName: String): Boolean {
    while (next()) {
        if (getString("name") == columnName) return true
    }
    return false
}

private fun Connection.ensureColumn(table: String, column: String, definition: String) {
    val exists = createStatement().use { statement ->
        statement.executeQuery("PRAGMA table_info($table)").use { it.hasColumn(column) }
    }
    if (!exists) createStatement().use { it.executeUpdate("ALTER TABLE $table ADD COLUMN $column $definition") }
}

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

private fun List<String>.toJsonArray(): String = JsonArray(map(::JsonPrimitive)).toString()

private fun String.parseStringArray(): List<String> =
    kotlinx.serialization.json.Json.parseToJsonElement(this).jsonArray.map { it.jsonPrimitive.content }
