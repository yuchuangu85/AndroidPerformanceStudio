@file:Suppress("MaxLineLength")

package com.androidperformancestudio.source

import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlin.math.absoluteValue

class SourceWorkspaceIntegrationTest {
    @Test
    fun `local snapshot changes when dirty source content changes and ignores build output`() = withTempDirectory { root ->
        root.resolve("src/main/kotlin").createDirectories()
        val source = root.resolve("src/main/kotlin/Sample.kt")
        source.writeText("package sample\nclass Sample")
        root.resolve("build/generated").createDirectories()
        root.resolve("build/generated/Ignored.kt").writeText("class Ignored")
        val provider = LocalSourceProvider()

        val first = runBlocking { provider.resolveRevision(SourceProviderConfig.Local(root)) }
        val files = runBlocking { provider.listFiles(SourceProviderConfig.Local(root), first) }
        source.writeText("package sample\nclass Changed")
        val second = runBlocking { provider.resolveRevision(SourceProviderConfig.Local(root)) }

        assertNotEquals(first, second)
        assertEquals(listOf("src/main/kotlin/Sample.kt"), files.map { it.relativePath })
        assertFailsWith<IllegalArgumentException> {
            runBlocking { provider.readFile(SourceProviderConfig.Local(root), second, "../secret.kt") }
        }
    }

    @Test
    fun `service caches indexes persists and resolves exact Kotlin type and Android resource`() = withTempDirectory { root ->
        val sourceRoot = root.resolve("project")
        sourceRoot.resolve("src/main/kotlin/sample").createDirectories()
        sourceRoot.resolve("src/main/kotlin/sample/Widget.kt").writeText(
            """
            package sample
            class Widget {
                fun render(value: Int) = value
            }
            """.trimIndent(),
        )
        sourceRoot.resolve("src/main/res/layout").createDirectories()
        sourceRoot.resolve("src/main/res/layout/widget.xml").writeText(
            """<TextView android:id="@+id/title" />""",
        )
        val repository = InMemorySourceWorkspaceRepository()
        val service = DefaultSourceWorkspaceService(
            SourceProviderRegistry(listOf(LocalSourceProvider())),
            repository,
            ContentAddressedSourceCache(root.resolve("cache")),
        )

        val workspace = runBlocking { service.add("sample", SourceProviderConfig.Local(sourceRoot)) }
        service.setAiSourceUploadAllowed(workspace.id, true)
        val snapshotId = requireNotNull(workspace.activeSnapshotId)
        val candidates = runBlocking {
            IndexedSourceResolver(repository).resolve(
                setOf(snapshotId),
                listOf(
                    SourceResolutionEvidence.TypeName(PerformanceEvidenceId("type"), "sample.Widget"),
                    SourceResolutionEvidence.AndroidResource(PerformanceEvidenceId("resource"), "id", "title"),
                    SourceResolutionEvidence.SourceFileLine(
                        PerformanceEvidenceId("compose"),
                        "Widget.kt",
                        "sample".hashCode().absoluteValue,
                        2,
                    ),
                ),
            )
        }

        assertEquals(SourceWorkspacePhase.READY, workspace.phase)
        assertTrue(service.workspaces.value.single().allowAiSourceUpload)
        assertTrue(candidates.any { it.evidenceId.value == "type" && it.confidence == ResolutionConfidence.EXACT })
        assertTrue(candidates.any { it.evidenceId.value == "resource" && it.confidence == ResolutionConfidence.EXACT })
        assertEquals(2, candidates.single { it.evidenceId.value == "compose" }.location.range?.startLine)
        val content = runBlocking { service.read(candidates.first { it.evidenceId.value == "type" }.location) }
        assertTrue(content.text.contains("class Widget"))
    }

    @Test
    fun `content cache rejects corrupted objects`() = withTempDirectory { root ->
        val cache = ContentAddressedSourceCache(root)
        val hash = cache.put("trusted".encodeToByteArray())
        root.resolve(hash.take(2)).resolve(hash.drop(2)).writeText("tampered")

        assertFailsWith<IllegalStateException> { cache.read(hash) }
    }

    @Test
    fun `simpleperf managed symbols resolve exact while name-only native symbols degrade`() = runBlocking {
        val repository = InMemorySourceWorkspaceRepository()
        val workspaceId = SourceWorkspaceId("workspace")
        val snapshotId = SourceSnapshotId("snapshot")
        repository.saveWorkspace(
            SourceWorkspace(
                workspaceId,
                "sources",
                SourceProviderConfig.Aosp("platform/frameworks/base", "main"),
                snapshotId,
                SourceWorkspacePhase.READY,
                1f,
            ),
        )
        repository.saveSnapshot(
            SourceSnapshot(snapshotId, workspaceId, "a".repeat(40), null, "manifest", Instant.EPOCH, 1, true),
            listOf(
                SourceFile(snapshotId, "sample/Widget.kt", SourceLanguage.KOTLIN, "a".repeat(64), 10),
                SourceFile(snapshotId, "native/render.cpp", SourceLanguage.CPP, "b".repeat(64), 10),
            ),
            listOf(
                SourceSymbol(snapshotId, "sample/Widget.kt", SourceSymbolKind.METHOD, "sample.render", null, 4, 4),
                SourceSymbol(snapshotId, "native/render.cpp", SourceSymbolKind.NATIVE_SYMBOL, "Renderer::draw", null, 7, 7),
            ),
        )

        val candidates = IndexedSourceResolver(repository).resolve(
            setOf(snapshotId),
            listOf(
                SourceResolutionEvidence.ManagedSymbol(PerformanceEvidenceId("managed"), "sample.Widget", "render"),
                SourceResolutionEvidence.NativeSymbol(PerformanceEvidenceId("native"), "Renderer::draw", "libui.so"),
            ),
        )

        assertEquals(ResolutionConfidence.EXACT, candidates.single { it.evidenceId.value == "managed" }.confidence)
        assertEquals(ResolutionConfidence.PROBABLE, candidates.single { it.evidenceId.value == "native" }.confidence)
    }

