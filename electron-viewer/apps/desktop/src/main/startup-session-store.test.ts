import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import {
  createStartupSession,
  DEFAULT_STARTUP_EXPERIMENT,
  UNAVAILABLE_EVIDENCE,
  exportKotlinStartupJson,
  importKotlinStartupJson,
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

  it('persists an imported legacy report without inventing a package identity', async () => {
    const directory = await temporaryDirectory();
    const store = new StartupSessionStore(join(directory, 'startup'));
    const report = JSON.stringify({
      schemaVersion: 1,
      warnings: [],
      runs: [{
        iteration: 1,
        runId: 'legacy-run',
        requestedType: 'COLD',
        observedType: 'COLD',
        displayedTimeMs: 120,
        rawEvidence: { amStartOutput: 'Status: ok' },
      }],
    });
    const imported = importKotlinStartupJson(report, {
      id: 'legacy-import',
      capturedAtEpochMillis: 123,
      sourceFileName: 'legacy-startup.json',
    });

    const summary = await store.add(imported);
    expect(summary).toEqual({
      id: 'legacy-import',
      capturedAtEpochMillis: 123,
      measuredRuns: 1,
      p90LowResolution: true,
    });
    expect(summary).not.toHaveProperty('packageName');
    expect(summary).not.toHaveProperty('medianTotalTimeMs');
    expect(summary).not.toHaveProperty('p90TotalTimeMs');

    const loaded = await store.load('legacy-import');
    expect(loaded).toMatchObject({
      id: 'legacy-import',
      origin: 'IMPORTED',
      deviceSerial: 'IMPORTED',
      sourceFileName: 'legacy-startup.json',
    });
    expect(loaded).not.toHaveProperty('packageName');
    expect(loaded).not.toHaveProperty('sourceDeviceLocalId');
    expect(loaded).toBeDefined();
    expect(exportKotlinStartupJson(loaded!)).toBe(report);
  });

  it('retains an imported Kotlin device pseudonym as provenance rather than a device serial', async () => {
    const directory = await temporaryDirectory();
    const store = new StartupSessionStore(join(directory, 'startup'));
    const imported = importKotlinStartupJson(
      JSON.stringify({
        schemaVersion: 1,
        warnings: [],
        runs: [{
          iteration: 1,
          runId: 'contextual-run',
          requestedType: 'COLD',
          observedType: 'COLD',
          rawEvidence: { amStartOutput: 'Status: ok' },
          context: {
            deviceLocalId: 'pseudonymous-device',
            packageName: 'com.example.app',
            componentName: 'com.example.app/.MainActivity',
          },
        }],
      }),
      {
        id: 'contextual-import',
        capturedAtEpochMillis: 456,
        sourceFileName: 'contextual-startup.json',
      },
    );

    await store.add(imported);
    expect(await store.load('contextual-import')).toMatchObject({
      origin: 'IMPORTED',
      deviceSerial: 'IMPORTED',
      sourceDeviceLocalId: 'pseudonymous-device',
      sourceFileName: 'contextual-startup.json',
      packageName: 'com.example.app',
    });
  });
});
