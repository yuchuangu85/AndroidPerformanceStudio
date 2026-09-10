import {
  totalNetworkBytes,
  type BatteryRun,
  type BatteryRunDelta,
  type BatterySnapshot,
  type BatteryStatistics,
  type EnergyEstimate,
  type NetworkUsage,
  type ResourceTimer,
} from './model.js';

const TEMPERATURE_WARNING_TENTHS = 30;

export interface BatteryAnalysisResult {
  readonly runs: readonly BatteryRunDelta[];
  readonly wakelockDurationMs: BatteryStatistics;
  readonly wakeupAlarmCount: BatteryStatistics;
  readonly jobDurationMs: BatteryStatistics;
  readonly sensorDurationMs: BatteryStatistics;
  readonly networkBytes: BatteryStatistics;
  readonly energyMah: BatteryStatistics;
  readonly warnings: readonly string[];
}

function percentile(sorted: readonly number[], fraction: number): number {
  const index = Math.min(Math.max(Math.ceil(fraction * sorted.length) - 1, 0), sorted.length - 1);
  return sorted[index] as number;
}

export function batteryStatistics(values: readonly (number | undefined)[]): BatteryStatistics {
  const present = values.filter((value): value is number => value !== undefined).sort((left, right) => left - right);
  if (present.length === 0) return { count: 0, missingCount: values.length };
  const mean = present.reduce((total, value) => total + value, 0) / present.length;
  const median = percentile(present, 0.5);
  const deviations = present.map((value) => Math.abs(value - median)).sort((left, right) => left - right);
  const variance = present.reduce((total, value) => total + (value - mean) * (value - mean), 0) / present.length;
  const minimum = present[0] as number;
  const maximum = present[present.length - 1] as number;
  return {
    count: present.length,
    missingCount: values.length - present.length,
    minimum,
    maximum,
    median,
    mean,
    p90: percentile(present, 0.9),
    p95: percentile(present, 0.95),
    standardDeviation: Math.sqrt(variance),
    medianAbsoluteDeviation: percentile(deviations, 0.5),
  };
}

/** Counter resets invalidate a delta, so the resource is dropped with a warning. */
function diffTimers(
  before: Readonly<Record<string, ResourceTimer>>,
  after: Readonly<Record<string, ResourceTimer>>,
  kind: string,
  warnings: string[],
): ResourceTimer[] {
  const names = [...new Set([...Object.keys(before), ...Object.keys(after)])];
  const deltas: ResourceTimer[] = [];
  for (const name of names) {
    const current = after[name];
    if (current === undefined) continue;
    const previous = before[name];
    const durationMs = current.durationMs - (previous?.durationMs ?? 0);
    const count = current.count - (previous?.count ?? 0);
    if (durationMs < 0 || count < 0) {
      warnings.push("The " + kind + " counter '" + name + "' reset during the experiment.");
      continue;
    }
    if (durationMs === 0 && count === 0) continue;
    deltas.push({ ...current, durationMs, count });
  }
  return deltas.sort((left, right) => right.durationMs - left.durationMs);
}

function diffNetwork(before: NetworkUsage, after: NetworkUsage, warnings: string[]): NetworkUsage {
  const delta = (name: string, oldValue: number, current: number): number => {
    if (current >= oldValue) return current - oldValue;
    warnings.push("The network counter '" + name + "' reset during the experiment.");
    return 0;
  };
  return {
    mobileRxBytes: delta('mobileRxBytes', before.mobileRxBytes, after.mobileRxBytes),
    mobileTxBytes: delta('mobileTxBytes', before.mobileTxBytes, after.mobileTxBytes),
    wifiRxBytes: delta('wifiRxBytes', before.wifiRxBytes, after.wifiRxBytes),
    wifiTxBytes: delta('wifiTxBytes', before.wifiTxBytes, after.wifiTxBytes),
    bluetoothRxBytes: delta('bluetoothRxBytes', before.bluetoothRxBytes, after.bluetoothRxBytes),
    bluetoothTxBytes: delta('bluetoothTxBytes', before.bluetoothTxBytes, after.bluetoothTxBytes),
    mobileRxPackets: delta('mobileRxPackets', before.mobileRxPackets, after.mobileRxPackets),
    mobileTxPackets: delta('mobileTxPackets', before.mobileTxPackets, after.mobileTxPackets),
    wifiRxPackets: delta('wifiRxPackets', before.wifiRxPackets, after.wifiRxPackets),
    wifiTxPackets: delta('wifiTxPackets', before.wifiTxPackets, after.wifiTxPackets),
    mobileRadioActiveMs: delta('mobileRadioActiveMs', before.mobileRadioActiveMs, after.mobileRadioActiveMs),
  };
}

