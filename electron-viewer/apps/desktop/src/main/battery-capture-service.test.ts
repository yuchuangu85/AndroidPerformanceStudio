import { describe, expect, it } from 'vitest';
import { DEFAULT_BATTERY_EXPERIMENT } from '@aps/battery-profiler';
import { captureBatterySnapshot, runBatteryExperiment, type BatteryCaptureAdb } from './battery-capture-service.js';

const CHECKIN = [
  '7,0,l,start,1700000000',
  '9,10123,l,wl,AudioMix,1500000,4,0,0,0',
  '9,10123,l,nt,100,200,300,400,0,0,1,2,3,4,500000',
].join('\n');
const REPORT = 'Estimated power use (mAh):\n  Uid 10123: 12.5 ( cpu=8.0 )';
const BATTERY = '  AC powered: false\n  level: 80\n  temperature: 300';

interface Harness {
  readonly adb: BatteryCaptureAdb;
  readonly commands: string[][];
  readonly sleeps: number[];
  readonly dependencies: {
    adb: BatteryCaptureAdb;
    now: () => number;
    newId: () => string;
    sleep: (milliseconds: number) => Promise<void>;
  };
}

function harness(overrides: { checkinFails?: boolean; reportFails?: boolean; batteryFails?: boolean } = {}): Harness {
  const commands: string[][] = [];
  const sleeps: number[] = [];
  let clock = 1000;
  const adb: BatteryCaptureAdb = {
    shell: async (args) => {
      commands.push([...args]);
      if (args[1] === 'batterystats' && args[2] === '--checkin') {
        if (overrides.checkinFails === true) throw new Error('checkin failed');
        return { stdout: CHECKIN };
      }
      if (args[1] === 'batterystats') {
        if (overrides.reportFails === true) throw new Error('report failed');
        return { stdout: REPORT };
      }
      if (args[1] === 'battery') {
        if (overrides.batteryFails === true) throw new Error('battery failed');
        return { stdout: BATTERY };
      }
      if (args[0] === 'settings') return { stdout: '128' };
      if (args[1] === 'power') return { stdout: 'mWakefulness=Awake' };
      if (args[1] === 'deviceidle') return { stdout: 'mState=ACTIVE' };
      return { stdout: '' };
    },
  };
  let counter = 0;
  return {
    adb,
    commands,
    sleeps,
    dependencies: {
      adb,
      now: () => (clock += 1000),
      newId: () => 'session-' + String((counter += 1)),
      sleep: async (milliseconds) => {
        sleeps.push(milliseconds);
      },
    },
  };
}

const TARGET = { serial: 'emulator-5554', packageName: 'com.example.app', uid: 10123 };

describe('captureBatterySnapshot', () => {
  it('parses checkin, report, battery, and conditions', async () => {
    const test = harness();
    const result = await captureBatterySnapshot(test.dependencies, { ...TARGET, sessionId: 's', sequence: 0 });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.uidStats.wakelocks['AudioMix']?.durationMs).toBe(1500);
    expect(result.value.uidStats.network.wifiRxBytes).toBe(300);
    expect(result.value.uidStats.energy['total']?.energyMah).toBe(12.5);
    expect(result.value.deviceState.levelPercent).toBe(80);
    expect(result.value.conditions).toEqual({ screenBrightness: '128', screenOn: 'on', doze: 'ACTIVE' });
    expect(result.value.statsPeriodId).toBe('1700000000');
  });

  it('never resets device statistics', async () => {
    const test = harness();
    await captureBatterySnapshot(test.dependencies, { ...TARGET, sessionId: 's', sequence: 0 });
    expect(test.commands.some((args) => args.includes('reset') || args.includes('--reset'))).toBe(false);
  });

  it('reports each dump failure with a stable code', async () => {
    const checkin = await captureBatterySnapshot(harness({ checkinFails: true }).dependencies, {
      ...TARGET,
      sessionId: 's',
      sequence: 0,
    });
    expect(checkin.ok).toBe(false);
    if (!checkin.ok) expect(checkin.error.code).toBe('BATTERY_CHECKIN_FAILED');

    const report = await captureBatterySnapshot(harness({ reportFails: true }).dependencies, {
      ...TARGET,
      sessionId: 's',
      sequence: 0,
    });
    expect(report.ok).toBe(false);
    if (!report.ok) expect(report.error.code).toBe('BATTERY_REPORT_FAILED');

    const battery = await captureBatterySnapshot(harness({ batteryFails: true }).dependencies, {
      ...TARGET,
      sessionId: 's',
      sequence: 0,
    });
    expect(battery.ok).toBe(false);
    if (!battery.ok) expect(battery.error.code).toBe('BATTERY_STATE_FAILED');
  });
});

describe('runBatteryExperiment', () => {
  it('collects a baseline, timed samples, and a final snapshot', async () => {
    const test = harness();
    const result = await runBatteryExperiment(test.dependencies, {
      ...TARGET,
      config: { ...DEFAULT_BATTERY_EXPERIMENT, durationSeconds: 20, pollingIntervalSeconds: 10 },
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.runs).toHaveLength(1);
    expect(result.value.runs[0]?.samples).toHaveLength(2);
    expect(test.sleeps).toEqual([10_000, 10_000]);
    expect(result.value.session.attributionScope).toBe('UID');
  });

  it('waits the full window when the interval exceeds the duration', async () => {
    const test = harness();
    const result = await runBatteryExperiment(test.dependencies, {
      ...TARGET,
      config: { ...DEFAULT_BATTERY_EXPERIMENT, durationSeconds: 5, pollingIntervalSeconds: 60 },
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(test.sleeps).toEqual([5000]);
    expect(result.value.runs[0]?.samples).toHaveLength(1);
  });

  it('runs repeated iterations with a cooldown and never resets statistics', async () => {
    const test = harness();
    const result = await runBatteryExperiment(test.dependencies, {
      ...TARGET,
      config: {
        ...DEFAULT_BATTERY_EXPERIMENT,
        mode: 'REPEATED',
        measuredRuns: 2,
        cooldownSeconds: 30,
        durationSeconds: 5,
        pollingIntervalSeconds: 5,
      },
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.runs.map((run) => run.iteration)).toEqual([1, 2]);
    expect(test.sleeps).toContain(30_000);
    expect(test.commands.some((args) => args.includes('reset') || args.includes('--reset'))).toBe(false);
  });

  it('rejects invalid configuration, a blank package, and a non-numeric uid', async () => {
    const invalid = await runBatteryExperiment(harness().dependencies, {
      ...TARGET,
      config: { ...DEFAULT_BATTERY_EXPERIMENT, durationSeconds: 1 },
    });
    expect(invalid.ok).toBe(false);
    if (!invalid.ok) expect(invalid.error.code).toBe('BATTERY_CONFIG_INVALID');

    const noPackage = await runBatteryExperiment(harness().dependencies, {
      ...TARGET,
      packageName: '  ',
      config: DEFAULT_BATTERY_EXPERIMENT,
    });
    expect(noPackage.ok).toBe(false);
    if (!noPackage.ok) expect(noPackage.error.code).toBe('BATTERY_PACKAGE_REQUIRED');

    const noUid = await runBatteryExperiment(harness().dependencies, {
      ...TARGET,
      uid: -1,
      config: DEFAULT_BATTERY_EXPERIMENT,
    });
    expect(noUid.ok).toBe(false);
    if (!noUid.ok) expect(noUid.error.code).toBe('BATTERY_UID_REQUIRED');
  });
});
