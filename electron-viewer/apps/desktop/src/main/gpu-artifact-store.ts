import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import {
  defaultArtifactIndexDependencies,
  importGpuArtifact,
  mergeArtifactLocation,
  resolveArtifactLocation,
  type ArtifactIndexDependencies,
} from '@aps/gpu-inspector/node';
import { artifactLocations, type GpuArtifact } from '@aps/gpu-inspector';
import type { GpuArtifactSummary, GpuArtifactLocationReport } from '../shared/ipc.js';

const INDEX_FILE = 'index.json';

/**
 * Stores GPU artifacts as content-addressed evidence. The index keeps a primary
 * path plus every alternative location for the same content digest.
 */
export class GpuArtifactStore {
  private readonly directory: string;
  private readonly dependencies: ArtifactIndexDependencies;

  constructor(directory: string, dependencies: ArtifactIndexDependencies = defaultArtifactIndexDependencies()) {
    this.directory = directory;
    this.dependencies = dependencies;
  }

  async list(): Promise<GpuArtifact[]> {
    try {
      const parsed: unknown = JSON.parse(await readFile(join(this.directory, INDEX_FILE), 'utf8'));
      if (!Array.isArray(parsed)) return [];
      return parsed.filter(isArtifact);
    } catch {
      return [];
    }
  }

  private async save(artifacts: readonly GpuArtifact[]): Promise<void> {
    await mkdir(this.directory, { recursive: true });
    await writeFile(join(this.directory, INDEX_FILE), JSON.stringify(artifacts, null, 2));
  }

  async importArtifact(options: {
    readonly path: string;
    readonly id: string;
    readonly now: () => number;
    readonly agiVersion?: string;
    readonly notes?: string;
  }): Promise<GpuArtifact> {
    const imported = await importGpuArtifact(this.dependencies, options);
    const merged = mergeArtifactLocation(await this.list(), imported);
    await this.save(merged);
    return merged[0] as GpuArtifact;
  }

  async relocate(id: string, newPath: string): Promise<GpuArtifact | undefined> {
    const artifacts = await this.list();
    const artifact = artifacts.find((entry) => entry.id === id);
    if (artifact === undefined) return undefined;
    if (!this.dependencies.isRegularFile(newPath)) throw new Error('Artifact does not exist: ' + newPath);
    const size = this.dependencies.sizeOf(newPath);
    if (size !== artifact.sizeBytes) throw new Error('Selected file size does not match the indexed artifact');
    const digest = await this.dependencies.sha256(newPath);
    if (digest !== artifact.sha256) throw new Error('Selected file content does not match the indexed artifact');
    const relocated: GpuArtifact = {
      ...artifact,
      path: newPath,
      alternativePaths: artifactLocations(artifact).filter((path) => path !== newPath),
    };
    await this.save([relocated, ...artifacts.filter((entry) => entry.id !== id)]);
    return relocated;
  }

  async resolveLocation(id: string): Promise<GpuArtifactLocationReport | undefined> {
    const artifact = (await this.list()).find((entry) => entry.id === id);
    if (artifact === undefined) return undefined;
    const resolution = resolveArtifactLocation(artifact, {
      isRegularFile: this.dependencies.isRegularFile,
      sizeOf: this.dependencies.sizeOf,
    });
    return {
      id,
      status: resolution.status,
      ...(resolution.path !== undefined ? { path: resolution.path } : {}),
    };
  }

  async summarize(): Promise<GpuArtifactSummary[]> {
    const artifacts = await this.list();
    return artifacts.map((artifact) => {
      const resolution = resolveArtifactLocation(artifact, {
        isRegularFile: this.dependencies.isRegularFile,
        sizeOf: this.dependencies.sizeOf,
      });
      return {
        id: artifact.id,
        kind: artifact.kind,
        path: artifact.path,
        sizeBytes: artifact.sizeBytes,
        sha256: artifact.sha256,
        openRoute: artifact.openRoute,
        locationStatus: resolution.status,
        locationCount: artifactLocations(artifact).length,
        importedAtEpochMillis: artifact.importedAtEpochMillis,
        warningCount: artifact.warnings.length,
        ...(artifact.agiVersion !== undefined ? { agiVersion: artifact.agiVersion } : {}),
      };
    });
  }
}

function isArtifact(value: unknown): value is GpuArtifact {
  if (value === null || typeof value !== 'object') return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record['id'] === 'string' &&
    typeof record['path'] === 'string' &&
    typeof record['sha256'] === 'string' &&
    typeof record['sizeBytes'] === 'number'
  );
}
