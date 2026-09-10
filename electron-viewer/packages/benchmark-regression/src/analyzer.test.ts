import { describe, expect, it } from 'vitest';
import { compareBenchmarkRuns, compatibilityIssues } from './analyzer.js';
import {
  DEFAULT_REGRESSION_POLICY,
  type BenchmarkCase,
  type BenchmarkMetric,
  type BenchmarkRun,
  type EvidenceConfidence,
  type MetricDirection,
} from './model.js';

function metric(name: string, median: number, samples: number[], direction: MetricDirection = 'LOWER_IS_BETTER'): BenchmarkMetric {
  return {
    name,
    unit: name.endsWith('Ns') ? 'ns' : 'unit',
    direction,
    samples,
    median,
    minimum: Math.min(...samples),
    maximum: Math.max(...samples),
    confidence: (samples.length >= 3 ? 'EXACT' : 'PARTIAL') as EvidenceConfidence,
    sourceFields: {},
  };
}

function benchmarkCase(metrics: BenchmarkMetric[], name = 'com.example.Benchmark#someTest'): BenchmarkCase {
  const [className, testName] = name.split('#');
  return { className: className as string, testName: testName as string, metrics, traceArtifacts: [] };
}

function run(overrides: Partial<BenchmarkRun> = {}): BenchmarkRun {
  return {
    id: 'run',
    sourceFile: '/tmp/a.json',
    device: { model: 'Pixel 8', apiLevel: 34, abi: 'arm64-v8a' },
    build: { variant: 'benchmark' },
    importedAtEpochMillis: 0,
    cases: [],
    warnings: [],
    ...overrides,
  };
}

const POLICY = { ...DEFAULT_REGRESSION_POLICY, relativeThresholdPercent: 5, absoluteThreshold: 1 };

describe('compatibilityIssues', () => {
  it('flags hard device and build differences', () => {
    const issues = compatibilityIssues(
      run(),
      run({ device: { model: 'Pixel 9', apiLevel: 34, abi: 'arm64-v8a' }, build: { variant: 'release' } }),
      DEFAULT_REGRESSION_POLICY,
    );
    expect(issues.map((issue) => issue.field).sort()).toEqual(['build.variant', 'device.model']);
    expect(issues.every((issue) => issue.hard)).toBe(true);
  });

  it('treats a fingerprint change as a soft issue', () => {
    const issues = compatibilityIssues(
      run({ device: { model: 'Pixel 8', apiLevel: 34, abi: 'arm64-v8a', fingerprint: 'a' } }),
      run({ device: { model: 'Pixel 8', apiLevel: 34, abi: 'arm64-v8a', fingerprint: 'b' } }),
      DEFAULT_REGRESSION_POLICY,
    );
    expect(issues).toHaveLength(1);
    expect(issues[0]).toMatchObject({ field: 'device.fingerprint', hard: false });
  });
});

describe('compareBenchmarkRuns', () => {
  it('classifies regressions, improvements, and stable deltas', () => {
    const baseline = run({
      cases: [
        benchmarkCase([
          metric('timeNs', 100, [98, 100, 102]),
          metric('throughput', 100, [98, 100, 102], 'HIGHER_IS_BETTER'),
        ]),
      ],
    });
    const current = run({
      id: 'current',
      cases: [
        benchmarkCase([
          metric('timeNs', 130, [128, 130, 132]),
          metric('throughput', 130, [128, 130, 132], 'HIGHER_IS_BETTER'),
        ]),
      ],
    });
    const report = compareBenchmarkRuns(baseline, current, POLICY, () => 5);
    expect(report.regressionCount).toBe(1);
    const time = report.comparisons.find((comparison) => comparison.metricName === 'timeNs');
    expect(time?.classification).toBe('REGRESSED');
    expect(time?.relativeDeltaPercent).toBeCloseTo(30);
    const throughput = report.comparisons.find((comparison) => comparison.metricName === 'throughput');
    expect(throughput?.classification).toBe('IMPROVED');
    expect(report.createdAtEpochMillis).toBe(5);
  });

  it('keeps deltas inside the baseline noise band stable', () => {
    const baseline = run({ cases: [benchmarkCase([metric('timeNs', 100, [90, 100, 110])])] });
    const current = run({ id: 'c', cases: [benchmarkCase([metric('timeNs', 104, [90, 100, 110])])] });
    const report = compareBenchmarkRuns(baseline, current, POLICY, () => 0);
    expect(report.comparisons[0]?.classification).toBe('STABLE');
  });

  it('marks runs incompatible when the environment changed', () => {
    const baseline = run({ cases: [benchmarkCase([metric('timeNs', 100, [100, 100, 100])])] });
    const current = run({
      id: 'c',
      device: { model: 'Pixel 9', apiLevel: 34, abi: 'arm64-v8a' },
      cases: [benchmarkCase([metric('timeNs', 200, [200, 200, 200])])],
    });
    const report = compareBenchmarkRuns(baseline, current, POLICY, () => 0);
    expect(report.comparisons[0]?.classification).toBe('INCOMPATIBLE');
    expect(report.comparisons[0]?.reasons[0]).toContain('device.model');
  });

  it('reports inconclusive results for missing baselines, unknown direction, and thin samples', () => {
    const baseline = run({ cases: [benchmarkCase([metric('timeNs', 100, [100, 100, 100])])] });

    const missing = compareBenchmarkRuns(
      baseline,
      run({ id: 'c', cases: [benchmarkCase([metric('otherNs', 100, [100, 100, 100])])] }),
      POLICY,
      () => 0,
    );
    expect(missing.comparisons[0]?.classification).toBe('INCONCLUSIVE');

    const unknownDirection = compareBenchmarkRuns(
      baseline,
      run({ id: 'c', cases: [benchmarkCase([metric('timeNs', 200, [200, 200, 200], 'UNKNOWN')])] }),
      POLICY,
      () => 0,
    );
    expect(unknownDirection.comparisons[0]?.classification).toBe('INCONCLUSIVE');

    const thinSamples = compareBenchmarkRuns(
      run({ cases: [benchmarkCase([metric('timeNs', 100, [100])])] }),
      run({ id: 'c', cases: [benchmarkCase([metric('timeNs', 300, [300])])] }),
      POLICY,
      () => 0,
    );
    expect(thinSamples.comparisons[0]?.confidence).toBe('PARTIAL');
    expect(thinSamples.comparisons[0]?.classification).toBe('INCONCLUSIVE');
    expect(thinSamples.comparisons[0]?.reasons.some((reason) => reason.includes('Insufficient raw samples'))).toBe(true);
  });

  it('requires an explicit threshold before gating', () => {
    const baseline = run({ cases: [benchmarkCase([metric('timeNs', 100, [100, 100, 100])])] });
    const current = run({ id: 'c', cases: [benchmarkCase([metric('timeNs', 500, [500, 500, 500])])] });
    const report = compareBenchmarkRuns(baseline, current, DEFAULT_REGRESSION_POLICY, () => 0);
    expect(report.comparisons[0]?.classification).toBe('INCONCLUSIVE');
    expect(report.comparisons[0]?.reasons.some((reason) => reason.includes('No regression threshold is configured'))).toBe(true);
  });
});
