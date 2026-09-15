@file:Suppress("MagicNumber")

package com.androidperformancestudio.source

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant

/**
 * Produces the checked-in source-workspace SQLite fixture consumed by Electron's
 * cross-runtime compatibility test. The fixture must be written through the
 * Kotlin repository, rather than manually seeded from JavaScript.
 */
public object ElectronSourceWorkspaceFixtureWriter {
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 1) { "Expected exactly one fixture output path" }
        val databasePath = Path.of(args.single()).toAbsolutePath()
        databasePath.parent?.let(Files::createDirectories)
        Files.deleteIfExists(databasePath)
        Files.deleteIfExists(Path.of("$databasePath-wal"))
        Files.deleteIfExists(Path.of("$databasePath-shm"))

        SqliteSourceWorkspaceRepository(databasePath).use { repository ->
            val workspaceId = SourceWorkspaceId("kotlin-workspace")
            val snapshotId = SourceSnapshotId("kotlin-snapshot")
            val contentHash = "c".repeat(64)
            repository.saveWorkspace(
                SourceWorkspace(
                    id = workspaceId,
                    displayName = "Kotlin-written source workspace",
                    config = SourceProviderConfig.GitHub(
                        owner = "android",
                        repository = "performance-studio",
                        ref = "refs/tags/v1.0.0",
                        credentialKey = "kotlin-fixture-credential",
                    ),
                    activeSnapshotId = snapshotId,
                    phase = SourceWorkspacePhase.READY,
                    progress = 1f,
                    message = "Written by the Kotlin fixture generator",
                    allowAiSourceUpload = true,
                ),
            )
            repository.saveSnapshot(
                snapshot = SourceSnapshot(
                    id = snapshotId,
                    workspaceId = workspaceId,
                    immutableRevision = "a".repeat(40),
                    dirtyContentDigest = "dirty-content-digest",
                    manifestHash = "b".repeat(64),
                    createdAt = Instant.parse("2026-09-14T00:00:00Z"),
                    indexVersion = 7,
                    indexComplete = true,
                ),
                files = listOf(
                    SourceFile(
                        snapshotId = snapshotId,
                        relativePath = "src/main/kotlin/com/example/Renderer.kt",
                        language = SourceLanguage.KOTLIN,
                        contentHash = contentHash,
                        sizeBytes = 1234,
                    ),
                ),
                symbols = listOf(
                    SourceSymbol(
                        snapshotId = snapshotId,
                        relativePath = "src/main/kotlin/com/example/Renderer.kt",
                        kind = SourceSymbolKind.TYPE,
                        qualifiedName = "com.example.Renderer",
                        signature = null,
                        startLine = 3,
                        endLine = 42,
                    ),
                    SourceSymbol(
                        snapshotId = snapshotId,
                        relativePath = "src/main/kotlin/com/example/Renderer.kt",
                        kind = SourceSymbolKind.FUNCTION,
                        qualifiedName = "com.example.Renderer.render",
                        signature = "frame: Frame",
                        startLine = 17,
                        endLine = 22,
                    ),
                ),
            )
            repository.saveCandidates(
                listOf(
                    ResolutionCandidate(
                        id = ResolutionCandidateId("kotlin-candidate"),
                        evidenceId = PerformanceEvidenceId("kotlin-evidence"),
                        location = SourceLocation(
                            workspaceId = workspaceId,
                            snapshotId = snapshotId,
                            relativePath = "src/main/kotlin/com/example/Renderer.kt",
                            range = SourceRange(startLine = 17, startColumn = 4, endLine = 22, endColumn = 19),
                            contentHash = contentHash,
                        ),
                        confidence = ResolutionConfidence.EXACT,
                        reasons = listOf("Qualified type matched", "Build identity verified"),
                        indexVersion = 7,
                        indexComplete = true,
                    ),
                    ResolutionCandidate(
                        id = ResolutionCandidateId("kotlin-null-range-candidate"),
                        evidenceId = PerformanceEvidenceId("kotlin-null-range-evidence"),
                        location = SourceLocation(
                            workspaceId = workspaceId,
                            snapshotId = snapshotId,
                            relativePath = "src/main/kotlin/com/example/Renderer.kt",
                            range = null,
                            contentHash = contentHash,
                        ),
                        confidence = ResolutionConfidence.PROBABLE,
                        reasons = listOf("No source range was available"),
                        indexVersion = 7,
                        indexComplete = true,
                    ),
                ),
            )
        }

        // Keep the fixture self-contained in Git: the repository itself writes
        // in WAL mode, so checkpoint and remove the sidecars after it closes.
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                statement.execute("PRAGMA journal_mode=DELETE")
            }
        }
    }
}
