import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { defaultArtifactIndexDependencies } from '@aps/gpu-inspector/node';
import { GpuArtifactStore } from './gpu-artifact-store.js';

const directories: string[] = [];

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'aps-gpu-store-'));
  directories.push(directory);
  return directory;
}

afterEach(async () => {
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

describe('GpuArtifactStore', () => {
  it('imports an artifact and reports its location status', async () => {
    const directory = await temporaryDirectory();
    const file = join(directory, 'profile.pftrace');
    await writeFile(file, 'PERFETTO-DATA');
    const store = new GpuArtifactStore(join(directory, 'index'), defaultArtifactIndexDependencies());

    expect(await store.list()).toEqual([]);
    const artifact = await store.importArtifact({ path: file, id: 'a', now: () => 7 });
    expect(artifact.kind).toBe('PERFETTO_TRACE');
    expect(artifact.openRoute).toBe('PERFETTO');
    expect(artifact.importedAtEpochMillis).toBe(7);

    const summaries = await store.summarize();
    expect(summaries).toHaveLength(1);
    expect(summaries[0]).toMatchObject({ id: 'a', locationStatus: 'AVAILABLE', locationCount: 1 });
    expect((await store.resolveLocation('a'))?.status).toBe('AVAILABLE');
    expect(await store.resolveLocation('missing')).toBeUndefined();
  });

  it('merges a same-content import and keeps both locations', async () => {
    const directory = await temporaryDirectory();
    const first = join(directory, 'one.pftrace');
    const second = join(directory, 'two.pftrace');
    await writeFile(first, 'SAME-CONTENT');
    await writeFile(second, 'SAME-CONTENT');
    const store = new GpuArtifactStore(join(directory, 'index'), defaultArtifactIndexDependencies());

    await store.importArtifact({ path: first, id: 'a', now: () => 1 });
    const merged = await store.importArtifact({ path: second, id: 'b', now: () => 2 });
    expect(merged.id).toBe('a');
    expect(merged.path).toBe(second);
    expect(await store.list()).toHaveLength(1);
    expect((await store.summarize())[0]?.locationCount).toBe(2);
  });

  it('requires a relocation target with the same size and digest', async () => {
    const directory = await temporaryDirectory();
    const file = join(directory, 'profile.pftrace');
    await writeFile(file, 'PERFETTO-DATA');
    const store = new GpuArtifactStore(join(directory, 'index'), defaultArtifactIndexDependencies());
    await store.importArtifact({ path: file, id: 'a', now: () => 1 });

    const same = join(directory, 'moved.pftrace');
    await writeFile(same, 'PERFETTO-DATA');
    const relocated = await store.relocate('a', same);
    expect(relocated?.path).toBe(same);
    expect(await store.resolveLocation('a')).toMatchObject({ status: 'AVAILABLE', path: same });

    const different = join(directory, 'different.pftrace');
    await writeFile(different, 'OTHER');
    await expect(store.relocate('a', different)).rejects.toThrow(/size does not match/);
    await expect(store.relocate('unknown', same)).resolves.toBeUndefined();

    // The original location still holds the same content, so the artifact stays
    // available even after the copy is gone; only losing every location makes it
    // missing.
    await rm(same);
    expect((await store.resolveLocation('a'))?.status).toBe('AVAILABLE');
    await rm(file);
    expect((await store.resolveLocation('a'))?.status).toBe('MISSING');
  });
});
