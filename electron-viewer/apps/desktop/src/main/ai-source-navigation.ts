import type { AnalysisCandidateSummary } from '@aps/ai-core/node';
import type { SourceFile, SourceLocation, SourceSnapshot } from '@aps/source-workspace';

export interface PersistedAiSourceCandidate {
  readonly candidate: AnalysisCandidateSummary;
  readonly sourceSnapshotIds: readonly string[];
}

/**
 * Reconstructs a source location from the AI session's persisted candidate and
 * snapshot, rather than re-resolving symbols against a newer source index.
 */
export function sourceLocationForPersistedAiCandidate(
  reference: PersistedAiSourceCandidate,
  snapshots: ReadonlyMap<string, SourceSnapshot>,
  files: ReadonlyMap<string, readonly SourceFile[]>,
): SourceLocation | undefined {
  for (const snapshotId of reference.sourceSnapshotIds) {
    const snapshot = snapshots.get(snapshotId);
    const file = files
      .get(snapshotId)
      ?.find((entry) => entry.relativePath === reference.candidate.relativePath && entry.contentHash === reference.candidate.contentHash);
    if (snapshot === undefined || file === undefined) continue;
    return {
      workspaceId: snapshot.workspaceId,
      snapshotId,
      relativePath: file.relativePath,
      contentHash: file.contentHash,
      ...(reference.candidate.startLine === null
        ? {}
        : { range: { startLine: reference.candidate.startLine, endLine: reference.candidate.endLine ?? reference.candidate.startLine } }),
    };
  }
  return undefined;
}
