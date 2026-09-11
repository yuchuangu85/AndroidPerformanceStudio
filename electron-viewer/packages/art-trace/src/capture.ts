/**
 * Captures a Java/Kotlin method trace from a device through am profile
 * start/stop, following MethodTraceCaptureSession.
 *
 * am profile stop flushes the buffered trace to the device file, so the file is
 * polled for after the stop rather than trusted immediately. The device copy is
 * removed on every path, and a cleanup failure is reported as a warning instead
 * of failing the capture, because the local trace is already complete.
 */
import { fail, ok, type StudioResult } from '@aps/contracts';
import { MINIMUM_METHOD_TRACE_API } from './device-gate.js';

export interface MethodTraceAdb {
  shell(
    args: readonly string[],
    options: { readonly timeoutMs: number },
  ): Promise<{ stdout: string; stderr?: string }>;
  pull(remote: string, local: string, options: { readonly timeoutMs: number }): Promise<void>;
}

export interface MethodTraceCaptureDependencies {
  readonly adb: MethodTraceAdb;
  readonly temporaryPath: (name: string) => string;
  readonly sizeOf: (path: string) => Promise<number>;
  readonly removeFile: (path: string) => Promise<void>;
  readonly sleep: (milliseconds: number) => Promise<void>;
  readonly now: () => number;
  readonly newId: () => string;
  /** Polled while recording so the UI can end a capture early. */
  readonly shouldStop?: () => boolean;
}

export interface MethodTraceCaptureRequest {
  readonly serial: string;
  readonly packageName: string;
  readonly pid: number;
  readonly durationSeconds: number;
}

export interface MethodTraceWarning {
  readonly code: string;
  readonly message: string;
}

export interface MethodTraceCaptureResult {
  readonly id: string;
  readonly capturedAtEpochMillis: number;
  readonly serial: string;
  readonly packageName: string;
  readonly pid: number;
  readonly durationSeconds: number;
  readonly deviceSdkApiLevel: number;
  /** Local temporary path of the captured trace. */
  readonly traceFile: string;
  readonly traceBytes: number;
  readonly warnings: readonly MethodTraceWarning[];
}

const COMMAND_TIMEOUT_MS = 30_000;
const STOP_POLL_INTERVAL_MS = 100;
const FILE_POLL_ATTEMPTS = 20;
const FILE_POLL_INTERVAL_MS = 250;
export const MAX_METHOD_TRACE_BYTES = 1024 * 1024 * 1024;

export function deviceTracePath(id: string): string {
  return '/data/local/tmp/aps-' + id + '.trace';
}

