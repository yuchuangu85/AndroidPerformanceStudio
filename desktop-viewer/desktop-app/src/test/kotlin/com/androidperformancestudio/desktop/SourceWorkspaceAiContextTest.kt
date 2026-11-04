package com.androidperformancestudio.desktop

import com.androidperformancestudio.ai.AnalysisScopeKind
import com.androidperformancestudio.ai.PerformanceEvidence
import com.androidperformancestudio.ai.ProfilerKind
import com.androidperformancestudio.aiplatform.app.createBusinessContext
import com.androidperformancestudio.source.SourceContentState
import com.androidperformancestudio.source.SourceFile
import com.androidperformancestudio.source.SourceLanguage
import com.androidperformancestudio.source.SourceLocation
import com.androidperformancestudio.source.SourceRange
import com.androidperformancestudio.source.SourceSnapshot
import com.androidperformancestudio.source.SourceSnapshotId
import com.androidperformancestudio.source.SourceWorkspaceId
import com.androidperformancestudio.source.VerifiedSourceContent
import com.androidperformancestudio.ui.uiAiEvidenceId
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceWorkspaceAiContextTest {
    private val workspaceId = SourceWorkspaceId("private-workspace")
    private val snapshotId = SourceSnapshotId("private-snapshot")
    private val location = SourceLocation(workspaceId, snapshotId, "private/src/Secret.kt", SourceRange(2, endLine = 3), "a".repeat(64))
    private val text = "// private source\nfun secret() = Unit\n// private end"
    private val content = VerifiedSourceContent(location, text)
    private val file = SourceFile(snapshotId, location.relativePath, SourceLanguage.KOTLIN, location.contentHash, text.toByteArray().size.toLong())
    private val snapshot = SourceSnapshot(snapshotId, workspaceId, "private-revision", "private-dirty", "private-manifest", Instant.EPOCH, 1, true)

    @Test
    fun `verified selection publishes only bounded source metadata`() {
        val context = requireNotNull(sourceWorkspaceAiContext(location, content, snapshot, file))
        val evidence = context.evidence.single()
        assertEquals("source-workspace", context.key)
        assertTrue(context.currentSelection)
        assertEquals("source-workspace-selection", evidence.kind)
        assertTrue(evidence.id.matches(Regex("source-workspace-selection:[a-f0-9]{64}")))
        assertEquals(
            "{\"evidenceType\":\"source-metadata\",\"performanceMeasurement\":false," +
                "\"snapshotId\":\"${uiAiEvidenceId("source-snapshot", snapshotId.value)}\"," +
                "\"contentHash\":\"${location.contentHash}\",\"language\":\"KOTLIN\",\"contentState\":\"CURRENT\"," +
                "\"sizeBytes\":${file.sizeBytes},\"lineCount\":3,\"startLine\":2,\"endLine\":3}",
            evidence.payloadJson,
        )
        assertTrue(evidence.summary.contains("not performance measurement or current working-tree contents"))
    }

    @Test
    fun `missing selection or any verified input is unavailable`() {
        assertNull(sourceWorkspaceAiContext(null, content, snapshot, file))
        assertNull(sourceWorkspaceAiContext(location, null, snapshot, file))
        assertNull(sourceWorkspaceAiContext(location, content, null, file))
        assertNull(sourceWorkspaceAiContext(location, content, snapshot, null))
    }

    @Test
    fun `late content from another selection cannot be published`() {
        val otherLocations = listOf(
            location.copy(workspaceId = SourceWorkspaceId("other-workspace")),
            location.copy(snapshotId = SourceSnapshotId("other-snapshot")),
            location.copy(relativePath = "other.kt"),
            location.copy(contentHash = "b".repeat(64)),
            location.copy(range = null),
        )
        otherLocations.forEach { other ->
            assertNull(sourceWorkspaceAiContext(location, content.copy(location = other), snapshot, file))
        }
    }

    @Test
    fun `snapshot ownership and indexed file identity must match the selection`() {
        assertNull(sourceWorkspaceAiContext(location, content, snapshot.copy(workspaceId = SourceWorkspaceId("other")), file))
        assertNull(sourceWorkspaceAiContext(location, content, snapshot.copy(id = SourceSnapshotId("other")), file))
        listOf(
            file.copy(snapshotId = SourceSnapshotId("other")),
            file.copy(relativePath = "other.kt"),
            file.copy(contentHash = "b".repeat(64)),
            file.copy(sizeBytes = -1),
        ).forEach { other -> assertNull(sourceWorkspaceAiContext(location, content, snapshot, other)) }
    }

    @Test
    fun `unverified line ranges fail closed`() {
        listOf(SourceRange(0), SourceRange(3, endLine = 2), SourceRange(2, endLine = 4)).forEach { range ->
            val selected = location.copy(range = range)
            assertNull(sourceWorkspaceAiContext(selected, content.copy(location = selected), snapshot, file))
        }
    }

    @Test
    fun `whole file selection leaves line range absent and counts mixed line endings`() {
        val selected = location.copy(range = null)
        val loaded = content.copy(location = selected, text = "first\r\nsecond\rthird\n")
        val payload = requireNotNull(sourceWorkspaceAiContext(selected, loaded, snapshot, file)).evidence.single().payloadJson
        assertTrue(payload.contains("\"lineCount\":4"))
        assertTrue(payload.contains("\"startLine\":null,\"endLine\":null"))
    }

    @Test
    fun `stale working tree still identifies verified immutable snapshot content`() {
        val payload = requireNotNull(sourceWorkspaceAiContext(location, content.copy(state = SourceContentState.STALE), snapshot, file))
            .evidence.single().payloadJson
        assertTrue(payload.contains("\"contentState\":\"STALE\""))
        assertTrue(payload.contains("\"performanceMeasurement\":false"))
        assertTrue(payload.contains("\"contentHash\":\"${location.contentHash}\""))
    }

    @Test
    fun `identity is deterministic and changes with snapshot file range or metadata`() {
        val original = requireNotNull(sourceWorkspaceAiContext(location, content, snapshot, file))
        assertEquals(original, sourceWorkspaceAiContext(location.copy(), content.copy(), snapshot.copy(), file.copy()))
        val selections = listOf(
            location.copy(snapshotId = SourceSnapshotId("new-snapshot")),
            location.copy(relativePath = "renamed.kt"),
            location.copy(range = SourceRange(1)),
            location.copy(contentHash = "b".repeat(64)),
        )
        selections.forEach { selected ->
            val updated = requireNotNull(sourceWorkspaceAiContext(
                selected,
                content.copy(location = selected),
                snapshot.copy(id = selected.snapshotId),
                file.copy(snapshotId = selected.snapshotId, relativePath = selected.relativePath, contentHash = selected.contentHash),
            ))
            assertNotEquals(original.evidence.single().id, updated.evidence.single().id)
        }
        val resized = requireNotNull(sourceWorkspaceAiContext(location, content, snapshot, file.copy(sizeBytes = file.sizeBytes + 1)))
        assertNotEquals(original.evidence.single().id, resized.evidence.single().id)
    }

    @Test
    fun `even oversized sensitive identities and source text never enter published context`() {
        val root = "/Users/private/credential-project/"
        val selected = location.copy(
            workspaceId = SourceWorkspaceId(root),
            snapshotId = SourceSnapshotId(root.repeat(200)),
            relativePath = root.repeat(200) + "Secret.kt",
            range = null,
        )
        val context = requireNotNull(sourceWorkspaceAiContext(
            selected,
            content.copy(location = selected, text = "credential=private-token".repeat(10_000)),
            snapshot.copy(id = selected.snapshotId, workspaceId = selected.workspaceId),
            file.copy(snapshotId = selected.snapshotId, relativePath = selected.relativePath),
        ))
        val published = context.toString()
        listOf(root, "credential", "private-token", "Secret.kt", "private-revision", "private-manifest", "sourceSnippet").forEach {
            assertFalse(published.contains(it), it)
        }
        assertTrue(context.evidence.single().payloadJson.length < 1024)
    }

    @Test
    fun `invalid content identity is unavailable rather than exposed as arbitrary metadata`() {
        listOf("", "short-hash", "/Users/private/source.kt", "a".repeat(65)).forEach { hash ->
            val selected = location.copy(contentHash = hash)
            assertNull(sourceWorkspaceAiContext(selected, content.copy(location = selected), snapshot, file.copy(contentHash = hash)))
        }
    }

    @Test
    fun `source metadata maps through the existing request contract without snippets`() {
        val context = requireNotNull(sourceWorkspaceAiContext(location, content, snapshot, file))
        val evidence = context.evidence.map { PerformanceEvidence(it.id, it.kind, it.summary, it.payloadJson) }
        val business = createBusinessContext(context.key, context.title, evidence, context.currentSelection)
        assertEquals(ProfilerKind.SOURCE_WORKSPACE, business.request.originProfiler)
        assertEquals(AnalysisScopeKind.CURRENT_SELECTION, business.request.scope.kind)
        assertEquals(evidence, business.request.evidence)
        assertTrue(business.request.sourceCandidates.isEmpty())
        assertNull(business.request.userQuestion)
    }

    @Test
    fun `page publishes verified selection and cancels obsolete reads`() {
        val page = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/SourceWorkspacesPage.kt"))
        assertTrue(page.contains("PublishAiContext(\"source-workspace\", aiContext)"))
        assertTrue(page.contains("sourceContent?.takeIf { it.location == selectedLocation }"))
        assertTrue(page.contains("LaunchedEffect(selectedLocation, sourceReadRevision)"))
        assertTrue(page.contains("if (selectedLocation == location && sourceReadRevision == revision) sourceContent = it"))
        assertTrue(page.contains("if (it is CancellationException) throw it"))
        assertEquals(1, Regex("runtime\\.service\\.read\\(").findAll(page).count())
    }
}
