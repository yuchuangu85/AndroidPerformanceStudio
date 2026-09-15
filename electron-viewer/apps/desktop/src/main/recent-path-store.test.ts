import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { RecentPathStore } from './recent-path-store.js';

const temporaryDirectories: string[] = [];

async function temporaryStorage(): Promise<{ root: string; storage: string }> {
  const root = await mkdtemp(join(tmpdir(), 'aps-recent-paths-'));
  temporaryDirectories.push(root);
  return { root, storage: join(root, 'state', 'recent-layout-archives.txt') };
}

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

describe('RecentPathStore', () => {
  it('normalizes, de-duplicates, bounds, and durably persists newest-first paths', async () => {
    const { root, storage } = await temporaryStorage();
    const store = new RecentPathStore(storage, 3);
    const first = join(root, 'captures', 'one.apinspect');
    const second = join(root, 'captures', 'two.apinspect');
    const third = join(root, 'captures', 'three.apinspect');
    const fourth = join(root, 'captures', 'four.apinspect');

    await store.record(first);
    await store.record(second);
    await store.record(third);
    await store.record(join(root, 'captures', 'nested', '..', 'one.apinspect'));
    const result = await store.record(fourth);

    expect(result).toEqual([resolve(fourth), resolve(first), resolve(third)]);
    expect(await new RecentPathStore(storage, 3).load()).toEqual(result);
    expect(await readFile(storage, 'utf8')).toBe(result.join('\n') + '\n');
  });

  it('ignores blank, malformed, duplicate, and excess persisted entries without hiding valid paths', async () => {
    const { root, storage } = await temporaryStorage();
    await mkdir(join(root, 'state'), { recursive: true });
    const first = resolve(root, 'first.apinspect');
    const second = resolve(root, 'second.apinspect');
    await writeFile(storage, `\n\0invalid\n${first}\n${first}\n${second}\n${resolve(root, 'excess.apinspect')}\n`);

    expect(await new RecentPathStore(storage, 2).load()).toEqual([first, second]);
  });

  it('returns an empty history for missing directories and unreadable storage', async () => {
    const { storage } = await temporaryStorage();
    expect(await new RecentPathStore(storage).load()).toEqual([]);

    await mkdir(storage, { recursive: true });
    expect(await new RecentPathStore(storage).load()).toEqual([]);
  });

  it('serializes concurrent records and clear removes the persisted history', async () => {
    const { root, storage } = await temporaryStorage();
    const store = new RecentPathStore(storage);
    const first = resolve(root, 'first.apinspect');
    const second = resolve(root, 'second.apinspect');

    await Promise.all([store.record(first), store.record(second)]);
    expect(await store.load()).toEqual([second, first]);

    await store.clear();
    expect(await store.load()).toEqual([]);
    await expect(readFile(storage, 'utf8')).rejects.toMatchObject({ code: 'ENOENT' });
  });

  it('rejects invalid capacity and paths containing NUL', async () => {
    const { storage } = await temporaryStorage();
    expect(() => new RecentPathStore(storage, 0)).toThrow('maximumEntries must be a positive integer');
    await expect(new RecentPathStore(storage).record('\0invalid')).rejects.toThrow('path must not contain NUL');
  });
});
