import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { samplesToCallStackTable, type CpuProfileSessionRecord, type NormalizedSample } from '@aps/simpleperf-profiler';
import { CpuProfileStore } from './cpu-profile-store.js';

const directories: string[] = [];

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'aps-cpu-store-'));
  directories.push(directory);
  return directory;
}

afterEach(async () => {
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

function record(id: string, capturedAtEpochMillis: number): CpuProfileSessionRecord {
  return {
    id,
    capturedAtEpochMillis,
    serial: 'emulator-5554',
    reportFile: id + '/report.pb',
    perfDataBytes: 4096,
    sampleCount: 2,
    lostCount: 0,
    eventTypes: ['cpu-clock'],
    threadKeys: ['main (tid 42)'],
    metadata: { traceOffCpu: false },
    parameters: { target: 'com.example.app', event: 'cpu-clock', rate: '1000 Hz', callGraph: 'DWARF', scope: 'BOTH' },
  };
}

function table() {
  const samples: NormalizedSample[] = [
    {
      timestampNanos: 1n,
      processId: 1,
      threadId: 42,
      threadName: 'main',
      eventType: 'cpu-clock',
      eventCount: 3n,
      frames: [
        {
          virtualAddress: 1n,
          fileId: 0,
          symbolId: 0,
          filePath: '/lib.so',
          symbolName: 'memcpy',
          executionType: 'NATIVE',
        },
      ],
    },
  ];
  return samplesToCallStackTable(samples);
}

describe('CpuProfileStore', () => {
  it('saves the report, the record, and the cached table', async () => {
    const directory = await temporaryDirectory();
    const store = new CpuProfileStore(join(directory, 'cpu-profiles'));
    expect(await store.list()).toEqual([]);

    const parsed = table();
    await store.save(record('a', 1_000), new Uint8Array([1, 2, 3]), parsed);
    expect(await store.list()).toHaveLength(1);
    expect(store.cachedTable('a')).toBe(parsed);
    expect(store.reportPath('a')).toBe(join(directory, 'cpu-profiles', 'a', 'report.pb'));

    const loaded = await store.readRecord('a');
    expect(loaded?.id).toBe('a');
    const report = loaded === undefined ? undefined : await store.readReport(loaded);
    expect(report).toBeInstanceOf(Uint8Array);
    expect(Array.from(report ?? [])).toEqual([1, 2, 3]);
  });

  it('lists newest first and ignores an index that is not a session', async () => {
    const directory = await temporaryDirectory();
    const store = new CpuProfileStore(join(directory, 'cpu-profiles'));
    await store.save(record('old', 1_000), new Uint8Array([1]), table());
    await store.save(record('new', 2_000), new Uint8Array([2]), table());
    expect((await store.list()).map((entry) => entry.id)).toEqual(['new', 'old']);
    expect(await store.readRecord('index.json')).toBeUndefined();
  });

  it('refuses path traversal ids, unknown ids, and removes a session', async () => {
    const directory = await temporaryDirectory();
    const store = new CpuProfileStore(join(directory, 'cpu-profiles'));
    await store.save(record('a', 1_000), new Uint8Array([1]), table());

    expect(await store.readRecord('../escape')).toBeUndefined();
    expect(await store.readRecord('missing')).toBeUndefined();
    expect(await store.remove('missing')).toBe(false);
    expect(await store.remove('a')).toBe(true);
    expect(await store.list()).toEqual([]);
    expect(store.cachedTable('a')).toBeUndefined();
  });
});
