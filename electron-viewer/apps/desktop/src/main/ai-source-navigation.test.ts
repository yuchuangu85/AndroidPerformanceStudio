import { describe, expect, it } from 'vitest';
import type { AnalysisCandidateSummary } from '@aps/ai-core/node';
import type { SourceFile, SourceSnapshot } from '@aps/source-workspace';
import { sourceLocationForPersistedAiCandidate } from './ai-source-navigation.js';

const candidate: AnalysisCandidateSummary = {
  id: 'candidate-1', relativePath: 'src/Main.kt', startLine: 12, endLine: 15,
  resolutionConfidence: 'EXACT', contentHash: 'a'.repeat(64),
};
const snapshot: SourceSnapshot = {
  id: 'snapshot-1', workspaceId: 'workspace-1', immutableRevision: 'abc', manifestHash: 'b'.repeat(64),
  createdAtEpochMillis: 1, indexVersion: 1, indexComplete: true,
};
const file: SourceFile = {
  snapshotId: 'snapshot-1', relativePath: 'src/Main.kt', contentHash: 'a'.repeat(64), language: 'KOTLIN', sizeBytes: 10,
};

describe('sourceLocationForPersistedAiCandidate', () => {
  it('uses the cited session snapshot and content hash rather than a current symbol lookup', () => {
    expect(sourceLocationForPersistedAiCandidate(
      { candidate, sourceSnapshotIds: ['snapshot-1'] },
      new Map([[snapshot.id, snapshot]]),
      new Map([[snapshot.id, [file]]]),
    )).toEqual({
      workspaceId: 'workspace-1', snapshotId: 'snapshot-1', relativePath: 'src/Main.kt', contentHash: 'a'.repeat(64),
      range: { startLine: 12, endLine: 15 },
    });
  });

  it('fails closed when a historical candidate is absent from every referenced snapshot', () => {
    expect(sourceLocationForPersistedAiCandidate(
      { candidate, sourceSnapshotIds: ['snapshot-1'] },
      new Map([[snapshot.id, snapshot]]),
      new Map([[snapshot.id, [{ ...file, contentHash: 'b'.repeat(64) }]]]),
    )).toBeUndefined();
  });
});
