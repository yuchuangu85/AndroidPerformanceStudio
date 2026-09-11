/**
 * Port of DefaultSourceWorkspaceService.kt.
 *
 * Adding a workspace registers it and immediately refreshes it: resolve the
 * revision, list the provider files, hash the manifest and every file, index
 * each file, then publish an immutable snapshot. A failure leaves the workspace
 * in FAILED with the reason rather than throwing at the caller, which is what
 * the UI shows. Reads come from the content addressed cache, so a snapshot stays
 * readable after the working tree moves on; for a local tree the content is
 * compared with the file on disk to report CURRENT or STALE.
 */
import { indexSourceFile } from './indexer.js';
import { sourceLanguage } from './language.js';
import type { SourceFile, SourceLocation, SourceSnapshot, SourceSymbol, SourceWorkspace } from './model.js';
import type { SourceProviderRegistry } from './providers.js';
import type { SourceWorkspaceRepository } from './repository.js';
import type { VerifiedSourceContent } from './model.js';
import type { ContentAddressedSourceCache } from './node.js';

export interface SourceWorkspaceService {
  workspaces(): readonly SourceWorkspace[];
  /** Mirrors the Kotlin StateFlow: called on every workspace change. */
  subscribe(listener: (workspaces: readonly SourceWorkspace[]) => void): () => void;
  add(displayName: string, config: SourceWorkspace['config']): Promise<SourceWorkspace>;
  refresh(id: string): Promise<SourceWorkspace>;
  remove(id: string): void;
  setAiSourceUploadAllowed(id: string, allowed: boolean): void;
  read(location: SourceLocation): Promise<VerifiedSourceContent>;
}

export interface DefaultSourceWorkspaceServiceDependencies {
  readonly providers: SourceProviderRegistry;
  readonly repository: SourceWorkspaceRepository;
  readonly cache: ContentAddressedSourceCache;
  readonly sha256: (value: string) => string;
  readonly sha256Bytes: (value: Uint8Array) => string;
  /** Injected so a test can pin the ids and the clock. */
  readonly newId: () => string;
  readonly now: () => number;
}

const PROGRESS_UPDATE_INTERVAL = 100;

export class DefaultSourceWorkspaceService implements SourceWorkspaceService {
  private readonly providers: SourceProviderRegistry;
  private readonly repository: SourceWorkspaceRepository;
  private readonly cache: ContentAddressedSourceCache;
  private readonly sha256: (value: string) => string;
  private readonly sha256Bytes: (value: Uint8Array) => string;
  private readonly newId: () => string;
  private readonly now: () => number;
  private readonly listeners = new Set<(workspaces: readonly SourceWorkspace[]) => void>();

  constructor(dependencies: DefaultSourceWorkspaceServiceDependencies) {
    this.providers = dependencies.providers;
    this.repository = dependencies.repository;
    this.cache = dependencies.cache;
    this.sha256 = dependencies.sha256;
    this.sha256Bytes = dependencies.sha256Bytes;
    this.newId = dependencies.newId;
    this.now = dependencies.now;
  }

  workspaces(): readonly SourceWorkspace[] {
    return this.repository.workspaces();
  }

