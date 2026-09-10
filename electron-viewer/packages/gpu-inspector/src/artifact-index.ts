import { createHash } from 'node:crypto';
import { statSync } from 'node:fs';
import { open } from 'node:fs/promises';
import {
  artifactLocations,
  openRouteFor,
  type ArtifactLocationStatus,
  type GpuArtifact,
  type GpuArtifactKind,
} from './model.js';

export const MAX_GPU_ARTIFACT_BYTES = 8 * 1024 * 1024 * 1024;

export interface ArtifactLocationResolution {
  readonly status: ArtifactLocationStatus;
  readonly path?: string;
}

export interface ArtifactIndexDependencies {
  readonly isRegularFile: (path: string) => boolean;
  readonly sizeOf: (path: string) => number;
  readonly lastModified: (path: string) => number;
  readonly readHeader: (path: string, bytes: number) => Promise<string>;
  readonly sha256: (path: string) => Promise<string>;
}

export function detectArtifactKind(fileName: string, header: string): GpuArtifactKind {
  const name = fileName.toLowerCase();
  if (name.endsWith('.perfetto-trace') || name.endsWith('.pftrace') || header.includes('PERFETTO')) {
    return 'PERFETTO_TRACE';
  }
  if (name.endsWith('.gfxtrace') || name.endsWith('.agi')) return 'AGI_FRAME_PROFILE';
  if (name.endsWith('.trace')) return 'AGI_SYSTEM_PROFILE';
  if (name.endsWith('.png') || name.endsWith('.jpg') || name.endsWith('.jpeg')) return 'SCREENSHOT';
  if (name.endsWith('.html') || name.endsWith('.pdf')) return 'EXTERNAL_REPORT';
  return 'UNKNOWN';
}

/** Indexes a GPU artifact as content-addressed evidence. */
export async function importGpuArtifact(
  dependencies: ArtifactIndexDependencies,
  options: {
    readonly path: string;
    readonly id: string;
    readonly now: () => number;
    readonly agiVersion?: string;
    readonly notes?: string;
  },
): Promise<GpuArtifact> {
  if (!dependencies.isRegularFile(options.path)) {
    throw new Error('Artifact does not exist: ' + options.path);
  }
  const sizeBytes = dependencies.sizeOf(options.path);
  if (sizeBytes > MAX_GPU_ARTIFACT_BYTES) {
    throw new Error('Artifact exceeds the configured ' + MAX_GPU_ARTIFACT_BYTES + ' byte limit');
  }
  const fileName = options.path.slice(options.path.lastIndexOf('/') + 1);
  const header = await dependencies.readHeader(options.path, 32);
  const kind = detectArtifactKind(fileName, header);
  return {
    id: options.id,
    kind,
    path: options.path,
    sha256: await dependencies.sha256(options.path),
    sizeBytes,
    openRoute: openRouteFor(kind),
    alternativePaths: [],
    warnings:
      kind === 'UNKNOWN' ? ['Unknown artifact format; it is indexed as opaque evidence.'] : [],
    ...(options.agiVersion !== undefined ? { agiVersion: options.agiVersion } : {}),
    capturedAtEpochMillis: dependencies.lastModified(options.path),
    importedAtEpochMillis: options.now(),
    ...(options.notes !== undefined ? { notes: options.notes } : {}),
  };
}

/**
 * A location is only AVAILABLE when a candidate still matches the indexed size;
 * a differing size means the content moved on, which is not the same as missing.
 */
export function resolveArtifactLocation(
  artifact: GpuArtifact,
  probe: { readonly isRegularFile: (path: string) => boolean; readonly sizeOf: (path: string) => number },
): ArtifactLocationResolution {
  let foundRegularFile = false;
  for (const path of artifactLocations(artifact)) {
    if (!probe.isRegularFile(path)) continue;
    foundRegularFile = true;
    if (probe.sizeOf(path) === artifact.sizeBytes) return { status: 'AVAILABLE', path };
  }
  return { status: foundRegularFile ? 'SIZE_CHANGED' : 'MISSING' };
}

/** Same-content imports merge into one artifact carrying every known location. */
export function mergeArtifactLocation(artifacts: readonly GpuArtifact[], imported: GpuArtifact): GpuArtifact[] {
  const existing = artifacts.find((artifact) => artifact.sha256 === imported.sha256);
  if (existing === undefined) return [imported, ...artifacts];
  const merged: GpuArtifact = {
    ...existing,
    path: imported.path,
    alternativePaths: [...imported.alternativePaths, ...artifactLocations(existing)]
      .filter((path) => path !== imported.path)
      .filter((path, index, all) => all.indexOf(path) === index),
    importedAtEpochMillis: imported.importedAtEpochMillis,
  };
  return [merged, ...artifacts.filter((artifact) => artifact.id !== existing.id)];
}

export function defaultArtifactIndexDependencies(): ArtifactIndexDependencies {
  return {
    isRegularFile: (path) => {
      try {
        return statSync(path).isFile();
      } catch {
        return false;
      }
    },
    sizeOf: (path) => statSync(path).size,
    lastModified: (path) => statSync(path).mtimeMs,
    readHeader: async (path, bytes) => {
      const handle = await open(path, 'r');
      try {
        const buffer = Buffer.alloc(bytes);
        const { bytesRead } = await handle.read(buffer, 0, bytes, 0);
        return buffer.subarray(0, bytesRead).toString('utf8');
      } finally {
        await handle.close();
      }
    },
    sha256: async (path) => {
      const hash = createHash('sha256');
      const { createReadStream } = await import('node:fs');
      for await (const chunk of createReadStream(path, { highWaterMark: 1024 * 1024 })) {
        hash.update(chunk as Buffer);
      }
      return hash.digest('hex');
    },
  };
}

