import {
  caseIdentity,
  medianAbsoluteDeviation,
  representativeValue,
  type BenchmarkMetric,
  type BenchmarkRun,
  type CompatibilityIssue,
  type MetricComparison,
  type RegressionClassification,
  type RegressionPolicy,
  type RegressionReport,
} from './model.js';

export function compatibilityIssues(
  baseline: BenchmarkRun,
  current: BenchmarkRun,
  policy: RegressionPolicy,
): CompatibilityIssue[] {
  const issues: CompatibilityIssue[] = [];
  if (policy.requireSameDevice && baseline.device.model !== current.device.model) {
    issues.push({ field: 'device.model', ...optionalSides(baseline.device.model, current.device.model), hard: true });
  }
  if (baseline.device.apiLevel !== current.device.apiLevel) {
    issues.push({
      field: 'device.apiLevel',
      ...optionalSides(baseline.device.apiLevel?.toString(), current.device.apiLevel?.toString()),
      hard: true,
    });
  }
  if (baseline.device.abi !== current.device.abi) {
    issues.push({ field: 'device.abi', ...optionalSides(baseline.device.abi, current.device.abi), hard: true });
  }
  if (
    baseline.device.fingerprint !== undefined &&
    current.device.fingerprint !== undefined &&
    baseline.device.fingerprint !== current.device.fingerprint
  ) {
    issues.push({
      field: 'device.fingerprint',
      ...optionalSides(baseline.device.fingerprint, current.device.fingerprint),
      hard: false,
    });
  }
  if (baseline.build.variant !== current.build.variant) {
    issues.push({ field: 'build.variant', ...optionalSides(baseline.build.variant, current.build.variant), hard: true });
  }
  return issues;
}

function optionalSides(baseline: string | undefined, current: string | undefined): { baseline?: string; current?: string } {
  return {
    ...(baseline !== undefined ? { baseline } : {}),
    ...(current !== undefined ? { current } : {}),
  };
}

/**
 * Port of RegressionAnalyzer.classifyDelta. A delta inside the baseline noise
 * band, or below both thresholds, is stable even when it is directionally worse.
 */
function classifyDelta(
  absoluteDelta: number,
  relativeDelta: number | undefined,
  direction: BenchmarkMetric['direction'],
  policy: RegressionPolicy,
  noiseBand: number,
  allowGate: boolean,
): RegressionClassification {
  const exceedsRelative =
    policy.relativeThresholdPercent === undefined
      ? true
      : relativeDelta !== undefined && Math.abs(relativeDelta) >= policy.relativeThresholdPercent;
  const exceedsAbsolute =
    policy.absoluteThreshold === undefined ? true : Math.abs(absoluteDelta) >= policy.absoluteThreshold;
  if (!exceedsRelative || !exceedsAbsolute || Math.abs(absoluteDelta) <= noiseBand) return 'STABLE';
  if (!allowGate) return 'INCONCLUSIVE';
  if (direction === 'UNKNOWN') return 'INCONCLUSIVE';
  const worse = direction === 'LOWER_IS_BETTER' ? absoluteDelta > 0 : absoluteDelta < 0;
  return worse ? 'REGRESSED' : 'IMPROVED';
}

