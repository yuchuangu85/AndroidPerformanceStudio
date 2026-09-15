import { computeStartupStatistics, type StartupStatistics } from './statistics.js';
import type { StartupExperimentConfig } from './experiment.js';
import type { StartupRun } from './model.js';

export interface StartupSessionStatistics {
  readonly totalTimeMs: StartupStatistics;
  readonly ttidMs: StartupStatistics;
  readonly ttfdMs: StartupStatistics;
  readonly measuredRuns: number;
  readonly warmupRuns: number;
  readonly unknownObservedRuns: number;
  readonly modeMismatchRuns: number;
}

export type StartupSessionOrigin = 'CAPTURED' | 'IMPORTED';

export interface StartupSession {
  readonly id: string;
  /** Imported reports do not expose a live ADB serial. */
  readonly deviceSerial: string;
  /** Kotlin's public JSON may omit run context, hence no package identity. */
  readonly packageName?: string;
  readonly componentName?: string;
  readonly origin?: StartupSessionOrigin;
  /** Kotlin's exported pseudonym; never treated as an ADB serial. */
  readonly sourceDeviceLocalId?: string;
  /** SHA-256 of a read-only imported Kotlin Startup SQLite source. */
  readonly sourceDatabaseSha256?: string;
  readonly sourceFileName?: string;
  /**
   * Schema-gated original Kotlin v1 report retained only for an imported session.
   * Re-exporting it preserves Kotlin evidence that Electron does not model.
   */
  readonly kotlinJsonV1?: string;
  readonly config: StartupExperimentConfig;
  readonly createdAtEpochMillis: number;
  readonly runs: readonly StartupRun[];
  readonly statistics: StartupSessionStatistics;
}

function collect(values: ReadonlyArray<number | undefined>, expected: number): { values: number[]; missing: number } {
  const present = values.filter((value): value is number => value !== undefined);
  return { values: present, missing: expected - present.length };
}

/** Statistics cover measured runs only; warmups exist solely to prime the mode. */
export function summarizeStartupSession(runs: readonly StartupRun[]): StartupSessionStatistics {
  const measured = runs.filter((run) => run.measured);
  const totalTime = collect(measured.map((run) => run.platform.totalTimeMs), measured.length);
  const ttid = collect(measured.map((run) => run.platform.displayedTimeMs), measured.length);
  const ttfd = collect(measured.map((run) => run.platform.fullyDrawnTimeMs), measured.length);
  return {
    totalTimeMs: computeStartupStatistics(totalTime.values, totalTime.missing),
    ttidMs: computeStartupStatistics(ttid.values, ttid.missing),
    ttfdMs: computeStartupStatistics(ttfd.values, ttfd.missing),
    measuredRuns: measured.length,
    warmupRuns: runs.length - measured.length,
    unknownObservedRuns: measured.filter((run) => run.observedType === 'UNKNOWN').length,
    modeMismatchRuns: measured.filter(
      (run) => run.observedType !== 'UNKNOWN' && run.observedType !== run.requestedType,
    ).length,
  };
}

export function createStartupSession(options: {
  readonly id: string;
  readonly deviceSerial: string;
  readonly packageName?: string;
  readonly componentName?: string;
  readonly config: StartupExperimentConfig;
  readonly createdAtEpochMillis: number;
  readonly runs: readonly StartupRun[];
}): StartupSession {
  return {
    id: options.id,
    deviceSerial: options.deviceSerial,
    ...(options.packageName !== undefined ? { packageName: options.packageName } : {}),
    origin: 'CAPTURED',
    ...(options.componentName !== undefined ? { componentName: options.componentName } : {}),
    config: options.config,
    createdAtEpochMillis: options.createdAtEpochMillis,
    runs: options.runs,
    statistics: summarizeStartupSession(options.runs),
  };
}
