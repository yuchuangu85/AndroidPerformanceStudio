@file:Suppress("TooManyFunctions", "TooGenericExceptionCaught", "MaxLineLength", "MagicNumber", "LongMethod")

package com.androidperformancestudio.source

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

public class SqliteSourceWorkspaceRepository(
    databasePath: Path,
) : SourceWorkspaceRepository,
    AutoCloseable {
    private val connection: Connection

    init {
        databasePath.parent?.let(Files::createDirectories)
        connection = DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA journal_mode = WAL")
        }
        createSchema()
    }

    override fun saveWorkspace(workspace: SourceWorkspace) {
        connection.prepareStatement(
            """
            INSERT INTO source_workspace(
              id, display_name, provider_kind, local_root, github_owner, github_repository,
              provider_ref, credential_key, aosp_project, active_snapshot_id, phase, progress, message,
              allow_ai_source_upload
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
              display_name=excluded.display_name, provider_kind=excluded.provider_kind,
              local_root=excluded.local_root, github_owner=excluded.github_owner,
              github_repository=excluded.github_repository, provider_ref=excluded.provider_ref,
              credential_key=excluded.credential_key, aosp_project=excluded.aosp_project,
              active_snapshot_id=excluded.active_snapshot_id, phase=excluded.phase,
              progress=excluded.progress, message=excluded.message,
              allow_ai_source_upload=excluded.allow_ai_source_upload
            """.trimIndent(),
        ).use { statement ->
            val config = workspace.config
            statement.setString(1, workspace.id.value)
            statement.setString(2, workspace.displayName)
            statement.setString(3, config.kind.name)
            statement.setString(4, (config as? SourceProviderConfig.Local)?.root?.toString())
            statement.setString(5, (config as? SourceProviderConfig.GitHub)?.owner)
            statement.setString(6, (config as? SourceProviderConfig.GitHub)?.repository)
            statement.setString(7, when (config) {
                is SourceProviderConfig.Local -> null
                is SourceProviderConfig.GitHub -> config.ref
                is SourceProviderConfig.Aosp -> config.ref
            })
            statement.setString(8, (config as? SourceProviderConfig.GitHub)?.credentialKey)
            statement.setString(9, (config as? SourceProviderConfig.Aosp)?.project)
            statement.setString(10, workspace.activeSnapshotId?.value)
            statement.setString(11, workspace.phase.name)
            statement.setFloat(12, workspace.progress)
            statement.setString(13, workspace.message)
            statement.setBoolean(14, workspace.allowAiSourceUpload)
            statement.executeUpdate()
        }
    }

    override fun workspace(id: SourceWorkspaceId): SourceWorkspace? =
        connection.prepareStatement("SELECT * FROM source_workspace WHERE id = ?").use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { result -> if (result.next()) result.toWorkspace() else null }
        }

    override fun workspaces(): List<SourceWorkspace> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM source_workspace ORDER BY display_name").use { result ->
                buildList { while (result.next()) add(result.toWorkspace()) }
            }
        }

    override fun deleteWorkspace(id: SourceWorkspaceId) {
        connection.prepareStatement("DELETE FROM source_workspace WHERE id = ?").use { statement ->
            statement.setString(1, id.value)
            statement.executeUpdate()
        }
    }

    override fun saveSnapshot(
        snapshot: SourceSnapshot,
        files: List<SourceFile>,
        symbols: List<SourceSymbol>,
    ) {
        connection.autoCommit = false
        try {
            connection.prepareStatement(
                """
                INSERT OR REPLACE INTO source_snapshot(
                  id, workspace_id, immutable_revision, dirty_digest, manifest_hash,
                  created_at, index_version, index_complete
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, snapshot.id.value)
                statement.setString(2, snapshot.workspaceId.value)
                statement.setString(3, snapshot.immutableRevision)
                statement.setString(4, snapshot.dirtyContentDigest)
                statement.setString(5, snapshot.manifestHash)
                statement.setString(6, snapshot.createdAt.toString())
                statement.setLong(7, snapshot.indexVersion)
                statement.setBoolean(8, snapshot.indexComplete)
                statement.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM source_file WHERE snapshot_id = ?").use { statement ->
                statement.setString(1, snapshot.id.value)
                statement.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM source_symbol WHERE snapshot_id = ?").use { statement ->
                statement.setString(1, snapshot.id.value)
                statement.executeUpdate()
            }
            insertFiles(files)
            insertSymbols(symbols)
            connection.commit()
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    override fun snapshot(snapshotId: SourceSnapshotId): SourceSnapshot? =
        connection.prepareStatement("SELECT * FROM source_snapshot WHERE id = ?").use { statement ->
            statement.setString(1, snapshotId.value)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                SourceSnapshot(
                    id = snapshotId,
                    workspaceId = SourceWorkspaceId(result.getString("workspace_id")),
                    immutableRevision = result.getString("immutable_revision"),
                    dirtyContentDigest = result.getString("dirty_digest"),
                    manifestHash = result.getString("manifest_hash"),
                    createdAt = Instant.parse(result.getString("created_at")),
                    indexVersion = result.getLong("index_version"),
                    indexComplete = result.getBoolean("index_complete"),
                )
            }
        }

    override fun files(snapshotId: SourceSnapshotId): List<SourceFile> =
        connection.prepareStatement("SELECT * FROM source_file WHERE snapshot_id = ? ORDER BY relative_path").use { statement ->
            statement.setString(1, snapshotId.value)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            SourceFile(
                                snapshotId = snapshotId,
                                relativePath = result.getString("relative_path"),
                                language = SourceLanguage.valueOf(result.getString("language")),
                                contentHash = result.getString("content_hash"),
                                sizeBytes = result.getLong("size_bytes"),
                            ),
                        )
                    }
                }
            }
        }

    override fun symbols(snapshotId: SourceSnapshotId): List<SourceSymbol> =
        connection.prepareStatement("SELECT * FROM source_symbol WHERE snapshot_id = ? ORDER BY relative_path, start_line").use { statement ->
            statement.setString(1, snapshotId.value)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            SourceSymbol(
                                snapshotId = snapshotId,
                                relativePath = result.getString("relative_path"),
                                kind = SourceSymbolKind.valueOf(result.getString("kind")),
                                qualifiedName = result.getString("qualified_name"),
                                signature = result.getString("signature"),
                                startLine = result.getInt("start_line"),
                                endLine = result.getInt("end_line"),
                            ),
                        )
                    }
                }
            }
        }

    override fun close() {
        connection.close()
    }

    override fun saveCandidates(candidates: List<ResolutionCandidate>) {
        connection.prepareStatement(
            """
            INSERT OR REPLACE INTO resolution_candidate(
              id, evidence_id, workspace_id, snapshot_id, relative_path, start_line, start_column,
              end_line, end_column, content_hash, confidence, reasons, index_version, index_complete
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            candidates.forEach { candidate ->
                val range = candidate.location.range
                statement.setString(1, candidate.id.value)
                statement.setString(2, candidate.evidenceId.value)
                statement.setString(3, candidate.location.workspaceId.value)
                statement.setString(4, candidate.location.snapshotId.value)
                statement.setString(5, candidate.location.relativePath)
                statement.setObject(6, range?.startLine)
                statement.setObject(7, range?.startColumn)
                statement.setObject(8, range?.endLine)
                statement.setObject(9, range?.endColumn)
                statement.setString(10, candidate.location.contentHash)
                statement.setString(11, candidate.confidence.name)
                statement.setString(12, JsonArray(candidate.reasons.map(::JsonPrimitive)).toString())
                statement.setLong(13, candidate.indexVersion)
                statement.setBoolean(14, candidate.indexComplete)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    override fun candidate(id: ResolutionCandidateId): ResolutionCandidate? =
        connection.prepareStatement("SELECT * FROM resolution_candidate WHERE id = ?").use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                val startLine = result.getInt("start_line").takeUnless { result.wasNull() }
                ResolutionCandidate(
                    id = id,
                    evidenceId = PerformanceEvidenceId(result.getString("evidence_id")),
                    location = SourceLocation(
                        workspaceId = SourceWorkspaceId(result.getString("workspace_id")),
                        snapshotId = SourceSnapshotId(result.getString("snapshot_id")),
                        relativePath = result.getString("relative_path"),
                        range = startLine?.let {
                            SourceRange(
                                startLine = it,
                                startColumn = result.getInt("start_column"),
                                endLine = result.getInt("end_line"),
                                endColumn = result.getInt("end_column"),
                            )
                        },
                        contentHash = result.getString("content_hash"),
                    ),
                    confidence = ResolutionConfidence.valueOf(result.getString("confidence")),
                    reasons = Json.parseToJsonElement(result.getString("reasons")).jsonArray.map { it.jsonPrimitive.content },
                    indexVersion = result.getLong("index_version"),
                    indexComplete = result.getBoolean("index_complete"),
                )
            }
        }

    private fun createSchema() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS source_workspace(
                  id TEXT PRIMARY KEY, display_name TEXT NOT NULL, provider_kind TEXT NOT NULL,
                  local_root TEXT, github_owner TEXT, github_repository TEXT, provider_ref TEXT,
                  credential_key TEXT, aosp_project TEXT, active_snapshot_id TEXT,
                  phase TEXT NOT NULL, progress REAL NOT NULL, message TEXT,
                  allow_ai_source_upload INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS source_snapshot(
                  id TEXT PRIMARY KEY, workspace_id TEXT NOT NULL REFERENCES source_workspace(id) ON DELETE CASCADE,
                  immutable_revision TEXT NOT NULL, dirty_digest TEXT, manifest_hash TEXT NOT NULL,
                  created_at TEXT NOT NULL, index_version INTEGER NOT NULL, index_complete INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            runCatching {
                statement.executeUpdate(
                    "ALTER TABLE source_workspace ADD COLUMN allow_ai_source_upload INTEGER NOT NULL DEFAULT 0",
                )
            }
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS source_file(
                  snapshot_id TEXT NOT NULL REFERENCES source_snapshot(id) ON DELETE CASCADE,
                  relative_path TEXT NOT NULL, language TEXT NOT NULL, content_hash TEXT NOT NULL,
                  size_bytes INTEGER NOT NULL, PRIMARY KEY(snapshot_id, relative_path)
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS source_symbol(
                  snapshot_id TEXT NOT NULL REFERENCES source_snapshot(id) ON DELETE CASCADE,
                  relative_path TEXT NOT NULL, kind TEXT NOT NULL, qualified_name TEXT NOT NULL,
                  signature TEXT, start_line INTEGER NOT NULL, end_line INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS source_symbol_name ON source_symbol(snapshot_id, qualified_name)")
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS resolution_candidate(
                  id TEXT PRIMARY KEY, evidence_id TEXT NOT NULL, workspace_id TEXT NOT NULL,
                  snapshot_id TEXT NOT NULL REFERENCES source_snapshot(id) ON DELETE CASCADE,
                  relative_path TEXT NOT NULL, start_line INTEGER, start_column INTEGER,
                  end_line INTEGER, end_column INTEGER, content_hash TEXT NOT NULL,
                  confidence TEXT NOT NULL, reasons TEXT NOT NULL, index_version INTEGER NOT NULL,
                  index_complete INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    private fun insertFiles(files: List<SourceFile>) {
        connection.prepareStatement(
            "INSERT INTO source_file(snapshot_id, relative_path, language, content_hash, size_bytes) VALUES (?, ?, ?, ?, ?)",
        ).use { statement ->
            files.forEach { file ->
                statement.setString(1, file.snapshotId.value)
                statement.setString(2, file.relativePath)
                statement.setString(3, file.language.name)
                statement.setString(4, file.contentHash)
                statement.setLong(5, file.sizeBytes)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertSymbols(symbols: List<SourceSymbol>) {
        connection.prepareStatement(
            """
            INSERT INTO source_symbol(snapshot_id, relative_path, kind, qualified_name, signature, start_line, end_line)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            symbols.forEach { symbol ->
                statement.setString(1, symbol.snapshotId.value)
                statement.setString(2, symbol.relativePath)
                statement.setString(3, symbol.kind.name)
                statement.setString(4, symbol.qualifiedName)
                statement.setString(5, symbol.signature)
                statement.setInt(6, symbol.startLine)
                statement.setInt(7, symbol.endLine)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }
}

private fun java.sql.ResultSet.toWorkspace(): SourceWorkspace {
    val kind = SourceProviderKind.valueOf(getString("provider_kind"))
    val config = when (kind) {
        SourceProviderKind.LOCAL -> SourceProviderConfig.Local(Path.of(getString("local_root")))
        SourceProviderKind.GITHUB ->
            SourceProviderConfig.GitHub(
                owner = getString("github_owner"),
                repository = getString("github_repository"),
                ref = getString("provider_ref"),
                credentialKey = getString("credential_key"),
            )
        SourceProviderKind.AOSP -> SourceProviderConfig.Aosp(getString("aosp_project"), getString("provider_ref"))
    }
    return SourceWorkspace(
        id = SourceWorkspaceId(getString("id")),
        displayName = getString("display_name"),
        config = config,
        activeSnapshotId = getString("active_snapshot_id")?.let(::SourceSnapshotId),
        phase = SourceWorkspacePhase.valueOf(getString("phase")),
        progress = getFloat("progress"),
        message = getString("message"),
        allowAiSourceUpload = getBoolean("allow_ai_source_upload"),
    )
}
