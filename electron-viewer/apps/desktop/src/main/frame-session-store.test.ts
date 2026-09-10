import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { createFrameSession, type FrameSample } from '@aps/frame-profiler';
import { FrameSessionStore } from './frame-session-store.js';

const directories: string[] = [];

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'aps-frames-'));
  directories.push(directory);
  return directory;
}

afterEach(async () => {
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

function sample(frameId: number, totalDurationNs: number): FrameSample {
  return {
    frameId,
    sessionId: 's',
    source: 'GFXINFO',
    expectedDurationNs: 16_666_666,
    expectedDurationSource: 'PLATFORM_DEADLINE',
    totalDurationNs,
    stages: {},
    eligibleForJank: true,
    platformJankTypes: [],
    states: {},
  };
}

describe('FrameSessionStore', () => {
  it('stores raw frames and indexes a derived summary', async () => {
    const directory = await temporaryDirectory();
    const store = new FrameSessionStore(join(directory, 'frames'));
    expect(await store.list()).toEqual([]);

    const session = createFrameSession({
      id: 'session-1',
      packageName: 'com.example.app',
      capturedAtEpochMillis: 100,
      frames: [sample(0, 10_000_000), sample(1, 40_000_000)],
    });
    const summary = await store.add(session);
    expect(summary.frameCount).toBe(2);
    expect(summary.deadlineMissRate).toBeCloseTo(0.5);
    expect(summary.worstDurationNs).toBe(40_000_000);

    const loaded = await store.load('session-1');
    expect(loaded?.frames).toHaveLength(2);
    expect(loaded?.warnings).toEqual([]);
    expect((await store.list())[0]?.id).toBe('session-1');
  });

  it('keeps the newest session first and tolerates a malformed index', async () => {
    const directory = await temporaryDirectory();
    const store = new FrameSessionStore(join(directory, 'frames'));
    await store.add(createFrameSession({ id: 'a', packageName: 'p', capturedAtEpochMillis: 1, frames: [sample(0, 1)] }));
    await store.add(createFrameSession({ id: 'b', packageName: 'p', capturedAtEpochMillis: 2, frames: [sample(0, 1)] }));
    expect((await store.list()).map((entry) => entry.id)).toEqual(['b', 'a']);
    expect(await store.load('missing')).toBeUndefined();
  });
});
