package com.androidperformancestudio.desktop

import com.androidperformancestudio.ai.CredentialStore
import com.androidperformancestudio.ai.InMemoryCredentialStore
import com.androidperformancestudio.ai.MacOsKeychainCredentialStore
import com.androidperformancestudio.ai.SqliteAnalysisSessionRepository
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
import com.androidperformancestudio.source.SourceLocation
import com.androidperformancestudio.source.SqliteSourceWorkspaceRepository
import java.nio.file.Path
import java.util.prefs.Preferences

internal class SourceWorkspaceRuntime private constructor(
    val repository: SqliteSourceWorkspaceRepository,
    val service: DefaultSourceWorkspaceService,
    val resolver: IndexedSourceResolver,
    val analysisSessions: SqliteAnalysisSessionRepository,
    private val credentials: CredentialStore,
    private val preferences: Preferences,
) : AutoCloseable {
    private val candidates: MutableMap<String, ResolutionCandidate> = java.util.concurrent.ConcurrentHashMap()

    fun saveCredential(
        key: String,
        value: String,
    ) {
        credentials.write(key, value)
    }

    fun credential(key: String): String? = credentials.read(key)

    fun aiModel(): String = preferences.get(AI_MODEL_KEY, DEFAULT_AI_MODEL)

    fun aiEndpoint(): String = preferences.get(AI_ENDPOINT_KEY, com.androidperformancestudio.ai.OpenAiResponsesClient.DEFAULT_ENDPOINT)

    fun saveAiConfiguration(
        model: String,
        endpoint: String,
    ) {
        require(model.isNotBlank()) { "AI model is required" }
        require(endpoint.startsWith("https://") || endpoint.startsWith("http://localhost")) {
            "AI endpoint must use HTTPS (or localhost for development)"
        }
        preferences.put(AI_MODEL_KEY, model.trim())
        preferences.put(AI_ENDPOINT_KEY, endpoint.trim())
        preferences.flush()
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
        private const val DEFAULT_AI_MODEL = "gpt-5.6-luna"
    }
}
