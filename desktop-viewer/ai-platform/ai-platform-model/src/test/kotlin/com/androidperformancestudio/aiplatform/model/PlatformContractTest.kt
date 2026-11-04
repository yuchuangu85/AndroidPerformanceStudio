package com.androidperformancestudio.aiplatform.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformContractTest {
    @Test
    fun `manifest schema is strict versioned and round trips`() {
        val manifest = manifest()
        val encoded = PlatformJson.codec.encodeToString(manifest)
        assertEquals(manifest, PlatformJson.decodeManifest(encoded.encodeToByteArray()))
        assertFailsWith<IllegalArgumentException> {
            PlatformJson.decodeManifest(encoded.replace("\"schemaVersion\":1", "\"schemaVersion\":2").encodeToByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            PlatformJson.decodeManifest(encoded.replaceFirst("{", "{\"schemaVersion\":1,").encodeToByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            PlatformJson.decodeManifest(encoded.replaceFirst("{", "{\"\\u006eame\":\"duplicate\",").encodeToByteArray())
        }
        for (field in listOf("origin", "trust", "enabled", "signature")) {
            assertFailsWith<SerializationException> {
                PlatformJson.decodeManifest(encoded.replaceFirst("{", "{\"$field\":true,").encodeToByteArray())
            }
        }
    }

    @Test
    fun `bundle ids paths hashes and script declarations fail closed`() {
        for (id in listOf("../bad", "agent/bad", "agent\\bad", "A", "", "a..b")) {
            assertFailsWith<IllegalArgumentException> { manifest().copy(id = id) }
        }
        for (path in listOf(
            "../a.md",
            "/a.md",
            "scripts/../../a.md",
            "a\\b.md",
            "C:a.md",
            "a//b.md",
            "CON.md",
            "aux.txt",
            "scripts/COM1.py",
            "trailing.",
        )) {
            assertFailsWith<IllegalArgumentException> { manifest().copy(files = mapOf(path to "a".repeat(64))) }
        }
        assertFailsWith<IllegalArgumentException> { manifest().copy(files = mapOf("prompt.md" to "no hash")) }
        assertFailsWith<IllegalArgumentException> { manifest().copy(version = "../1") }
        assertFailsWith<IllegalArgumentException> { manifest().copy(scripts = setOf("scripts/run.py")) }
        assertFailsWith<IllegalArgumentException> { ResourceBudget(maxChildDepth = 100) }
        assertFailsWith<IllegalArgumentException> { ResourceBudget(timeoutMillis = Long.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { PlatformJson.decodeManifest(ByteArray(65_537)) }
    }

    @Test
    fun `unknown runtime and undeclared permission are rejected`() {
        val encoded = PlatformJson.codec.encodeToString(manifest())
        assertFailsWith<SerializationException> {
            PlatformJson.decodeManifest(encoded.replace("\"NONE\"", "\"SHELL\"").encodeToByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            manifest().copy(
                files = manifest().files + ("scripts/run.py" to "b".repeat(64)),
                scripts = setOf("scripts/run.py"),
                runtime = ScriptRuntime.PYTHON,
            )
        }
        for (path in listOf("undeclared.py", "scripts/hidden.js", "run.sh", "plugin.jar")) {
            assertFailsWith<IllegalArgumentException> {
                manifest().copy(files = manifest().files + (path to "b".repeat(64)))
            }
        }
        assertEquals(emptySet(), manifest().permissions)
    }

    @Test
    fun `conversation audit and approval schemas persist references not payloads`() {
        val conversation =
            ConversationRecord(
                id = "conversation-1",
                analysisSessionId = "analysis-1",
                contextHash = "a".repeat(64),
                turns = listOf(ConversationTurn("run-1", 1, RunStatus.RUNNING, listOf("evidence-1"), emptyList())),
            )
        val encoded = PlatformJson.codec.encodeToString(conversation)
        assertEquals(conversation, PlatformJson.codec.decodeFromString<ConversationRecord>(encoded))
        assertFailsWith<SerializationException> {
            PlatformJson.codec.decodeFromString<ConversationRecord>(encoded.replaceFirst("{", "{\"sourceSnippet\":\"secret\","))
        }
        assertFailsWith<IllegalArgumentException> { conversation.copy(schemaVersion = 2) }
        assertFailsWith<IllegalArgumentException> { conversation.copy(contextHash = "/local/source/path") }
        val audit = AuditRecord(action = AuditAction.INSTALLED, subjectId = "main-agent", timestampEpochMillis = 1)
        assertEquals(audit, PlatformJson.codec.decodeFromString<AuditRecord>(PlatformJson.codec.encodeToString(audit)))
        val approval =
            ApprovalGrant(
                ApprovalRequest("approval-1", "run-1", "analysis-1", 1, "a".repeat(64), setOf(Permission.PROVIDER_NETWORK), 100),
                "host-issued-opaque-grant",
            )
        assertEquals(approval, PlatformJson.codec.decodeFromString<ApprovalGrant>(PlatformJson.codec.encodeToString(approval)))
        assertTrue(RunStatus.INTERRUPTED.terminal)
        assertFalse(RunStatus.AWAITING_APPROVAL.terminal)
    }

    private fun manifest() =
        ExtensionManifest(
            kind = ExtensionKind.AGENT,
            id = "main-agent",
            version = "1.0.0",
            name = "Main Agent",
            description = "Evidence-bound analysis",
            entrypoint = "prompt.md",
            inputSchema = "analysis-request-v1",
            outputSchema = "analysis-result-v1",
            contextKinds = setOf("simpleperf"),
            evidenceKinds = setOf("cpu-hotspot"),
            files = mapOf("prompt.md" to "a".repeat(64)),
        )
}
