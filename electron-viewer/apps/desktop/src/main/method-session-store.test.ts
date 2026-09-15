import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { parseArtTrace, toCallStackTable } from '@aps/art-trace';
import { streamingTrace } from '@aps/art-trace/testing';
import { MethodSessionStore, type MethodSessionRecord } from './method-session-store.js';

const directories: string[] = [];

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'aps-method-store-'));
  directories.push(directory);
  return directory;
}

afterEach(async () => {
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

function record(id: string, capturedAtEpochMillis: number): MethodSessionRecord {
  return {
    id,
    capturedAtEpochMillis,
    origin: 'CAPTURED',
    serial: 'emulator-5554',
    packageName: 'com.example.app',
    pid: 4242,
    durationSeconds: 5,
    deviceSdkApiLevel: 34,
    traceVersion: 5,
    traceBytes: 128,
    eventCount: 2,
    methodCount: 2,
    threadCount: 1,
    threadKeys: ['main (tid 7)'],
    warnings: [],
  };
}

function importedRecord(id: string, capturedAtEpochMillis: number): MethodSessionRecord {
  return {
    id,
    capturedAtEpochMillis,
    origin: 'IMPORTED',
    sourceFileName: 'offline.trace',
    traceVersion: 5,
    traceBytes: 128,
    eventCount: 2,
    methodCount: 2,
    threadCount: 1,
    threadKeys: ['main (tid 7)'],
    warnings: [],
  };
}

function parsed() {
  const result = parseArtTrace(streamingTrace());
  if (!result.ok) throw new Error('fixture failed to parse');
  return { analysis: result.value, table: toCallStackTable(result.value) };
}

describe('MethodSessionStore', () => {
  it('stores the trace, the record, and the cached session', async () => {
    const directory = await temporaryDirectory();
    const store = new MethodSessionStore(join(directory, 'method-sessions'));
    expect(await store.list()).toEqual([]);

    const session = parsed();
    const entry = record('a', 1_000);
    await store.save(entry, streamingTrace(), { record: entry, ...session });
    expect(store.cached('a')?.analysis.events).toHaveLength(2);
    expect(await store.list()).toHaveLength(1);

    const loaded = await store.readRecord('a');
    expect(loaded?.packageName).toBe('com.example.app');
    const trace = await store.readTrace('a');
    expect(trace?.length).toBeGreaterThan(0);
    expect(store.tracePath('a')).toBe(join(directory, 'method-sessions', 'a', 'method.trace'));
  });

  it('round trips an imported trace without fabricated capture metadata', async () => {
    const directory = await temporaryDirectory();
    const store = new MethodSessionStore(join(directory, 'method-sessions'));
    const session = parsed();
    const entry = importedRecord('imported', 2_000);
    const bytes = streamingTrace();

    await store.save(entry, bytes, { record: entry, ...session });

    expect(await store.readRecord('imported')).toEqual(entry);
    expect(await store.readTrace('imported')).toEqual(bytes);
    expect((await store.list())[0]).toMatchObject({
      id: 'imported',
      origin: 'IMPORTED',
      sourceFileName: 'offline.trace',
    });
  });

  it('reads captured records written before origins were recorded', async () => {
    const directory = await temporaryDirectory();
    const store = new MethodSessionStore(join(directory, 'method-sessions'));
    const legacy = { ...record('legacy', 1_000), origin: undefined };
    await mkdir(store.directoryFor(legacy.id), { recursive: true });
    await writeFile(store.tracePath(legacy.id), streamingTrace());
    await writeFile(join(store.directoryFor(legacy.id), 'session.json'), JSON.stringify(legacy));

    expect(await store.readRecord(legacy.id)).toEqual(legacy);
  });

  it('skips malformed provenance records without hiding valid sessions', async () => {
    const directory = await temporaryDirectory();
    const store = new MethodSessionStore(join(directory, 'method-sessions'));
    const valid = importedRecord('valid', 2_000);
    const invalidRecords: readonly [string, unknown][] = [
      ['missing-source', { ...valid, id: 'missing-source', sourceFileName: undefined }],
      ['missing-target', { ...record('missing-target', 1_000), pid: undefined }],
      ['unknown-origin', { ...valid, id: 'unknown-origin', origin: 'OTHER' }],
      ['invalid-threads', { ...valid, id: 'invalid-threads', threadKeys: ['main', 7] }],
      ['invalid-warnings', { ...valid, id: 'invalid-warnings', warnings: 'none' }],
    ];
    await mkdir(store.directoryFor(valid.id), { recursive: true });
    await writeFile(join(store.directoryFor(valid.id), 'session.json'), JSON.stringify(valid));
    for (const [id, value] of invalidRecords) {
      await mkdir(store.directoryFor(id), { recursive: true });
      await writeFile(join(store.directoryFor(id), 'session.json'), JSON.stringify(value));
    }

    expect((await store.list()).map((entry) => entry.id)).toEqual(['valid']);
  });

  it('lists newest first and rejects traversal or unknown ids', async () => {
    const directory = await temporaryDirectory();
    const store = new MethodSessionStore(join(directory, 'method-sessions'));
    const session = parsed();
    await store.save(record('old', 1_000), streamingTrace(), { record: record('old', 1_000), ...session });
    await store.save(record('new', 2_000), streamingTrace(), { record: record('new', 2_000), ...session });
    expect((await store.list()).map((entry) => entry.id)).toEqual(['new', 'old']);
    expect(await store.readRecord('../escape')).toBeUndefined();
    expect(await store.readTrace('missing')).toBeUndefined();
    expect(await store.remove('missing')).toBe(false);
    expect(await store.remove('new')).toBe(true);
    expect((await store.list()).map((entry) => entry.id)).toEqual(['old']);
  });
});