function compareMetric(
  identity: string,
  baseline: BenchmarkMetric | undefined,
  current: BenchmarkMetric,
  policy: RegressionPolicy,
  hardIssues: readonly CompatibilityIssue[],
): MetricComparison {
  const reasons: string[] = [];
  if (hardIssues.length > 0) {
    reasons.push('Run environments are incompatible: ' + hardIssues.map((issue) => issue.field).join(', '));
  }
  if (baseline === undefined) reasons.push('No baseline metric with the same case and metric identity.');
  if (baseline !== undefined && baseline.unit !== current.unit) {
    reasons.push('Metric units differ (' + baseline.unit + ' vs ' + current.unit + ').');
  }
  if (current.direction === 'UNKNOWN') {
    reasons.push('Metric direction is unknown and requires project configuration.');
  }
  // The original silently returned INCONCLUSIVE here; explain it so the UI can.
  if (policy.relativeThresholdPercent === undefined && policy.absoluteThreshold === undefined) {
    reasons.push('No regression threshold is configured, so the comparison cannot be gated.');
  }
  const baselineValue = baseline === undefined ? undefined : representativeValue(baseline);
  const currentValue = representativeValue(current);
  const absoluteDelta =
    baselineValue !== undefined && currentValue !== undefined ? currentValue - baselineValue : undefined;
  const relativeDelta =
    absoluteDelta !== undefined && baselineValue !== undefined && baselineValue !== 0
      ? (absoluteDelta / Math.abs(baselineValue)) * 100
      : undefined;
  const sampleConfidence: BenchmarkMetric['confidence'] =
    baseline !== undefined &&
    baseline.samples.length >= policy.minimumSampleCount &&
    current.samples.length >= policy.minimumSampleCount
      ? 'EXACT'
      : 'PARTIAL';
  const noiseBand =
    baseline === undefined ? 0 : medianAbsoluteDeviation(baseline.samples) * policy.noiseBandMadMultiplier;

  let classification: RegressionClassification;
  if (hardIssues.length > 0 || (baseline !== undefined && baseline.unit !== current.unit)) {
    classification = 'INCOMPATIBLE';
  } else if (
    baseline === undefined ||
    baselineValue === undefined ||
    currentValue === undefined ||
    current.direction === 'UNKNOWN' ||
    (policy.relativeThresholdPercent === undefined && policy.absoluteThreshold === undefined)
  ) {
    classification = 'INCONCLUSIVE';
  } else if (sampleConfidence === 'PARTIAL') {
    reasons.push('Insufficient raw samples for a high-confidence gate.');
    classification = classifyDelta(
      absoluteDelta ?? 0,
      relativeDelta,
      current.direction,
      policy,
      noiseBand,
      false,
    );
  } else {
    classification = classifyDelta(absoluteDelta ?? 0, relativeDelta, current.direction, policy, noiseBand, true);
  }

  return {
    caseIdentity: identity,
    metricName: current.name,
    unit: current.unit,
    ...(baselineValue !== undefined ? { baselineValue } : {}),
    ...(currentValue !== undefined ? { currentValue } : {}),
    ...(absoluteDelta !== undefined ? { absoluteDelta } : {}),
    ...(relativeDelta !== undefined ? { relativeDeltaPercent: relativeDelta } : {}),
    classification,
    confidence: sampleConfidence,
    reasons,
  };
}

export function compareBenchmarkRuns(
  baseline: BenchmarkRun,
  current: BenchmarkRun,
  policy: RegressionPolicy,
  now: () => number = () => Date.now(),
): RegressionReport {
  const issues = compatibilityIssues(baseline, current, policy);
  const hardIssues = issues.filter((issue) => issue.hard);
  const baselineMetrics = new Map<string, BenchmarkMetric>();
  for (const benchmarkCase of baseline.cases) {
    for (const metric of benchmarkCase.metrics) {
      baselineMetrics.set(caseIdentity(benchmarkCase) + '|' + metric.name, metric);
    }
  }
  const comparisons: MetricComparison[] = [];
  for (const benchmarkCase of current.cases) {
    const identity = caseIdentity(benchmarkCase);
    for (const metric of benchmarkCase.metrics) {
      comparisons.push(
        compareMetric(identity, baselineMetrics.get(identity + '|' + metric.name), metric, policy, hardIssues),
      );
    }
  }
  return {
    baselineRunId: baseline.id,
    currentRunId: current.id,
    createdAtEpochMillis: now(),
    comparisons,
    compatibilityIssues: issues,
    regressionCount: comparisons.filter((comparison) => comparison.classification === 'REGRESSED').length,
  };
}
