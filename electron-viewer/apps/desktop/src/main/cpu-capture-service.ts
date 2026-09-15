import { fail, ok, type StudioResult } from '@aps/contracts';
import {
  DEFAULT_SAMPLING_PARAMETERS,
  captureSimpleperfProfile,
  samplingParameters,
  type CallGraphMode,
  type EventScope,
  type NormalizedProfile,
  type SamplingParameters,
  type SimpleperfCaptureAdb,
  type SimpleperfCaptureDependencies,
  type SimpleperfReportParser,
  type SimpleperfTarget,
} from '@aps/simpleperf-profiler';

export interface CpuCaptureInput {
  readonly serial: string;
  readonly packageName?: string;
  readonly event: string;
  readonly frequencyHertz: number;
  readonly periodEvents: number;
  readonly rateMode: 'FREQUENCY' | 'PERIOD';
  readonly durationSeconds: number;
  readonly callGraph: CallGraphMode;
  readonly scope: EventScope;
  readonly target: 'APP' | 'SYSTEM_WIDE';
}

export interface CpuCaptureDependencies {
  readonly adb: SimpleperfCaptureAdb;
  /** Locates the host simpleperf; its failure is reported to the user verbatim. */
  readonly locateHostSimpleperf: () => Promise<StudioResult<{ readonly executable: string }>>;
  /** Runs report-sample with the located host binary and validates its output. */
  readonly convert: (input: {
    readonly executable: string;
    readonly perfData: string;
    readonly protobufTrace: string;
    readonly symbolDirectory?: string;
    readonly proguardMapping?: string;
  }) => Promise<StudioResult<void>>;
  readonly temporaryPath: (name: string) => string;
  readonly sizeOf: (path: string) => Promise<number>;
  readonly readFile: (path: string) => Promise<Uint8Array>;
  readonly removeFile: (path: string) => Promise<void>;
  readonly now: () => number;
  readonly newId: () => string;
  /** Keeps raw perf.data available until the caller persists it. */
  readonly retainPerfData?: boolean;
  readonly onStage?: (stage: string) => void;
  /** Defaults to direct parsing; Electron injects a worker-backed report parser. */
  readonly parseReport?: SimpleperfReportParser;
}

export interface CpuCaptureOutcome {
  readonly profile: NormalizedProfile;
  readonly table: import('@aps/profile-analysis').CallStackTable;
  readonly report: Uint8Array;
  readonly parameters: SamplingParameters;
  readonly id: string;
  readonly capturedAtEpochMillis: number;
  readonly simpleperfVersion?: string;
  readonly perfDataBytes: number;
  /** Raw evidence retained by the caller before capture cleanup. */
  readonly perfDataPath?: string;
}

export function samplingParametersFor(input: CpuCaptureInput): SamplingParameters {
  const target: SimpleperfTarget =
    input.target === 'APP' && input.packageName !== undefined && input.packageName.trim().length > 0
      ? { kind: 'APP', packageName: input.packageName.trim() }
      : { kind: 'SYSTEM_WIDE' };
  return samplingParameters({
    ...DEFAULT_SAMPLING_PARAMETERS,
    target,
    event: input.event,
    rate:
      input.rateMode === 'PERIOD'
        ? { kind: 'PERIOD', events: BigInt(input.periodEvents) }
        : { kind: 'FREQUENCY', hertz: input.frequencyHertz },
    durationSeconds: input.durationSeconds,
    callGraph: input.callGraph,
    scope: input.scope,
  });
}

/**
 * Runs one CPU capture end to end. The protobuf report is read back before the
 * temporary directory is dropped. Callers can additionally retain the raw
 * perf.data evidence when they need Kotlin-compatible session-package export.
 */
export async function captureCpuProfile(
  dependencies: CpuCaptureDependencies,
  input: CpuCaptureInput,
): Promise<StudioResult<CpuCaptureOutcome>> {
  let parameters: SamplingParameters;
  try {
    parameters = samplingParametersFor(input);
  } catch (error) {
    return fail(
      'CONFIGURATION',
      'CPU_SAMPLING_PARAMETERS_INVALID',
      error instanceof Error ? error.message : 'Invalid sampling parameters',
    );
  }

  const host = await dependencies.locateHostSimpleperf();
  if (!host.ok) return host;

  const captureDependencies: SimpleperfCaptureDependencies = {
    adb: dependencies.adb,
    convert: (conversion) => dependencies.convert({ executable: host.value.executable, ...conversion }),
    temporaryPath: dependencies.temporaryPath,
    sizeOf: dependencies.sizeOf,
    readFile: dependencies.readFile,
    removeFile: dependencies.removeFile,
    now: dependencies.now,
    newId: dependencies.newId,
    ...(dependencies.retainPerfData === true ? { retainPerfData: true } : {}),
    ...(dependencies.onStage !== undefined ? { onStage: dependencies.onStage } : {}),
    ...(dependencies.parseReport !== undefined ? { parseReport: dependencies.parseReport } : {}),
  };

  const captured = await captureSimpleperfProfile(captureDependencies, {
    serial: input.serial,
    ...(input.packageName !== undefined && input.packageName.trim().length > 0
      ? { packageName: input.packageName.trim() }
      : {}),
    parameters,
  });
  if (!captured.ok) return captured;

  let report: Uint8Array;
  try {
    report = await dependencies.readFile(captured.value.protobufTrace);
  } catch (error) {
    return fail(
      'IO',
      'CPU_REPORT_READ_FAILED',
      error instanceof Error ? error.message : 'Failed to read the protobuf report',
    );
  }
  return ok({
    profile: captured.value.profile,
    table: captured.value.table,
    report,
    parameters,
    id: captured.value.id,
    capturedAtEpochMillis: captured.value.capturedAtEpochMillis,
    ...(captured.value.simpleperfVersion !== undefined
      ? { simpleperfVersion: captured.value.simpleperfVersion }
      : {}),
    perfDataBytes: captured.value.perfDataBytes,
    ...(captured.value.perfDataPath !== undefined ? { perfDataPath: captured.value.perfDataPath } : {}),
  });
}
