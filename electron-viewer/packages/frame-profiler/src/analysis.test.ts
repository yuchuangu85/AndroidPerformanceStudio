import { describe, expect, it } from 'vitest';
import { analyzeFrame, analyzeFrames, clusterDeadlineMisses, summarize } from './analysis.js';
import type { FrameSample } from './model.js';

function sample(overrides: Partial<FrameSample> = {}): FrameSample {
  return {
    frameId: 0,
    sessionId: 's',
    source: 'GFXINFO',
    intendedVsyncNs: 1_000_000_000,
    frameCompletedNs: 1_010_000_000,
    expectedDurationNs: 16_666_666,
    expectedDurationSource: 'PLATFORM_DEADLINE',
    totalDurationNs: 10_000_000,
    stages: {},
    eligibleForJank: true,
    platformJankTypes: [],
    states: {},
    ...overrides,
  };
}

describe('analyzeFrame', () => {
  it('classifies a frame inside its budget as smooth', () => {
    const analyzed = analyzeFrame(sample());
    expect(analyzed.deadlineVerdict).toBe('MET');
    expect(analyzed.severity).toBe('SMOOTH');
    expect(analyzed.missedVsyncCount).toBe(0);
  });

  it('classifies a missed deadline and counts missed vsyncs', () => {
    const analyzed = analyzeFrame(sample({ totalDurationNs: 20_000_000 }));
    expect(analyzed.deadlineVerdict).toBe('MISSED');
    expect(analyzed.missedVsyncCount).toBe(1);
    expect(analyzed.severity).toBe('MINOR');

    const major = analyzeFrame(sample({ totalDurationNs: 45_000_000 }));
    expect(major.missedVsyncCount).toBe(2);
    expect(major.severity).toBe('MAJOR');

    const severeFourMisses = analyzeFrame(sample({ totalDurationNs: 70_000_000 }));
    expect(severeFourMisses.missedVsyncCount).toBe(4);
    expect(severeFourMisses.severity).toBe('SEVERE');

    const severe = analyzeFrame(sample({ totalDurationNs: 120_000_000 }));
    expect(severe.missedVsyncCount).toBe(7);
    expect(severe.severity).toBe('SEVERE');
  });

  it('treats a frame at or beyond the frozen threshold as frozen', () => {
    expect(analyzeFrame(sample({ totalDurationNs: 700_000_000 })).severity).toBe('FROZEN');
  });

  it('keeps frames ineligible for jank analysis unknown', () => {
    const analyzed = analyzeFrame(sample({ eligibleForJank: false }));
    expect(analyzed.deadlineVerdict).toBe('UNKNOWN');
    expect(analyzed.severity).toBe('UNKNOWN');
    expect(analyzed.missedVsyncCount).toBeUndefined();
  });

  it('reports platform jank separately from deadline misses', () => {
    const analyzed = analyzeFrame(sample({ platformJank: true }));
    expect(analyzed.deadlineVerdict).toBe('MET');
    expect(analyzed.platformJankTypes).toContain('PLATFORM_REPORTED');
  });

  it('names the largest reported stage', () => {
    const analyzed = analyzeFrame(sample({ stages: { inputNs: 1_000_000, gpuNs: 5_000_000 } }));
    expect(analyzed.largestReportedStage).toBe('gpuNs');
  });
});

describe('summarize', () => {
  it('reports separate deadline and platform rates plus percentiles', () => {
    const frames = [
      analyzeFrame(sample({ totalDurationNs: 10_000_000 })),
      analyzeFrame(sample({ totalDurationNs: 20_000_000 })),
      analyzeFrame(sample({ totalDurationNs: 40_000_000, platformJank: true })),
      analyzeFrame(sample({ totalDurationNs: 80_000_000, platformJank: false })),
    ];
    const summary = summarize(frames);
    expect(summary.totalFrames).toBe(4);
    expect(summary.deadlineClassifiedFrames).toBe(4);
    expect(summary.deadlineMissFrames).toBe(3);
    expect(summary.deadlineMissRate).toBeCloseTo(0.75);
    expect(summary.platformClassifiedFrames).toBe(2);
    expect(summary.platformJankFrames).toBe(1);
    expect(summary.platformJankRate).toBeCloseTo(0.5);
    expect(summary.p50DurationNs).toBe(20_000_000);
    expect(summary.p95DurationNs).toBe(80_000_000);
    expect(summary.worstDurationNs).toBe(80_000_000);
  });

  it('leaves rates undefined when nothing was classified', () => {
    const summary = summarize([analyzeFrame(sample({ eligibleForJank: false }))]);
    expect(summary.deadlineMissRate).toBeUndefined();
    expect(summary.platformJankRate).toBeUndefined();
    expect(summary.deadlineUnknownFrames).toBe(1);
  });
});

describe('clusterDeadlineMisses', () => {
  it('groups consecutive misses and splits on a wide smooth gap', () => {
    const frames = [
      analyzeFrame(sample({ frameId: 0, totalDurationNs: 20_000_000 })),
      analyzeFrame(sample({ frameId: 1, totalDurationNs: 20_000_000 })),
      analyzeFrame(sample({ frameId: 2, totalDurationNs: 10_000_000 })),
      analyzeFrame(sample({ frameId: 3, totalDurationNs: 10_000_000 })),
      analyzeFrame(sample({ frameId: 4, totalDurationNs: 10_000_000 })),
      analyzeFrame(sample({ frameId: 5, totalDurationNs: 20_000_000 })),
    ];
    const clusters = clusterDeadlineMisses(frames, 2);
    expect(clusters).toHaveLength(2);
    expect(clusters[0]?.deadlineMissFrameIds).toEqual([0, 1]);
    expect(clusters[0]?.worstSeverity).toBe('MINOR');
    expect(clusters[1]?.deadlineMissFrameIds).toEqual([5]);
  });

  it('returns no clusters when every frame meets its deadline', () => {
    expect(clusterDeadlineMisses([analyzeFrame(sample())])).toEqual([]);
  });
});

describe('analyzeFrames', () => {
  it('sorts by intended vsync before analyzing', () => {
    const result = analyzeFrames([
      sample({ frameId: 5, intendedVsyncNs: 2_000_000_000 }),
      sample({ frameId: 1, intendedVsyncNs: 1_000_000_000 }),
    ]);
    expect(result.frames.map((frame) => frame.sample.frameId)).toEqual([1, 5]);
    expect(result.summary.totalFrames).toBe(2);
  });
});
