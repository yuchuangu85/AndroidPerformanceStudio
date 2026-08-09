package com.androidperformancestudio.desktop

import com.androidperformancestudio.ai.CredentialStore
import com.androidperformancestudio.ai.InMemoryCredentialStore
import com.androidperformancestudio.ai.MacOsKeychainCredentialStore
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
import com.androidperformancestudio.source.SourceProviderKind
import com.androidperformancestudio.source.SourceResolutionEvidence
import com.androidperformancestudio.source.SourceWorkspace
import com.androidperformancestudio.source.SourceWorkspaceId
import com.androidperformancestudio.source.PerformanceEvidenceId
import com.androidperformancestudio.source.SqliteSourceWorkspaceRepository
import java.nio.file.Path
import java.util.prefs.Preferences

internal class SourceWorkspaceRuntime(
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
