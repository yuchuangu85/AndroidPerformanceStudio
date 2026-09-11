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
import type { SourceWorkspaceRecord } from '../shared/ipc.js';
import { join } from 'node:path';
import {
  AospSourceProvider,
  DefaultSourceWorkspaceService,
  GitHubSourceProvider,
  SourceProviderRegistry,
  fetchSourceHttpTransport,
  resolveEvidence,
  type SourceHttpTransport,
  type BuildIdentityMatch,
  type ResolutionCandidate,
  type SourceContentState,
  type SourceLanguage,
  type SourceLocation,
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

export interface SourceBackendOptions {
  readonly databasePath: string;
  readonly cacheDirectory: string;
  /** Injected in tests; the app uses the real fetch transport. */
  readonly transport?: SourceHttpTransport;
  readonly now?: () => number;
  readonly newId?: () => string;
}

let backend: SourceBackend | undefined;

export function sourceBackend(): SourceBackend {
  if (backend !== undefined) return backend;
  backend = createSourceBackend({
    databasePath: sourceWorkspacesDatabasePath(),
    cacheDirectory: sourceCacheDirectory(),
  });
  return backend;
}

/** Builds a backend against explicit paths so a test never touches the real ones. */
export function createSourceBackend(options: SourceBackendOptions): SourceBackend {
  const repository = new SqliteSourceWorkspaceRepository(options.databasePath);
  const cache = new ContentAddressedSourceCache(options.cacheDirectory);
  const transport = options.transport ?? fetchSourceHttpTransport();
  const now = options.now ?? (() => Date.now());
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
    newId: options.newId ?? (() => globalThis.crypto.randomUUID()),
    now,
  });
  const built: SourceBackend = {
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
    },
  };
  return built;
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
      fileCount: snapshotId === undefined ? 0 : target.repository.fileCount(snapshotId),
      symbolCount: snapshotId === undefined ? 0 : target.repository.symbolCount(snapshotId),
      allowAiSourceUpload: workspace.allowAiSourceUpload,
    };
  });
}

/**
 * Imports the workspaces the app created before the shared database existed.
 *
 * A workspace is matched by its local root, so running this twice is harmless,
 * and a refresh re-resolves the revision and re-indexes into the content
 * addressed cache. Only local workspaces can be migrated: the legacy store only
 * ever held a root, and a remote workspace needs an owner and repository that
 * were never recorded.
 */
export interface LegacySourceWorkspace {
  readonly id: string;
  readonly displayName: string;
  readonly root: string;
  readonly phase: string;
}

export interface SourceMigrationResult {
  readonly migrated: number;
  readonly skipped: number;
  readonly failed: number;
}

export async function migrateLegacyWorkspaces(
  legacy: readonly LegacySourceWorkspace[],
  target: SourceBackend = sourceBackend(),
): Promise<SourceMigrationResult> {
  let migrated = 0;
  let skipped = 0;
  let failed = 0;
  for (const record of legacy) {
    if (record.root.trim().length === 0) {
      skipped += 1;
      continue;
    }
    const existing = target
      .repository
      .workspaces()
      .find((workspace) => workspace.config.kind === 'LOCAL' && workspace.config.root === record.root);
    if (existing !== undefined && existing.activeSnapshotId !== undefined) {
      skipped += 1;
      continue;
    }
    try {
      if (existing === undefined) {
        await target.service.add(record.displayName, { kind: 'LOCAL', root: record.root });
      } else {
        await target.service.refresh(existing.id);
      }
      migrated += 1;
    } catch {
      failed += 1;
    }
  }
  return { migrated, skipped, failed };
}

/**
 * The backend view in the shape the source IPC already returns, so the handler
 * switch is a one line change per channel and the panel keeps working. The
 * legacy three state phase is derived: only a finished or failed index is
 * reported as such, anything in flight reads as partial.
 */
export function toSourceWorkspaceRecords(target: SourceBackend = sourceBackend()): SourceWorkspaceRecord[] {
  return listSourceWorkspaces(target).map((workspace) => ({
    id: workspace.id,
    displayName: workspace.displayName,
    root: workspace.providerKind === 'LOCAL' ? localRootOf(workspace.id, target) : '',
    providerKind: workspace.providerKind,
    allowAiSourceUpload: workspace.allowAiSourceUpload,
    phase: workspace.phase === 'READY' ? 'READY' : workspace.phase === 'FAILED' ? 'FAILED' : 'PARTIAL',
    ...(workspace.message !== undefined ? { message: workspace.message } : {}),
    ...(workspace.revision !== undefined ? { revision: workspace.revision } : {}),
    ...(workspace.manifestHash !== undefined ? { manifestHash: workspace.manifestHash } : {}),
    ...(workspace.indexedAtEpochMillis !== undefined
      ? { indexedAtEpochMillis: workspace.indexedAtEpochMillis }
      : {}),
    fileCount: workspace.fileCount,
    symbolCount: workspace.symbolCount,
  }));
}

function localRootOf(workspaceId: string, target: SourceBackend): string {
  const workspace = target.repository.workspace(workspaceId);
  return workspace !== undefined && workspace.config.kind === 'LOCAL' ? workspace.config.root : '';
}

/** Symbol search over the indexed snapshot, newest state first. */
export function searchBackendSymbols(
  workspaceId: string,
  query: string,
  limit: number,
  target: SourceBackend = sourceBackend(),
): readonly { kind: string; qualifiedName: string; relativePath: string; signature?: string; startLine: number }[] {
  const snapshotId = target.snapshotIdOf(workspaceId);
  if (snapshotId === undefined) return [];
  const normalized = query.trim().toLowerCase();
  if (normalized.length === 0) return [];
  return target.repository
    .symbols(snapshotId)
    .filter((symbol) => symbol.qualifiedName.toLowerCase().includes(normalized))
    .slice(0, Math.max(limit, 1))
    .map((symbol) => ({
      kind: symbol.kind,
      qualifiedName: symbol.qualifiedName,
      relativePath: symbol.relativePath,
      ...(symbol.signature !== undefined ? { signature: symbol.signature } : {}),
      startLine: symbol.startLine,
    }));
}

/**
 * Reads verified content through the service, which reports CURRENT or STALE for
 * a local tree. Undefined when the workspace, its snapshot, or the file is gone.
 */
export async function readBackendSource(
  workspaceId: string,
  relativePath: string,
  target: SourceBackend = sourceBackend(),
): Promise<{ readonly text: string; readonly language: SourceLanguage; readonly state: SourceContentState } | undefined> {
  const snapshotId = target.snapshotIdOf(workspaceId);
  if (snapshotId === undefined) return undefined;
  const file = target.repository.files(snapshotId).find((entry) => entry.relativePath === relativePath);
  if (file === undefined) return undefined;
  const location: SourceLocation = {
    workspaceId,
    snapshotId,
    relativePath,
    contentHash: file.contentHash,
  };
  const content = await target.service.read(location);
  return { text: content.text, language: file.language, state: content.state };
}
