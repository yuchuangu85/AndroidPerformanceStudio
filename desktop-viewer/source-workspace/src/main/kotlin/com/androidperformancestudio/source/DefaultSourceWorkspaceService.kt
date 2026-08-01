@file:Suppress("LongMethod", "MaxLineLength", "MagicNumber")

package com.androidperformancestudio.source

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public interface SourceWorkspaceService {
    public val workspaces: StateFlow<List<SourceWorkspace>>

    public suspend fun add(
        displayName: String,
        config: SourceProviderConfig,
    ): SourceWorkspace

    public suspend fun refresh(id: SourceWorkspaceId): SourceWorkspace

    public fun remove(id: SourceWorkspaceId)

    public fun setAiSourceUploadAllowed(
        id: SourceWorkspaceId,
        allowed: Boolean,
    )

    public suspend fun read(location: SourceLocation): VerifiedSourceContent
}

public class DefaultSourceWorkspaceService(
    private val providers: SourceProviderRegistry,
    private val repository: SourceWorkspaceRepository,
    private val cache: ContentAddressedSourceCache,
    private val indexer: StructuralSourceIndexer = StructuralSourceIndexer(),
) : SourceWorkspaceService {
    private val mutableWorkspaces = MutableStateFlow(repository.workspaces())
    override val workspaces: StateFlow<List<SourceWorkspace>> = mutableWorkspaces.asStateFlow()

    override suspend fun add(
        displayName: String,
        config: SourceProviderConfig,
    ): SourceWorkspace {
        require(displayName.isNotBlank()) { "Source workspace name is required" }
        val workspace = SourceWorkspace(
            id = SourceWorkspaceId(UUID.randomUUID().toString()),
            displayName = displayName.trim(),
            config = config,
            activeSnapshotId = null,
            phase = SourceWorkspacePhase.REGISTERING,
            progress = 0f,
        )
        save(workspace)
        return refresh(workspace.id)
    }

    override suspend fun refresh(id: SourceWorkspaceId): SourceWorkspace {
        val initial = requireNotNull(repository.workspace(id)) { "Unknown source workspace: ${id.value}" }
        val provider = providers.providerFor(initial.config.kind)
        return runCatching {
            save(initial.copy(phase = SourceWorkspacePhase.RESOLVING_REVISION, progress = 0.05f, message = null))
            val revision = provider.resolveRevision(initial.config)
            save(initial.copy(phase = SourceWorkspacePhase.BUILDING_MANIFEST, progress = 0.1f, message = revision.take(12)))
            val providerFiles = provider.listFiles(initial.config, revision)
            val manifestHash = providerFiles.joinToString("\n") { "${it.relativePath}:${it.contentHash.orEmpty()}:${it.sizeBytes}" }.sha256()
            val snapshotId = SourceSnapshotId("${initial.id.value}:$revision:$manifestHash".sha256())
            val sourceFiles = ArrayList<SourceFile>(providerFiles.size)
            val symbols = mutableListOf<SourceSymbol>()
            providerFiles.forEachIndexed { index, providerFile ->
                val content = provider.readFile(initial.config, revision, providerFile.relativePath)
                val contentHash = cache.put(content)
                val sourceFile = SourceFile(
                    snapshotId = snapshotId,
                    relativePath = providerFile.relativePath,
                    language = sourceLanguage(providerFile.relativePath),
                    contentHash = contentHash,
                    sizeBytes = content.size.toLong(),
                )
                sourceFiles += sourceFile
                symbols += indexer.index(sourceFile, content.decodeToString())
                if (index == providerFiles.lastIndex || index % PROGRESS_UPDATE_INTERVAL == 0) {
                    val fraction = if (providerFiles.isEmpty()) 1f else (index + 1f) / providerFiles.size
                    save(
                        initial.copy(
                            phase = SourceWorkspacePhase.INDEXING,
                            progress = 0.1f + fraction * 0.85f,
                            message = "${index + 1}/${providerFiles.size}",
                        ),
                    )
                }
            }
            val snapshot = SourceSnapshot(
                id = snapshotId,
                workspaceId = initial.id,
                immutableRevision = revision.substringBefore("-dirty-"),
                dirtyContentDigest = revision.substringAfter("-dirty-", "").takeIf(String::isNotBlank),
                manifestHash = manifestHash,
                createdAt = Instant.now(),
                indexVersion = System.currentTimeMillis(),
                indexComplete = true,
            )
            repository.saveSnapshot(snapshot, sourceFiles, symbols)
            initial.copy(
                activeSnapshotId = snapshotId,
                phase = SourceWorkspacePhase.READY,
                progress = 1f,
                message = "${sourceFiles.size} files · ${symbols.size} symbols",
            ).also(::save)
        }.getOrElse { failure ->
            initial.copy(
                phase = SourceWorkspacePhase.FAILED,
                progress = 0f,
                message = failure.message ?: failure::class.simpleName,
            ).also(::save)
        }
    }

    override fun remove(id: SourceWorkspaceId) {
        repository.deleteWorkspace(id)
        publish()
    }

    override fun setAiSourceUploadAllowed(
        id: SourceWorkspaceId,
        allowed: Boolean,
    ) {
        val workspace = requireNotNull(repository.workspace(id)) { "Unknown source workspace: ${id.value}" }
        save(workspace.copy(allowAiSourceUpload = allowed))
    }

    override suspend fun read(location: SourceLocation): VerifiedSourceContent {
        val content = cache.read(location.contentHash)
        return VerifiedSourceContent(location, content.decodeToString())
    }

    private fun save(workspace: SourceWorkspace) {
        repository.saveWorkspace(workspace)
        publish()
    }

    private fun publish() {
        mutableWorkspaces.value = repository.workspaces()
    }

    private companion object {
        const val PROGRESS_UPDATE_INTERVAL: Int = 100
    }
}
