/**
 * Port of SourceWorkspaceRepository.kt: the in-memory store plus the contract the
 * SQLite store implements. Deleting a workspace drops its snapshots, their file
 * and symbol indexes, and the candidates that pointed into them.
 */
import type { SourceIndexView } from './resolver.js';
import type {
  ResolutionCandidate,
  ResolutionCandidateId,
  SourceFile,
  SourceSnapshot,
  SourceSnapshotId,
  SourceSymbol,
  SourceWorkspace,
  SourceWorkspaceId,
} from './model.js';

export interface SourceWorkspaceRepository extends SourceIndexView {
  saveWorkspace(workspace: SourceWorkspace): void;
  workspace(id: SourceWorkspaceId): SourceWorkspace | undefined;
  workspaces(): SourceWorkspace[];
  deleteWorkspace(id: SourceWorkspaceId): void;
  saveSnapshot(snapshot: SourceSnapshot, files: readonly SourceFile[], symbols: readonly SourceSymbol[]): void;
  saveCandidates(candidates: readonly ResolutionCandidate[]): void;
  candidate(id: ResolutionCandidateId): ResolutionCandidate | undefined;
  /**
   * Counts without materialising the rows. Listing workspaces asks for these on
   * every call, and a real Android tree holds six figures of symbols.
   */
  fileCount(snapshotId: SourceSnapshotId): number;
  symbolCount(snapshotId: SourceSnapshotId): number;
}

export class InMemorySourceWorkspaceRepository implements SourceWorkspaceRepository {
  private readonly workspaceValues = new Map<SourceWorkspaceId, SourceWorkspace>();
  private readonly snapshotValues = new Map<SourceSnapshotId, SourceSnapshot>();
  private readonly filesBySnapshot = new Map<SourceSnapshotId, SourceFile[]>();
  private readonly symbolsBySnapshot = new Map<SourceSnapshotId, SourceSymbol[]>();
  private readonly candidateValues = new Map<ResolutionCandidateId, ResolutionCandidate>();

  saveWorkspace(workspace: SourceWorkspace): void {
    this.workspaceValues.set(workspace.id, workspace);
  }

  workspace(id: SourceWorkspaceId): SourceWorkspace | undefined {
    return this.workspaceValues.get(id);
  }

  workspaces(): SourceWorkspace[] {
    return [...this.workspaceValues.values()];
  }

  deleteWorkspace(id: SourceWorkspaceId): void {
    const snapshotIds = [...this.snapshotValues.values()]
      .filter((snapshot) => snapshot.workspaceId === id)
      .map((snapshot) => snapshot.id);
    for (const snapshotId of snapshotIds) {
      this.snapshotValues.delete(snapshotId);
      this.filesBySnapshot.delete(snapshotId);
      this.symbolsBySnapshot.delete(snapshotId);
      for (const [candidateId, candidate] of [...this.candidateValues]) {
        if (candidate.location.snapshotId === snapshotId) this.candidateValues.delete(candidateId);
      }
    }
    this.workspaceValues.delete(id);
  }

  saveSnapshot(snapshot: SourceSnapshot, files: readonly SourceFile[], symbols: readonly SourceSymbol[]): void {
    this.snapshotValues.set(snapshot.id, snapshot);
    this.filesBySnapshot.set(snapshot.id, [...files]);
    this.symbolsBySnapshot.set(snapshot.id, [...symbols]);
  }

  snapshot(snapshotId: SourceSnapshotId): SourceSnapshot | undefined {
    return this.snapshotValues.get(snapshotId);
  }

  files(snapshotId: SourceSnapshotId): readonly SourceFile[] {
    return this.filesBySnapshot.get(snapshotId) ?? [];
  }

  symbols(snapshotId: SourceSnapshotId): readonly SourceSymbol[] {
    return this.symbolsBySnapshot.get(snapshotId) ?? [];
  }

  saveCandidates(candidates: readonly ResolutionCandidate[]): void {
    for (const candidate of candidates) this.candidateValues.set(candidate.id, candidate);
  }

  candidate(id: ResolutionCandidateId): ResolutionCandidate | undefined {
    return this.candidateValues.get(id);
  }

  fileCount(snapshotId: SourceSnapshotId): number {
    return (this.filesBySnapshot.get(snapshotId) ?? []).length;
  }

  symbolCount(snapshotId: SourceSnapshotId): number {
    return (this.symbolsBySnapshot.get(snapshotId) ?? []).length;
  }
}
