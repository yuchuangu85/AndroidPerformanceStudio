@file:Suppress("TooGenericExceptionCaught", "MaxLineLength", "MagicNumber")

package com.androidperformancestudio.ai

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

public interface AnalysisSessionRepository {
    public fun saveSession(session: AnalysisSession)

    public fun saveResult(result: AnalysisResult)

    public fun session(id: AnalysisSessionId): AnalysisSession?

    public fun findings(id: AnalysisSessionId): List<AnalysisFinding>
}

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
    }

    override fun saveSession(session: AnalysisSession) {
        connection.prepareStatement(
            """
            INSERT INTO analysis_session(
              id, origin_profiler, scope_kind, scope_description, model, prompt_version,
              payload_policy_version, source_snapshot_ids, build_evidence_ids, status,
              created_at, parent_session_id, summary, error_message
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET model=excluded.model, status=excluded.status,
              summary=excluded.summary, error_message=excluded.error_message
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, session.id.value)
            statement.setString(2, session.originProfiler.name)
            statement.setString(3, session.scope.kind.name)
            statement.setString(4, session.scope.description)
            statement.setString(5, session.model)
            statement.setString(6, session.promptVersion)
            statement.setString(7, session.payloadPolicyVersion)
            statement.setString(8, session.sourceSnapshotIds.toJsonArray())
            statement.setString(9, session.buildEvidenceBundleIds.toJsonArray())
            statement.setString(10, session.status.name)
            statement.setString(11, session.createdAt.toString())
            statement.setString(12, session.parentSessionId?.value)
            statement.setString(13, session.summary)
            statement.setString(14, session.errorMessage)
            statement.executeUpdate()
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

    override fun close() {
        connection.close()
    }

    private fun createSchema() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS analysis_session(
                  id TEXT PRIMARY KEY, origin_profiler TEXT NOT NULL, scope_kind TEXT NOT NULL,
                  scope_description TEXT NOT NULL, model TEXT, prompt_version TEXT NOT NULL,
                  payload_policy_version TEXT NOT NULL, source_snapshot_ids TEXT NOT NULL,
                  build_evidence_ids TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL,
                  parent_session_id TEXT, summary TEXT, error_message TEXT
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS analysis_finding(
                  id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES analysis_session(id) ON DELETE CASCADE,
                  severity TEXT NOT NULL, title TEXT NOT NULL, explanation TEXT NOT NULL,
                  recommendation TEXT NOT NULL, analysis_confidence REAL NOT NULL,
                  evidence_ids TEXT NOT NULL, candidate_ids TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }
    }
}

private fun List<String>.toJsonArray(): String = JsonArray(map(::JsonPrimitive)).toString()

private fun String.parseStringArray(): List<String> =
    kotlinx.serialization.json.Json.parseToJsonElement(this).jsonArray.map { it.jsonPrimitive.content }
