package com.androidperformancestudio.desktop

import com.androidperformancestudio.ai.CredentialStore
import com.androidperformancestudio.ai.AiAnalysisGateway
import com.androidperformancestudio.ai.AiHttpTransport
import com.androidperformancestudio.ai.ClaudeModelCatalog
import com.androidperformancestudio.ai.InMemoryCredentialStore
import com.androidperformancestudio.ai.JdkAiHttpTransport
import com.androidperformancestudio.ai.MacOsKeychainCredentialStore
import com.androidperformancestudio.ai.OpenAiModelCatalog
import com.androidperformancestudio.ai.OpenAiAnalysisGateway
import com.androidperformancestudio.ai.OpenAiResponsesClient
import com.androidperformancestudio.ai.ImportedCredentialKind
import com.androidperformancestudio.ai.SqliteAnalysisSessionRepository
import com.androidperformancestudio.analysis.AiSourceCandidateReference
import com.androidperformancestudio.source.AospSourceProvider
import com.androidperformancestudio.source.ContentAddressedSourceCache
import com.androidperformancestudio.source.DefaultSourceWorkspaceService
import com.androidperformancestudio.source.GitHubSourceProvider
import com.androidperformancestudio.source.IndexedSourceResolver
import com.androidperformancestudio.source.JdkSourceHttpTransport
import com.androidperformancestudio.source.LocalSourceProvider
import com.androidperformancestudio.source.SourceCredentialProvider
import com.androidperformancestudio.source.SourceProviderRegistry
import com.androidperformancestudio.source.ResolutionCandidate
import com.androidperformancestudio.source.ResolutionConfidence
import com.androidperformancestudio.source.SourceLocation
import com.androidperformancestudio.source.SourceRange
import com.androidperformancestudio.source.SourceProviderKind
import com.androidperformancestudio.source.SourceResolutionEvidence
import com.androidperformancestudio.source.SourceWorkspace
import com.androidperformancestudio.source.SourceWorkspaceId
import com.androidperformancestudio.source.PerformanceEvidenceId
import com.androidperformancestudio.source.SqliteSourceWorkspaceRepository
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.prefs.Preferences

internal class AiModelCredentialException(val endpointMismatch: Boolean) :
    IllegalStateException(if (endpointMismatch) "E_AI_CREDENTIAL_ENDPOINT" else "E_AI_CREDENTIAL_REQUIRED")