    @Test
    fun `sqlite repository round trips provider metadata and cascades snapshots`() = withTempDirectory { root ->
        SqliteSourceWorkspaceRepository(root.resolve("source.db")).use { repository ->
            val workspace = SourceWorkspace(
                SourceWorkspaceId("workspace"),
                "private repo",
                SourceProviderConfig.GitHub("owner", "repo", "main", "credential-ref"),
                SourceSnapshotId("snapshot"),
                SourceWorkspacePhase.READY,
                1f,
            )
            repository.saveWorkspace(workspace)
            repository.saveSnapshot(
                SourceSnapshot(
                    SourceSnapshotId("snapshot"),
                    workspace.id,
                    "a".repeat(40),
                    null,
                    "manifest",
                    Instant.EPOCH,
                    1,
                    true,
                ),
                listOf(SourceFile(SourceSnapshotId("snapshot"), "A.kt", SourceLanguage.KOTLIN, "f".repeat(64), 1)),
                emptyList(),
            )
            val candidate = ResolutionCandidate(
                ResolutionCandidateId("candidate"),
                PerformanceEvidenceId("evidence"),
                SourceLocation(
                    workspace.id,
                    SourceSnapshotId("snapshot"),
                    "A.kt",
                    SourceRange(3, 1, 3, 5),
                    "f".repeat(64),
                ),
                ResolutionConfidence.EXACT,
                listOf("Qualified type matched"),
                1,
                true,
            )
            repository.saveCandidates(listOf(candidate))

            assertEquals(workspace, repository.workspace(workspace.id))
            assertEquals(candidate, repository.candidate(candidate.id))
            repository.deleteWorkspace(workspace.id)
            assertFalse(repository.files(SourceSnapshotId("snapshot")).isNotEmpty())
            assertEquals(null, repository.candidate(candidate.id))
        }
    }

    @Test
    fun `github provider resolves moving ref sends credential and reads source`() = runBlocking {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val transport = SourceHttpTransport { url, headers ->
            requests += url to headers
            when {
                "/commits/main" in url -> SourceHttpResponse(200, """{"sha":"${"a".repeat(40)}"}""".encodeToByteArray())
                "/git/trees/" in url -> SourceHttpResponse(
                    200,
                    """{"tree":[{"path":"src/A.kt","type":"blob","size":12,"sha":"blob"},{"path":"README.md","type":"blob","size":3,"sha":"docs"}]}""".encodeToByteArray(),
                )
                "/contents/src/A.kt" in url -> SourceHttpResponse(200, "class A".encodeToByteArray())
                else -> SourceHttpResponse(404, byteArrayOf())
            }
        }
        val provider = GitHubSourceProvider(transport, SourceCredentialProvider { "secret" })
        val config = SourceProviderConfig.GitHub("owner", "repo", "main", "github-key")

        val revision = provider.resolveRevision(config)
        val files = provider.listFiles(config, revision)
        val content = provider.readFile(config, revision, "src/A.kt")

        assertEquals("a".repeat(40), revision)
        assertEquals(listOf("src/A.kt"), files.map { it.relativePath })
        assertEquals("class A", content.decodeToString())
        assertTrue(requests.all { it.second["Authorization"] == "Bearer secret" })
        assertTrue(requests.none { (url, _) -> "secret" in url })
    }

    @Test
    fun `aosp provider resolves ref indexes Gitiles tree and decodes source`() = runBlocking {
        val revision = "b".repeat(40)
        val transport = SourceHttpTransport { url, _ ->
            when {
                "refs%2Fheads%2Fmain?format=JSON" in url ->
                    SourceHttpResponse(200, ")]}'\n{\"commit\":\"$revision\"}".encodeToByteArray())
                url.endsWith("/$revision?format=JSON") ->
                    SourceHttpResponse(200, ")]}'\n{\"entries\":[{\"name\":\"src\",\"type\":\"tree\"}]}".encodeToByteArray())
                url.endsWith("/$revision/src?format=JSON") ->
                    SourceHttpResponse(200, ")]}'\n{\"entries\":[{\"name\":\"A.kt\",\"type\":\"blob\",\"id\":\"blob\"}]}".encodeToByteArray())
                url.endsWith("/$revision/src/A.kt?format=TEXT") ->
                    SourceHttpResponse(200, Base64.getEncoder().encode("class A".encodeToByteArray()))
                else -> SourceHttpResponse(404, byteArrayOf())
            }
        }
        val provider = AospSourceProvider(transport)
        val config = SourceProviderConfig.Aosp("platform/frameworks/base", "refs/heads/main")

        assertEquals(revision, provider.resolveRevision(config))
        assertEquals(listOf("src/A.kt"), provider.listFiles(config, revision).map { it.relativePath })
        assertEquals("class A", provider.readFile(config, revision, "src/A.kt").decodeToString())
    }

    private fun withTempDirectory(block: (Path) -> Unit) {
        val directory = createTempDirectory("source-workspace-test")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
