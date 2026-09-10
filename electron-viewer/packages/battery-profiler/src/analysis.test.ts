import { describe, expect, it } from 'vitest';
import { analyzeBatteryRuns, batteryStatistics, diffBatteryRun } from './analysis.js';
import {
  EMPTY_NETWORK_USAGE,
  type BatteryRun,
  type BatterySnapshot,
  type EnergyEstimate,
  type ResourceTimer,
  type UidBatteryStats,
} from './model.js';

function timer(name: string, durationMs: number, count = 1): ResourceTimer {
  return { name, durationMs, count, confidence: 'EXACT' };
}

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

function run(baseline: BatterySnapshot, finalSnapshot: BatterySnapshot, iteration = 1): BatteryRun {
  return { id: 'run-' + String(iteration), sessionId: 'session', iteration, baseline, samples: [], finalSnapshot };
}

describe('batteryStatistics', () => {
  it('computes spread and counts missing values', () => {
    const statistics = batteryStatistics([1, 2, 3, 4, undefined]);
    expect(statistics.count).toBe(4);
    expect(statistics.missingCount).toBe(1);
    // Nearest-rank percentile: ceil(4 * 0.5) - 1 = index 1.
    expect(statistics.median).toBe(2);
    expect(statistics.maximum).toBe(4);
  });

  it('returns an empty result when nothing is present', () => {
    expect(batteryStatistics([undefined, undefined])).toEqual({ count: 0, missingCount: 2 });
  });
});

