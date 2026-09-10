import type { BatteryAnalysisResult } from './analysis.js';
import type { AttributionScope, BatteryCaptureMode, BatteryExperimentConfig, BatteryRun } from './model.js';

export interface BatterySession {
  readonly id: string;
  readonly deviceSerial: string;
  readonly packageName: string;
  readonly uid: number;
  readonly attributionScope: AttributionScope;
  readonly config: BatteryExperimentConfig;
  readonly createdAtEpochMillis: number;
}

export interface BatteryExperimentResult {
  readonly session: BatterySession;
  readonly runs: readonly BatteryRun[];
  readonly analysis: BatteryAnalysisResult;
}

export function createBatterySession(options: {
  readonly id: string;
  readonly deviceSerial: string;
  readonly packageName: string;
  readonly uid: number;
  readonly config: BatteryExperimentConfig;
  readonly createdAtEpochMillis: number;
}): BatterySession {
  return {
    id: options.id,
    deviceSerial: options.deviceSerial,
    packageName: options.packageName,
    uid: options.uid,
    attributionScope: 'UID',
    config: options.config,
    createdAtEpochMillis: options.createdAtEpochMillis,
  };
}

/** Repeated mode iterates; every other mode measures once per invocation. */
export function planBatteryIterations(config: BatteryExperimentConfig): number[] {
  const runs = config.mode === 'REPEATED' ? config.measuredRuns : 1;
  return Array.from({ length: runs }, (_value, index) => index + 1);
}

export function describeCaptureMode(mode: BatteryCaptureMode): string {
  if (mode === 'TIMED') return 'fixed observation window';
  if (mode === 'REPEATED') return 'repeated runs with cooldown';
  if (mode === 'ONLINE') return 'live observation';
  return 'user-driven observation';
}
