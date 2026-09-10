export interface StartupStatistics {
  readonly count: number;
  readonly missingCount: number;
  readonly minimumMs?: number;
  readonly maximumMs?: number;
  readonly medianMs?: number;
  readonly meanMs?: number;
  readonly p90Ms?: number;
  readonly p95Ms?: number;
  readonly standardDeviationMs?: number;
  readonly medianAbsoluteDeviationMs?: number;
  readonly p90LowResolution: boolean;
  readonly p95LowResolution: boolean;
}

function quantile(sorted: readonly number[], fraction: number): number | undefined {
  if (sorted.length === 0) return undefined;
  const index = Math.min(Math.max(Math.ceil(sorted.length * fraction) - 1, 0), sorted.length - 1);
  return sorted[index];
}

/**
 * Percentiles are flagged as low resolution when the sample count cannot place a
 * single observation in the tail: p90 needs at least 10 runs, p95 at least 20.
 */
export function computeStartupStatistics(values: readonly number[], missingCount = 0): StartupStatistics {
  const sorted = [...values].filter((value) => Number.isFinite(value)).sort((left, right) => left - right);
  const count = sorted.length;
  if (count === 0) {
    return { count: 0, missingCount, p90LowResolution: true, p95LowResolution: true };
  }
  const mean = sorted.reduce((total, value) => total + value, 0) / count;
  const variance = sorted.reduce((total, value) => total + (value - mean) * (value - mean), 0) / count;
  const median = quantile(sorted, 0.5) as number;
  const deviations = sorted.map((value) => Math.abs(value - median)).sort((left, right) => left - right);
  const p90 = quantile(sorted, 0.9);
  const p95 = quantile(sorted, 0.95);
  const mad = quantile(deviations, 0.5);
  const minimum = sorted[0];
  const maximum = sorted[count - 1];
  return {
    count,
    missingCount,
    ...(minimum !== undefined ? { minimumMs: minimum } : {}),
    ...(maximum !== undefined ? { maximumMs: maximum } : {}),
    medianMs: median,
    meanMs: mean,
    ...(p90 !== undefined ? { p90Ms: p90 } : {}),
    ...(p95 !== undefined ? { p95Ms: p95 } : {}),
    standardDeviationMs: Math.sqrt(variance),
    ...(mad !== undefined ? { medianAbsoluteDeviationMs: mad } : {}),
    p90LowResolution: count < 10,
    p95LowResolution: count < 20,
  };
}
