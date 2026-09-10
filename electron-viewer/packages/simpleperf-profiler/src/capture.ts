/**
 * Device-side capture: probe simpleperf, record on the device, pull perf.data,
 * convert it to the protobuf report on the host, and parse it.
 *
 * Every dependency is injected so the whole flow is testable without a device,
 * and every stage reports its own stable error code. Device storage is scarce,
 * so the remote perf.data is removed even when the capture fails.
 */
import { fail, ok, type StudioResult } from '@aps/contracts';
import type { NormalizedProfile } from './report.js';
import { normalizeSimpleperfReport } from './report.js';
import { recordShellArguments, type SamplingParameters } from './toolchain.js';

export interface SimpleperfCaptureAdb {
  shell(
    args: readonly string[],
    options: { readonly timeoutMs: number },
  ): Promise<{ stdout: string; stderr?: string }>;
  pull(remote: string, local: string, options: { readonly timeoutMs: number }): Promise<void>;
}

export interface SimpleperfCaptureDependencies {
  readonly adb: SimpleperfCaptureAdb;
  /** Runs the host conversion; resolves with the produced protobuf path. */
  readonly convert: (input: {
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
  readonly onStage?: (stage: SimpleperfCaptureStage) => void;
}

export const SIMPERF_CAPTURE_STAGES = [
  'PROBE',
  'RECORD',
  'PULL',
  'CONVERT',
  'PARSE',
  'CLEANUP',
] as const;

export type SimpleperfCaptureStage = (typeof SIMPERF_CAPTURE_STAGES)[number];

export interface SimpleperfCaptureRequest {
  readonly serial: string;
  readonly packageName?: string;
  readonly parameters: SamplingParameters;
  /** Host directory holding unstripped symbol libraries, when available. */
  readonly symbolDirectory?: string;
  readonly proguardMapping?: string;
}

export interface SimpleperfCaptureResult {
  readonly id: string;
  readonly capturedAtEpochMillis: number;
  readonly serial: string;
  readonly parameters: SamplingParameters;
  readonly profile: NormalizedProfile;
  /** Local path of the retained protobuf report. */
  readonly protobufTrace: string;
  readonly perfDataBytes: number;
  readonly simpleperfVersion?: string;
  readonly devicePath: string;
}

const DEVICE_DIRECTORY = '/data/local/tmp/aps';
const DEVICE_SIMPLEPERF = 'simpleperf';
const PROBE_TIMEOUT_MS = 20_000;
const PULL_TIMEOUT_MS = 10 * 60 * 1000;
const MINUTE_MS = 60 * 1000;
export const MAX_PERF_DATA_BYTES = 4 * 1024 * 1024 * 1024;

/** simpleperf records for the requested duration, so allow generous slack. */
export function recordTimeoutMs(parameters: SamplingParameters): number {
  const duration = parameters.durationSeconds ?? 120;
  return Math.ceil((duration + 60) * 1000);
}

/**
 * Finds a usable simpleperf on the device. Only the device binary is supported:
 * bundling host-built binaries for every ABI is a separate decision, and
 * silently using a mismatched one would be worse than failing.
 */
export async function probeDeviceSimpleperf(
  dependencies: SimpleperfCaptureDependencies,
  serial: string,
): Promise<StudioResult<{ readonly path: string; readonly version?: string }>> {
  const candidates = [DEVICE_SIMPLEPERF, DEVICE_DIRECTORY + '/simpleperf'];
  let lastError = '';
  for (const candidate of candidates) {
    try {
      const result = await dependencies.adb.shell([candidate, '--version'], { timeoutMs: PROBE_TIMEOUT_MS });
      const version = (result.stdout.trim() || (result.stderr ?? '').trim()).split('\n')[0]?.trim();
      if (version !== undefined && version.length > 0) {
        return ok({ path: candidate, ...(version.length > 0 ? { version } : {}) });
      }
      lastError = candidate + ' printed no version';
    } catch (error) {
      lastError = error instanceof Error ? error.message : 'probe failed';
    }
  }
  return fail(
    'CONFIGURATION',
    'DEVICE_SIMPLEPERF_NOT_FOUND',
    'No usable simpleperf on device ' +
      serial +
      ' (tried ' +
      candidates.join(', ') +
      '): ' +
      lastError,
  );
}

export async function captureSimpleperfProfile(
  dependencies: SimpleperfCaptureDependencies,
  request: SimpleperfCaptureRequest,
): Promise<StudioResult<SimpleperfCaptureResult>> {
  const stage = (value: SimpleperfCaptureStage): void => dependencies.onStage?.(value);
  const id = dependencies.newId();
  const devicePath = request.parameters.outputPath;
  const localPerfData = dependencies.temporaryPath('aps-' + id + '.perf.data');
  const protobufTrace = dependencies.temporaryPath('aps-' + id + '.pb');

  stage('PROBE');
  const probe = await probeDeviceSimpleperf(dependencies, request.serial);
  if (!probe.ok) return probe;

  try {
    stage('RECORD');
    try {
      await dependencies.adb.shell(['mkdir', '-p', DEVICE_DIRECTORY], { timeoutMs: PROBE_TIMEOUT_MS });
      await dependencies.adb.shell(['rm', '-f', devicePath], { timeoutMs: PROBE_TIMEOUT_MS });
      await dependencies.adb.shell(
        [probe.value.path, ...recordShellArguments(request.parameters)],
        { timeoutMs: recordTimeoutMs(request.parameters) },
      );
    } catch (error) {
      return fail('PROCESS_EXIT', 'SIMPLEPERF_RECORD_FAILED', describe(error, request.serial));
    }

    stage('PULL');
    let perfDataBytes = 0;
    try {
      const listed = await dependencies.adb.shell(['stat', '-c', '%s', devicePath], { timeoutMs: PROBE_TIMEOUT_MS });
      perfDataBytes = Number.parseInt(listed.stdout.trim(), 10);
      if (Number.isFinite(perfDataBytes) && perfDataBytes > MAX_PERF_DATA_BYTES) {
        return fail(
          'DATA_VALIDATION',
          'SIMPLEPERF_PERF_DATA_TOO_LARGE',
          'perf.data is ' + String(perfDataBytes) + ' bytes; the limit is ' + String(MAX_PERF_DATA_BYTES),
        );
      }
      await dependencies.adb.pull(devicePath, localPerfData, { timeoutMs: PULL_TIMEOUT_MS });
    } catch (error) {
      return fail('IO', 'SIMPLEPERF_PULL_FAILED', describe(error, request.serial));
    }
    if (!Number.isFinite(perfDataBytes) || perfDataBytes <= 0) {
      perfDataBytes = await dependencies.sizeOf(localPerfData).catch(() => 0);
    }
    if (perfDataBytes <= 0) {
      return fail('DATA_VALIDATION', 'SIMPLEPERF_PERF_DATA_EMPTY', 'simpleperf recorded an empty perf.data');
    }

    stage('CONVERT');
    const converted = await dependencies.convert({
      perfData: localPerfData,
      protobufTrace,
      ...(request.symbolDirectory !== undefined ? { symbolDirectory: request.symbolDirectory } : {}),
      ...(request.proguardMapping !== undefined ? { proguardMapping: request.proguardMapping } : {}),
    });
    if (!converted.ok) return converted;

    stage('PARSE');
    let profile: NormalizedProfile;
    try {
      const bytes = await dependencies.readFile(protobufTrace);
      const parsed = normalizeSimpleperfReport(bytes);
      if (!parsed.ok) return parsed;
      profile = parsed.value;
    } catch (error) {
      return fail('IO', 'SIMPLEPERF_REPORT_READ_FAILED', describe(error, request.serial));
    }
    if (profile.samples.length === 0) {
      return fail(
        'DATA_VALIDATION',
        'SIMPLEPERF_NO_SAMPLES',
        'The report contains no samples; the target may not have been running',
      );
    }

    return ok({
      id,
      capturedAtEpochMillis: dependencies.now(),
      serial: request.serial,
      parameters: request.parameters,
      profile,
      protobufTrace,
      perfDataBytes,
      ...(probe.value.version !== undefined ? { simpleperfVersion: probe.value.version } : {}),
      devicePath,
    });
  } finally {
    stage('CLEANUP');
    await dependencies.adb.shell(['rm', '-f', devicePath], { timeoutMs: PROBE_TIMEOUT_MS }).catch(() => undefined);
    await dependencies.removeFile(localPerfData).catch(() => undefined);
  }
}

function describe(error: unknown, serial: string): string {
  const message = error instanceof Error ? error.message : 'simpleperf capture failed';
  return message + ' (device ' + serial + ')';
}

/** Records how long a run may take, used for the UI progress copy. */
export function estimatedCaptureSeconds(parameters: SamplingParameters): number {
  return Math.ceil(parameters.durationSeconds ?? 120) + 30;
}

export { MINUTE_MS };
