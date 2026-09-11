import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import type { SourceIndex } from './source-workspace-service.js';
import { SourceWorkspaceStore } from './source-workspace-store.js';
import type { SourceWorkspaceRecord } from '../shared/ipc.js';

const directories: string[] = [];

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'aps-source-store-'));
  directories.push(directory);
  return directory;
}

afterEach(async () => {
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

function record(id: string, displayName: string): SourceWorkspaceRecord {
  return { id, displayName, root: '/src/' + id, phase: 'READY', fileCount: 2, symbolCount: 3 };
}

function index(workspaceId: string): SourceIndex {
  return {
    snapshot: {
      id: 'snap-' + workspaceId,
      workspaceId,
      immutableRevision: 'abc123',
      manifestHash: 'manifest',
      createdAtEpochMillis: 1_700_000_000_000,
      indexVersion: 1,
      indexComplete: true,
    },
    files: [],
    symbols: [],
  };
}

describe('SourceWorkspaceStore', () => {
  it('persists a workspace with its index summary', async () => {
    const directory = await temporaryDirectory();
    const store = new SourceWorkspaceStore(join(directory, 'sources'));
    expect(await store.list()).toEqual([]);

    await store.save(record('a', 'Alpha'), index('a'));
    expect(store.cachedIndex('a')?.snapshot.id).toBe('snap-a');
    const records = await store.list();
    expect(records).toHaveLength(1);
    expect(records[0]?.displayName).toBe('Alpha');
  });

  it('keeps the newest summary and drops the cache when the index is cleared', async () => {
    const directory = await temporaryDirectory();
    const store = new SourceWorkspaceStore(join(directory, 'sources'));
    await store.save(record('a', 'Alpha'), index('a'));
    await store.save({ ...record('a', 'Alpha renamed'), fileCount: 0, symbolCount: 0 }, undefined);
    expect(store.cachedIndex('a')).toBeUndefined();
    expect((await store.list())[0]?.displayName).toBe('Alpha renamed');
  });

  it('sorts by name and refuses traversal or unknown ids', async () => {
    const directory = await temporaryDirectory();
    const store = new SourceWorkspaceStore(join(directory, 'sources'));
    await store.save(record('b', 'Beta'), index('b'));
    await store.save(record('a', 'Alpha'), index('a'));
    expect((await store.list()).map((entry) => entry.displayName)).toEqual(['Alpha', 'Beta']);
    expect(await store.readRecord('../escape')).toBeUndefined();
    expect(await store.remove('missing')).toBe(false);
    expect(await store.remove('a')).toBe(true);
    expect((await store.list()).map((entry) => entry.id)).toEqual(['b']);
  });
});
