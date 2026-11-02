package com.androidperformancestudio.aiplatform.script

import com.androidperformancestudio.aiplatform.model.ArtifactFormat
import com.androidperformancestudio.aiplatform.model.Permission
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtifactPublisherTest {
    @Test
    fun `HTML escapes active text and contains restrictive CSP and provenance`() =
        withRoot { root ->
            val publisher = ArtifactPublisher(root)
            val artifact = publisher.publish(request("<script>steal()</script><img src='https://evil/'>"))
            val html = publisher.read(artifact.hash).decodeToString()
            assertFalse("<script>" in html)
            assertFalse("<img" in html)
            assertTrue("&lt;script&gt;steal()&lt;/script&gt;" in html)
            assertTrue("default-src 'none'" in html)
            assertTrue("Evidence: evidence-1" in html)
            assertTrue("Agent aps.report@1.0.0" in html)
            assertEquals(artifact, publisher.metadata(artifact.hash))
            assertEquals(artifact, publisher.publish(request("<script>steal()</script><img src='https://evil/'>")))
        }

    @Test
    fun `Markdown fences cannot be escaped by provider text`() {
        val text = "```\n<script>unsafe</script>\n[click](javascript:evil)\n`````"
        val output = renderArtifact(request(text).copy(format = ArtifactFormat.MARKDOWN))
        assertTrue("``````\n$text\n``````" in output)
        assertFalse(output.startsWith("<"))
    }

    @Test
    fun `unknown references and missing publication grant reject before writes`() =
        withRoot { root ->
            val publisher = ArtifactPublisher(root)
            assertCode("E_ARTIFACT_PERMISSION") { publisher.publish(request("text").copy(grantedPermissions = emptySet())) }
            assertCode("E_ARTIFACT_REFERENCE") { publisher.publish(request("text").copy(allowedEvidenceIds = emptySet())) }
            assertCode("E_ARTIFACT_REFERENCE") { publisher.publish(request("text").copy(allowedCandidateIds = emptySet())) }
            assertFalse(Files.exists(root))
        }

    @Test
    fun `oversized reports reject without publication`() =
        withRoot { root ->
            assertCode("E_ARTIFACT_LIMIT") { ArtifactPublisher(root, 10).publish(request("text")) }
            assertFalse(Files.exists(root))
        }

    @Test
    fun `content tampering is rejected every read and cannot replace immutable version`() =
        withRoot { root ->
            val publisher = ArtifactPublisher(root)
            val artifact = publisher.publish(request("original"))
            Files.writeString(root.resolve(artifact.hash).resolve("content"), "changed")
            assertCode("E_ARTIFACT_HASH") { publisher.read(artifact.hash) }
            assertCode("E_ARTIFACT_HASH") { publisher.publish(request("original")) }
        }

    @Test
    fun `invalid hash traversal and symlink content are rejected`() =
        withRoot { root ->
            val publisher = ArtifactPublisher(root)
            assertCode("E_ARTIFACT_HASH") { publisher.read("../escape") }
            val artifact = publisher.publish(request("original"))
            val content = root.resolve(artifact.hash).resolve("content")
            Files.delete(content)
            Files.createSymbolicLink(content, root.parent.resolve("outside"))
            assertCode("E_SYMLINK") { publisher.read(artifact.hash) }
        }

    private fun request(text: String) =
        ArtifactRequest(
            title = "Performance report",
            sections = listOf(ArtifactSection("Finding", text)),
            provenance =
                ArtifactProvenance(
                    "session-1",
                    1,
                    "aps.report",
                    "1.0.0",
                    setOf("evidence-1"),
                    setOf("candidate-1"),
                    "a".repeat(64),
                ),
            grantedPermissions = setOf(Permission.WRITE_ARTIFACT),
            allowedEvidenceIds = setOf("evidence-1"),
            allowedCandidateIds = setOf("candidate-1"),
        )

    private fun assertCode(
        code: String,
        block: () -> Unit,
    ) = assertEquals(code, assertFailsWith<ScriptException>(block = block).code)

    private fun withRoot(block: (java.nio.file.Path) -> Unit) {
        val temp = Files.createTempDirectory("aps-artifact-test-").toRealPath()
        try {
            block(temp.resolve("artifacts"))
        } finally {
            temp.toFile().deleteRecursively()
        }
    }
}