function diffEnergy(
  before: Readonly<Record<string, EnergyEstimate>>,
  after: Readonly<Record<string, EnergyEstimate>>,
  warnings: string[],
): EnergyEstimate[] {
  const deltas: EnergyEstimate[] = [];
  for (const [component, current] of Object.entries(after)) {
    const previous = before[component];
    if (previous === undefined) {
      warnings.push("Energy component '" + component + "' was absent from the baseline and cannot be diffed.");
      continue;
    }
    if ((current.energyMah !== undefined && previous.energyMah === undefined) ||
        (current.energyUws !== undefined && previous.energyUws === undefined)) {
      warnings.push("Energy component '" + component + "' changed units or was incomplete at baseline.");
    }
    const mah =
      current.energyMah !== undefined && previous.energyMah !== undefined
        ? current.energyMah - previous.energyMah
        : undefined;
    const uws =
      current.energyUws !== undefined && previous.energyUws !== undefined
        ? current.energyUws - previous.energyUws
        : undefined;
    if ((mah !== undefined && mah < 0) || (uws !== undefined && uws < 0)) {
      warnings.push("The energy counter '" + component + "' reset during the experiment.");
      continue;
    }
    if (mah === undefined && uws === undefined) continue;
    if (mah === 0 && (uws === undefined || uws === 0)) continue;
    deltas.push({
      ...current,
      ...(mah !== undefined ? { energyMah: mah } : {}),
      ...(uws !== undefined ? { energyUws: uws } : {}),
    });
  }
  return deltas.sort((left, right) => (right.energyMah ?? 0) - (left.energyMah ?? 0));
}

function diagnose(wakelocks: readonly ResourceTimer[], durationMs: number): string[] {
  const totalWakelock = wakelocks.reduce((total, timer) => total + timer.durationMs, 0);
  return durationMs > 0 && totalWakelock >= durationMs
    ? ['Wakelock time is close to the experiment duration; inspect overlapping or unreleased locks.']
    : [];
}

function snapshotWarnings(before: BatterySnapshot, after: BatterySnapshot, warnings: string[]): void {
  if (before.bootId !== undefined && after.bootId !== undefined && before.bootId !== after.bootId) {
    warnings.push('Device boot ID changed during the experiment; cumulative deltas may be invalid.');
  }
  if (
    before.statsPeriodId !== undefined &&
    after.statsPeriodId !== undefined &&
    before.statsPeriodId !== after.statsPeriodId
  ) {
    warnings.push('Battery statistics period changed during the experiment.');
  }
  if (before.uidStats.uid !== after.uidStats.uid) warnings.push('Target UID changed during the experiment.');
  const poweredBefore = before.deviceState.powered;
  const poweredAfter = after.deviceState.powered;
  if (poweredBefore !== undefined && poweredAfter !== undefined && poweredBefore !== poweredAfter) {
    warnings.push('Charging state changed during the experiment.');
  }
  const temperatureBefore = before.deviceState.temperatureTenthsCelsius;
  const temperatureAfter = after.deviceState.temperatureTenthsCelsius;
  if (
    temperatureBefore !== undefined &&
    temperatureAfter !== undefined &&
    Math.abs(temperatureAfter - temperatureBefore) >= TEMPERATURE_WARNING_TENTHS
  ) {
    warnings.push('Battery temperature changed by at least 3C.');
  }
  for (const name of new Set([...Object.keys(before.conditions), ...Object.keys(after.conditions)])) {
    const oldValue = before.conditions[name];
    const newValue = after.conditions[name];
    if (oldValue !== undefined && newValue !== undefined && oldValue !== newValue) {
      warnings.push("Experiment condition '" + name + "' changed from '" + oldValue + "' to '" + newValue + "'.");
    }
  }
}

