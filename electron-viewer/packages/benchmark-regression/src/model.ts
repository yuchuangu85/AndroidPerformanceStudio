export type EvidenceConfidence = 'EXACT' | 'DERIVED' | 'INFERRED' | 'PARTIAL' | 'UNKNOWN';
export type MetricDirection = 'LOWER_IS_BETTER' | 'HIGHER_IS_BETTER' | 'UNKNOWN';
export type RegressionClassification = 'REGRESSED' | 'IMPROVED' | 'STABLE' | 'INCONCLUSIVE' | 'INCOMPATIBLE';
export type BaselinePolicy = 'PINNED' | 'BRANCH_HEAD' | 'ROLLING_MEDIAN' | 'RELEASE_TAG';

export interface BenchmarkDevice {
  readonly model?: string;
  readonly brand?: string;
  readonly apiLevel?: number;
  readonly osVersion?: string;
  readonly abi?: string;
  readonly fingerprint?: string;
  readonly cpuCoreCount?: number;
  readonly physicalDevice?: boolean;
}

export interface BenchmarkBuild {
  readonly targetPackage?: string;
  readonly versionName?: string;
  readonly versionCode?: number;
  readonly variant?: string;
  readonly gitCommit?: string;
  readonly gitBranch?: string;
}

export interface BenchmarkMetric {
  readonly name: string;
  readonly unit: string;
  readonly direction: MetricDirection;
  readonly samples: readonly number[];
  readonly minimum?: number;
  readonly median?: number;
  readonly maximum?: number;
  readonly confidence: EvidenceConfidence;
  readonly sourceFields: Readonly<Record<string, string>>;
}

export interface BenchmarkCase {
  readonly className: string;
  readonly testName: string;
  readonly packageName?: string;
  readonly compilationMode?: string;
  readonly startupMode?: string;
  readonly iterationCount?: number;
  readonly metrics: readonly BenchmarkMetric[];
  readonly traceArtifacts: readonly string[];
}

export interface BenchmarkRun {
  readonly id: string;
  readonly sourceFile: string;
  readonly benchmarkDataVersion?: number;
  readonly benchmarkLibraryVersion?: string;
  readonly device: BenchmarkDevice;
  readonly build: BenchmarkBuild;
  readonly importedAtEpochMillis: number;
  readonly cases: readonly BenchmarkCase[];
  readonly warnings: readonly string[];
}

export interface CompatibilityIssue {
  readonly field: string;
  readonly baseline?: string;
  readonly current?: string;
  readonly hard: boolean;
}

export interface MetricComparison {
  readonly caseIdentity: string;
  readonly metricName: string;
  readonly unit: string;
  readonly baselineValue?: number;
  readonly currentValue?: number;
  readonly absoluteDelta?: number;
  readonly relativeDeltaPercent?: number;
  readonly classification: RegressionClassification;
  readonly confidence: EvidenceConfidence;
  readonly reasons: readonly string[];
}

export interface RegressionReport {
  readonly baselineRunId: string;
  readonly currentRunId: string;
  readonly createdAtEpochMillis: number;
  readonly comparisons: readonly MetricComparison[];
  readonly compatibilityIssues: readonly CompatibilityIssue[];
  readonly regressionCount: number;
}

export interface RegressionPolicy {
  readonly relativeThresholdPercent?: number;
  readonly absoluteThreshold?: number;
  readonly minimumSampleCount: number;
  readonly noiseBandMadMultiplier: number;
  readonly requireSameDevice: boolean;
}

export const DEFAULT_REGRESSION_POLICY: RegressionPolicy = {
  minimumSampleCount: 3,
  noiseBandMadMultiplier: 3,
  requireSameDevice: true,
};

export function caseIdentity(value: BenchmarkCase): string {
  return value.className + '#' + value.testName;
}

/** Median of a sample list; even counts average the two middle values. */
export function medianOf(values: readonly number[]): number | undefined {
  if (values.length === 0) return undefined;
  const sorted = [...values].sort((left, right) => left - right);
  const middle = Math.floor(sorted.length / 2);
  if (sorted.length % 2 === 0) {
    const lower = sorted[middle - 1] as number;
    const upper = sorted[middle] as number;
    return (lower + upper) / 2;
  }
  return sorted[middle];
}

export function representativeValue(metric: BenchmarkMetric): number | undefined {
  return metric.median ?? medianOf(metric.samples);
}

/** Median absolute deviation, used as the baseline noise band. */
export function medianAbsoluteDeviation(values: readonly number[]): number {
  const median = medianOf(values);
  if (median === undefined) return 0;
  return medianOf(values.map((value) => Math.abs(value - median))) ?? 0;
}
