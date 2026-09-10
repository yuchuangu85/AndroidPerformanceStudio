import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import type { MemorySession } from '@aps/memory-profiler';
import { MemorySessionStore, summarizeMemorySession } from './memory-session-store.js';

const directories: string[] = [];

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'aps-memory-store-'));
  directories.push(directory);
  return directory;
}

afterEach(async () => {
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

function session(id: string): MemorySession {
  return {
    id,
    packageName: 'com.example.app',
    deviceSerial: 'SER',
    capturedAtEpochMillis: 10,
    summary: { version: '1.0.3', identifierSize: 4, classCount: 3, instanceCount: 5, arrayCount: 1, shallowBytes: 2048 },
    histogram: [{ className: 'com.example.Node', instanceCount: 5, shallowBytes: 2048 }],
    suspects: [
      { className: 'com.example.Node', objectId: '0x300', retainedBytes: 1024, shallowBytes: 512, referenceChain: ['0x200', '0x300'] },
    ],
    warnings: ['one warning'],
  };
}

describe('MemorySessionStore', () => {
  it('summarizes and round-trips a session', async () => {
    const directory = await temporaryDirectory();
    const store = new MemorySessionStore(join(directory, 'memory'));
    expect(await store.list()).toEqual([]);

    const summary = await store.add(session('a'));
    expect(summary).toMatchObject({
      id: 'a',
      instanceCount: 5,
      shallowBytes: 2048,
      suspectCount: 1,
      warningCount: 1,
      packageName: 'com.example.app',
    });
    const loaded = await store.load('a');
    expect(loaded?.suspects[0]?.objectId).toBe('0x300');
    expect(summarizeMemorySession(session('b')).classCount).toBe(3);
  });

  it('keeps the newest session first and returns undefined for an unknown id', async () => {
    const directory = await temporaryDirectory();
    const store = new MemorySessionStore(join(directory, 'memory'));
    await store.add(session('a'));
    await store.add(session('b'));
    expect((await store.list()).map((entry) => entry.id)).toEqual(['b', 'a']);
    expect(await store.load('missing')).toBeUndefined();
  });
});
