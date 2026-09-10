import type { StartupType } from './model.js';

export const MAX_STARTUP_RUNS = 100;
export const MIN_TIMEOUT_SECONDS = 5;
export const MAX_TIMEOUT_SECONDS = 300;

export interface StartupExperimentConfig {
  readonly requestedType: StartupType;
  readonly warmupRuns: number;
  readonly measuredRuns: number;
  readonly timeoutSeconds: number;
}

export const DEFAULT_STARTUP_EXPERIMENT: StartupExperimentConfig = {
  requestedType: 'COLD',
  warmupRuns: 0,
  measuredRuns: 5,
  timeoutSeconds: 30,
};

/** Returns human-readable validation errors; an empty list means valid. */
export function validateStartupExperimentConfig(config: StartupExperimentConfig): string[] {
  const errors: string[] = [];
  if (!Number.isInteger(config.warmupRuns) || config.warmupRuns < 0 || config.warmupRuns > MAX_STARTUP_RUNS) {
    errors.push('warmupRuns must be an integer between 0 and ' + MAX_STARTUP_RUNS);
  }
  if (!Number.isInteger(config.measuredRuns) || config.measuredRuns < 1 || config.measuredRuns > MAX_STARTUP_RUNS) {
    errors.push('measuredRuns must be an integer between 1 and ' + MAX_STARTUP_RUNS);
  }
  if (!Number.isInteger(config.timeoutSeconds) || config.timeoutSeconds < MIN_TIMEOUT_SECONDS || config.timeoutSeconds > MAX_TIMEOUT_SECONDS) {
    errors.push('timeoutSeconds must be an integer between ' + MIN_TIMEOUT_SECONDS + ' and ' + MAX_TIMEOUT_SECONDS);
  }
  if (config.requestedType !== 'COLD' && config.requestedType !== 'WARM' && config.requestedType !== 'HOT') {
    errors.push('requestedType must be COLD, WARM, or HOT');
  }
  return errors;
}

export interface StartupRunPlanEntry {
  readonly iteration: number;
  /** Warmup runs only prime the requested mode and are excluded from statistics. */
  readonly measured: boolean;
}

export function planStartupRuns(config: StartupExperimentConfig): StartupRunPlanEntry[] {
  const plan: StartupRunPlanEntry[] = [];
  for (let index = 0; index < config.warmupRuns; index += 1) {
    plan.push({ iteration: index + 1, measured: false });
  }
  for (let index = 0; index < config.measuredRuns; index += 1) {
    plan.push({ iteration: config.warmupRuns + index + 1, measured: true });
  }
  return plan;
}

/** Cold runs must reset the process; warm and hot runs must not. */
export function requiresForceStop(requestedType: StartupType): boolean {
  return requestedType === 'COLD';
}
