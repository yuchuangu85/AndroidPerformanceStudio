import { describe, expect, it } from 'vitest';
import { analyzeSession, createFrameSession, type FrameSession } from './session.js';
import type { FrameSample } from './model.js';

function sample(totalDurationNs: number): FrameSample {
  return {
    frameId: 0,
    sessionId: 's',
    source: 'GFXINFO',
    expectedDurationNs: 16_666_666,
    expectedDurationSource: 'PLATFORM_DEADLINE',
    totalDurationNs,
    stages: {},
    eligibleForJank: true,
    platformJankTypes: [],
    states: {},
  };
}

describe('FrameSession', () => {
  it('defaults warnings and recomputes analysis on demand', () => {
    const session: FrameSession = createFrameSession({
      id: '1',
      packageName: 'com.example.app',
      capturedAtEpochMillis: 100,
      frames: [sample(10_000_000), sample(30_000_000)],
    });
    expect(session.warnings).toEqual([]);
    const analysis = analyzeSession(session);
    expect(analysis.summary.totalFrames).toBe(2);
    expect(analysis.summary.deadlineMissFrames).toBe(1);
    expect(analysis.clusters).toHaveLength(1);
  });
});
