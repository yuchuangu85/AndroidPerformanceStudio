import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import {
  createStartupSession,
  DEFAULT_STARTUP_EXPERIMENT,
  UNAVAILABLE_EVIDENCE,
  type StartupRun,
} from '@aps/startup-profiler';
import { StartupSessionStore } from './startup-session-store.js';

const directories: string[] = [];

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'aps-startup-'));
  directories.push(directory);
  return directory;
}

afterEach(async () => {
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

function run(iteration: number, totalTimeMs: number | undefined): StartupRun {
  return {
    id: 'run-' + String(iteration),
    sessionId: 'session',
    iteration,
    measured: true,
    requestedType: 'COLD',
    observedType: 'COLD',
    platform: { complete: true, ...(totalTimeMs !== undefined ? { totalTimeMs } : {}) },
    warnings: [],
    amStartOutput: '',
    ttidEvidence: UNAVAILABLE_EVIDENCE,
    ttfdEvidence: UNAVAILABLE_EVIDENCE,
  };
}

function session(id: string, times: Array<number | undefined>) {
  return createStartupSession({
    id,
    deviceSerial: 'SER',
    packageName: 'com.example.app',
    config: DEFAULT_STARTUP_EXPERIMENT,
    createdAtEpochMillis: 100,
    runs: times.map((time, index) => run(index + 1, time)),
  });
}

describe('StartupSessionStore', () => {
  it('stores sessions and indexes measured statistics', async () => {
    const directory = await temporaryDirectory();
    const store = new StartupSessionStore(join(directory, 'startup'));
    expect(await store.list()).toEqual([]);

    const summary = await store.add(session('a', [500, 520, 540, 560, 580]));
    expect(summary.measuredRuns).toBe(5);
    expect(summary.medianTotalTimeMs).toBe(540);
    expect(summary.p90TotalTimeMs).toBe(580);
    expect(summary.p90LowResolution).toBe(true);

    const loaded = await store.load('a');
    expect(loaded?.runs).toHaveLength(5);
    expect((await store.list()).map((entry) => entry.id)).toEqual(['a']);
  });

  it('keeps the newest session first and returns undefined for an unknown id', async () => {
    const directory = await temporaryDirectory();
    const store = new StartupSessionStore(join(directory, 'startup'));
    await store.add(session('a', [500]));
    await store.add(session('b', [600]));
    expect((await store.list()).map((entry) => entry.id)).toEqual(['b', 'a']);
    expect(await store.load('missing')).toBeUndefined();
  });

  it('omits statistics when no run reported a total time', async () => {
    const directory = await temporaryDirectory();
    const store = new StartupSessionStore(join(directory, 'startup'));
    const summary = await store.add(session('a', [undefined, undefined]));
    expect(summary.medianTotalTimeMs).toBeUndefined();
    expect(summary.p90TotalTimeMs).toBeUndefined();
    expect(summary.measuredRuns).toBe(2);
  });
});