export async function captureMethodTrace(
  dependencies: MethodTraceCaptureDependencies,
  request: MethodTraceCaptureRequest,
): Promise<StudioResult<MethodTraceCaptureResult>> {
  if (!Number.isInteger(request.pid) || request.pid <= 0) {
    return fail('CONFIGURATION', 'METHOD_TRACE_PID_INVALID', 'A positive process id is required');
  }
  if (!(request.durationSeconds > 0)) {
    return fail('CONFIGURATION', 'METHOD_TRACE_DURATION_INVALID', 'The capture duration must be positive');
  }
  const id = dependencies.newId();
  const tracePath = deviceTracePath(id);
  const localTrace = dependencies.temporaryPath(id + '.trace');

  const sdk = await readSdkApiLevel(dependencies);
  if (sdk === undefined) {
    return fail('DATA_VALIDATION', 'METHOD_TRACE_SDK_UNKNOWN', 'Unable to read the Android API level');
  }
  if (sdk < MINIMUM_METHOD_TRACE_API) {
    return fail(
      'UNSUPPORTED_PLATFORM',
      'METHOD_TRACE_UNSUPPORTED_API',
      'Method tracing requires Android API ' +
        String(MINIMUM_METHOD_TRACE_API) +
        '+; the connected device is API ' +
        String(sdk),
    );
  }

  try {
    try {
      await dependencies.adb.shell(['am', 'profile', 'start', String(request.pid), tracePath], {
        timeoutMs: COMMAND_TIMEOUT_MS,
      });
    } catch (error) {
      return fail(
        'PROCESS_EXIT',
        'METHOD_TRACE_START_FAILED',
        'Failed to start method tracing; the target process may not be debuggable or profileable. ' +
          describe(error),
      );
    }

    await waitForDuration(dependencies, request.durationSeconds);

    try {
      await dependencies.adb.shell(['am', 'profile', 'stop', String(request.pid)], {
        timeoutMs: COMMAND_TIMEOUT_MS,
      });
    } catch (error) {
      return fail('PROCESS_EXIT', 'METHOD_TRACE_STOP_FAILED', 'Failed to stop method tracing. ' + describe(error));
    }

    if (!(await waitForTraceFile(dependencies, tracePath))) {
      return fail('DATA_VALIDATION', 'METHOD_TRACE_EMPTY', 'Method trace was not produced on the device');
    }

    try {
      await dependencies.adb.pull(tracePath, localTrace, { timeoutMs: COMMAND_TIMEOUT_MS });
    } catch (error) {
      return fail('PROCESS_EXIT', 'METHOD_TRACE_PULL_FAILED', 'Failed to pull the method trace. ' + describe(error));
    }

    const traceBytes = await dependencies.sizeOf(localTrace).catch(() => 0);
    if (traceBytes <= 0) {
      return fail('DATA_VALIDATION', 'METHOD_TRACE_EMPTY', 'Method trace capture produced an empty file');
    }
    if (traceBytes > MAX_METHOD_TRACE_BYTES) {
      return fail(
        'DATA_VALIDATION',
        'METHOD_TRACE_TOO_LARGE',
        'Method trace is ' + String(traceBytes) + ' bytes; the limit is ' + String(MAX_METHOD_TRACE_BYTES),
      );
    }

    const warnings: MethodTraceWarning[] = [];
    const cleanupFailure = await cleanup(dependencies, tracePath);
    if (cleanupFailure !== undefined) warnings.push(cleanupFailure);

    return ok({
      id,
      capturedAtEpochMillis: dependencies.now(),
      serial: request.serial,
      packageName: request.packageName,
      pid: request.pid,
      durationSeconds: request.durationSeconds,
      deviceSdkApiLevel: sdk,
      traceFile: localTrace,
      traceBytes,
      warnings,
    });
  } finally {
    await dependencies.adb.shell(['rm', '-f', tracePath], { timeoutMs: COMMAND_TIMEOUT_MS }).catch(() => undefined);
    await dependencies.removeFile(localTrace).catch(() => undefined);
  }
}

async function readSdkApiLevel(
  dependencies: MethodTraceCaptureDependencies,
): Promise<number | undefined> {
  try {
    const result = await dependencies.adb.shell(['getprop', 'ro.build.version.sdk'], {
      timeoutMs: COMMAND_TIMEOUT_MS,
    });
    const parsed = Number.parseInt(result.stdout.trim(), 10);
    return Number.isFinite(parsed) ? parsed : undefined;
  } catch {
    return undefined;
  }
}

/**
 * Polls until the capture duration has elapsed. The tick count is derived from
 * the duration rather than from the wall clock so the whole flow stays
 * deterministic and injectable.
 */
async function waitForDuration(
  dependencies: MethodTraceCaptureDependencies,
  durationSeconds: number,
): Promise<void> {
  const ticks = Math.max(Math.ceil((durationSeconds * 1000) / STOP_POLL_INTERVAL_MS), 1);
  for (let tick = 0; tick < ticks; tick += 1) {
    if (dependencies.shouldStop?.() === true) return;
    await dependencies.sleep(STOP_POLL_INTERVAL_MS);
  }
}

async function waitForTraceFile(
  dependencies: MethodTraceCaptureDependencies,
  tracePath: string,
): Promise<boolean> {
  for (let attempt = 0; attempt < FILE_POLL_ATTEMPTS; attempt += 1) {
    try {
      await dependencies.adb.shell(['ls', '-l', tracePath], { timeoutMs: COMMAND_TIMEOUT_MS });
      return true;
    } catch {
      // The stop command flushes asynchronously; keep polling.
    }
    await dependencies.sleep(FILE_POLL_INTERVAL_MS);
  }
  return false;
}

async function cleanup(
  dependencies: MethodTraceCaptureDependencies,
  tracePath: string,
): Promise<MethodTraceWarning | undefined> {
  try {
    await dependencies.adb.shell(['rm', '-f', tracePath], { timeoutMs: COMMAND_TIMEOUT_MS });
    return undefined;
  } catch {
    return {
      code: 'DEVICE_CLEANUP_FAILED',
      message: 'Failed to remove the temporary device method-trace file ' + tracePath + '.',
    };
  }
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : 'ADB command failed';
}
