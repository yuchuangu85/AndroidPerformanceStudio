import { fail, ok, type StudioResult } from '@aps/contracts';
import {
  analyzeBatteryRuns,
  createBatterySession,
  parseBatteryConditions,
  parseBatteryDeviceState,
  parseBatteryStats,
  planBatteryIterations,
  validateBatteryExperimentConfig,
  type BatteryExperimentConfig,
  type BatteryExperimentResult,
  type BatteryRun,
  type BatterySnapshot,
} from '@aps/battery-profiler';

export interface BatteryCaptureAdb {
  shell(args: readonly string[], options: { readonly timeoutMs: number }): Promise<{ stdout: string }>;
}

export interface BatteryCaptureDependencies {
  readonly adb: BatteryCaptureAdb;
  readonly now: () => number;
  readonly newId: () => string;
  readonly sleep: (milliseconds: number) => Promise<void>;
}

export interface BatteryTargetOptions {
  readonly serial: string;
  readonly packageName: string;
  readonly uid: number;
}

const DUMP_TIMEOUT_MS = 60_000;
const SHORT_TIMEOUT_MS = 30_000;

async function optionalShell(
  dependencies: BatteryCaptureDependencies,
  args: readonly string[],
  timeoutMs: number,
): Promise<string> {
  try {
    return (await dependencies.adb.shell(args, { timeoutMs })).stdout;
  } catch {
    return '';
  }
}

/**
 * Captures one battery snapshot. The experiment is strictly read-only: it never
 * resets statistics or changes device power, brightness, or network state.
 */
export async function captureBatterySnapshot(
  dependencies: BatteryCaptureDependencies,
  options: BatteryTargetOptions & { readonly sessionId: string; readonly sequence: number },
): Promise<StudioResult<BatterySnapshot>> {
  const { adb } = dependencies;
  let checkin: string;
  try {
    checkin = (await adb.shell(['dumpsys', 'batterystats', '--checkin'], { timeoutMs: DUMP_TIMEOUT_MS })).stdout;
  } catch (error) {
    return fail('PROCESS_EXIT', 'BATTERY_CHECKIN_FAILED', describe(error, options.serial));
  }
  let report: string;
  try {
    report = (await adb.shell(['dumpsys', 'batterystats'], { timeoutMs: DUMP_TIMEOUT_MS })).stdout;
  } catch (error) {
    return fail('PROCESS_EXIT', 'BATTERY_REPORT_FAILED', describe(error, options.serial));
  }
  let battery: string;
  try {
    battery = (await adb.shell(['dumpsys', 'battery'], { timeoutMs: SHORT_TIMEOUT_MS })).stdout;
  } catch (error) {
    return fail('PROCESS_EXIT', 'BATTERY_STATE_FAILED', describe(error, options.serial));
  }

  const brightness = await optionalShell(dependencies, ['settings', 'get', 'system', 'screen_brightness'], SHORT_TIMEOUT_MS);
  const power = await optionalShell(dependencies, ['dumpsys', 'power'], SHORT_TIMEOUT_MS);
  const deviceIdle = await optionalShell(dependencies, ['dumpsys', 'deviceidle'], SHORT_TIMEOUT_MS);

  const parsed = parseBatteryStats(checkin, report, battery, options.uid);
  const snapshot: BatterySnapshot = {
    id: options.sessionId + '-' + String(options.sequence),
    sessionId: options.sessionId,
    sequence: options.sequence,
    capturedAtEpochMillis: dependencies.now(),
    uidStats: parsed.uidStats,
    deviceState: parseBatteryDeviceState(battery),
    history: parsed.history,
    warnings: parsed.warnings,
    ...(parsed.statsPeriodId !== undefined ? { statsPeriodId: parsed.statsPeriodId } : {}),
    conditions: parseBatteryConditions(brightness, power, deviceIdle),
  };
  return ok(snapshot);
}

export interface BatteryExperimentOptions extends BatteryTargetOptions {
  readonly config: BatteryExperimentConfig;
}

/**
 * Runs a read-only battery experiment. INTERACTIVE and ONLINE modes observe one
 * window; REPEATED adds cooldown-separated iterations.
 */
export async function runBatteryExperiment(
  dependencies: BatteryCaptureDependencies,
  options: BatteryExperimentOptions,
): Promise<StudioResult<BatteryExperimentResult>> {
  const errors = validateBatteryExperimentConfig(options.config);
  if (errors.length > 0) {
    return fail('DATA_VALIDATION', 'BATTERY_CONFIG_INVALID', errors.join('; '));
  }
  if (options.packageName.trim().length === 0) {
    return fail('DATA_VALIDATION', 'BATTERY_PACKAGE_REQUIRED', 'A package name is required');
  }
  if (!Number.isInteger(options.uid) || options.uid < 0) {
    return fail('DATA_VALIDATION', 'BATTERY_UID_REQUIRED', 'A numeric UID is required');
  }
  const sessionId = dependencies.newId();
  const session = createBatterySession({
    id: sessionId,
    deviceSerial: options.serial,
    packageName: options.packageName,
    uid: options.uid,
    config: options.config,
    createdAtEpochMillis: dependencies.now(),
  });

  const iterations = planBatteryIterations(options.config);
  const runs: BatteryRun[] = [];
  for (const iteration of iterations) {
    const baseline = await captureBatterySnapshot(dependencies, { ...options, sessionId, sequence: 0 });
    if (!baseline.ok) return baseline;
    const samples: BatterySnapshot[] = [];
    let sequence = 1;
    const intervalMs = options.config.pollingIntervalSeconds * 1000;
    const totalMs = options.config.durationSeconds * 1000;
    let elapsed = 0;
    while (elapsed + intervalMs <= totalMs) {
      await dependencies.sleep(intervalMs);
      elapsed += intervalMs;
      const sample = await captureBatterySnapshot(dependencies, { ...options, sessionId, sequence });
      if (!sample.ok) return sample;
      samples.push(sample.value);
      sequence += 1;
    }
    const remaining = totalMs - elapsed;
    if (remaining > 0) {
      await dependencies.sleep(remaining);
    }
    let finalSnapshot = samples.at(-1);
    if (finalSnapshot === undefined || remaining > 0) {
      const captured = await captureBatterySnapshot(dependencies, { ...options, sessionId, sequence });
      if (!captured.ok) return captured;
      finalSnapshot = captured.value;
      if (remaining > 0) samples.push(captured.value);
    }
    runs.push({
      id: sessionId + '-run-' + String(iteration),
      sessionId,
      iteration,
      baseline: baseline.value,
      samples,
      finalSnapshot,
    });
    if (iteration < iterations.length && options.config.cooldownSeconds > 0) {
      await dependencies.sleep(options.config.cooldownSeconds * 1000);
    }
  }

  let analysis;
  try {
    analysis = analyzeBatteryRuns(runs);
  } catch (error) {
    return fail('DATA_VALIDATION', 'BATTERY_ANALYSIS_FAILED', describe(error, options.serial));
  }
  return ok({ session, runs, analysis });
}

function describe(error: unknown, serial: string): string {
  const message = error instanceof Error ? error.message : 'battery capture failed';
  return message + ' (device ' + serial + ')';
}
