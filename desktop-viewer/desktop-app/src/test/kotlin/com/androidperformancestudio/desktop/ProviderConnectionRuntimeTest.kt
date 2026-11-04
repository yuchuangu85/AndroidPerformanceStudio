package com.androidperformancestudio.desktop

import com.androidperformancestudio.ai.AiHttpMethod
import com.androidperformancestudio.ai.AiHttpRequest
import com.androidperformancestudio.ai.AiHttpResponse
import com.androidperformancestudio.ai.AiHttpTransport
import com.androidperformancestudio.ai.AiModelCatalogException
import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisScope
import com.androidperformancestudio.ai.AnalysisScopeKind
import com.androidperformancestudio.ai.AnalysisSessionId
import com.androidperformancestudio.ai.CredentialStore
import com.androidperformancestudio.ai.ImportedCredentialKind
import com.androidperformancestudio.ai.LocalAiConfigSource
import com.androidperformancestudio.ai.ProfilerKind
import com.androidperformancestudio.ai.SqliteAnalysisSessionRepository
import com.androidperformancestudio.aiplatform.app.AiPlatformSettings
import com.androidperformancestudio.aiplatform.app.AiPlatformSettingsStore
import com.androidperformancestudio.source.ContentAddressedSourceCache
import com.androidperformancestudio.source.DefaultSourceWorkspaceService
import com.androidperformancestudio.source.IndexedSourceResolver
import com.androidperformancestudio.source.LocalSourceProvider
import com.androidperformancestudio.source.SourceProviderRegistry
import com.androidperformancestudio.source.SqliteSourceWorkspaceRepository
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.AbstractPreferences
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProviderConnectionRuntimeTest {
    @Test
    fun `provider identifiers preserve the OpenAI custom migration alias`() {
        assertEquals(AiProviderConnection.CODEX, AiProviderConnection.fromProviderId("codex"))
        assertEquals(AiProviderConnection.CLAUDE, AiProviderConnection.fromProviderId("claude"))
        assertEquals(AiProviderConnection.CUSTOM, AiProviderConnection.fromProviderId("openai"))
        assertEquals(LocalAiConfigSource.CODEX, AiProviderConnection.CODEX.localSource)
        assertEquals(LocalAiConfigSource.CLAUDE, AiProviderConnection.CLAUDE.localSource)
        assertNull(AiProviderConnection.CUSTOM.localSource)
    }

    @Test
    fun `saved connections and model catalogs stay isolated after reopening`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            AiProviderConnection.entries.forEach { connection ->
                fixture.save(connection, "${connection.name}-key", ImportedCredentialKind.BEARER_TOKEN)
            }
            fixture.reopen()

            AiProviderConnection.entries.forEach { connection ->
                val runtime = fixture.runtime
                assertEquals(connectionModel(connection), runtime.providerModel(connection))
                assertEquals(connectionEndpoint(connection), runtime.providerEndpoint(connection))
                assertEquals(ImportedCredentialKind.BEARER_TOKEN, runtime.providerCredentialKind(connection))
                assertTrue(runtime.providerHasCredential(connection))
                assertEquals(
                    listOf("gpt-4.1"),
                    runtime.providerModels(connection, "", connectionEndpoint(connection), kind = ImportedCredentialKind.BEARER_TOKEN),
                )
                val request = fixture.requests.last()
                assertEquals(connectionCatalogEndpoint(connection), request.url)
                assertEquals("Bearer ${connection.name}-key", request.headers["Authorization"])
            }
            assertEquals("CODEX-key", fixture.credentials.values["openai:api-key"])
            assertEquals("CUSTOM-key", fixture.credentials.values["custom:api-key"])
            assertEquals("CLAUDE-key", fixture.credentials.values["claude:auth-token"])
            assertEquals(connectionModel(AiProviderConnection.CODEX), fixture.runtime.aiModel())
            assertEquals(connectionEndpoint(AiProviderConnection.CODEX), fixture.runtime.aiEndpoint())
            assertEquals(connectionModel(AiProviderConnection.CLAUDE), fixture.runtime.claudeModel())
            assertEquals(
                mapOf("ANTHROPIC_AUTH_TOKEN" to "CLAUDE-key", "ANTHROPIC_BASE_URL" to connectionEndpoint(AiProviderConnection.CLAUDE)),
                fixture.runtime.claudeEnvironment(),
            )
        }
    }

    @Test
    fun `legacy custom reads migrate into an independent saved connection`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val runtime = fixture.runtime
            runtime.saveAiConfiguration("legacy-model", "https://legacy.example/v1/responses", "https://legacy.example/catalog/models")
            runtime.saveCredential("openai:api-key", "legacy-key")
            val before = fixture.preferences.snapshot()
            assertEquals("legacy-model", runtime.providerModel(AiProviderConnection.CUSTOM))
            assertEquals(runtime.aiEndpoint(), runtime.providerEndpoint(AiProviderConnection.CUSTOM))
            assertEquals(runtime.aiModelsEndpoint(), runtime.providerModelsEndpoint(AiProviderConnection.CUSTOM))
            assertTrue(runtime.providerHasCredential(AiProviderConnection.CUSTOM))
            runtime.providerGateway(AiProviderConnection.CUSTOM, "legacy-model").analyze(chatRequest())
            assertEquals("https://legacy.example/v1/responses", fixture.requests.single().url)
            assertEquals("Bearer legacy-key", fixture.requests.single().headers["Authorization"])
            assertEquals(before, fixture.preferences.snapshot(), "Reading the migration alias does not save it")
            assertNull(fixture.credentials.values["custom:api-key"])

            runtime.saveProviderConnection(
                AiProviderConnection.CUSTOM, "custom-model", runtime.aiEndpoint(), runtime.aiModelsEndpoint(),
                ImportedCredentialKind.API_KEY, replacementCredential = null,
            )
            assertEquals("legacy-key", fixture.credentials.values["custom:api-key"])
            assertEquals("legacy-model", runtime.aiModel(), "Saving Custom must leave Codex unchanged")
            fixture.save(AiProviderConnection.CODEX, "new-codex-key")
            fixture.reopen()

            assertEquals("custom-model", fixture.runtime.providerModel(AiProviderConnection.CUSTOM))
            assertEquals("https://legacy.example/v1/responses", fixture.runtime.providerEndpoint(AiProviderConnection.CUSTOM))
            assertEquals("https://legacy.example/catalog/models", fixture.runtime.providerModelsEndpoint(AiProviderConnection.CUSTOM))
            fixture.runtime.providerGateway(AiProviderConnection.CUSTOM, "custom-model").analyze(chatRequest())
            assertEquals("Bearer legacy-key", fixture.requests.last().headers["Authorization"])
            assertEquals("https://legacy.example/v1/responses", fixture.requests.last().url)
            assertEquals("new-codex-key", fixture.credentials.values["openai:api-key"])
        }
    }

    @Test
    fun `an edited migration endpoint cannot borrow the legacy credential`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.runtime.saveAiConfiguration("legacy-model", "https://legacy.example/v1/responses")
            fixture.runtime.saveCredential("openai:api-key", "legacy-key")
            val preferencesBefore = fixture.preferences.snapshot()
            val readsBefore = fixture.credentials.readKeys.size
            val writesBefore = fixture.credentials.writeKeys.size
            val endpoint = "https://edited.example/v1/responses"
            assertFalse(fixture.runtime.providerHasCredential(AiProviderConnection.CUSTOM, endpoint))
            val failure = assertThrows<AiModelCredentialException> {
                fixture.runtime.providerModels(AiProviderConnection.CUSTOM, "", endpoint)
            }
            assertTrue(failure.endpointMismatch)
            assertThrows<AiModelCredentialException> {
                fixture.runtime.saveProviderConnection(
                    AiProviderConnection.CUSTOM, "edited-model", endpoint, "", ImportedCredentialKind.API_KEY, null,
                )
            }
            assertEquals(readsBefore, fixture.credentials.readKeys.size)
            assertEquals(writesBefore, fixture.credentials.writeKeys.size)
            assertEquals(preferencesBefore, fixture.preferences.snapshot())
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun `saved credentials cannot be reused for edited endpoints or authentication kinds`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            AiProviderConnection.entries.forEach { fixture.save(it, "${it.name}-key", ImportedCredentialKind.BEARER_TOKEN) }
            val before = fixture.preferences.snapshot()
            val readsBefore = fixture.credentials.readKeys.size
            val writesBefore = fixture.credentials.writeKeys.size
            AiProviderConnection.entries.forEach { connection ->
                val endpoint = connectionEndpoint(connection)
                val edited = "https://edited.example/v1/responses"
                assertFalse(fixture.runtime.providerHasCredential(connection, edited, ImportedCredentialKind.BEARER_TOKEN))
                assertFalse(fixture.runtime.providerHasCredential(connection, endpoint, ImportedCredentialKind.API_KEY))
                assertThrows<AiModelCredentialException> {
                    fixture.runtime.providerModels(connection, "", edited, kind = ImportedCredentialKind.BEARER_TOKEN)
                }
                assertThrows<AiModelCredentialException> {
                    fixture.runtime.providerModels(connection, "", endpoint, kind = ImportedCredentialKind.API_KEY)
                }
                assertThrows<AiModelCredentialException> {
                    fixture.runtime.saveProviderConnection(connection, "edited-model", edited, "", ImportedCredentialKind.BEARER_TOKEN, null)
                }
            }
            assertEquals(readsBefore, fixture.credentials.readKeys.size)
            assertEquals(writesBefore, fixture.credentials.writeKeys.size)
            assertEquals(before, fixture.preferences.snapshot())
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun `explicit draft credentials list edited endpoints without saving or reading stored secrets`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.save(AiProviderConnection.CODEX, "codex-key")
            fixture.save(AiProviderConnection.CUSTOM, "custom-key")
            val before = fixture.preferences.snapshot()
            val readsBefore = fixture.credentials.readKeys.size
            val writesBefore = fixture.credentials.writeKeys.size
            listOf(AiProviderConnection.CODEX, AiProviderConnection.CUSTOM).forEach { connection ->
                assertEquals(
                    listOf("gpt-4.1"),
                    fixture.runtime.providerModels(
                        connection, "draft-key", "https://draft.example/responses", "https://draft.example/catalog/models",
                        ImportedCredentialKind.BEARER_TOKEN,
                    ),
                )
                assertEquals("https://draft.example/catalog/models", fixture.requests.last().url)
                assertEquals("Bearer draft-key", fixture.requests.last().headers["Authorization"])
            }
            assertEquals(readsBefore, fixture.credentials.readKeys.size)
            assertEquals(writesBefore, fixture.credentials.writeKeys.size)
            assertEquals(before, fixture.preferences.snapshot())
        }
    }

    @Test
    fun `a saved custom connection never falls back after its own credential is deleted`() {
        Fixture().use { fixture ->
            fixture.save(AiProviderConnection.CODEX, "codex-key")
            fixture.save(AiProviderConnection.CUSTOM, "custom-key")
            fixture.credentials.delete("custom:api-key")
            fixture.credentials.readKeys.clear()

            assertFalse(fixture.runtime.providerHasCredential(AiProviderConnection.CUSTOM))
            val failure = assertThrows<AiModelCredentialException> {
                fixture.runtime.providerGateway(AiProviderConnection.CUSTOM, "custom-model")
            }
            assertFalse(failure.endpointMismatch)
            assertEquals(listOf("custom:api-key", "custom:api-key"), fixture.credentials.readKeys)
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun `gateway snapshots keep captured model endpoint and credential across later saves`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.save(AiProviderConnection.CODEX, "codex-key")
            fixture.save(AiProviderConnection.CUSTOM, "custom-old-key")
            val codexGateway = fixture.runtime.providerGateway(AiProviderConnection.CODEX, "codex-request-model")
            val customGateway = fixture.runtime.providerGateway(AiProviderConnection.CUSTOM, "custom-old-request-model")
            fixture.runtime.saveProviderConnection(
                AiProviderConnection.CUSTOM, "custom-new-model", "https://custom-new.example/v1/responses", "",
                ImportedCredentialKind.API_KEY, "custom-new-key",
            )
            fixture.save(AiProviderConnection.CODEX, "codex-new-key")
            assertEquals("ok", customGateway.analyze(chatRequest()).summary)
            assertEquals("ok", codexGateway.analyze(chatRequest()).summary)
            fixture.runtime.providerGateway(AiProviderConnection.CUSTOM, "custom-new-request-model").analyze(chatRequest())

            assertEquals(
                listOf(connectionEndpoint(AiProviderConnection.CUSTOM), connectionEndpoint(AiProviderConnection.CODEX), "https://custom-new.example/v1/responses"),
                fixture.requests.map { it.url },
            )
            assertEquals(
                listOf("Bearer custom-old-key", "Bearer codex-key", "Bearer custom-new-key"),
                fixture.requests.map { it.headers["Authorization"] },
            )
            assertEquals(
                listOf("custom-old-request-model", "codex-request-model", "custom-new-request-model"),
                fixture.requests.map { Json.parseToJsonElement(it.body).jsonObject.getValue("model").jsonPrimitive.content },
            )
        }
    }

    @Test
    fun `invalid saves validate all fields before accessing credentials or changing preferences`() {
        Fixture().use { fixture ->
            AiProviderConnection.entries.forEach { fixture.save(it, "${it.name}-key") }
            val before = fixture.preferences.snapshot()
            val readsBefore = fixture.credentials.readKeys.size
            val writesBefore = fixture.credentials.writeKeys.size
            val invalidInputs = listOf(
                SaveInput(model = ""),
                SaveInput(model = "x".repeat(129)),
                SaveInput(model = "bad\nmodel"),
                SaveInput(endpoint = "http://remote.example/v1/responses"),
                SaveInput(endpoint = "https://user:password@remote.example/v1/responses"),
                SaveInput(endpoint = "https://remote.example/v1/responses?key=secret"),
                SaveInput(endpoint = "https://remote.example/v1/responses#fragment"),
                SaveInput(modelsEndpoint = "https://different.example/models"),
                SaveInput(replacement = ""),
                SaveInput(replacement = "bad\nkey"),
                SaveInput(replacement = "x".repeat(8193)),
            )
            AiProviderConnection.entries.forEach { connection ->
                invalidInputs.forEach { input ->
                    val failure = assertThrows<RuntimeException> {
                        fixture.runtime.saveProviderConnection(
                            connection, input.model, input.endpoint, input.modelsEndpoint, ImportedCredentialKind.API_KEY, input.replacement,
                        )
                    }
                    assertEquals(
                        if (input.modelsEndpoint.isNotEmpty()) AiModelCatalogException::class.java else IllegalArgumentException::class.java,
                        failure.javaClass,
                    )
                }
            }
            assertEquals(readsBefore, fixture.credentials.readKeys.size)
            assertEquals(writesBefore, fixture.credentials.writeKeys.size)
            assertEquals(before, fixture.preferences.snapshot())
        }
    }

    @Test
    fun `failed replacement writes invalidate only the selected connection and fail closed`() {
        Fixture().use { fixture ->
            fixture.save(AiProviderConnection.CODEX, "codex-key")
            fixture.save(AiProviderConnection.CUSTOM, "custom-key")
            fixture.credentials.failAfterWrite = true
            assertThrows<IllegalStateException> { fixture.save(AiProviderConnection.CUSTOM, "partial-custom-key") }
            fixture.credentials.failAfterWrite = false
            assertFalse(fixture.runtime.providerHasCredential(AiProviderConnection.CUSTOM))
            assertTrue(fixture.runtime.providerHasCredential(AiProviderConnection.CODEX))
            assertThrows<AiModelCredentialException> {
                fixture.runtime.providerGateway(AiProviderConnection.CUSTOM, "custom-model")
            }

            fixture.credentials.failAfterWrite = true
            assertThrows<IllegalStateException> { fixture.save(AiProviderConnection.CODEX, "partial-codex-key") }
            fixture.credentials.failAfterWrite = false
            assertFalse(fixture.runtime.providerHasCredential(AiProviderConnection.CODEX))
            assertThrows<AiModelCredentialException> {
                fixture.runtime.providerGateway(AiProviderConnection.CODEX, "codex-model")
            }
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun `saving Codex first preserves the original legacy Custom connection before replacing it`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.runtime.saveAiConfiguration("legacy-model", "https://legacy.example/responses", "https://legacy.example/private/models")
            fixture.runtime.saveCredential("openai:api-key", "legacy-key")
            fixture.preferences.put("credentialKind", ImportedCredentialKind.BEARER_TOKEN.name)
            val legacyRevision = fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM)
            fixture.save(AiProviderConnection.CODEX, "new-codex-key")
            val customRevision = fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM)
            assertNotEquals(legacyRevision, customRevision)
            assertNotEquals(fixture.runtime.providerConnectionRevision(AiProviderConnection.CODEX), customRevision)
            UUID.fromString(customRevision)
            fixture.reopen()

            assertEquals("legacy-model", fixture.runtime.providerModel(AiProviderConnection.CUSTOM))
            assertEquals("https://legacy.example/responses", fixture.runtime.providerEndpoint(AiProviderConnection.CUSTOM))
            assertEquals("https://legacy.example/private/models", fixture.runtime.providerModelsEndpoint(AiProviderConnection.CUSTOM))
            assertEquals(ImportedCredentialKind.BEARER_TOKEN, fixture.runtime.providerCredentialKind(AiProviderConnection.CUSTOM))
            assertEquals("legacy-key", fixture.credentials.values["custom:api-key"])
            assertEquals(customRevision, fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM))
            fixture.runtime.providerGateway(AiProviderConnection.CUSTOM, "legacy-model").analyze(chatRequest())
            assertEquals("https://legacy.example/responses", fixture.requests.last().url)
            assertEquals("Bearer legacy-key", fixture.requests.last().headers["Authorization"])
            fixture.runtime.providerGateway(AiProviderConnection.CODEX, "codex-model").analyze(chatRequest())
            assertEquals(connectionEndpoint(AiProviderConnection.CODEX), fixture.requests.last().url)
            assertEquals("Bearer new-codex-key", fixture.requests.last().headers["Authorization"])
        }
    }

    @Test
    fun `Codex freezes incomplete legacy metadata without borrowing its new credential`() {
        listOf(null, "", "   ", "unbound-key", "invalid\nkey").forEach { legacyKey ->
            Fixture().use { fixture ->
                fixture.runtime.saveAiConfiguration("legacy-model", "https://legacy.example/responses", "https://legacy.example/private/models")
                fixture.preferences.put("credentialKind", ImportedCredentialKind.BEARER_TOKEN.name)
                if (legacyKey != null) fixture.credentials.values["openai:api-key"] = legacyKey
                if (legacyKey == "unbound-key") fixture.preferences.put("credentialEndpoint", "https://different.example/responses")
                fixture.save(AiProviderConnection.CODEX, "new-codex-key")
                val customRevision = fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM)
                UUID.fromString(customRevision)
                fixture.reopen()
                assertEquals("legacy-model", fixture.runtime.providerModel(AiProviderConnection.CUSTOM))
                assertEquals("https://legacy.example/responses", fixture.runtime.providerEndpoint(AiProviderConnection.CUSTOM))
                assertEquals("https://legacy.example/private/models", fixture.runtime.providerModelsEndpoint(AiProviderConnection.CUSTOM))
                assertEquals(ImportedCredentialKind.BEARER_TOKEN, fixture.runtime.providerCredentialKind(AiProviderConnection.CUSTOM))
                assertEquals(customRevision, fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM))
                assertEquals("", fixture.preferences.get("custom.credentialEndpoint", null))
                assertFalse(fixture.runtime.providerHasCredential(AiProviderConnection.CUSTOM))
                assertTrue(fixture.runtime.providerHasCredential(AiProviderConnection.CODEX))
                assertThrows<AiModelCredentialException> {
                    fixture.runtime.providerGateway(AiProviderConnection.CUSTOM, "legacy-model", customRevision)
                }
                assertNull(fixture.credentials.values["custom:api-key"])
                assertEquals(listOf("openai:api-key"), fixture.credentials.writeKeys)
                assertTrue(fixture.requests.isEmpty())
            }
        }
    }

    @Test
    fun `invalid legacy metadata remains isolated without preventing a valid Codex save`() {
        listOf(
            "model" to "",
            "endpoint" to "",
            "endpoint" to "http://remote.example/responses",
            "endpoint" to "not a valid endpoint",
            "modelsEndpoint" to "https://different.example/models",
        ).forEach { (field, invalidValue) ->
            Fixture().use { fixture ->
                fixture.runtime.saveAiConfiguration("legacy-model", "https://legacy.example/responses")
                fixture.runtime.saveCredential("openai:api-key", "legacy-key")
                fixture.preferences.put(field, invalidValue)
                val oldModel = fixture.runtime.aiModel()
                val oldEndpoint = fixture.runtime.aiEndpoint()
                val oldCatalogEndpoint = fixture.runtime.aiModelsEndpoint()
                fixture.save(AiProviderConnection.CODEX, "new-codex-key")
                fixture.reopen()
                assertEquals(oldModel, fixture.runtime.providerModel(AiProviderConnection.CUSTOM))
                assertEquals(oldEndpoint, fixture.runtime.providerEndpoint(AiProviderConnection.CUSTOM))
                assertEquals(oldCatalogEndpoint, fixture.runtime.providerModelsEndpoint(AiProviderConnection.CUSTOM))
                assertFalse(fixture.runtime.providerHasCredential(AiProviderConnection.CUSTOM))
                assertNull(fixture.credentials.values["custom:api-key"])
                assertEquals("", fixture.preferences.get("custom.credentialEndpoint", null))
                assertEquals(connectionEndpoint(AiProviderConnection.CODEX), fixture.runtime.providerEndpoint(AiProviderConnection.CODEX))
                assertTrue(fixture.runtime.providerHasCredential(AiProviderConnection.CODEX))
                assertTrue(fixture.requests.isEmpty())
            }
        }
    }

    @Test
    fun `a Codex update leaves an already independent Custom revision and connection untouched`() {
        Fixture().use { fixture ->
            fixture.save(AiProviderConnection.CUSTOM, "custom-key")
            val customPreferences = fixture.preferences.snapshot().filterKeys { it.startsWith("custom.") }
            val revision = fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM)
            fixture.save(AiProviderConnection.CODEX, "new-codex-key")
            assertEquals(customPreferences, fixture.preferences.snapshot().filterKeys { it.startsWith("custom.") })
            assertEquals(revision, fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM))
            assertEquals("custom-key", fixture.credentials.values["custom:api-key"])
        }
    }

    @Test
    fun `activation failures restore all connection fields secrets and original revisions`() = runBlocking<Unit> {
        AiProviderConnection.entries.forEach { connection ->
            Fixture().use { fixture ->
                AiProviderConnection.entries.forEach { fixture.save(it, "${it.name}-old-key") }
                val preferencesBefore = fixture.preferences.snapshot()
                val credentialsBefore = fixture.credentials.values.toMap()
                val revisionBefore = fixture.runtime.providerConnectionRevision(connection)
                val callbackFailure = IllegalStateException("Synthetic activation flush failure")
                var calls = 0
                val failure = assertThrows<IllegalStateException> {
                    fixture.runtime.saveAndApplyProviderConnection(
                        connection, "replacement-model", "https://replacement.example/responses", "",
                        ImportedCredentialKind.BEARER_TOKEN, "replacement-key",
                    ) {
                        calls++
                        assertEquals("replacement-model", fixture.runtime.providerModel(connection))
                        assertEquals("https://replacement.example/responses", fixture.runtime.providerEndpoint(connection))
                        assertNotEquals(revisionBefore, fixture.runtime.providerConnectionRevision(connection))
                        throw callbackFailure
                    }
                }
                assertSame(callbackFailure, failure)
                assertEquals(1, calls)
                assertEquals(preferencesBefore, fixture.preferences.snapshot())
                assertEquals(credentialsBefore, fixture.credentials.values)
                assertEquals(revisionBefore, fixture.runtime.providerConnectionRevision(connection))
                fixture.reopen()
                assertEquals(revisionBefore, fixture.runtime.providerConnectionRevision(connection))
                assertTrue(fixture.runtime.providerHasCredential(connection))
                if (connection == AiProviderConnection.CLAUDE) {
                    assertEquals(
                        mapOf("ANTHROPIC_API_KEY" to "CLAUDE-old-key", "ANTHROPIC_BASE_URL" to connectionEndpoint(connection)),
                        fixture.runtime.claudeEnvironment(),
                    )
                } else {
                    fixture.runtime.providerGateway(connection, connectionModel(connection)).analyze(chatRequest())
                    assertEquals(connectionEndpoint(connection), fixture.requests.last().url)
                    assertEquals("Bearer ${connection.name}-old-key", fixture.requests.last().headers["Authorization"])
                }
            }
        }
    }

    @Test
    fun `first activation failure restores absent preferences secrets and legacy revision`() {
        AiProviderConnection.entries.forEach { connection ->
            Fixture().use { fixture ->
                val revisionBefore = fixture.runtime.providerConnectionRevision(connection)
                assertThrows<IllegalStateException> {
                    fixture.runtime.saveAndApplyProviderConnection(
                        connection, connectionModel(connection), connectionEndpoint(connection), "", ImportedCredentialKind.API_KEY, "first-key",
                    ) { error("Synthetic first activation failure") }
                }
                assertTrue(fixture.preferences.keys().isEmpty())
                assertTrue(fixture.credentials.values.isEmpty())
                assertEquals(revisionBefore, fixture.runtime.providerConnectionRevision(connection))
                assertFalse(fixture.runtime.providerHasCredential(connection))
            }
        }
    }

    @Test
    fun `Codex activation failure also rolls back its pre-save Custom migration`() {
        Fixture().use { fixture ->
            fixture.runtime.saveAiConfiguration("legacy-model", "https://legacy.example/responses", "https://legacy.example/private/models")
            fixture.runtime.saveCredential("openai:api-key", "legacy-key")
            val preferencesBefore = fixture.preferences.snapshot()
            val credentialsBefore = fixture.credentials.values.toMap()
            val revisionsBefore = AiProviderConnection.entries.associateWith(fixture.runtime::providerConnectionRevision)
            assertThrows<IllegalStateException> {
                fixture.runtime.saveAndApplyProviderConnection(
                    AiProviderConnection.CODEX, "new-codex-model", "https://codex-new.example/responses", "",
                    ImportedCredentialKind.API_KEY, "new-codex-key",
                ) {
                    assertEquals("legacy-key", fixture.credentials.values["custom:api-key"])
                    assertEquals("legacy-model", fixture.runtime.providerModel(AiProviderConnection.CUSTOM))
                    error("Synthetic migration activation failure")
                }
            }
            assertEquals(preferencesBefore, fixture.preferences.snapshot())
            assertEquals(credentialsBefore, fixture.credentials.values)
            assertEquals(revisionsBefore, AiProviderConnection.entries.associateWith(fixture.runtime::providerConnectionRevision))
            assertTrue(fixture.preferences.keys().none { it.startsWith("custom.") })
            assertTrue(fixture.runtime.providerHasCredential(AiProviderConnection.CUSTOM))
        }
    }

    @Test
    fun `source write failure skips activation and restores the previous connection`() {
        Fixture().use { fixture ->
            fixture.save(AiProviderConnection.CUSTOM, "custom-old-key")
            val preferencesBefore = fixture.preferences.snapshot()
            val credentialsBefore = fixture.credentials.values.toMap()
            fixture.credentials.remainingWriteFailures = 1
            var activated = false
            assertThrows<IllegalStateException> {
                fixture.runtime.saveAndApplyProviderConnection(
                    AiProviderConnection.CUSTOM, "new-model", "https://new.example/responses", "",
                    ImportedCredentialKind.API_KEY, "new-key",
                ) { activated = true }
            }
            assertFalse(activated)
            assertEquals(preferencesBefore, fixture.preferences.snapshot())
            assertEquals(credentialsBefore, fixture.credentials.values)
            assertTrue(fixture.runtime.providerHasCredential(AiProviderConnection.CUSTOM))
        }
    }

    @Test
    fun `a failed secret rollback invalidates only the affected connection even after reopening`() {
        AiProviderConnection.entries.forEach { connection ->
            Fixture().use { fixture ->
                AiProviderConnection.entries.forEach { fixture.save(it, "${it.name}-old-key") }
                val revisionBefore = fixture.runtime.providerConnectionRevision(connection)
                val callbackFailure = IllegalStateException("Synthetic activation failure")
                val failure = assertThrows<IllegalStateException> {
                    fixture.runtime.saveAndApplyProviderConnection(
                        connection, "new-model", "https://new.example/responses", "", ImportedCredentialKind.API_KEY, "new-key",
                    ) {
                        fixture.credentials.failAfterWrite = true
                        throw callbackFailure
                    }
                }
                assertSame(callbackFailure, failure)
                fixture.credentials.failAfterWrite = false
                assertNotEquals(revisionBefore, fixture.runtime.providerConnectionRevision(connection))
                fixture.reopen()
                assertFalse(fixture.runtime.providerHasCredential(connection))
                AiProviderConnection.entries.filter { it != connection }.forEach {
                    assertTrue(fixture.runtime.providerHasCredential(it))
                }
                if (connection == AiProviderConnection.CLAUDE) {
                    assertEquals(mapOf("ANTHROPIC_BASE_URL" to connectionEndpoint(connection)), fixture.runtime.claudeEnvironment())
                } else {
                    assertThrows<AiModelCredentialException> { fixture.runtime.providerGateway(connection, "old-model") }
                }
                assertTrue(fixture.requests.isEmpty())
                fixture.save(connection, "recovered-key")
                assertTrue(fixture.runtime.providerHasCredential(connection))
            }
        }
    }

    @Test
    fun `a silently ignored credential delete keeps rollback fail closed`() {
        Fixture().use { fixture ->
            fixture.save(AiProviderConnection.CLAUDE, "old-api-key")
            val before = fixture.runtime.providerConnectionRevision(AiProviderConnection.CLAUDE)
            val callbackFailure = IllegalStateException("Synthetic activation failure")
            val failure = assertThrows<IllegalStateException> {
                fixture.runtime.saveAndApplyProviderConnection(
                    AiProviderConnection.CLAUDE, "new-model", "https://new.example", "", ImportedCredentialKind.BEARER_TOKEN, "new-token",
                ) {
                    fixture.credentials.ignoreDeletes = true
                    throw callbackFailure
                }
            }
            assertSame(callbackFailure, failure)
            assertEquals("new-token", fixture.credentials.values["claude:auth-token"])
            assertNotEquals(before, fixture.runtime.providerConnectionRevision(AiProviderConnection.CLAUDE))
            fixture.credentials.ignoreDeletes = false
            fixture.reopen()
            assertFalse(fixture.runtime.providerHasCredential(AiProviderConnection.CLAUDE))
            assertFalse(fixture.runtime.providerHasCredential(AiProviderConnection.CLAUDE, kind = ImportedCredentialKind.BEARER_TOKEN))
            assertEquals(mapOf("ANTHROPIC_BASE_URL" to connectionEndpoint(AiProviderConnection.CLAUDE)), fixture.runtime.claudeEnvironment())
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun `a silently ignored credential write keeps rollback fail closed`() {
        Fixture().use { fixture ->
            fixture.save(AiProviderConnection.CUSTOM, "old-key")
            val before = fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM)
            assertThrows<IllegalStateException> {
                fixture.runtime.saveAndApplyProviderConnection(
                    AiProviderConnection.CUSTOM, "new-model", "https://new.example/responses", "", ImportedCredentialKind.API_KEY, "new-key",
                ) {
                    fixture.credentials.ignoreWrites = true
                    error("Synthetic activation failure")
                }
            }
            assertEquals("new-key", fixture.credentials.values["custom:api-key"])
            assertNotEquals(before, fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM))
            fixture.credentials.ignoreWrites = false
            fixture.reopen()
            assertFalse(fixture.runtime.providerHasCredential(AiProviderConnection.CUSTOM))
            assertThrows<AiModelCredentialException> { fixture.runtime.providerGateway(AiProviderConnection.CUSTOM, "old-model") }
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun `connection revisions are stable secret-free and rotate for credential-only saves`() {
        Fixture().use { fixture ->
            val before = fixture.preferences.snapshot()
            val readsBefore = fixture.credentials.readKeys.size
            val legacy = AiProviderConnection.entries.associateWith(fixture.runtime::providerConnectionRevision)
            fixture.reopen()
            assertEquals(legacy, AiProviderConnection.entries.associateWith(fixture.runtime::providerConnectionRevision))
            assertEquals(before, fixture.preferences.snapshot())
            assertEquals(readsBefore, fixture.credentials.readKeys.size)
            AiProviderConnection.entries.forEach { connection ->
                fixture.save(connection, "first-key")
                val first = fixture.runtime.providerConnectionRevision(connection)
                UUID.fromString(first)
                assertNotEquals(legacy.getValue(connection), first)
                fixture.save(connection, "second-key")
                val second = fixture.runtime.providerConnectionRevision(connection)
                UUID.fromString(second)
                assertNotEquals(first, second)
                fixture.reopen()
                assertEquals(second, fixture.runtime.providerConnectionRevision(connection))
                assertTrue(fixture.preferences.snapshot().values.none { it == "first-key" || it == "second-key" })
            }
        }
    }

    @Test
    fun `successful guarded apply commits the captured connection and revision`() {
        Fixture().use { fixture ->
            val before = fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM)
            var appliedRevision: String? = null
            fixture.runtime.saveAndApplyProviderConnection(
                AiProviderConnection.CUSTOM, "committed-model", "https://committed.example/responses", "",
                ImportedCredentialKind.API_KEY, "committed-key",
            ) {
                assertEquals("committed-model", fixture.runtime.providerModel(AiProviderConnection.CUSTOM))
                assertTrue(fixture.runtime.providerHasCredential(AiProviderConnection.CUSTOM))
                appliedRevision = fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM)
            }
            assertNotEquals(before, appliedRevision)
            fixture.reopen()
            assertEquals(appliedRevision, fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM))
            assertEquals("committed-key", fixture.credentials.values["custom:api-key"])
        }
    }

    @Test
    fun `invalid guarded saves cannot read secrets mutate revisions or activate settings`() {
        Fixture().use { fixture ->
            fixture.save(AiProviderConnection.CUSTOM, "old-key")
            val before = fixture.preferences.snapshot()
            val readsBefore = fixture.credentials.readKeys.size
            val writesBefore = fixture.credentials.writeKeys.size
            var activated = false
            assertThrows<IllegalArgumentException> {
                fixture.runtime.saveAndApplyProviderConnection(
                    AiProviderConnection.CUSTOM, "", "https://valid.example/responses", "", ImportedCredentialKind.API_KEY, "new-key",
                ) { activated = true }
            }
            assertFalse(activated)
            assertEquals(before, fixture.preferences.snapshot())
            assertEquals(readsBefore, fixture.credentials.readKeys.size)
            assertEquals(writesBefore, fixture.credentials.writeKeys.size)
        }
    }

    @Test
    fun `a source flush failure rolls back source secrets revision and skips activation`() {
        val preferences = FaultingPreferences()
        Fixture(preferences).use { fixture ->
            fixture.save(AiProviderConnection.CUSTOM, "old-key")
            val before = preferences.snapshot()
            val savedCredentials = fixture.credentials.values.toMap()
            var activated = false
            preferences.failFlushes = 1
            assertThrows<BackingStoreException> {
                fixture.runtime.saveAndApplyProviderConnection(
                    AiProviderConnection.CUSTOM, "new-model", "https://new.example/responses", "", ImportedCredentialKind.API_KEY, "new-key",
                ) { activated = true }
            }
            assertFalse(activated)
            assertEquals(before, preferences.snapshot())
            assertEquals(savedCredentials, fixture.credentials.values)
            fixture.reopen()
            assertTrue(fixture.runtime.providerHasCredential(AiProviderConnection.CUSTOM))
            assertEquals(before.getValue("custom.connectionRevision"), fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM))
        }
    }

    @Test
    fun `a rollback flush failure persists a blocked connection instead of reporting restored success`() {
        val preferences = FaultingPreferences()
        Fixture(preferences).use { fixture ->
            fixture.save(AiProviderConnection.CUSTOM, "old-key")
            val before = fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM)
            assertThrows<IllegalStateException> {
                fixture.runtime.saveAndApplyProviderConnection(
                    AiProviderConnection.CUSTOM, "new-model", "https://new.example/responses", "", ImportedCredentialKind.API_KEY, "new-key",
                ) {
                    preferences.failFlushes = 1
                    error("Synthetic activation failure")
                }
            }
            assertNotEquals(before, fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM))
            assertFalse(fixture.runtime.providerHasCredential(AiProviderConnection.CUSTOM))
            fixture.reopen()
            assertFalse(fixture.runtime.providerHasCredential(AiProviderConnection.CUSTOM))
            assertThrows<AiModelCredentialException> { fixture.runtime.providerGateway(AiProviderConnection.CUSTOM, "old-model") }
        }
    }

    @Test
    fun `unwritable rollback preferences still invalidate the in-memory revision and prevent requests`() {
        val preferences = FaultingPreferences()
        Fixture(preferences).use { fixture ->
            fixture.save(AiProviderConnection.CUSTOM, "old-key")
            var stagedRevision: String? = null
            assertThrows<IllegalStateException> {
                fixture.runtime.saveAndApplyProviderConnection(
                    AiProviderConnection.CUSTOM, "new-model", "https://new.example/responses", "", ImportedCredentialKind.API_KEY, "new-key",
                ) {
                    stagedRevision = fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM)
                    preferences.failWrites = true
                    error("Synthetic activation failure")
                }
            }
            assertNotEquals(stagedRevision, fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM))
            assertFalse(fixture.runtime.providerHasCredential(AiProviderConnection.CUSTOM))
            assertThrows<AiModelCredentialException> { fixture.runtime.providerGateway(AiProviderConnection.CUSTOM, "old-model") }
            assertTrue(fixture.requests.isEmpty())
            preferences.failWrites = false
            fixture.save(AiProviderConnection.CUSTOM, "recovered-key")
            assertTrue(fixture.runtime.providerHasCredential(AiProviderConnection.CUSTOM))
        }
    }

    @Test
    fun `production settings update flush failure restores source revision settings preferences and published state`() {
        val platformPreferences = FaultingPreferences().node("platform-${UUID.randomUUID()}") as FaultingPreferences
        try {
            Fixture().use { fixture ->
                AiProviderConnection.entries.forEach { fixture.save(it, "${it.name}-old-key") }
                val settingsStore = AiPlatformSettingsStore(platformPreferences)
                val applied = AiPlatformSettings(
                    providerId = "openai", model = "applied-model", agentId = "kept.agent", allowArtifacts = true, allowScripts = true,
                )
                settingsStore.save(applied)
                val sourceBefore = fixture.preferences.snapshot()
                val credentialsBefore = fixture.credentials.values.toMap()
                val platformBefore = platformPreferences.snapshot()
                val revisionBefore = fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM)
                platformPreferences.failFlushes = 1
                assertThrows<BackingStoreException> {
                    fixture.runtime.saveAndApplyProviderConnection(
                        AiProviderConnection.CUSTOM, "replacement-model", "https://replacement.example/responses", "",
                        ImportedCredentialKind.API_KEY, "replacement-key",
                    ) {
                        settingsStore.update { it.copy(providerId = "openai", model = "replacement-model") }
                    }
                }
                assertEquals(sourceBefore, fixture.preferences.snapshot())
                assertEquals(credentialsBefore, fixture.credentials.values)
                assertEquals(revisionBefore, fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM))
                assertEquals(platformBefore, platformPreferences.snapshot())
                assertEquals(applied, settingsStore.load())
                assertEquals(applied, settingsStore.settings.value)
                fixture.reopen()
                assertEquals(revisionBefore, fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM))
                assertTrue(fixture.runtime.providerHasCredential(AiProviderConnection.CUSTOM))
                assertEquals(applied, AiPlatformSettingsStore(platformPreferences).load())
            }
        } finally {
            platformPreferences.removeNode()
        }
    }

    @Test
    fun `stale expected revisions reject gateway capture before credentials or transport are accessed`() = runBlocking<Unit> {
        listOf(AiProviderConnection.CODEX, AiProviderConnection.CUSTOM).forEach { connection ->
            Fixture().use { fixture ->
                fixture.save(connection, "old-key")
                val frozenRevision = fixture.runtime.providerConnectionRevision(connection)
                fixture.save(connection, "replacement-key")
                val readsBefore = fixture.credentials.readKeys.size
                val failure = assertThrows<IllegalStateException> {
                    fixture.runtime.providerGateway(connection, "request-model", frozenRevision)
                }
                assertEquals("E_PROVIDER_CONNECTION_CHANGED", failure.message)
                assertEquals(readsBefore, fixture.credentials.readKeys.size)
                assertTrue(fixture.requests.isEmpty())
                val currentRevision = fixture.runtime.providerConnectionRevision(connection)
                fixture.runtime.providerGateway(connection, "request-model", currentRevision).analyze(chatRequest())
                assertEquals(connectionEndpoint(connection), fixture.requests.single().url)
                assertEquals("Bearer replacement-key", fixture.requests.single().headers["Authorization"])
            }
        }
    }

    @Test
    fun `Claude revision guarded capture rejects stale credentials and accepts the matching environment`() {
        Fixture().use { fixture ->
            fixture.save(AiProviderConnection.CLAUDE, "old-key")
            val frozenRevision = fixture.runtime.providerConnectionRevision(AiProviderConnection.CLAUDE)
            fixture.runtime.saveProviderConnection(
                AiProviderConnection.CLAUDE, "replacement-model", "https://replacement.example", "",
                ImportedCredentialKind.BEARER_TOKEN, "replacement-token",
            )
            val readsBefore = fixture.credentials.readKeys.size
            val failure = assertThrows<IllegalStateException> { fixture.runtime.claudeEnvironment(frozenRevision) }
            assertEquals("E_PROVIDER_CONNECTION_CHANGED", failure.message)
            assertEquals(readsBefore, fixture.credentials.readKeys.size)
            assertTrue(fixture.requests.isEmpty())
            val currentRevision = fixture.runtime.providerConnectionRevision(AiProviderConnection.CLAUDE)
            assertEquals(
                mapOf("ANTHROPIC_AUTH_TOKEN" to "replacement-token", "ANTHROPIC_BASE_URL" to "https://replacement.example"),
                fixture.runtime.claudeEnvironment(currentRevision),
            )
        }
    }

    @Test
    fun `saved catalogs use provider-specific saved routes and in-memory caches`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            AiProviderConnection.entries.forEach { fixture.save(it, "${it.name}-key", ImportedCredentialKind.BEARER_TOKEN) }
            val before = fixture.preferences.snapshot()
            AiProviderConnection.entries.forEach { connection ->
                assertEquals(listOf("gpt-4.1"), fixture.runtime.savedProviderModels(connection.providerId))
                assertEquals(connectionCatalogEndpoint(connection), fixture.requests.last().url)
                assertEquals("Bearer ${connection.name}-key", fixture.requests.last().headers["Authorization"])
                val readsBefore = fixture.credentials.readKeys.size
                assertEquals(listOf("gpt-4.1"), fixture.runtime.savedProviderModels(connection.providerId))
                assertEquals(readsBefore, fixture.credentials.readKeys.size)
            }
            assertEquals(3, fixture.requests.size)
            assertEquals(before, fixture.preferences.snapshot())
            fixture.reopen()
            assertEquals(listOf("gpt-4.1"), fixture.runtime.savedProviderModels("codex"))
            assertEquals(4, fixture.requests.size)
        }
    }

    @Test
    fun `explicit chat catalog refresh bypasses a previously loaded cache without changing the saved connection`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.save(AiProviderConnection.CODEX, "codex-key")
            val before = fixture.preferences.snapshot()
            val revision = fixture.runtime.providerConnectionRevision(AiProviderConnection.CODEX)
            fixture.runtime.cacheProviderModels(AiProviderConnection.CODEX, listOf("old-cached-model"))
            assertEquals(listOf("old-cached-model"), fixture.runtime.savedProviderModels("codex"))
            assertTrue(fixture.requests.isEmpty())
            assertEquals(listOf("gpt-4.1"), fixture.runtime.savedProviderModels("codex", refresh = true))
            assertEquals(1, fixture.requests.size)
            assertEquals(listOf("gpt-4.1"), fixture.runtime.savedProviderModels("codex"))
            assertEquals(1, fixture.requests.size)
            assertEquals(before, fixture.preferences.snapshot())
            assertEquals(revision, fixture.runtime.providerConnectionRevision(AiProviderConnection.CODEX))
        }
    }

    @Test
    fun `saved Claude catalogs preserve API key authentication`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.save(AiProviderConnection.CLAUDE, "claude-key")
            assertEquals(listOf("gpt-4.1"), fixture.runtime.savedProviderModels("claude"))
            assertEquals("claude-key", fixture.requests.single().headers["x-api-key"])
            assertNull(fixture.requests.single().headers["Authorization"])
            assertEquals(ImportedCredentialKind.API_KEY, fixture.runtime.providerCredentialKind(AiProviderConnection.CLAUDE))
        }
    }

    @Test
    fun `seeded catalogs preserve exact bounded ids and empty results without credentials or requests`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            AiProviderConnection.entries.forEach { fixture.save(it, "${it.name}-key") }
            val before = fixture.preferences.snapshot()
            val readsBefore = fixture.credentials.readKeys.size
            val ids = mutableListOf(" gateway/gpt-exact ", "x".repeat(256), "gateway/gpt-exact", "gateway/gpt-exact")
            val expected = ids.toList()
            fixture.runtime.cacheProviderModels(AiProviderConnection.CODEX, ids)
            fixture.runtime.cacheProviderModels(AiProviderConnection.CLAUDE, emptyList())
            fixture.runtime.cacheProviderModels(AiProviderConnection.CUSTOM, List(10_000) { "model-$it" })
            ids.clear()
            val returned = fixture.runtime.savedProviderModels("codex")
            assertEquals(expected, returned)
            (returned as MutableList<String>).clear()
            assertEquals(expected, fixture.runtime.savedProviderModels("codex"))
            assertEquals(emptyList<String>(), fixture.runtime.savedProviderModels("claude"))
            assertEquals(10_000, fixture.runtime.savedProviderModels("openai").size)
            assertEquals(readsBefore, fixture.credentials.readKeys.size)
            assertEquals(before, fixture.preferences.snapshot())
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun `invalid seeds and mismatched publication revisions cannot overwrite the current cache`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.save(AiProviderConnection.CUSTOM, "custom-key")
            val previousRevision = fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM)
            fixture.save(AiProviderConnection.CUSTOM, "replacement-key")
            fixture.runtime.cacheProviderModels(AiProviderConnection.CUSTOM, listOf("kept-model"))
            val failure = assertThrows<IllegalStateException> {
                fixture.runtime.cacheProviderModels(AiProviderConnection.CUSTOM, listOf("stale-model"), previousRevision)
            }
            assertEquals("E_PROVIDER_CONNECTION_CHANGED", failure.message)
            listOf(listOf(""), listOf("  "), listOf("bad\nmodel"), listOf("bad\u007fmodel"), listOf("x".repeat(257)), List(10_001) { "model" })
                .forEach { models ->
                    assertThrows<IllegalArgumentException> { fixture.runtime.cacheProviderModels(AiProviderConnection.CUSTOM, models) }
                    assertEquals(listOf("kept-model"), fixture.runtime.savedProviderModels("openai"))
                }
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun `connection and credential edits invalidate only their own seeded catalogs`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            AiProviderConnection.entries.forEach { fixture.save(it, "${it.name}-key") }
            AiProviderConnection.entries.forEach { fixture.runtime.cacheProviderModels(it, listOf("${it.name}-cached")) }
            fixture.save(AiProviderConnection.CUSTOM, "replacement-key")
            assertThrows<IllegalStateException> { fixture.runtime.selectProviderModel("openai", "CUSTOM-cached") {} }
            assertEquals(listOf("gpt-4.1"), fixture.runtime.savedProviderModels("openai"))
            assertEquals("Bearer replacement-key", fixture.requests.single().headers["Authorization"])
            assertEquals(listOf("CODEX-cached"), fixture.runtime.savedProviderModels("codex"))
            assertEquals(listOf("CLAUDE-cached"), fixture.runtime.savedProviderModels("claude"))
            fixture.runtime.saveProviderConnection(
                AiProviderConnection.CUSTOM, "edited-model", "https://edited.example/responses", "https://edited.example/catalog/models",
                ImportedCredentialKind.BEARER_TOKEN, "edited-key",
            )
            assertEquals(listOf("gpt-4.1"), fixture.runtime.savedProviderModels("openai"))
            assertEquals("https://edited.example/catalog/models", fixture.requests.last().url)
            assertEquals("Bearer edited-key", fixture.requests.last().headers["Authorization"])
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test
    fun `stale loads use captured credentials and cannot replace newer revision caches`() = runBlocking<Unit> {
        AiProviderConnection.entries.forEach { connection ->
            Fixture().use { fixture ->
                fixture.save(connection, "old-key", ImportedCredentialKind.BEARER_TOKEN)
                val pending = CompletableDeferred<AiHttpResponse>()
                fixture.catalogResponse = { pending.await() }
                val result = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { fixture.runtime.savedProviderModels(connection.providerId) }
                }
                assertEquals(connectionCatalogEndpoint(connection), fixture.requests.single().url)
                assertEquals("Bearer old-key", fixture.requests.single().headers["Authorization"])
                fixture.runtime.saveProviderConnection(
                    connection, "new-model", "https://new.example/v1", "", ImportedCredentialKind.API_KEY, "new-key",
                )
                fixture.runtime.cacheProviderModels(connection, listOf("new-model"))
                pending.complete(AiHttpResponse(200, """{"data":[{"id":"gpt-stale"}]}"""))
                assertEquals("E_PROVIDER_CONNECTION_CHANGED", result.await().exceptionOrNull()?.message)
                assertEquals(listOf("new-model"), fixture.runtime.savedProviderModels(connection.providerId))
                assertEquals(1, fixture.requests.size)
            }
        }
    }

    @Test
    fun `failed and cancelled loads cannot publish a catalog or overwrite a later seed`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.save(AiProviderConnection.CUSTOM, "custom-key")
            fixture.catalogResponse = { AiHttpResponse(200, "not a model catalog") }
            assertThrows<AiModelCatalogException> { fixture.runtime.savedProviderModels("openai") }
            assertThrows<IllegalStateException> { fixture.runtime.selectProviderModel("openai", "gpt-4.1") {} }
            val pending = CompletableDeferred<AiHttpResponse>()
            fixture.catalogResponse = { pending.await() }
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { fixture.runtime.savedProviderModels("openai") }
            }
            fixture.runtime.cacheProviderModels(AiProviderConnection.CUSTOM, listOf("kept-model"))
            pending.complete(AiHttpResponse(200, "not a model catalog"))
            assertTrue(result.await().exceptionOrNull() is AiModelCatalogException)
            assertEquals(listOf("kept-model"), fixture.runtime.savedProviderModels("openai"))
            fixture.save(AiProviderConnection.CUSTOM, "replacement-key")
            fixture.catalogResponse = { throw kotlinx.coroutines.CancellationException("Synthetic cancellation") }
            assertThrows<kotlinx.coroutines.CancellationException> { fixture.runtime.savedProviderModels("openai") }
            assertThrows<IllegalStateException> { fixture.runtime.selectProviderModel("openai", "kept-model") {} }
        }
    }

    @Test
    fun `selection changes only each provider model and preserves provider agent flags keys and revisions`() {
        val platformPreferences = FaultingPreferences().node("selection-${UUID.randomUUID()}") as FaultingPreferences
        try {
            Fixture().use { fixture ->
                AiProviderConnection.entries.forEach { fixture.save(it, "${it.name}-key") }
                val settingsStore = AiPlatformSettingsStore(platformPreferences)
                AiProviderConnection.entries.forEach { connection ->
                    val applied = AiPlatformSettings(
                        providerId = connection.providerId, model = connectionModel(connection), agentId = "kept.agent",
                        allowArtifacts = true, allowScripts = true,
                    )
                    settingsStore.save(applied)
                    val selectedModel = " gateway/gpt-${connection.name} "
                    fixture.runtime.cacheProviderModels(connection, listOf(selectedModel))
                    val before = fixture.preferences.snapshot()
                    val credentialsBefore = fixture.credentials.values.toMap()
                    val readsBefore = fixture.credentials.readKeys.size
                    val writesBefore = fixture.credentials.writeKeys.size
                    val modelKey = when (connection) {
                        AiProviderConnection.CODEX -> "model"
                        AiProviderConnection.CLAUDE -> "claude.model"
                        AiProviderConnection.CUSTOM -> "custom.model"
                    }
                    fixture.runtime.selectProviderModel(connection.providerId, selectedModel) {
                        assertEquals(selectedModel, fixture.runtime.providerModel(connection))
                        settingsStore.update {
                            check(it.providerId == connection.providerId)
                            it.copy(model = selectedModel)
                        }
                    }
                    assertEquals(before + (modelKey to selectedModel), fixture.preferences.snapshot())
                    assertEquals(applied.copy(model = selectedModel), settingsStore.load())
                    assertEquals(credentialsBefore, fixture.credentials.values)
                    assertEquals(readsBefore, fixture.credentials.readKeys.size)
                    assertEquals(writesBefore, fixture.credentials.writeKeys.size)
                }
                assertTrue(fixture.requests.isEmpty())
            }
        } finally {
            platformPreferences.removeNode()
        }
    }

    @Test
    fun `selection rejects missing unknown and oversized models before changing state`() {
        Fixture().use { fixture ->
            fixture.save(AiProviderConnection.CUSTOM, "custom-key")
            val before = fixture.preferences.snapshot()
            val readsBefore = fixture.credentials.readKeys.size
            var applied = false
            assertThrows<IllegalStateException> { fixture.runtime.selectProviderModel("openai", "gpt-next") { applied = true } }
            fixture.runtime.cacheProviderModels(AiProviderConnection.CUSTOM, listOf("gpt-next", "x".repeat(129)))
            assertThrows<IllegalStateException> { fixture.runtime.selectProviderModel("openai", "not-loaded") { applied = true } }
            listOf("", "  ", "bad\nmodel", "x".repeat(129)).forEach { model ->
                assertThrows<IllegalArgumentException> { fixture.runtime.selectProviderModel("openai", model) { applied = true } }
            }
            assertThrows<IllegalArgumentException> { fixture.runtime.selectProviderModel("unknown", "gpt-next") { applied = true } }
            assertFalse(applied)
            assertEquals(before, fixture.preferences.snapshot())
            assertEquals(readsBefore, fixture.credentials.readKeys.size)
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun `activation failure restores exact preferences and cache without reading credentials`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            AiProviderConnection.entries.forEach { fixture.save(it, "${it.name}-key") }
            AiProviderConnection.entries.forEach { connection ->
                fixture.runtime.cacheProviderModels(connection, listOf("next-model"))
                val before = fixture.preferences.snapshot()
                val credentialsBefore = fixture.credentials.values.toMap()
                val readsBefore = fixture.credentials.readKeys.size
                val writesBefore = fixture.credentials.writeKeys.size
                val callbackFailure = IllegalStateException("Synthetic activation failure")
                assertSame(callbackFailure, assertThrows<IllegalStateException> {
                    fixture.runtime.selectProviderModel(connection.providerId, "next-model") { throw callbackFailure }
                })
                assertEquals(before, fixture.preferences.snapshot())
                assertEquals(credentialsBefore, fixture.credentials.values)
                assertEquals(readsBefore, fixture.credentials.readKeys.size)
                assertEquals(writesBefore, fixture.credentials.writeKeys.size)
                assertEquals(listOf("next-model"), fixture.runtime.savedProviderModels(connection.providerId))
            }
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun `flush failure skips activation and rollback flush failure invalidates the connection`() = runBlocking<Unit> {
        val preferences = FaultingPreferences()
        Fixture(preferences).use { fixture ->
            fixture.save(AiProviderConnection.CUSTOM, "custom-key")
            fixture.runtime.cacheProviderModels(AiProviderConnection.CUSTOM, listOf("next-model"))
            val before = fixture.preferences.snapshot()
            val readsBefore = fixture.credentials.readKeys.size
            val writesBefore = fixture.credentials.writeKeys.size
            var applied = false
            preferences.failFlushes = 1
            assertThrows<BackingStoreException> { fixture.runtime.selectProviderModel("openai", "next-model") { applied = true } }
            assertFalse(applied)
            assertEquals(before, fixture.preferences.snapshot())
            assertEquals(listOf("next-model"), fixture.runtime.savedProviderModels("openai"))
            val callbackFailure = IllegalStateException("Synthetic activation failure")
            assertSame(callbackFailure, assertThrows<IllegalStateException> {
                fixture.runtime.selectProviderModel("openai", "next-model") {
                    preferences.failFlushes = 1
                    throw callbackFailure
                }
            })
            assertNotEquals(before.getValue("custom.connectionRevision"), fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM))
            assertEquals(readsBefore, fixture.credentials.readKeys.size)
            assertEquals(writesBefore, fixture.credentials.writeKeys.size)
            assertThrows<IllegalStateException> { fixture.runtime.savedProviderModels("openai") }
            assertThrows<IllegalStateException> { fixture.runtime.selectProviderModel("openai", "next-model") {} }
            fixture.reopen()
            assertFalse(fixture.runtime.providerHasCredential(AiProviderConnection.CUSTOM))
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun `legacy Custom catalog selection migrates only Custom and rebinds its loaded cache`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.runtime.saveAiConfiguration("legacy-model", "https://legacy.example/responses", "https://legacy.example/catalog/models")
            fixture.runtime.saveCredential("openai:api-key", "legacy-key")
            val before = fixture.preferences.snapshot()
            val codexRevision = fixture.runtime.providerConnectionRevision(AiProviderConnection.CODEX)
            val legacyRevision = fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM)
            assertEquals(listOf("gpt-4.1"), fixture.runtime.savedProviderModels("openai"))
            var applied = false
            fixture.runtime.selectProviderModel("openai", "gpt-4.1") { applied = true }
            assertTrue(applied)
            assertEquals("gpt-4.1", fixture.runtime.providerModel(AiProviderConnection.CUSTOM))
            assertEquals("legacy-model", fixture.runtime.aiModel())
            assertEquals(codexRevision, fixture.runtime.providerConnectionRevision(AiProviderConnection.CODEX))
            assertNotEquals(legacyRevision, fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM))
            assertEquals(before, fixture.preferences.snapshot().filterKeys { !it.startsWith("custom.") })
            assertEquals(mapOf("openai:api-key" to "legacy-key", "custom:api-key" to "legacy-key"), fixture.credentials.values)
            assertEquals(listOf("gpt-4.1"), fixture.runtime.savedProviderModels("openai"))
            assertEquals(1, fixture.requests.size)
            fixture.reopen()
            assertEquals("gpt-4.1", fixture.runtime.providerModel(AiProviderConnection.CUSTOM))
            assertTrue(fixture.runtime.providerHasCredential(AiProviderConnection.CUSTOM))
            assertEquals("https://legacy.example/responses", fixture.runtime.providerEndpoint(AiProviderConnection.CUSTOM))
        }
    }

    @Test
    fun `failed legacy Custom activation restores the alias original revision and loaded cache`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.runtime.saveAiConfiguration("legacy-model", "https://legacy.example/responses", "https://legacy.example/catalog/models")
            fixture.runtime.saveCredential("openai:api-key", "legacy-key")
            assertEquals(listOf("gpt-4.1"), fixture.runtime.savedProviderModels("openai"))
            val before = fixture.preferences.snapshot()
            val revision = fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM)
            val callbackFailure = IllegalStateException("Synthetic activation failure")
            assertSame(callbackFailure, assertThrows<IllegalStateException> {
                fixture.runtime.selectProviderModel("openai", "gpt-4.1") { throw callbackFailure }
            })
            assertEquals(before, fixture.preferences.snapshot())
            assertEquals(revision, fixture.runtime.providerConnectionRevision(AiProviderConnection.CUSTOM))
            assertEquals("legacy-model", fixture.runtime.providerModel(AiProviderConnection.CUSTOM))
            assertEquals(mapOf("openai:api-key" to "legacy-key"), fixture.credentials.values)
            assertEquals(listOf("gpt-4.1"), fixture.runtime.savedProviderModels("openai"))
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test
    fun `legacy Custom selection preserves exact catalog ids through migration`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.runtime.saveAiConfiguration("legacy-model", "https://legacy.example/responses")
            fixture.runtime.saveCredential("openai:api-key", "legacy-key")
            val model = " gateway/gpt-exact "
            fixture.runtime.cacheProviderModels(AiProviderConnection.CUSTOM, listOf(model))
            fixture.runtime.selectProviderModel("openai", model) {
                assertEquals(model, fixture.runtime.providerModel(AiProviderConnection.CUSTOM))
            }
            assertEquals(model, fixture.runtime.providerModel(AiProviderConnection.CUSTOM))
            assertEquals("legacy-model", fixture.runtime.aiModel())
            assertEquals(listOf(model), fixture.runtime.savedProviderModels("openai"))
            assertTrue(fixture.requests.isEmpty())
        }
    }

    private data class SaveInput(
        val model: String = "valid-model",
        val endpoint: String = "https://valid.example/v1/responses",
        val modelsEndpoint: String = "",
        val replacement: String = "replacement-key",
    )

    private class Fixture(
        val preferences: Preferences = Preferences.userRoot().node("aps-provider-connections-${UUID.randomUUID()}"),
    ) : AutoCloseable {
        private val root = Files.createTempDirectory("aps-provider-connections-")
        val credentials = RecordingCredentials()
        val requests = mutableListOf<AiHttpRequest>()
        var catalogResponse: suspend (AiHttpRequest) -> AiHttpResponse = { AiHttpResponse(200, """{"data":[{"id":"gpt-4.1"}]}""") }
        var runtime: SourceWorkspaceRuntime = createRuntime()
            private set

        fun save(connection: AiProviderConnection, key: String, kind: ImportedCredentialKind = ImportedCredentialKind.API_KEY) {
            runtime.saveProviderConnection(
                connection, connectionModel(connection), connectionEndpoint(connection),
                if (connection == AiProviderConnection.CLAUDE) "" else connectionCatalogEndpoint(connection), kind, key,
            )
        }

        fun reopen() {
            runtime.close()
            runtime = createRuntime()
        }

        private fun createRuntime(): SourceWorkspaceRuntime {
            val repository = SqliteSourceWorkspaceRepository(root.resolve("source.db"))
            return SourceWorkspaceRuntime(
                repository,
                DefaultSourceWorkspaceService(
                    SourceProviderRegistry(listOf(LocalSourceProvider())), repository, ContentAddressedSourceCache(root.resolve("cache")),
                ),
                IndexedSourceResolver(repository),
                SqliteAnalysisSessionRepository(root.resolve("analysis.db")),
                credentials,
                preferences,
                modelCatalogTransport = AiHttpTransport { request ->
                    requests += request
                    if (request.method == AiHttpMethod.GET) {
                        catalogResponse(request)
                    } else {
                        AiHttpResponse(200, """{"output_text":"{\"summary\":\"ok\",\"findings\":[]}"}""")
                    }
                },
            )
        }

        override fun close() {
            runtime.close()
            if (preferences.parent() != null) preferences.removeNode()
            root.toFile().deleteRecursively()
        }
    }

    private class RecordingCredentials : CredentialStore {
        val values = mutableMapOf<String, String>()
        val readKeys = mutableListOf<String>()
        val writeKeys = mutableListOf<String>()
        var failAfterWrite = false
        var remainingWriteFailures = 0
        var ignoreDeletes = false
        var ignoreWrites = false

        override fun read(key: String): String? {
            readKeys += key
            return values[key]
        }

        override fun write(key: String, value: String) {
            writeKeys += key
            if (ignoreWrites) return
            values[key] = value
            if (remainingWriteFailures > 0) {
                remainingWriteFailures--
                error("Synthetic temporary secret write failure")
            }
            check(!failAfterWrite) { "Synthetic secret write failure" }
        }

        override fun delete(key: String) {
            if (ignoreDeletes) return
            values.remove(key)
        }
    }

    private class FaultingPreferences(
        parent: AbstractPreferences? = null,
        name: String = "",
    ) : AbstractPreferences(parent, name) {
        private val values = mutableMapOf<String, String>()
        var failFlushes = 0
        var failWrites = false

        override fun putSpi(key: String, value: String) {
            check(!failWrites) { "Synthetic preference write failure" }
            values[key] = value
        }

        override fun getSpi(key: String): String? = values[key]

        override fun removeSpi(key: String) {
            check(!failWrites) { "Synthetic preference write failure" }
            values.remove(key)
        }

        override fun removeNodeSpi() = values.clear()

        override fun keysSpi(): Array<String> = values.keys.toTypedArray()

        override fun childrenNamesSpi(): Array<String> = emptyArray()

        override fun childSpi(name: String): AbstractPreferences = FaultingPreferences(this, name)

        override fun syncSpi() = Unit

        override fun flushSpi() {
            if (failFlushes > 0) {
                failFlushes--
                throw BackingStoreException("Synthetic preference flush failure")
            }
        }
    }

    private companion object {
        fun connectionModel(connection: AiProviderConnection): String = "${connection.name.lowercase()}-model"

        fun connectionEndpoint(connection: AiProviderConnection): String =
            if (connection == AiProviderConnection.CLAUDE) "https://claude.example" else "https://${connection.name.lowercase()}.example/v1/responses"

        fun connectionCatalogEndpoint(connection: AiProviderConnection): String =
            if (connection == AiProviderConnection.CLAUDE) "https://claude.example/v1/models" else "https://${connection.name.lowercase()}.example/catalog/models"

        fun chatRequest(): AnalysisRequest = AnalysisRequest(
            AnalysisSessionId("provider-connection-test"), ProfilerKind.CHAT,
            AnalysisScope(AnalysisScopeKind.CONVERSATION, "Chat"), emptyList(), emptyList(), "test-prompt", "test-policy", "Hello",
        )

        fun Preferences.snapshot(): Map<String, String?> = keys().associateWith { get(it, null) }
    }
}
