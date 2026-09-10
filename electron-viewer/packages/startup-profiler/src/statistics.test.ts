import { describe, expect, it } from 'vitest';
import { computeStartupStatistics } from './statistics.js';

describe('computeStartupStatistics', () => {
  it('reports an empty result without samples', () => {
    const statistics = computeStartupStatistics([], 3);
    expect(statistics.count).toBe(0);
    expect(statistics.missingCount).toBe(3);
    expect(statistics.meanMs).toBeUndefined();
    expect(statistics.p90LowResolution).toBe(true);
  });

  it('computes mean, percentiles, deviation, and dispersion', () => {
    const values = [500, 520, 510, 530, 540, 505, 515, 525, 535, 545];
    const statistics = computeStartupStatistics(values);
    expect(statistics.count).toBe(10);
    expect(statistics.minimumMs).toBe(500);
    expect(statistics.maximumMs).toBe(545);
    expect(statistics.meanMs).toBeCloseTo(522.5);
    expect(statistics.medianMs).toBe(520);
    expect(statistics.p90Ms).toBe(540);
    expect(statistics.p95Ms).toBe(545);
    expect(statistics.standardDeviationMs).toBeCloseTo(14.36, 1);
    expect(statistics.medianAbsoluteDeviationMs).toBe(10);
  });

  it('flags low-resolution percentiles for small sample counts', () => {
    const five = computeStartupStatistics([500, 510, 520, 530, 540]);
    expect(five.p90LowResolution).toBe(true);
    expect(five.p95LowResolution).toBe(true);
    const twenty = computeStartupStatistics(Array.from({ length: 20 }, (_value, index) => 500 + index));
    expect(twenty.p90LowResolution).toBe(false);
    expect(twenty.p95LowResolution).toBe(false);
  });
});