describe('diffBatteryRun', () => {
  it('diffs timers, network, and energy between snapshots', () => {
    const before = snapshot({
      uidStats: uidStats({
        wakelocks: { AudioMix: timer('AudioMix', 1000, 1) },
        alarms: { Alarm: timer('Alarm', 0, 2) },
        network: { ...EMPTY_NETWORK_USAGE, wifiRxBytes: 1000, mobileRadioActiveMs: 100 },
        energy: { total: { component: 'total', energyMah: 10, source: 'SYSTEM_MODEL', attributionScope: 'UID', confidence: 'MODELED' } },
      }),
    });
    const after = snapshot({
      capturedAtEpochMillis: 61_000,
      uidStats: uidStats({
        wakelocks: { AudioMix: timer('AudioMix', 4000, 3) },
        alarms: { Alarm: timer('Alarm', 0, 5) },
        network: { ...EMPTY_NETWORK_USAGE, wifiRxBytes: 2500, mobileRadioActiveMs: 400 },
        energy: { total: { component: 'total', energyMah: 14.5, source: 'SYSTEM_MODEL', attributionScope: 'UID', confidence: 'MODELED' } },
      }),
    });
    const delta = diffBatteryRun(run(before, after));
    expect(delta.durationMs).toBe(60_000);
    expect(delta.wakelocks[0]).toMatchObject({ name: 'AudioMix', durationMs: 3000, count: 2 });
    expect(delta.alarms[0]?.count).toBe(3);
    expect(delta.network.wifiRxBytes).toBe(1500);
    expect(delta.network.mobileRadioActiveMs).toBe(300);
    expect(delta.energy[0]?.energyMah).toBeCloseTo(4.5);
    expect(delta.warnings).toEqual([]);
  });

  it('drops counters that reset and reports them', () => {
    const before = snapshot({
      uidStats: uidStats({
        wakelocks: { AudioMix: timer('AudioMix', 5000, 5) },
        network: { ...EMPTY_NETWORK_USAGE, wifiRxBytes: 900 },
        energy: { total: { component: 'total', energyMah: 20, source: 'SYSTEM_MODEL', attributionScope: 'UID', confidence: 'MODELED' } },
      }),
    });
    const after = snapshot({
      capturedAtEpochMillis: 2000,
      uidStats: uidStats({
        wakelocks: { AudioMix: timer('AudioMix', 100, 1) },
        network: { ...EMPTY_NETWORK_USAGE, wifiRxBytes: 10 },
        energy: { total: { component: 'total', energyMah: 1, source: 'SYSTEM_MODEL', attributionScope: 'UID', confidence: 'MODELED' } },
      }),
    });
    const delta = diffBatteryRun(run(before, after));
    expect(delta.wakelocks).toEqual([]);
    expect(delta.network.wifiRxBytes).toBe(0);
    expect(delta.energy).toEqual([]);
    expect(delta.warnings.some((warning) => warning.includes('wakelock') && warning.includes('reset'))).toBe(true);
    expect(delta.warnings.some((warning) => warning.includes('network') && warning.includes('reset'))).toBe(true);
    expect(delta.warnings.some((warning) => warning.includes('energy') && warning.includes('reset'))).toBe(true);
  });

  it('warns on environment changes and long wakelocks', () => {
    const before = snapshot({
      bootId: 'boot-a',
      statsPeriodId: 'period-a',
      deviceState: { powered: false, temperatureTenthsCelsius: 300, rawValues: {} },
      conditions: { brightness: 'high' },
      uidStats: uidStats({ wakelocks: { AudioMix: timer('AudioMix', 0, 0) } }),
    });
    const after = snapshot({
      capturedAtEpochMillis: 10_000,
      bootId: 'boot-b',
      statsPeriodId: 'period-b',
      deviceState: { powered: true, temperatureTenthsCelsius: 360, rawValues: {} },
      conditions: { brightness: 'low' },
      uidStats: uidStats({ wakelocks: { AudioMix: timer('AudioMix', 12_000, 1) } }),
    });
    const delta = diffBatteryRun(run(before, after));
    expect(delta.warnings.some((warning) => warning.includes('boot ID'))).toBe(true);
    expect(delta.warnings.some((warning) => warning.includes('statistics period'))).toBe(true);
    expect(delta.warnings.some((warning) => warning.includes('Charging state'))).toBe(true);
    expect(delta.warnings.some((warning) => warning.includes('temperature'))).toBe(true);
    expect(delta.warnings.some((warning) => warning.includes('brightness'))).toBe(true);
    expect(delta.warnings.some((warning) => warning.includes('Wakelock time'))).toBe(true);
  });

  it('warns when energy is missing from the baseline', () => {
    const energy: EnergyEstimate = {
      component: 'total',
      energyMah: 5,
      source: 'SYSTEM_MODEL',
      attributionScope: 'UID',
      confidence: 'MODELED',
    };
    const delta = diffBatteryRun(
      run(snapshot(), snapshot({ capturedAtEpochMillis: 2000, uidStats: uidStats({ energy: { total: energy } }) })),
    );
    expect(delta.energy).toEqual([]);
    expect(delta.warnings.some((warning) => warning.includes('absent from the baseline'))).toBe(true);
  });
});

describe('analyzeBatteryRuns', () => {
  it('summarizes deltas and flags baseline temperature drift', () => {
    const runs = [
      run(
        snapshot({ deviceState: { temperatureTenthsCelsius: 300, rawValues: {} } }),
        snapshot({
          capturedAtEpochMillis: 5000,
          uidStats: uidStats({ wakelocks: { AudioMix: timer('AudioMix', 2000, 1) } }),
        }),
        1,
      ),
      run(
        snapshot({ deviceState: { temperatureTenthsCelsius: 340, rawValues: {} } }),
        snapshot({
          capturedAtEpochMillis: 5000,
          uidStats: uidStats({ wakelocks: { AudioMix: timer('AudioMix', 4000, 2) } }),
        }),
        2,
      ),
    ];
    const result = analyzeBatteryRuns(runs);
    expect(result.runs).toHaveLength(2);
    expect(result.wakelockDurationMs.count).toBe(2);
    expect(result.wakelockDurationMs.median).toBe(2000);
    expect(result.warnings.some((warning) => warning.includes('drifted'))).toBe(true);
  });

  it('requires at least one run', () => {
    expect(() => analyzeBatteryRuns([])).toThrow();
  });
});
