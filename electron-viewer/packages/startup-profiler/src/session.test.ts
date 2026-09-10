import { describe, expect, it } from 'vitest';
import { DEFAULT_STARTUP_EXPERIMENT } from './experiment.js';
import { createStartupSession, summarizeStartupSession } from './session.js';
import { UNAVAILABLE_EVIDENCE, type StartupRun } from './model.js';

function run(iteration: number, overrides: Partial<StartupRun> = {}): StartupRun {
  return {
    id: 'run-' + String(iteration),
    sessionId: 'session',
    iteration,
    measured: true,
    requestedType: 'COLD',
    observedType: 'COLD',
    platform: { complete: true, totalTimeMs: 500 },
    warnings: [],
    amStartOutput: '',
    ttidEvidence: UNAVAILABLE_EVIDENCE,
    ttfdEvidence: UNAVAILABLE_EVIDENCE,
    ...overrides,
  };
}

describe('startup session statistics', () => {
  it('covers measured runs only and counts missing metrics separately', () => {
    const runs: StartupRun[] = [
      run(1, { measured: false, platform: { complete: true, totalTimeMs: 900, displayedTimeMs: 800 } }),
      run(2, { platform: { complete: true, totalTimeMs: 500, displayedTimeMs: 480 } }),
      run(3, { platform: { complete: true, totalTimeMs: 520, displayedTimeMs: 500 } }),
      run(4, { platform: { complete: true, totalTimeMs: 540, displayedTimeMs: 510 } }),
      run(5, { platform: { complete: true, totalTimeMs: 560, displayedTimeMs: 530 } }),
      run(6, { platform: { complete: true, totalTimeMs: 580, displayedTimeMs: 550 } }),
    ];
    const statistics = summarizeStartupSession(runs);
    expect(statistics.warmupRuns).toBe(1);
    expect(statistics.measuredRuns).toBe(5);
    expect(statistics.totalTimeMs.count).toBe(5);
    expect(statistics.totalTimeMs.medianMs).toBe(540);
    expect(statistics.ttidMs.count).toBe(5);
    expect(statistics.ttfdMs.count).toBe(0);
    expect(statistics.ttfdMs.missingCount).toBe(5);
    expect(statistics.ttfdMs.medianMs).toBeUndefined();
  });

  it('counts unknown observed modes and requested/observed mismatches', () => {
    const runs = [
      run(1, { observedType: 'WARM' }),
      run(2, { observedType: 'UNKNOWN' }),
      run(3, { observedType: 'COLD' }),
    ];
    const statistics = summarizeStartupSession(runs);
    expect(statistics.unknownObservedRuns).toBe(1);
    expect(statistics.modeMismatchRuns).toBe(1);
  });

  it('marks low-resolution percentiles for a default five-run experiment', () => {
    const session = createStartupSession({
      id: 's',
      deviceSerial: 'SER',
      packageName: 'com.example.app',
      config: DEFAULT_STARTUP_EXPERIMENT,
      createdAtEpochMillis: 1,
      runs: [run(1), run(2), run(3), run(4), run(5)],
    });
    expect(session.statistics.totalTimeMs.p90LowResolution).toBe(true);
    expect(session.statistics.totalTimeMs.p95LowResolution).toBe(true);
  });
});
