import { describe, expect, it } from 'vitest';
import { DEFAULT_BATTERY_EXPERIMENT, validateBatteryExperimentConfig } from './model.js';
import { createBatterySession, describeCaptureMode, planBatteryIterations } from './session.js';

describe('battery session', () => {
  it('creates a UID-scoped session', () => {
    const session = createBatterySession({
      id: 's',
      deviceSerial: 'SER',
      packageName: 'com.example.app',
      uid: 10123,
      config: DEFAULT_BATTERY_EXPERIMENT,
      createdAtEpochMillis: 5,
    });
    expect(session.attributionScope).toBe('UID');
    expect(session.uid).toBe(10123);
  });

  it('iterates only in repeated mode', () => {
    expect(planBatteryIterations(DEFAULT_BATTERY_EXPERIMENT)).toEqual([1]);
    expect(planBatteryIterations({ ...DEFAULT_BATTERY_EXPERIMENT, mode: 'REPEATED', measuredRuns: 3 })).toEqual([1, 2, 3]);
  });

  it('validates the experiment configuration', () => {
    expect(validateBatteryExperimentConfig(DEFAULT_BATTERY_EXPERIMENT)).toEqual([]);
    expect(validateBatteryExperimentConfig({ ...DEFAULT_BATTERY_EXPERIMENT, durationSeconds: 1 })).toHaveLength(1);
    expect(validateBatteryExperimentConfig({ ...DEFAULT_BATTERY_EXPERIMENT, pollingIntervalSeconds: 120 })).toHaveLength(1);
    expect(validateBatteryExperimentConfig({ ...DEFAULT_BATTERY_EXPERIMENT, measuredRuns: 0 })).toHaveLength(1);
    expect(validateBatteryExperimentConfig({ ...DEFAULT_BATTERY_EXPERIMENT, cooldownSeconds: 999 })).toHaveLength(1);
  });

  it('describes capture modes', () => {
    expect(describeCaptureMode('INTERACTIVE')).toContain('user-driven');
    expect(describeCaptureMode('REPEATED')).toContain('cooldown');
  });
});