/** Port of BatteryAnalyzer.diff: a snapshot difference with reset detection. */
export function diffBatteryRun(run: BatteryRun): BatteryRunDelta {
  const before = run.baseline;
  const after = run.finalSnapshot;
  const warnings: string[] = [];
  snapshotWarnings(before, after, warnings);
  const durationMs = Math.max(0, after.capturedAtEpochMillis - before.capturedAtEpochMillis);
  const wakelocks = diffTimers(before.uidStats.wakelocks, after.uidStats.wakelocks, 'wakelock', warnings);
  return {
    runId: run.id,
    sessionId: run.sessionId,
    iteration: run.iteration,
    durationMs,
    wakelocks,
    alarms: diffTimers(before.uidStats.alarms, after.uidStats.alarms, 'alarm', warnings),
    jobs: diffTimers(before.uidStats.jobs, after.uidStats.jobs, 'job', warnings),
    sensors: diffTimers(before.uidStats.sensors, after.uidStats.sensors, 'sensor', warnings),
    network: diffNetwork(before.uidStats.network, after.uidStats.network, warnings),
    energy: diffEnergy(before.uidStats.energy, after.uidStats.energy, warnings),
    history: after.history.filter((event) => event.uid === undefined || event.uid === after.uidStats.uid),
    warnings: [
      ...new Set([...before.warnings, ...after.warnings, ...warnings, ...diagnose(wakelocks, durationMs)]),
    ],
  };
}

/** Port of BatteryAnalyzer.analyze, including the cross-run temperature drift gate. */
export function analyzeBatteryRuns(runs: readonly BatteryRun[]): BatteryAnalysisResult {
  if (runs.length === 0) throw new Error('At least one battery run is required');
  const firstTemperature = runs[0]?.baseline.deviceState.temperatureTenthsCelsius;
  const deltas = runs.map((run) => {
    const delta = diffBatteryRun(run);
    const temperature = run.baseline.deviceState.temperatureTenthsCelsius;
    if (
      firstTemperature !== undefined &&
      temperature !== undefined &&
      Math.abs(temperature - firstTemperature) >= TEMPERATURE_WARNING_TENTHS
    ) {
      return {
        ...delta,
        warnings: [
          ...new Set([...delta.warnings, 'Run ' + run.iteration + ' baseline temperature drifted by at least 3C from run 1.']),
        ],
      };
    }
    return delta;
  });
  const energyValues = deltas.map((delta) => {
    const total = delta.energy.reduce((sum, estimate) => sum + (estimate.energyMah ?? 0), 0);
    return total > 0 ? total : undefined;
  });
  return {
    runs: deltas,
    wakelockDurationMs: batteryStatistics(deltas.map((delta) => delta.wakelocks.reduce((sum, timer) => sum + timer.durationMs, 0))),
    wakeupAlarmCount: batteryStatistics(deltas.map((delta) => delta.alarms.reduce((sum, timer) => sum + timer.count, 0))),
    jobDurationMs: batteryStatistics(deltas.map((delta) => delta.jobs.reduce((sum, timer) => sum + timer.durationMs, 0))),
    sensorDurationMs: batteryStatistics(deltas.map((delta) => delta.sensors.reduce((sum, timer) => sum + timer.durationMs, 0))),
    networkBytes: batteryStatistics(deltas.map((delta) => totalNetworkBytes(delta.network))),
    energyMah: batteryStatistics(energyValues),
    warnings: [...new Set(deltas.flatMap((delta) => delta.warnings))],
  };
}
