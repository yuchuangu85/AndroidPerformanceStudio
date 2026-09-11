import { fail, ok, type StudioResult } from '@aps/contracts';
import {
  captureMethodTrace,
  evaluateMethodTraceSupport,
  parsePackageCapabilities,
  parseSdkApiLevel,
  toCallStackTable,
  parseArtTrace,
  type ArtTraceAnalysis,
  type MethodTraceAdb,
  type MethodTraceDeviceSupport,
  type MethodTraceWarning,
} from '@aps/art-trace';
import type { CallStackTable } from '@aps/profile-analysis';

export interface MethodCaptureInput {
  readonly serial: string;
  readonly packageName: string;
  readonly pid: number;
  readonly durationSeconds: number;
}

export interface MethodCaptureDependencies {
  readonly adb: MethodTraceAdb;
  readonly temporaryPath: (name: string) => string;
  readonly sizeOf: (path: string) => Promise<number>;
  readonly readFile: (path: string) => Promise<Uint8Array>;
  readonly removeFile: (path: string) => Promise<void>;
  readonly sleep: (milliseconds: number) => Promise<void>;
  readonly now: () => number;
  readonly newId: () => string;
  readonly shouldStop?: () => boolean;
}

export interface MethodCaptureOutcome {
  readonly id: string;
  readonly capturedAtEpochMillis: number;
  readonly packageName: string;
  readonly pid: number;
  readonly durationSeconds: number;
  readonly deviceSdkApiLevel: number;
  readonly warnings: readonly MethodTraceWarning[];
  readonly trace: Uint8Array;
  readonly analysis: ArtTraceAnalysis;
  readonly table: CallStackTable;
}

const COMMAND_TIMEOUT_MS = 30_000;

/**
 * Reads the API level, root state, and package flags, then applies the gate.
 * Root is checked with "id -u": adbd running as root is the only reliable
 * signal without a su prompt.
 */
export async function readDeviceSupport(
  adb: MethodTraceAdb,
  serial: string,
  packageName: string,
): Promise<StudioResult<MethodTraceDeviceSupport>> {
  void serial;
  let sdkApiLevel: number | undefined;
  try {
    const sdk = await adb.shell(['getprop', 'ro.build.version.sdk'], { timeoutMs: COMMAND_TIMEOUT_MS });
    sdkApiLevel = parseSdkApiLevel(sdk.stdout);
  } catch {
    sdkApiLevel = undefined;
  }

  let isRoot: boolean;
  try {
    const id = await adb.shell(['id', '-u'], { timeoutMs: COMMAND_TIMEOUT_MS });
    isRoot = id.stdout.trim() === '0';
  } catch {
    // A shell that cannot report its uid is treated as unprivileged.
    isRoot = false;
  }

  let capabilities: ReturnType<typeof parsePackageCapabilities> | undefined;
  try {
    const details = await adb.shell(['dumpsys', 'package', packageName], { timeoutMs: COMMAND_TIMEOUT_MS });
    capabilities = parsePackageCapabilities(details.stdout);
  } catch {
    capabilities = undefined;
  }

  return ok(
    evaluateMethodTraceSupport({
      packageName,
      ...(sdkApiLevel !== undefined ? { sdkApiLevel } : {}),
      isRoot,
      ...(capabilities !== undefined ? { capabilities } : {}),
    }),
  );
}

/** Runs the gate and one capture, then parses the trace into a call stack table. */
export async function captureMethodRecording(
  dependencies: MethodCaptureDependencies,
  input: MethodCaptureInput,
): Promise<StudioResult<MethodCaptureOutcome>> {
  if (input.packageName.trim().length === 0) {
    return fail('CONFIGURATION', 'METHOD_TRACE_PACKAGE_REQUIRED', 'A package name is required');
  }
  const support = await readDeviceSupport(dependencies.adb, input.serial, input.packageName.trim());
  if (!support.ok) return support;
  if (!support.value.supported) {
    return fail(
      'UNSUPPORTED_PLATFORM',
      'METHOD_TRACE_UNSUPPORTED',
      support.value.reason ?? 'Method tracing is not available for this device and app',
    );
  }

  const captured = await captureMethodTrace(
    {
      adb: dependencies.adb,
      temporaryPath: dependencies.temporaryPath,
      sizeOf: dependencies.sizeOf,
      removeFile: dependencies.removeFile,
      sleep: dependencies.sleep,
      now: dependencies.now,
      newId: dependencies.newId,
      ...(dependencies.shouldStop !== undefined ? { shouldStop: dependencies.shouldStop } : {}),
    },
    {
      serial: input.serial,
      packageName: input.packageName.trim(),
      pid: input.pid,
      durationSeconds: input.durationSeconds,
    },
  );
  if (!captured.ok) return captured;

  let trace: Uint8Array;
  try {
    trace = await dependencies.readFile(captured.value.traceFile);
  } catch (error) {
    return fail(
      'IO',
      'METHOD_TRACE_READ_FAILED',
      error instanceof Error ? error.message : 'Failed to read the captured method trace',
    );
  }
  const parsed = parseArtTrace(trace);
  if (!parsed.ok) return parsed;
  if (parsed.value.events.length === 0) {
    return fail(
      'DATA_VALIDATION',
      'METHOD_TRACE_NO_EVENTS',
      'The method trace contains no events; the app may not have run any traced code',
    );
  }
  return ok({
    id: captured.value.id,
    capturedAtEpochMillis: captured.value.capturedAtEpochMillis,
    packageName: captured.value.packageName,
    pid: captured.value.pid,
    durationSeconds: captured.value.durationSeconds,
    deviceSdkApiLevel: captured.value.deviceSdkApiLevel,
    warnings: captured.value.warnings,
    trace,
    analysis: parsed.value,
    table: toCallStackTable(parsed.value),
  });
}
