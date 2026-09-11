/**
 * Wires @aps/source-workspace into the app.
 *
 * The Kotlin app keeps workspaces in ~/.android-performance-studio/source-workspaces.db
 * with a content addressed cache next to it, so the Electron build opens the
 * same files: workspaces, snapshots, indexes, and candidates stay readable in
 * both directions. All three providers are registered; the local one is what the
 * current UI adds, the remote ones are ready for the same service.
 *
 * This module is the seam. The source:* IPC handlers still talk to the older
 * JSON store; switching them over is the next step, and the AI analysis path is
 * meant to resolve its candidates through resolveForWorkspace below.
 */
import { homedir } from 'node:os';
import { join } from 'node:path';
import {
  AospSourceProvider,
  DefaultSourceWorkspaceService,
  GitHubSourceProvider,
  SourceProviderRegistry,
  fetchSourceHttpTransport,
  resolveEvidence,
  type BuildIdentityMatch,
  type ResolutionCandidate,
  type SourceResolutionEvidence,
  type SourceWorkspace,
  type SourceWorkspaceRepository,
} from '@aps/source-workspace';
import {
  ContentAddressedSourceCache,
  LocalSourceProvider,
  SqliteSourceWorkspaceRepository,
  sha256Bytes,
  sha256Text,
} from '@aps/source-workspace/node';

/** The paths the Kotlin app uses; changing them breaks the shared data. */
export function sourceWorkspacesDatabasePath(): string {
  return join(homedir(), '.android-performance-studio', 'source-workspaces.db');
}

export function sourceCacheDirectory(): string {
  return join(homedir(), '.android-performance-studio', 'source-cache');
}

export interface SourceBackend {
  readonly service: DefaultSourceWorkspaceService;
  readonly repository: SourceWorkspaceRepository;
  snapshotIdOf(workspaceId: string): string | undefined;
  resolveForWorkspace(
    workspaceId: string,
    evidence: readonly SourceResolutionEvidence[],
    buildIdentityMatch: BuildIdentityMatch,
  ): ResolutionCandidate[];
  aiUploadAllowed(workspaceId: string): boolean;
  setAiUploadAllowed(workspaceId: string, allowed: boolean): void;
  close(): void;
}

let backend: SourceBackend | undefined;

export function sourceBackend(): SourceBackend {
  if (backend !== undefined) return backend;
  const repository = new SqliteSourceWorkspaceRepository(sourceWorkspacesDatabasePath());
  const cache = new ContentAddressedSourceCache(sourceCacheDirectory());
  const transport = fetchSourceHttpTransport();
  const service = new DefaultSourceWorkspaceService({
    providers: new SourceProviderRegistry([
      new LocalSourceProvider(),
      new GitHubSourceProvider({ transport }),
      new AospSourceProvider({ transport }),
    ]),
    repository,
    cache,
    sha256: sha256Text,
    sha256Bytes,
    newId: () => globalThis.crypto.randomUUID(),
    now: () => Date.now(),
  });
  backend = {
    service,
    repository,
    snapshotIdOf(workspaceId) {
      return repository.workspace(workspaceId)?.activeSnapshotId;
    },
    resolveForWorkspace(workspaceId, evidence, buildIdentityMatch) {
      const workspace = repository.workspace(workspaceId);
      const snapshotId = workspace?.activeSnapshotId;
      if (workspace === undefined || snapshotId === undefined) return [];
      return resolveEvidence(repository, [snapshotId], evidence, buildIdentityMatch);
    },
    aiUploadAllowed(workspaceId) {
      return repository.workspace(workspaceId)?.allowAiSourceUpload ?? false;
    },
    setAiUploadAllowed(workspaceId, allowed) {
      service.setAiSourceUploadAllowed(workspaceId, allowed);
    },
    close() {
      repository.close();
      backend = undefined;
    },
  };
  return backend;
}

/** Workspaces as the shell lists them, with their snapshot counts. */
export interface SourceWorkspaceSummary {
  readonly id: string;
  readonly displayName: string;
  readonly providerKind: SourceWorkspace['config']['kind'];
  readonly phase: SourceWorkspace['phase'];
  readonly progress: number;
  readonly message?: string;
  readonly revision?: string;
  readonly manifestHash?: string;
  readonly fileCount: number;
  readonly symbolCount: number;
  readonly indexedAtEpochMillis?: number;
  readonly allowAiSourceUpload: boolean;
}

export function listSourceWorkspaces(target: SourceBackend = sourceBackend()): SourceWorkspaceSummary[] {
  return target.repository.workspaces().map((workspace) => {
    const snapshotId = workspace.activeSnapshotId;
    const snapshot = snapshotId === undefined ? undefined : target.repository.snapshot(snapshotId);
    return {
      id: workspace.id,
      displayName: workspace.displayName,
      providerKind: workspace.config.kind,
      phase: workspace.phase,
      progress: workspace.progress,
      ...(workspace.message !== undefined ? { message: workspace.message } : {}),
      ...(snapshot !== undefined
        ? {
            revision: snapshot.immutableRevision,
            manifestHash: snapshot.manifestHash,
            indexedAtEpochMillis: snapshot.createdAtEpochMillis,
          }
        : {}),
      fileCount: snapshotId === undefined ? 0 : target.repository.files(snapshotId).length,
      symbolCount: snapshotId === undefined ? 0 : target.repository.symbols(snapshotId).length,
      allowAiSourceUpload: workspace.allowAiSourceUpload,
    };
  });
}
