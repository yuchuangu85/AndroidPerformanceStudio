package com.androidperformancestudio.source

import java.nio.file.Path
import java.time.Instant

@JvmInline
public value class SourceWorkspaceId(public val value: String)

@JvmInline
public value class SourceSnapshotId(public val value: String)

@JvmInline
public value class PerformanceEvidenceId(public val value: String)

@JvmInline
public value class ResolutionCandidateId(public val value: String)

@JvmInline
public value class BuildEvidenceBundleId(public val value: String)

public enum class SourceProviderKind {
    LOCAL,
    GITHUB,
    AOSP,
}

public sealed interface SourceProviderConfig {
    public val kind: SourceProviderKind

    public data class Local(
        val root: Path,
    ) : SourceProviderConfig {
        override val kind: SourceProviderKind = SourceProviderKind.LOCAL
    }

    public data class GitHub(
        val owner: String,
        val repository: String,
        val ref: String,
        val credentialKey: String? = null,
    ) : SourceProviderConfig {
        override val kind: SourceProviderKind = SourceProviderKind.GITHUB
    }

    public data class Aosp(
        val project: String,
        val ref: String,
    ) : SourceProviderConfig {
        override val kind: SourceProviderKind = SourceProviderKind.AOSP
    }
}

public enum class SourceWorkspacePhase {
    REGISTERING,
    RESOLVING_REVISION,
    BUILDING_MANIFEST,
    INDEXING,
    READY,
    PARTIAL,
    FAILED,
}

public data class SourceWorkspace(
    val id: SourceWorkspaceId,
    val displayName: String,
    val config: SourceProviderConfig,
    val activeSnapshotId: SourceSnapshotId?,
    val phase: SourceWorkspacePhase,
    val progress: Float,
    val message: String? = null,
    val allowAiSourceUpload: Boolean = false,
)

public data class SourceSnapshot(
    val id: SourceSnapshotId,
    val workspaceId: SourceWorkspaceId,
    val immutableRevision: String,
    val dirtyContentDigest: String?,
    val manifestHash: String,
    val createdAt: Instant,
    val indexVersion: Long,
    val indexComplete: Boolean,
)

public enum class SourceLanguage {
    KOTLIN,
    JAVA,
    XML,
    C,
    CPP,
    OTHER,
}

public data class SourceFile(
    val snapshotId: SourceSnapshotId,
    val relativePath: String,
    val language: SourceLanguage,
    val contentHash: String,
    val sizeBytes: Long,
)

public enum class SourceSymbolKind {
    PACKAGE,
    TYPE,
    FUNCTION,
    METHOD,
    RESOURCE,
    NATIVE_SYMBOL,
}

public data class SourceSymbol(
    val snapshotId: SourceSnapshotId,
    val relativePath: String,
    val kind: SourceSymbolKind,
    val qualifiedName: String,
    val signature: String?,
    val startLine: Int,
    val endLine: Int,
)

public data class SourceRange(
    val startLine: Int,
    val startColumn: Int = 1,
    val endLine: Int = startLine,
    val endColumn: Int = 1,
)

public data class SourceLocation(
    val workspaceId: SourceWorkspaceId,
    val snapshotId: SourceSnapshotId,
    val relativePath: String,
    val range: SourceRange?,
    val contentHash: String,
)

public enum class ResolutionConfidence {
    EXACT,
    PROBABLE,
    WEAK,
}

public data class ResolutionCandidate(
    val id: ResolutionCandidateId,
    val evidenceId: PerformanceEvidenceId,
    val location: SourceLocation,
    val confidence: ResolutionConfidence,
    val reasons: List<String>,
    val indexVersion: Long,
    val indexComplete: Boolean,
)

public sealed interface SourceResolutionEvidence {
    public val id: PerformanceEvidenceId

    public data class ManagedSymbol(
        override val id: PerformanceEvidenceId,
        val className: String?,
        val methodName: String,
        val signature: String? = null,
        val resourcePath: String? = null,
    ) : SourceResolutionEvidence

    public data class NativeSymbol(
        override val id: PerformanceEvidenceId,
        val symbolName: String,
        val libraryPath: String?,
        val buildId: String? = null,
        val sourcePath: String? = null,
        val sourceLine: Int? = null,
    ) : SourceResolutionEvidence

    public data class AndroidResource(
        override val id: PerformanceEvidenceId,
        val resourceType: String,
        val resourceName: String,
    ) : SourceResolutionEvidence

    public data class TypeName(
        override val id: PerformanceEvidenceId,
        val qualifiedName: String,
    ) : SourceResolutionEvidence

    public data class SourceFileLine(
        override val id: PerformanceEvidenceId,
        val fileName: String,
        val packageHash: Int,
        val line: Int,
    ) : SourceResolutionEvidence
}

public data class BuildEvidenceBundle(
    val id: BuildEvidenceBundleId,
    val displayName: String,
    val packageName: String?,
    val variant: String?,
    val versionCode: Long?,
    val sourceRevision: String?,
    val buildFingerprint: String?,
    val mappingFiles: List<Path> = emptyList(),
    val nativeSymbolFiles: List<Path> = emptyList(),
)

public data class VerifiedSourceContent(
    val location: SourceLocation,
    val text: String,
)
