import { describe, expect, it } from 'vitest';
import { runStartupExperiment, type StartupCaptureAdb } from './startup-capture-service.js';

const AM_START = ['Status: ok', 'LaunchState: COLD', 'TotalTime: 512', 'Complete'].join('\n');
const EVENT_LOG = 'I/ActivityTaskManager( 900): Displayed com.example.app/.MainActivity: +480ms';

function harness(overrides: { forceStopFails?: boolean; startFails?: boolean } = {}) {
  const commands: Array<{ args: string[]; timeoutMs: number }> = [];
  const adb: StartupCaptureAdb = {
    shell: async (args, options) => {
      commands.push({ args: [...args], timeoutMs: options.timeoutMs });
      if (overrides.forceStopFails === true && args[1] === 'force-stop') throw new Error('force-stop failed');
      if (overrides.startFails === true && args[1] === 'start') throw new Error('am start failed');
      if (args[0] === 'logcat' && args[1] === '-d') return { stdout: EVENT_LOG };
      if (args[1] === 'start') return { stdout: AM_START };
      return { stdout: '' };
    },
  };
  let counter = 0;
  return { commands, dependencies: { adb, now: () => 1000, newId: () => 'session-' + String((counter += 1)) } };
}

const CONFIG = { requestedType: 'COLD' as const, warmupRuns: 1, measuredRuns: 2, timeoutSeconds: 12 };

describe('runStartupExperiment', () => {
  it('runs warmups then measured runs with force-stop, logcat clear, and events', async () => {
    const test = harness();
    const result = await runStartupExperiment(test.dependencies, {
      serial: 'emulator-5554',
      packageName: 'com.example.app',
      config: CONFIG,
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.runs).toHaveLength(3);
    expect(result.value.runs.map((run) => run.measured)).toEqual([false, true, true]);
    expect(result.value.statistics.warmupRuns).toBe(1);
    expect(result.value.statistics.measuredRuns).toBe(2);
    expect(result.value.statistics.totalTimeMs.medianMs).toBe(512);
    expect(result.value.statistics.ttidMs.count).toBe(2);
    expect(result.value.statistics.ttfdMs.count).toBe(0);

    const firstRun = test.commands.slice(0, 4).map((command) => command.args);
    expect(firstRun).toEqual([
      ['am', 'force-stop', 'com.example.app'],
      ['logcat', '-c'],
      ['am', 'start', '-W', '-p', 'com.example.app'],
      ['logcat', '-d', '-b', 'events', '-v', 'brief'],
    ]);
    expect(test.commands[2]?.timeoutMs).toBe(12_000);
    expect(result.value.runs[0]?.observedType).toBe('COLD');
    expect(result.value.runs[0]?.platform.displayedTimeMs).toBe(480);
    expect(result.value.runs[0]?.ttidEvidence).toEqual({ source: 'EVENT_LOG', confidence: 'EXACT' });
    expect(result.value.runs[0]?.ttfdEvidence.confidence).toBe('UNAVAILABLE');
  });

  it('launches an explicit component when provided and does not force-stop warm runs', async () => {
    const test = harness();
    await runStartupExperiment(test.dependencies, {
      serial: 'SER',
      packageName: 'com.example.app',
      componentName: 'com.example.app/.MainActivity',
      config: { ...CONFIG, requestedType: 'WARM', warmupRuns: 0, measuredRuns: 1 },
    });
    const args = test.commands.map((command) => command.args);
    expect(args).not.toContainEqual(['am', 'force-stop', 'com.example.app']);
    expect(args).toContainEqual(['am', 'start', '-W', '-n', 'com.example.app/.MainActivity']);
  });

  it('rejects an invalid configuration and unavailable runs', async () => {
    const invalid = await runStartupExperiment(harness().dependencies, {
      serial: 'SER',
      packageName: 'com.example.app',
      config: { ...CONFIG, measuredRuns: 0 },
    });
    expect(invalid.ok).toBe(false);
    if (!invalid.ok) expect(invalid.error.code).toBe('STARTUP_CONFIG_INVALID');

    const noPackage = await runStartupExperiment(harness().dependencies, {
      serial: 'SER',
      packageName: '   ',
      config: CONFIG,
    });
    expect(noPackage.ok).toBe(false);
    if (!noPackage.ok) expect(noPackage.error.code).toBe('STARTUP_PACKAGE_REQUIRED');

    const forceStop = await runStartupExperiment(harness({ forceStopFails: true }).dependencies, {
      serial: 'SER',
      packageName: 'com.example.app',
      config: CONFIG,
    });
    expect(forceStop.ok).toBe(false);
    if (!forceStop.ok) expect(forceStop.error.code).toBe('STARTUP_FORCE_STOP_FAILED');

    const start = await runStartupExperiment(harness({ startFails: true }).dependencies, {
      serial: 'SER',
      packageName: 'com.example.app',
      config: CONFIG,
    });
    expect(start.ok).toBe(false);
    if (!start.ok) expect(start.error.code).toBe('STARTUP_RUN_FAILED');
  });
});