  subscribe(listener: (workspaces: readonly SourceWorkspace[]) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  async add(displayName: string, config: SourceWorkspace['config']): Promise<SourceWorkspace> {
    if (displayName.trim().length === 0) throw new Error('Source workspace name is required');
    const workspace: SourceWorkspace = {
      id: this.newId(),
      displayName: displayName.trim(),
      config,
      phase: 'REGISTERING',
      progress: 0,
      allowAiSourceUpload: false,
    };
    this.save(workspace);
    return this.refresh(workspace.id);
  }

  async refresh(id: string): Promise<SourceWorkspace> {
    const initial = this.repository.workspace(id);
    if (initial === undefined) throw new Error('Unknown source workspace: ' + id);
    const provider = this.providers.providerFor(initial.config.kind);
    try {
      this.save({ ...initial, phase: 'RESOLVING_REVISION', progress: 0.05, message: undefined });
      const revision = await provider.resolveRevision(initial.config);
      this.save({ ...initial, phase: 'BUILDING_MANIFEST', progress: 0.1, message: revision.slice(0, 12) });
      const providerFiles = await provider.listFiles(initial.config, revision);
      const manifestHash = this.sha256(
        providerFiles
          .map((file) => file.relativePath + ':' + (file.contentHash ?? '') + ':' + String(file.sizeBytes))
          .join('\n'),
      );
      const snapshotId = this.sha256(initial.id + ':' + revision + ':' + manifestHash);
      const sourceFiles: SourceFile[] = [];
      const symbols: SourceSymbol[] = [];
      for (let index = 0; index < providerFiles.length; index += 1) {
        const providerFile = providerFiles[index];
        const content = await provider.readFile(initial.config, revision, providerFile.relativePath);
        const contentHash = this.cache.put(content);
        const sourceFile = {
          snapshotId,
          relativePath: providerFile.relativePath,
          language: sourceLanguage(providerFile.relativePath),
          contentHash,
          sizeBytes: content.length,
        };
        sourceFiles.push(sourceFile);
        symbols.push(...indexSourceFile(snapshotId, sourceFile.relativePath, new TextDecoder().decode(content)));
        if (index === providerFiles.length - 1 || index % PROGRESS_UPDATE_INTERVAL === 0) {
          const fraction = providerFiles.length === 0 ? 1 : (index + 1) / providerFiles.length;
          this.save({
            ...initial,
            phase: 'INDEXING',
            progress: 0.1 + fraction * 0.85,
            message: String(index + 1) + '/' + String(providerFiles.length),
          });
        }
      }
      const snapshot: SourceSnapshot = {
        id: snapshotId,
        workspaceId: initial.id,
        immutableRevision: revision.includes('-dirty-') ? revision.slice(0, revision.indexOf('-dirty-')) : revision,
        dirtyContentDigest: revision.includes('-dirty-')
          ? revision.slice(revision.indexOf('-dirty-') + '-dirty-'.length) || undefined
          : undefined,
        manifestHash,
        createdAtEpochMillis: this.now(),
        indexVersion: this.now(),
        indexComplete: true,
      };
      this.repository.saveSnapshot(snapshot, sourceFiles, symbols);
      const ready: SourceWorkspace = {
        ...initial,
        activeSnapshotId: snapshotId,
        phase: 'READY',
        progress: 1,
        message: String(sourceFiles.length) + ' files · ' + String(symbols.length) + ' symbols',
      };
      this.save(ready);
      return ready;
    } catch (error) {
      const failed: SourceWorkspace = {
        ...initial,
        phase: 'FAILED',
        progress: 0,
        message: error instanceof Error ? error.message : 'SourceWorkspaceFailure',
      };
      this.save(failed);
      return failed;
    }
  }

  remove(id: string): void {
    this.repository.deleteWorkspace(id);
    this.publish();
  }

  setAiSourceUploadAllowed(id: string, allowed: boolean): void {
    const workspace = this.repository.workspace(id);
    if (workspace === undefined) throw new Error('Unknown source workspace: ' + id);
    this.save({ ...workspace, allowAiSourceUpload: allowed });
  }

  async read(location: SourceLocation): Promise<VerifiedSourceContent> {
    const content = this.cache.read(location.contentHash);
    const workspace = this.repository.workspace(location.workspaceId);
    if (workspace === undefined) throw new Error('Unknown source workspace: ' + location.workspaceId);
    const state = workspace.config.kind === 'LOCAL' ? await this.currentOrStale(workspace, location) : 'CURRENT';
    return { location, text: new TextDecoder().decode(content), state };
  }

  private async currentOrStale(
    workspace: SourceWorkspace,
    location: SourceLocation,
  ): Promise<'CURRENT' | 'STALE'> {
    try {
      const provider = this.providers.providerFor('LOCAL');
      const current = await provider.readFile(workspace.config, 'current', location.relativePath);
      return this.sha256Bytes(current) === location.contentHash ? 'CURRENT' : 'STALE';
    } catch {
      return 'STALE';
    }
  }

  private save(workspace: SourceWorkspace): void {
    this.repository.saveWorkspace(workspace);
    this.publish();
  }

  private publish(): void {
    const current = this.repository.workspaces();
    for (const listener of this.listeners) listener(current);
  }
}