internal class SourceWorkspaceRuntime(
    val repository: SqliteSourceWorkspaceRepository,
    val service: DefaultSourceWorkspaceService,
    val resolver: IndexedSourceResolver,
    val analysisSessions: SqliteAnalysisSessionRepository,
    private val credentials: CredentialStore,
    private val preferences: Preferences,
    private val modelCatalogTransport: AiHttpTransport = JdkAiHttpTransport(),
) : AutoCloseable {
    private val candidates: MutableMap<String, ResolutionCandidate> = java.util.concurrent.ConcurrentHashMap()
    private val providerConnectionLock = Any()
    private val blockedProviderConnections = mutableMapOf<AiProviderConnection, String>()
    private val providerModelCatalogs = mutableMapOf<AiProviderConnection, ProviderModelCatalog>()

    private data class ProviderCatalogConfiguration(
        val revision: String,
        val endpoint: String,
        val modelsEndpoint: String,
        val kind: ImportedCredentialKind,
    )

    private data class ProviderModelCatalog(
        val configuration: ProviderCatalogConfiguration,
        val models: List<String>,
    )

    fun saveCredential(
        key: String,
        value: String,
    ) {
        synchronized(providerConnectionLock) { credentials.write(key, value) }
    }

    fun credential(key: String): String? = credentials.read(key)

    fun aiModel(): String = preferences.get(AI_MODEL_KEY, DEFAULT_AI_MODEL)

    fun aiEndpoint(): String = preferences.get(AI_ENDPOINT_KEY, com.androidperformancestudio.ai.OpenAiResponsesClient.DEFAULT_ENDPOINT)

    fun aiModelsEndpoint(): String = preferences.get(AI_MODELS_ENDPOINT_KEY, "")

    fun claudeModel(): String = preferences.get("claude.model", "")

    fun claudeEndpoint(): String = preferences.get("claude.endpoint", "https://api.anthropic.com")

    fun claudeCredentialKind(): ImportedCredentialKind =
        runCatching { ImportedCredentialKind.valueOf(preferences.get("claude.credentialKind", "API_KEY")) }
            .getOrDefault(ImportedCredentialKind.API_KEY)

    fun claudeCredentialReference(kind: ImportedCredentialKind = claudeCredentialKind()): String =
        if (kind == ImportedCredentialKind.BEARER_TOKEN) "claude:auth-token" else "claude:api-key"

    fun hasClaudeCredential(
        kind: ImportedCredentialKind = claudeCredentialKind(),
        endpoint: String = claudeEndpoint(),
    ): Boolean = synchronized(providerConnectionLock) {
        !providerConnectionBlocked(AiProviderConnection.CLAUDE) &&
            preferences.get("claude.credentialEndpoint.${kind.name}", null) == endpoint.trim() &&
            !credential(claudeCredentialReference(kind)).isNullOrBlank()
    }

    fun saveClaudeCredential(value: String, endpoint: String, kind: ImportedCredentialKind) {
        synchronized(providerConnectionLock) {
            validateAiConfiguration("claude", endpoint)
            require(value.isNotBlank() && value.length <= 8192 && value.none(Char::isISOControl)) { "Invalid credential" }
            // Invalidate the old binding before replacing its secret, including failed writes.
            preferences.remove("claude.credentialEndpoint.${kind.name}")
            try {
                saveCredential(claudeCredentialReference(kind), value)
                preferences.put("claude.credentialEndpoint.${kind.name}", endpoint.trim())
                preferences.flush()
            } catch (failure: Exception) {
                preferences.remove("claude.credentialEndpoint.${kind.name}")
                throw failure
            }
        }
    }

    fun claudeEnvironment(expectedRevision: String? = null): Map<String, String> {
        synchronized(providerConnectionLock) {
            requireProviderConnectionRevision(AiProviderConnection.CLAUDE, expectedRevision)
            val kind = claudeCredentialKind()
            val endpoint = claudeEndpoint()
            if (providerConnectionBlocked(AiProviderConnection.CLAUDE)) return mapOf("ANTHROPIC_BASE_URL" to endpoint)
            if (preferences.get("claude.credentialEndpoint.${kind.name}", null) != endpoint) {
                // A saved, incomplete connection must fail instead of borrowing process credentials.
                return if (preferences.get("claude.endpoint", null) != null) mapOf("ANTHROPIC_BASE_URL" to endpoint) else emptyMap()
            }
            val key = credential(claudeCredentialReference(kind))?.takeIf(String::isNotBlank)
                ?: return mapOf("ANTHROPIC_BASE_URL" to endpoint)
            val name = if (kind == ImportedCredentialKind.BEARER_TOKEN) "ANTHROPIC_AUTH_TOKEN" else "ANTHROPIC_API_KEY"
            return mapOf(name to key, "ANTHROPIC_BASE_URL" to endpoint)
        }
    }

    fun saveClaudeConfiguration(
        model: String,
        endpoint: String,
        kind: ImportedCredentialKind,
    ) {
        synchronized(providerConnectionLock) {
            validateAiConfiguration(model, endpoint)
            try {
                preferences.put("claude.model", model.trim())
                preferences.put("claude.endpoint", endpoint.trim())
                preferences.put("claude.credentialKind", kind.name)
                preferences.flush()
            } catch (failure: Exception) {
                preferences.remove("claude.credentialEndpoint.${kind.name}")
                throw failure
            }
        }
    }

    suspend fun aiModels(
        apiKeyOverride: String,
        endpoint: String,
        modelsEndpointOverride: String? = null,
    ): List<String> {
        return providerModels(AiProviderConnection.CODEX, apiKeyOverride, endpoint, modelsEndpointOverride)
    }

    suspend fun claudeModels(
        apiKeyOverride: String,
        endpoint: String,
        kind: ImportedCredentialKind,
    ): List<String> {
        val requestedEndpoint = endpoint.trim()
        ClaudeModelCatalog.validateEndpoint(requestedEndpoint)
        val apiKey = apiKeyOverride.trim().ifBlank {
            synchronized(providerConnectionLock) {
                if (!credentialMatches(AiProviderConnection.CLAUDE, requestedEndpoint, kind)) {
                    throw AiModelCredentialException(endpointMismatch = true)
                }
                credential(claudeCredentialReference(kind)).orEmpty()
            }
        }
        if (apiKey.isBlank()) throw AiModelCredentialException(endpointMismatch = false)
        return ClaudeModelCatalog(apiKey, requestedEndpoint, kind, modelCatalogTransport).listModels()
    }

    fun saveAiConfiguration(
        model: String,
        endpoint: String,
        modelsEndpoint: String? = null,
    ) {
        synchronized(providerConnectionLock) {
            val catalogEndpoint = modelsEndpoint ?: if (endpoint.trim() == aiEndpoint().trim()) aiModelsEndpoint() else ""
            validateAiConfiguration(model, endpoint, catalogEndpoint)
            preferences.put(AI_MODEL_KEY, model.trim())
            preferences.put(AI_ENDPOINT_KEY, endpoint.trim())
            preferences.put(AI_MODELS_ENDPOINT_KEY, catalogEndpoint.trim())
            preferences.flush()
        }
    }

    fun providerModel(connection: AiProviderConnection): String = synchronized(providerConnectionLock) {
        when (connection) {
            AiProviderConnection.CODEX -> aiModel()
            AiProviderConnection.CLAUDE -> claudeModel()
            AiProviderConnection.CUSTOM -> if (customConfigured()) preferences.get("custom.model", DEFAULT_AI_MODEL) else aiModel()
        }
    }

    fun providerEndpoint(connection: AiProviderConnection): String = synchronized(providerConnectionLock) {
        when (connection) {
            AiProviderConnection.CODEX -> aiEndpoint()
            AiProviderConnection.CLAUDE -> claudeEndpoint()
            AiProviderConnection.CUSTOM ->
                if (customConfigured()) preferences.get("custom.endpoint", OpenAiResponsesClient.DEFAULT_ENDPOINT) else aiEndpoint()
        }
    }

    fun providerModelsEndpoint(connection: AiProviderConnection): String = synchronized(providerConnectionLock) {
        when (connection) {
            AiProviderConnection.CODEX -> aiModelsEndpoint()
            AiProviderConnection.CLAUDE -> ""
            AiProviderConnection.CUSTOM -> if (customConfigured()) preferences.get("custom.modelsEndpoint", "") else aiModelsEndpoint()
        }
    }

    fun providerCredentialKind(connection: AiProviderConnection): ImportedCredentialKind = synchronized(providerConnectionLock) {
        if (connection == AiProviderConnection.CLAUDE) return@synchronized claudeCredentialKind()
        val prefix = if (connection == AiProviderConnection.CUSTOM && customConfigured()) "custom." else ""
        runCatching { ImportedCredentialKind.valueOf(preferences.get("${prefix}credentialKind", "API_KEY")) }
            .getOrDefault(ImportedCredentialKind.API_KEY)
    }

    fun providerConnectionRevision(connection: AiProviderConnection): String = synchronized(providerConnectionLock) {
        blockedProviderConnections[connection]?.let { return@synchronized it }
        if (connection == AiProviderConnection.CUSTOM && !customConfigured()) {
            blockedProviderConnections[AiProviderConnection.CODEX]?.let { return@synchronized it }
        }
        val prefix = if (connection == AiProviderConnection.CUSTOM && !customConfigured()) "" else providerPreferencePrefix(connection)
        preferences.get("${prefix}connectionRevision", null) ?: run {
            val identity = "${connection.providerId}\n${providerEndpoint(connection).trim()}\n${providerCredentialKind(connection).name}"
            "legacy:" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }

    fun providerHasCredential(
        connection: AiProviderConnection,
        endpoint: String = providerEndpoint(connection),
        kind: ImportedCredentialKind = providerCredentialKind(connection),
    ): Boolean = synchronized(providerConnectionLock) {
        credentialMatches(connection, endpoint.trim(), kind) &&
            !credential(providerCredentialReference(connection, kind)).isNullOrBlank()
    }

    suspend fun providerModels(
        connection: AiProviderConnection,
        apiKeyOverride: String,
        endpoint: String,
        modelsEndpointOverride: String? = null,
        kind: ImportedCredentialKind = providerCredentialKind(connection),
    ): List<String> {
        if (connection == AiProviderConnection.CLAUDE) return claudeModels(apiKeyOverride, endpoint, kind)
        val requestedEndpoint = endpoint.trim()
        val (apiKey, catalogEndpoint) = synchronized(providerConnectionLock) {
            val catalogEndpoint = modelsEndpointOverride
                ?: if (requestedEndpoint == providerEndpoint(connection).trim()) providerModelsEndpoint(connection) else ""
            OpenAiModelCatalog.validateEndpoints(requestedEndpoint, catalogEndpoint)
            val apiKey = apiKeyOverride.trim().takeIf(String::isNotEmpty)
                ?: providerCredential(connection, requestedEndpoint, kind)
            apiKey to catalogEndpoint
        }
        return OpenAiModelCatalog(apiKey, requestedEndpoint, modelCatalogTransport, modelsEndpointOverride = catalogEndpoint).listModels()
    }

    fun cacheProviderModels(connection: AiProviderConnection, models: List<String>, expectedRevision: String? = null) {
        synchronized(providerConnectionLock) {
            requireProviderConnectionRevision(connection, expectedRevision)
            check(!providerConnectionBlocked(connection)) { "E_PROVIDER_CONNECTION_CHANGED" }
            providerModelCatalogs[connection] = ProviderModelCatalog(providerCatalogConfiguration(connection), copyProviderModels(models))
        }
    }

    suspend fun savedProviderModels(providerId: String, refresh: Boolean = false): List<String> {
        val connection = savedProviderConnection(providerId)
        val (configuration, key) = synchronized(providerConnectionLock) {
            val configuration = providerCatalogConfiguration(connection)
            check(!providerConnectionBlocked(connection)) { "E_PROVIDER_CONNECTION_CHANGED" }
            if (!refresh) {
                providerModelCatalogs[connection]?.takeIf { it.configuration == configuration }?.let { return it.models.toList() }
            }
            // Capture the secret with its routing metadata, never after releasing the connection lock.
            configuration to providerCredential(connection, configuration.endpoint, configuration.kind)
        }
        val models = providerModels(connection, key, configuration.endpoint, configuration.modelsEndpoint, configuration.kind)
        return synchronized(providerConnectionLock) {
            check(!providerConnectionBlocked(connection) && providerCatalogConfiguration(connection) == configuration) {
                "E_PROVIDER_CONNECTION_CHANGED"
            }
            val copiedModels = copyProviderModels(models)
            providerModelCatalogs[connection] = ProviderModelCatalog(configuration, copiedModels)
            copiedModels.toList()
        }
    }

    fun selectProviderModel(providerId: String, model: String, apply: () -> Unit) {
        val connection = savedProviderConnection(providerId)
        synchronized(providerConnectionLock) {
            require(model.isNotBlank() && model.length <= 128 && model.none(Char::isISOControl)) { "AI model is required" }
            check(!providerConnectionBlocked(connection)) { "E_PROVIDER_CONNECTION_CHANGED" }
            val configuration = providerCatalogConfiguration(connection)
            val catalog = providerModelCatalogs[connection]?.takeIf { it.configuration == configuration }
            check(catalog != null && model in catalog.models) { "E_PROVIDER_MODEL_NOT_LOADED" }
            if (connection == AiProviderConnection.CUSTOM && !customConfigured()) {
                // Copy the legacy connection before selecting its independent model; never modify Codex.
                saveAndApplyProviderConnection(
                    connection, model, configuration.endpoint, configuration.modelsEndpoint, configuration.kind, null,
                ) {
                    if (model != model.trim()) {
                        preferences.put("custom.model", model)
                        preferences.flush()
                    }
                    apply()
                }
                providerModelCatalogs[connection] = ProviderModelCatalog(providerCatalogConfiguration(connection), catalog.models)
                return
            }
            val modelKey = "${providerPreferencePrefix(connection)}model"
            val previousModel = preferences.get(modelKey, null)
            try {
                preferences.put(modelKey, model)
                preferences.flush()
                apply()
            } catch (failure: Exception) {
                val restored = runCatching {
                    if (previousModel == null) preferences.remove(modelKey) else preferences.put(modelKey, previousModel)
                    preferences.flush()
                    check(preferences.get(modelKey, null) == previousModel)
                }.isSuccess
                if (!restored) invalidateProviderConnections(setOf(connection))
                throw failure
            }
        }
    }

    private fun savedProviderConnection(providerId: String): AiProviderConnection =
        AiProviderConnection.entries.firstOrNull { it.providerId == providerId }
            ?: throw IllegalArgumentException("E_PROVIDER_CONNECTION_UNKNOWN")

    private fun providerCatalogConfiguration(connection: AiProviderConnection): ProviderCatalogConfiguration =
        ProviderCatalogConfiguration(
            providerConnectionRevision(connection), providerEndpoint(connection).trim(), providerModelsEndpoint(connection).trim(),
            providerCredentialKind(connection),
        )

    private fun copyProviderModels(models: List<String>): List<String> {
        require(models.size <= 10_000) { "Invalid model catalog" }
        val copy = models.toList()
        require(copy.all { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) }) { "Invalid model catalog" }
        return copy
    }

    fun saveProviderConnection(
        connection: AiProviderConnection,
        model: String,
        endpoint: String,
        modelsEndpoint: String,
        kind: ImportedCredentialKind,
        replacementCredential: String?,
    ) {
        synchronized(providerConnectionLock) {
            val savedModel = model.trim()
            val savedEndpoint = endpoint.trim()
            val savedModelsEndpoint = modelsEndpoint.trim()
            validateAiConfiguration(savedModel, savedEndpoint, savedModelsEndpoint)
            val replacement = validatedReplacementCredential(replacementCredential)
            val existingCredential = if (replacement == null) providerCredential(connection, savedEndpoint, kind) else null
            if (connection == AiProviderConnection.CODEX) migrateLegacyCustomConnection()
            if (connection == AiProviderConnection.CLAUDE) {
                try {
                    replacement?.let { saveClaudeCredential(it, savedEndpoint, kind) }
                    saveClaudeConfiguration(savedModel, savedEndpoint, kind)
                    publishProviderConnectionRevision(connection)
                    preferences.flush()
                    blockedProviderConnections.remove(connection)
                } catch (failure: Exception) {
                    invalidateProviderConnections(setOf(connection))
                    throw failure
                }
                return
            }
            val custom = connection == AiProviderConnection.CUSTOM
            val migrating = custom && !customConfigured()
            val prefix = if (custom) "custom." else ""
            val binding = "${prefix}credentialEndpoint"
            // A failed secret or preference write must not restore the legacy unbound-key fallback.
            preferences.put(binding, "")
            if (custom) preferences.putBoolean("custom.configured", true)
            try {
                val credentialToWrite = replacement ?: existingCredential.takeIf { migrating }
                credentialToWrite?.let { credentials.write(if (custom) "custom:api-key" else "openai:api-key", it) }
                preferences.put("${prefix}model", savedModel)
                preferences.put("${prefix}endpoint", savedEndpoint)
                preferences.put("${prefix}modelsEndpoint", savedModelsEndpoint)
                preferences.put("${prefix}credentialKind", kind.name)
                preferences.put(binding, savedEndpoint)
                publishProviderConnectionRevision(connection)
                preferences.flush()
                blockedProviderConnections.remove(connection)
            } catch (failure: Exception) {
                invalidateProviderConnections(setOf(connection))
                throw failure
            }
        }
    }

    // The caller owns rollback of its activation store; this guard owns only connection state.
    fun saveAndApplyProviderConnection(
        connection: AiProviderConnection,
        model: String,
        endpoint: String,
        modelsEndpoint: String,
        kind: ImportedCredentialKind,
        replacementCredential: String?,
        apply: () -> Unit,
    ) {
        synchronized(providerConnectionLock) {
            validateAiConfiguration(model.trim(), endpoint.trim(), modelsEndpoint.trim())
            validatedReplacementCredential(replacementCredential)
            if (replacementCredential == null && !providerHasCredential(connection, endpoint, kind)) {
                providerCredential(connection, endpoint.trim(), kind)
            }
            val affected = if (connection == AiProviderConnection.CODEX && !customConfigured()) {
                setOf(connection, AiProviderConnection.CUSTOM)
            } else {
                setOf(connection)
            }
            val savedPreferences = affected.flatMap(::providerPreferenceKeys).associateWith { preferences.get(it, null) }
            val savedCredentials = affected.flatMap(::providerCredentialReferences).distinct().associateWith(credentials::read)
            val previouslyBlocked = blockedProviderConnections.filterKeys { it in affected }
            try {
                saveProviderConnection(connection, model, endpoint, modelsEndpoint, kind, replacementCredential)
                apply()
            } catch (failure: Exception) {
                affected.forEach { blockedProviderConnections[it] = UUID.randomUUID().toString() }
                var restored = true
                savedCredentials.forEach { (reference, value) ->
                    try {
                        if (value == null) credentials.delete(reference) else credentials.write(reference, value)
                        if (credentials.read(reference) != value) restored = false
                    } catch (_: Exception) {
                        restored = false
                    }
                }
                savedPreferences.forEach { (key, value) ->
                    try {
                        if (value == null) preferences.remove(key) else preferences.put(key, value)
                    } catch (_: Exception) {
                        restored = false
                    }
                }
                try {
                    preferences.flush()
                } catch (_: Exception) {
                    restored = false
                }
                if (restored) {
                    affected.forEach(blockedProviderConnections::remove)
                    blockedProviderConnections.putAll(previouslyBlocked)
                } else {
                    invalidateProviderConnections(affected)
                }
                throw failure
            }
        }
    }

    private fun migrateLegacyCustomConnection() {
        if (customConfigured()) return
        val model = aiModel()
        val endpoint = aiEndpoint()
        val modelsEndpoint = aiModelsEndpoint()
        val kind = providerCredentialKind(AiProviderConnection.CODEX)
        val validConfiguration = runCatching { validateAiConfiguration(model, endpoint, modelsEndpoint) }.isSuccess
        val key = if (validConfiguration && credentialMatches(AiProviderConnection.CODEX, endpoint.trim(), kind)) {
            credential("openai:api-key")?.takeIf { runCatching { validatedReplacementCredential(it) }.isSuccess }
        } else {
            null
        }
        if (key != null) {
            saveProviderConnection(AiProviderConnection.CUSTOM, model, endpoint, modelsEndpoint, kind, key)
            return
        }
        // Freeze even incomplete legacy metadata so a Codex save cannot retarget the Custom alias.
        try {
            preferences.putBoolean("custom.configured", true)
            preferences.put("custom.credentialEndpoint", "")
            preferences.put("custom.model", model)
            preferences.put("custom.endpoint", endpoint)
            preferences.put("custom.modelsEndpoint", modelsEndpoint)
            preferences.put("custom.credentialKind", kind.name)
            publishProviderConnectionRevision(AiProviderConnection.CUSTOM)
            preferences.flush()
            blockedProviderConnections.remove(AiProviderConnection.CUSTOM)
        } catch (failure: Exception) {
            invalidateProviderConnections(setOf(AiProviderConnection.CUSTOM))
            throw failure
        }
    }

    private fun validatedReplacementCredential(value: String?): String? = value?.trim()?.also {
        require(it.isNotBlank() && it.length <= 8192 && it.none(Char::isISOControl)) { "Invalid credential" }
    }

    private fun publishProviderConnectionRevision(connection: AiProviderConnection) {
        val prefix = providerPreferencePrefix(connection)
        preferences.put("${prefix}connectionRevision", UUID.randomUUID().toString())
        preferences.remove("${prefix}connectionBlocked")
    }

    private fun invalidateProviderConnections(connections: Set<AiProviderConnection>) {
        connections.forEach { connection ->
            val prefix = providerPreferencePrefix(connection)
            val revision = UUID.randomUUID().toString()
            blockedProviderConnections[connection] = revision
            val invalid = mapOf(
                "${prefix}connectionBlocked" to "true",
                "${prefix}connectionRevision" to revision,
            ) + if (connection == AiProviderConnection.CLAUDE) {
                ImportedCredentialKind.entries.associate { "claude.credentialEndpoint.${it.name}" to "" }
            } else {
                mapOf("${prefix}credentialEndpoint" to "")
            }
            invalid.forEach { (key, value) -> runCatching { preferences.put(key, value) } }
        }
        runCatching { preferences.flush() }
    }

    private fun providerConnectionBlocked(connection: AiProviderConnection): Boolean =
        connection in blockedProviderConnections || preferences.getBoolean("${providerPreferencePrefix(connection)}connectionBlocked", false) ||
            connection == AiProviderConnection.CUSTOM && !customConfigured() && providerConnectionBlocked(AiProviderConnection.CODEX)

    private fun providerPreferencePrefix(connection: AiProviderConnection): String = when (connection) {
        AiProviderConnection.CODEX -> ""
        AiProviderConnection.CLAUDE -> "claude."
        AiProviderConnection.CUSTOM -> "custom."
    }

    private fun providerPreferenceKeys(connection: AiProviderConnection): List<String> {
        val prefix = providerPreferencePrefix(connection)
        val common = listOf("model", "endpoint", "modelsEndpoint", "credentialKind", "connectionRevision", "connectionBlocked")
            .map { "$prefix$it" }
        return common + if (connection == AiProviderConnection.CLAUDE) {
            ImportedCredentialKind.entries.map { "claude.credentialEndpoint.${it.name}" }
        } else {
            listOf("${prefix}credentialEndpoint") + if (connection == AiProviderConnection.CUSTOM) listOf("custom.configured") else emptyList()
        }
    }

    private fun providerCredentialReferences(connection: AiProviderConnection): List<String> = when (connection) {
        AiProviderConnection.CODEX -> listOf("openai:api-key")
        AiProviderConnection.CLAUDE -> ImportedCredentialKind.entries.map(::claudeCredentialReference)
        AiProviderConnection.CUSTOM -> listOf("custom:api-key")
    }

    fun providerGateway(
        connection: AiProviderConnection,
        model: String,
        expectedRevision: String? = null,
    ): AiAnalysisGateway = synchronized(providerConnectionLock) {
        require(connection != AiProviderConnection.CLAUDE) { "Claude uses its own provider environment" }
        requireProviderConnectionRevision(connection, expectedRevision)
        val endpoint = providerEndpoint(connection)
        validateAiConfiguration(model, endpoint, providerModelsEndpoint(connection))
        val apiKey = providerCredential(connection, endpoint.trim(), providerCredentialKind(connection))
        OpenAiAnalysisGateway(OpenAiResponsesClient(apiKey, model.trim(), endpoint.trim(), modelCatalogTransport))
    }

    private fun requireProviderConnectionRevision(connection: AiProviderConnection, expectedRevision: String?) {
        check(expectedRevision == null || providerConnectionRevision(connection) == expectedRevision) { "E_PROVIDER_CONNECTION_CHANGED" }
    }

    private fun customConfigured(): Boolean =
        preferences.getBoolean("custom.configured", false) || preferences.get("custom.model", null) != null ||
            preferences.get("custom.endpoint", null) != null || preferences.get("custom.credentialEndpoint", null) != null

    private fun providerCredentialReference(connection: AiProviderConnection, kind: ImportedCredentialKind): String =
        when (connection) {
            AiProviderConnection.CODEX -> "openai:api-key"
            AiProviderConnection.CLAUDE -> claudeCredentialReference(kind)
            AiProviderConnection.CUSTOM -> if (customConfigured()) "custom:api-key" else "openai:api-key"
        }

    private fun credentialMatches(connection: AiProviderConnection, endpoint: String, kind: ImportedCredentialKind): Boolean {
        if (endpoint.isBlank() || providerConnectionBlocked(connection)) return false
        if (connection == AiProviderConnection.CLAUDE) {
            return preferences.get("claude.credentialEndpoint.${kind.name}", null) == endpoint
        }
        if (endpoint != providerEndpoint(connection).trim() || kind != providerCredentialKind(connection)) return false
        val prefix = if (connection == AiProviderConnection.CUSTOM && customConfigured()) "custom." else ""
        val binding = preferences.get("${prefix}credentialEndpoint", null)
        return binding == endpoint || binding == null && prefix.isEmpty()
    }

    private fun providerCredential(connection: AiProviderConnection, endpoint: String, kind: ImportedCredentialKind): String {
        if (!credentialMatches(connection, endpoint, kind)) throw AiModelCredentialException(endpointMismatch = true)
        return credential(providerCredentialReference(connection, kind))?.takeIf(String::isNotBlank)
            ?: throw AiModelCredentialException(endpointMismatch = false)
    }

    fun validateAiConfiguration(model: String, endpoint: String, modelsEndpoint: String? = null) {
        require(model.isNotBlank() && model.length <= 128 && model.none(Char::isISOControl)) { "AI model is required" }
        val uri = runCatching { java.net.URI(endpoint) }.getOrNull()
        require(uri != null && uri.userInfo == null && uri.query == null && uri.fragment == null && !uri.host.isNullOrBlank() &&
            (uri.port == -1 || uri.port in 1..65535) && !uri.rawAuthority.endsWith(':') &&
            (uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "::1", "[::1]"))) {
            "AI endpoint must use HTTPS (or an exact loopback host for development)"
        }
        OpenAiModelCatalog.validateEndpoints(endpoint.trim(), modelsEndpoint)
    }

    fun rememberCandidates(values: Iterable<ResolutionCandidate>) {
        val candidateList = values.toList()
        candidateList.forEach { candidate -> candidates[candidate.id.value] = candidate }
        repository.saveCandidates(candidateList)
    }

    fun candidate(id: String): ResolutionCandidate? {
        candidates[id]?.let { return it }
        val persisted = repository.candidate(com.androidperformancestudio.source.ResolutionCandidateId(id)) ?: return null
        candidates[id] = persisted
        return persisted
    }

    fun candidate(location: SourceLocation): ResolutionCandidate? = candidates.values.firstOrNull { it.location == location }

    fun rebindableLocalWorkspaces(reference: AiSourceCandidateReference): List<SourceWorkspace> =
        repository.workspaces().filter { archivedLocation(reference, it) != null }

    fun rebindArchivedSource(
        reference: AiSourceCandidateReference,
        workspaceId: SourceWorkspaceId,
    ): SourceLocation? = repository.workspace(workspaceId)?.let { archivedLocation(reference, it) }

    private fun archivedLocation(
        reference: AiSourceCandidateReference,
        workspace: SourceWorkspace,
    ): SourceLocation? {
        val expectedHash = reference.contentHash ?: return null
        val expectedRepository = reference.repositoryIdentity ?: return null
        val expectedRevision = reference.revision ?: return null
        if (reference.providerKind != SourceProviderKind.LOCAL.name) return null
        if (workspace.config.kind != SourceProviderKind.LOCAL || workspace.displayName != expectedRepository) return null
        val snapshotId = workspace.activeSnapshotId ?: return null
        if (repository.snapshot(snapshotId)?.immutableRevision != expectedRevision) return null
        val file = repository.files(snapshotId).firstOrNull {
            it.relativePath == reference.relativePath && it.contentHash == expectedHash
        } ?: return null
        return SourceLocation(
            workspace.id,
            snapshotId,
            file.relativePath,
            reference.startLine?.let { start ->
                com.androidperformancestudio.source.SourceRange(start, endLine = reference.endLine ?: start)
            },
            file.contentHash,
        )
    }

    suspend fun resolveComposeSources(fileName: String, packageHash: Int, line: Int): List<ResolutionCandidate> {
        val snapshotIds = repository.workspaces()
            .filter { it.config.kind == SourceProviderKind.LOCAL }
            .mapNotNull { it.activeSnapshotId }
            .toSet()
        val resolved = resolver.resolve(
            snapshotIds,
            listOf(
                SourceResolutionEvidence.SourceFileLine(
                    id = PerformanceEvidenceId("compose:$packageHash:$fileName:$line"),
                    fileName = fileName,
                    packageHash = packageHash,
                    line = line,
                ),
            ),
        )
        val navigable = resolved.filter { it.confidence != ResolutionConfidence.WEAK }
        rememberCandidates(navigable)
        return navigable
    }

    fun resolveSourcePath(path: String, line: Int): SourceLocation? {
        val normalized = path.replace('\\', '/').removePrefix("./")
        val matches = repository.workspaces().mapNotNull { workspace ->
            val snapshotId = workspace.activeSnapshotId ?: return@mapNotNull null
            repository.files(snapshotId).singleOrNull { file ->
                file.relativePath == normalized || normalized.endsWith("/${file.relativePath}")
            }?.let { file ->
                SourceLocation(
                    workspace.id,
                    snapshotId,
                    file.relativePath,
                    SourceRange(line.coerceAtLeast(1)),
                    file.contentHash,
                )
            }
        }
        return matches.singleOrNull()
    }

    override fun close() {
        analysisSessions.close()
        repository.close()
    }

    companion object {
        fun desktop(): SourceWorkspaceRuntime {
            val applicationDirectory =
                Path.of(System.getProperty("user.home"), ".android-performance-studio")
            val repository = SqliteSourceWorkspaceRepository(applicationDirectory.resolve("source-workspaces.db"))
            val analysisSessions = SqliteAnalysisSessionRepository(applicationDirectory.resolve("analysis-sessions.db"))
            val cache = ContentAddressedSourceCache(applicationDirectory.resolve("source-cache"))
            val credentialStore: CredentialStore =
                if (System.getProperty("os.name").contains("mac", ignoreCase = true)) {
                    MacOsKeychainCredentialStore()
                } else {
                    InMemoryCredentialStore()
                }
            val transport = JdkSourceHttpTransport()
            val registry =
                SourceProviderRegistry(
                    listOf(
                        LocalSourceProvider(),
                        GitHubSourceProvider(
                            transport = transport,
                            credentials = SourceCredentialProvider(credentialStore::read),
                        ),
                        AospSourceProvider(transport),
                    ),
                )
            val service = DefaultSourceWorkspaceService(registry, repository, cache)
            return SourceWorkspaceRuntime(
                repository = repository,
                service = service,
                resolver = IndexedSourceResolver(repository),
                analysisSessions = analysisSessions,
                credentials = credentialStore,
                preferences = Preferences.userRoot().node("com/androidperformancestudio/ai"),
            )
        }

        private const val AI_MODEL_KEY = "model"
        private const val AI_ENDPOINT_KEY = "endpoint"
        private const val AI_MODELS_ENDPOINT_KEY = "modelsEndpoint"
        private const val DEFAULT_AI_MODEL = "gpt-5.6-luna"
    }
}
