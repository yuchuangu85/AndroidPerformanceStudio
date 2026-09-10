import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { TraceStore } from './trace-store.js';

const directories: string[] = [];

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'aps-traces-'));
  directories.push(directory);
  return directory;
}

afterEach(async () => {
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

describe('TraceStore', () => {
  it('starts empty and records added traces newest first', async () => {
    const directory = await temporaryDirectory();
    const source = join(directory, 'source.pftrace');
    await writeFile(source, 'trace-bytes');
    const store = new TraceStore(join(directory, 'traces'));
    expect(await store.list()).toEqual([]);

    const first = await store.addTrace(source, {
      sha256: 'a'.repeat(64),
      capturedAtEpochMillis: 1,
      durationMillis: 1000,
      deviceSerial: 'emulator-5554',
    });
    const second = await store.addTrace(source, {
      sha256: 'b'.repeat(64),
      capturedAtEpochMillis: 2,
      durationMillis: 2000,
    });
    const records = await store.list();
    expect(records.map((record) => record.id)).toEqual([second.id, first.id]);
    expect(records[0]?.deviceSerial).toBeUndefined();
    expect(records[1]?.deviceSerial).toBe('emulator-5554');
  });

  it('replaces a record with the same id instead of duplicating it', async () => {
    const directory = await temporaryDirectory();
    const source = join(directory, 'source.pftrace');
    await writeFile(source, 'trace-bytes');
    const store = new TraceStore(join(directory, 'traces'));
    const meta = { sha256: 'c'.repeat(64), capturedAtEpochMillis: 5, durationMillis: 1000 };
    await store.addTrace(source, meta);
    await store.addTrace(source, meta);
    expect(await store.list()).toHaveLength(1);
  });

  it('ignores a malformed index', async () => {
    const directory = await temporaryDirectory();
    const store = new TraceStore(directory);
    await writeFile(join(directory, 'index.json'), '{not json');
    expect(await store.list()).toEqual([]);
  });
});
