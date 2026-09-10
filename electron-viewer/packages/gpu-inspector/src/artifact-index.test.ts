import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { detectArtifactKind, mergeArtifactLocation, resolveArtifactLocation, type ArtifactIndexDependencies } from './artifact-index.js';
import type { GpuArtifact } from './model.js';

const directories: string[] = [];

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'aps-gpu-'));
  directories.push(directory);
  return directory;
}

afterEach(async () => {
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

function artifact(overrides: Partial<GpuArtifact> = {}): GpuArtifact {
  return {
    id: 'a',
    kind: 'PERFETTO_TRACE',
    path: '/traces/a.pftrace',
    sha256: 'a'.repeat(64),
    sizeBytes: 1024,
    openRoute: 'PERFETTO',
    alternativePaths: [],
    warnings: [],
    importedAtEpochMillis: 0,
    ...overrides,
  };
}

const probe = (files: Record<string, number>) => ({
  isRegularFile: (path: string) => path in files,
  sizeOf: (path: string) => files[path] ?? 0,
});

describe('detectArtifactKind', () => {
  it('classifies by extension and header', () => {
    expect(detectArtifactKind('profile.pftrace', '')).toBe('PERFETTO_TRACE');
    expect(detectArtifactKind('capture.perfetto-trace', '')).toBe('PERFETTO_TRACE');
    expect(detectArtifactKind('mystery.bin', 'xxPERFETTOxx')).toBe('PERFETTO_TRACE');
    expect(detectArtifactKind('frame.gfxtrace', '')).toBe('AGI_FRAME_PROFILE');
    expect(detectArtifactKind('system.trace', '')).toBe('AGI_SYSTEM_PROFILE');
    expect(detectArtifactKind('shot.PNG', '')).toBe('SCREENSHOT');
    expect(detectArtifactKind('report.HTML', '')).toBe('EXTERNAL_REPORT');
    expect(detectArtifactKind('thing.dat', '')).toBe('UNKNOWN');
  });
});

describe('resolveArtifactLocation', () => {
  it('reports AVAILABLE only when a location still matches the indexed size', () => {
    expect(resolveArtifactLocation(artifact(), probe({ '/traces/a.pftrace': 1024 }))).toEqual({
      status: 'AVAILABLE',
      path: '/traces/a.pftrace',
    });
  });

  it('distinguishes MISSING from SIZE_CHANGED and falls back to alternative locations', () => {
    expect(resolveArtifactLocation(artifact(), probe({}))).toEqual({ status: 'MISSING' });
    expect(resolveArtifactLocation(artifact(), probe({ '/traces/a.pftrace': 2048 }))).toEqual({
      status: 'SIZE_CHANGED',
    });
    const moved = artifact({ path: '/gone/a.pftrace', alternativePaths: ['/kept/a.pftrace'] });
    expect(resolveArtifactLocation(moved, probe({ '/gone/a.pftrace': 1024, '/kept/a.pftrace': 1024 }))).toEqual({
      status: 'AVAILABLE',
      path: '/gone/a.pftrace',
    });
  });
});

describe('mergeArtifactLocation', () => {
  it('prepends a new artifact when the content is unknown', () => {
    const merged = mergeArtifactLocation([artifact({ id: 'old' })], artifact({ id: 'new', sha256: 'b'.repeat(64) }));
    expect(merged.map((entry) => entry.id)).toEqual(['new', 'old']);
  });

  it('merges a same-content import into one artifact with every location', () => {
    const existing = artifact({ id: 'old', path: '/first/a.pftrace', alternativePaths: ['/second/a.pftrace'] });
    const imported = artifact({ id: 'new', path: '/third/a.pftrace', importedAtEpochMillis: 42 });
    const merged = mergeArtifactLocation([existing], imported);
    expect(merged).toHaveLength(1);
    expect(merged[0]?.id).toBe('old');
    expect(merged[0]?.path).toBe('/third/a.pftrace');
    expect([...(merged[0]?.alternativePaths ?? [])].sort()).toEqual(['/first/a.pftrace', '/second/a.pftrace']);
    expect(merged[0]?.importedAtEpochMillis).toBe(42);
  });
});

describe('index dependencies', () => {
  it('hashes and reads real files through the default dependencies', async () => {
    const { defaultArtifactIndexDependencies, importGpuArtifact } = await import('./artifact-index.js');
    const directory = await temporaryDirectory();
    const file = join(directory, 'sample.pftrace');
    await writeFile(file, 'PERFETTO-DATA');
    const dependencies: ArtifactIndexDependencies = defaultArtifactIndexDependencies();

    expect(dependencies.isRegularFile(file)).toBe(true);
    expect(dependencies.isRegularFile(join(directory, 'missing'))).toBe(false);
    expect(await dependencies.readHeader(file, 8)).toBe('PERFETTO');

    const imported = await importGpuArtifact(dependencies, { path: file, id: 'x', now: () => 5 });
    expect(imported.kind).toBe('PERFETTO_TRACE');
    expect(imported.sha256).toMatch(/^[0-9a-f]{64}$/);
    expect(imported.sizeBytes).toBe(13);
    expect(imported.openRoute).toBe('PERFETTO');
    expect(imported.importedAtEpochMillis).toBe(5);
    await expect(
      importGpuArtifact(dependencies, { path: join(directory, 'missing'), id: 'y', now: () => 1 }),
    ).rejects.toThrow(/does not exist/);
  });

  it('flags an unknown format as opaque evidence', async () => {
    const { defaultArtifactIndexDependencies, importGpuArtifact } = await import('./artifact-index.js');
    const directory = await temporaryDirectory();
    const file = join(directory, 'mystery.dat');
    await writeFile(file, 'nothing recognizable');
    const imported = await importGpuArtifact(defaultArtifactIndexDependencies(), { path: file, id: 'z', now: () => 1 });
    expect(imported.kind).toBe('UNKNOWN');
    expect(imported.openRoute).toBe('NONE');
    expect(imported.warnings[0]).toContain('opaque evidence');
  });
});
