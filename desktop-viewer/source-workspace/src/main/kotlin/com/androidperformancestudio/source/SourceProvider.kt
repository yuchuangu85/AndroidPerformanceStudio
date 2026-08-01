package com.androidperformancestudio.source

public interface SourceProvider {
    public val kind: SourceProviderKind

    public suspend fun resolveRevision(config: SourceProviderConfig): String

    public suspend fun listFiles(
        config: SourceProviderConfig,
        revision: String,
    ): List<ProviderSourceFile>

    public suspend fun readFile(
        config: SourceProviderConfig,
        revision: String,
        relativePath: String,
    ): ByteArray
}

public data class ProviderSourceFile(
    val relativePath: String,
    val sizeBytes: Long,
    val contentHash: String?,
)

public class SourceProviderRegistry(
    providers: List<SourceProvider>,
) {
    private val providersByKind: Map<SourceProviderKind, SourceProvider> = providers.associateBy(SourceProvider::kind)

    public fun providerFor(kind: SourceProviderKind): SourceProvider =
        requireNotNull(providersByKind[kind]) { "No source provider registered for $kind" }
}
