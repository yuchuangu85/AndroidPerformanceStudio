@file:Suppress("MaxLineLength")

package com.androidperformancestudio.source

import kotlin.math.absoluteValue

public interface SourceIndexView {
    public fun snapshot(snapshotId: SourceSnapshotId): SourceSnapshot?

    public fun files(snapshotId: SourceSnapshotId): List<SourceFile>

    public fun symbols(snapshotId: SourceSnapshotId): List<SourceSymbol>
}

public interface SourceResolver {
    public suspend fun resolve(
        snapshotIds: Set<SourceSnapshotId>,
        evidence: List<SourceResolutionEvidence>,
        buildIdentityMatch: BuildIdentityMatch = BuildIdentityMatch.UNVERIFIED,
    ): List<ResolutionCandidate>
}

public class IndexedSourceResolver(
    private val index: SourceIndexView,
) : SourceResolver {
    override suspend fun resolve(
        snapshotIds: Set<SourceSnapshotId>,
        evidence: List<SourceResolutionEvidence>,
        buildIdentityMatch: BuildIdentityMatch,
    ): List<ResolutionCandidate> =
        evidence.flatMap { item ->
            snapshotIds.flatMap { snapshotId -> candidates(snapshotId, item) }
        }.distinctBy { candidate -> candidate.id }
            .map { candidate -> candidate.applyBuildIdentityCeiling(buildIdentityMatch) }

    private fun candidates(
        snapshotId: SourceSnapshotId,
        evidence: SourceResolutionEvidence,
    ): List<ResolutionCandidate> {
        val snapshot = index.snapshot(snapshotId) ?: return emptyList()
        val files = index.files(snapshotId).associateBy(SourceFile::relativePath)
        val symbols = index.symbols(snapshotId)
        return when (evidence) {
            is SourceResolutionEvidence.TypeName ->
                symbols.filter { it.kind == SourceSymbolKind.TYPE && it.qualifiedName == evidence.qualifiedName }
                    .map { it.candidate(snapshot, files, evidence.id, ResolutionConfidence.EXACT, "Qualified type matched") }
            is SourceResolutionEvidence.AndroidResource ->
                symbols.filter {
                    it.kind == SourceSymbolKind.RESOURCE &&
                        it.qualifiedName == "${evidence.resourceType}/${evidence.resourceName}"
                }.map { it.candidate(snapshot, files, evidence.id, ResolutionConfidence.EXACT, "Android resource matched") }
            is SourceResolutionEvidence.ManagedSymbol -> managedCandidates(snapshot, files, symbols, evidence)
            is SourceResolutionEvidence.NativeSymbol -> nativeCandidates(snapshot, files, symbols, evidence)
            is SourceResolutionEvidence.SourceFileLine -> sourceFileCandidates(snapshot, files, symbols, evidence)
        }
    }

    private fun sourceFileCandidates(
        snapshot: SourceSnapshot,
        files: Map<String, SourceFile>,
        symbols: List<SourceSymbol>,
        evidence: SourceResolutionEvidence.SourceFileLine,
    ): List<ResolutionCandidate> = files.values
        .filter { it.relativePath.substringAfterLast('/') == evidence.fileName }
        .mapNotNull { file ->
            val packageMatched = symbols.asSequence()
                .filter { it.relativePath == file.relativePath }
                .flatMap { symbol -> symbol.qualifiedName.packagePrefixes() }
                .any { it.hashCode().absoluteValue == evidence.packageHash }
            file.takeIf { packageMatched }?.candidate(
                snapshot = snapshot,
                evidenceId = evidence.id,
                confidence = ResolutionConfidence.EXACT,
                reason = "Compose file and package hash matched",
                line = evidence.line,
            )
        }

    private fun managedCandidates(
        snapshot: SourceSnapshot,
        files: Map<String, SourceFile>,
        symbols: List<SourceSymbol>,
        evidence: SourceResolutionEvidence.ManagedSymbol,
    ): List<ResolutionCandidate> =
        symbols.filter { symbol ->
            symbol.kind in setOf(SourceSymbolKind.FUNCTION, SourceSymbolKind.METHOD) &&
                symbol.qualifiedName.substringAfterLast('.') == evidence.methodName
        }.map { symbol ->
            val classMatches = evidence.className == null || symbol.relativePath.substringBeforeLast('.').endsWith(evidence.className.substringAfterLast('.'))
            val signatureMatches = evidence.signature != null && symbol.signature == evidence.signature
            val confidence = if (classMatches && (evidence.signature == null || signatureMatches)) ResolutionConfidence.EXACT else ResolutionConfidence.PROBABLE
            symbol.candidate(snapshot, files, evidence.id, confidence, if (classMatches) "Managed symbol matched" else "Method name matched")
        }

    private fun nativeCandidates(
        snapshot: SourceSnapshot,
        files: Map<String, SourceFile>,
        symbols: List<SourceSymbol>,
        evidence: SourceResolutionEvidence.NativeSymbol,
    ): List<ResolutionCandidate> {
        evidence.sourcePath?.let { sourcePath ->
            files.values.firstOrNull { file -> file.relativePath.endsWith(sourcePath.removePrefix("/")) }?.let { file ->
                return listOf(
                    file.candidate(
                        snapshot = snapshot,
                        evidenceId = evidence.id,
                        confidence = if (evidence.sourceLine != null) ResolutionConfidence.EXACT else ResolutionConfidence.PROBABLE,
                        reason = "Symbolizer source path matched",
                        line = evidence.sourceLine,
                    ),
                )
            }
        }
        return symbols.filter {
            it.kind == SourceSymbolKind.NATIVE_SYMBOL && it.qualifiedName.substringAfterLast("::") == evidence.symbolName.substringAfterLast("::")
        }.map { symbol ->
            symbol.candidate(snapshot, files, evidence.id, ResolutionConfidence.PROBABLE, "Native symbol name matched")
        }
    }
}

private fun ResolutionCandidate.applyBuildIdentityCeiling(match: BuildIdentityMatch): ResolutionCandidate =
    if (match == BuildIdentityMatch.UNVERIFIED && confidence == ResolutionConfidence.EXACT) {
        copy(
            confidence = ResolutionConfidence.PROBABLE,
            reasons = reasons + "Build identity not verified",
        )
    } else {
        this
    }

private fun String.packagePrefixes(): Sequence<String> {
    val parts = split('.')
    return (1 until parts.size).asSequence().map { parts.take(it).joinToString(".") }
}

private fun SourceSymbol.candidate(
    snapshot: SourceSnapshot,
    files: Map<String, SourceFile>,
    evidenceId: PerformanceEvidenceId,
    confidence: ResolutionConfidence,
    reason: String,
): ResolutionCandidate {
    val file = requireNotNull(files[relativePath])
    return file.candidate(snapshot, evidenceId, confidence, reason, startLine)
}

private fun SourceFile.candidate(
    snapshot: SourceSnapshot,
    evidenceId: PerformanceEvidenceId,
    confidence: ResolutionConfidence,
    reason: String,
    line: Int?,
): ResolutionCandidate {
    val identity = "${snapshot.id.value}:$relativePath:${line ?: 0}:${evidenceId.value}"
    return ResolutionCandidate(
        id = ResolutionCandidateId(identity.sha256()),
        evidenceId = evidenceId,
        location = SourceLocation(snapshot.workspaceId, snapshot.id, relativePath, line?.let(::SourceRange), contentHash),
        confidence = confidence,
        reasons = listOf(reason),
        indexVersion = snapshot.indexVersion,
        indexComplete = snapshot.indexComplete,
    )
}
