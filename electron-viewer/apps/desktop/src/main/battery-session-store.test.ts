import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import {
  analyzeBatteryRuns,
  createBatterySession,
  DEFAULT_BATTERY_EXPERIMENT,
  EMPTY_NETWORK_USAGE,
  type BatteryExperimentResult,
  type BatteryRun,
  type BatterySnapshot,
  type UidBatteryStats,
} from '@aps/battery-profiler';
import { BatterySessionStore, summarizeBatteryExperiment, totalBytesOf } from './battery-session-store.js';

const directories: string[] = [];

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'aps-battery-'));
  directories.push(directory);
  return directory;
}

afterEach(async () => {
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

function uidStats(overrides: Partial<UidBatteryStats> = {}): UidBatteryStats {
  return {
    uid: 10123,
    wakelocks: {},
    alarms: {},
    jobs: {},
    sensors: {},
    network: EMPTY_NETWORK_USAGE,
    energy: {},
    ...overrides,
  };
}

function snapshot(overrides: Partial<BatterySnapshot> = {}): BatterySnapshot {
  return {
    id: 'snap',
    sessionId: 'session',
    sequence: 0,
    capturedAtEpochMillis: 1000,
    uidStats: uidStats(),
    deviceState: { rawValues: {} },
    history: [],
    warnings: [],
    conditions: {},
    ...overrides,
  };
}

function result(id: string): BatteryExperimentResult {
  const session = createBatterySession({
    id,
    deviceSerial: 'SER',
    packageName: 'com.example.app',
    uid: 10123,
    config: DEFAULT_BATTERY_EXPERIMENT,
    createdAtEpochMillis: 100,
  });
  const run: BatteryRun = {
    id: id + '-run-1',
    sessionId: id,
    iteration: 1,
    baseline: snapshot({
      uidStats: uidStats({
        energy: {
          total: { component: 'total', energyMah: 0, source: 'SYSTEM_MODEL', attributionScope: 'UID', confidence: 'MODELED' },
        },
      }),
    }),
    samples: [],
    finalSnapshot: snapshot({
      capturedAtEpochMillis: 61_000,
      uidStats: uidStats({
        wakelocks: { AudioMix: { name: 'AudioMix', durationMs: 4000, count: 2, confidence: 'EXACT' } },
        network: { ...EMPTY_NETWORK_USAGE, wifiRxBytes: 2048 },
        energy: {
          total: { component: 'total', energyMah: 3.5, source: 'SYSTEM_MODEL', attributionScope: 'UID', confidence: 'MODELED' },
        },
      }),
      warnings: ['a snapshot warning'],
    }),
  };
  return { session, runs: [run], analysis: analyzeBatteryRuns([run]) };
}

describe('BatterySessionStore', () => {
  it('stores the experiment and indexes derived medians', async () => {
    const directory = await temporaryDirectory();
    const store = new BatterySessionStore(join(directory, 'battery'));
    expect(await store.list()).toEqual([]);

    const summary = await store.add(result('a'));
    expect(summary.runCount).toBe(1);
    expect(summary.wakelockMedianMs).toBe(4000);
    expect(summary.networkMedianBytes).toBe(2048);
    expect(summary.energyMedianMah).toBeCloseTo(3.5);
    expect(summary.warningCount).toBeGreaterThan(0);

    const loaded = await store.load('a');
    expect(loaded?.runs).toHaveLength(1);
    expect(totalBytesOf(loaded as BatteryExperimentResult)).toBe(2048);
    expect(summarizeBatteryExperiment(loaded as BatteryExperimentResult).id).toBe('a');
  });

  it('keeps the newest experiment first and tolerates a malformed index', async () => {
    const directory = await temporaryDirectory();
    const store = new BatterySessionStore(join(directory, 'battery'));
    await store.add(result('a'));
    await store.add(result('b'));
    expect((await store.list()).map((entry) => entry.id)).toEqual(['b', 'a']);
    expect(await store.load('missing')).toBeUndefined();
  });
});
