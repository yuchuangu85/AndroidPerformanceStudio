import { describe, expect, it } from 'vitest';
import {
  DEFAULT_STARTUP_EXPERIMENT,
  planStartupRuns,
  requiresForceStop,
  validateStartupExperimentConfig,
} from './experiment.js';

describe('startup experiment config', () => {
  it('accepts the default configuration', () => {
    expect(validateStartupExperimentConfig(DEFAULT_STARTUP_EXPERIMENT)).toEqual([]);
  });

  it('rejects out-of-range and non-integer values', () => {
    expect(validateStartupExperimentConfig({ ...DEFAULT_STARTUP_EXPERIMENT, measuredRuns: 0 })).toHaveLength(1);
    expect(validateStartupExperimentConfig({ ...DEFAULT_STARTUP_EXPERIMENT, warmupRuns: -1 })).toHaveLength(1);
    expect(validateStartupExperimentConfig({ ...DEFAULT_STARTUP_EXPERIMENT, timeoutSeconds: 2 })).toHaveLength(1);
    expect(validateStartupExperimentConfig({ ...DEFAULT_STARTUP_EXPERIMENT, measuredRuns: 2.5 })).toHaveLength(1);
    expect(validateStartupExperimentConfig({ ...DEFAULT_STARTUP_EXPERIMENT, requestedType: 'UNKNOWN' })).toHaveLength(1);
  });

  it('plans warmups before measured runs and numbers them sequentially', () => {
    const plan = planStartupRuns({ ...DEFAULT_STARTUP_EXPERIMENT, warmupRuns: 2, measuredRuns: 3 });
    expect(plan).toEqual([
      { iteration: 1, measured: false },
      { iteration: 2, measured: false },
      { iteration: 3, measured: true },
      { iteration: 4, measured: true },
      { iteration: 5, measured: true },
    ]);
  });

  it('only resets the process for cold runs', () => {
    expect(requiresForceStop('COLD')).toBe(true);
    expect(requiresForceStop('WARM')).toBe(false);
    expect(requiresForceStop('HOT')).toBe(false);
  });
});
