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

export interface ParsedMethodTrace {
  readonly analysis: ArtTraceAnalysis;
  readonly table: CallStackTable;
}

/** A running process eligible for the Kotlin-equivalent Method Recording picker. */
export interface MethodTraceProcessOption {
  readonly pid: number;
  readonly name: string;
  readonly packageName: string;
}

/** Allows production composition to parse CPU-heavy traces away from Electron's main thread. */
export type MethodTraceParser = (trace: Uint8Array) => Promise<StudioResult<ParsedMethodTrace>>;

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
  /** Defaults to the in-process parser; Electron injects its worker-backed implementation. */
  readonly parseTrace?: MethodTraceParser;
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

/** Dependencies kept small so an imported file can be parsed without ADB. */
export interface MethodTraceImportDependencies {
  readonly now: () => number;
  readonly newId: () => string;
  /** Defaults to the in-process parser; file import uses the worker-backed parser in Electron. */
  readonly parseTrace?: MethodTraceParser;
}

export interface MethodTraceImportInput {
  readonly fileName: string;
  readonly trace: Uint8Array;
}

export interface MethodTraceImportOutcome {
  readonly id: string;
  readonly capturedAtEpochMillis: number;
  readonly sourceFileName: string;
  readonly trace: Uint8Array;
  readonly analysis: ArtTraceAnalysis;
  readonly table: CallStackTable;
}

const COMMAND_TIMEOUT_MS = 30_000;
const METHOD_TRACE_EXTENSION = '.trace';

/**
 * Extracts running app processes from Android's stable `ps -A -o PID,NAME`
 * shape, then retains only package roots which `dumpsys package packages`
 * identifies as debuggable or profileable by shell. This is the same target
 * predicate Kotlin's MethodRecordingDeviceGateway applies after AdbTargetCatalog.
 */
export function parseMethodTraceProcessOptions(
  processOutput: string,
  packageDetailsOutput: string,
): readonly MethodTraceProcessOption[] {
  const capabilities = parsePackageCapabilities(packageDetailsOutput);
  const seen = new Set<number>();
  const options: MethodTraceProcessOption[] = [];
  for (const line of processOutput.split(/\r?\n/u)) {
    const columns = line.trim().split(/\s+/u);
    const pid = Number(columns[0]);
    const name = columns.slice(1).join(' ');
    if (!Number.isInteger(pid) || pid <= 0 || name.length === 0 || seen.has(pid)) continue;
    const packageName = name.split(':', 1)[0] ?? '';
    const capability = capabilities.get(packageName);
    if (capability === undefined || (!capability.debuggable && !capability.profileableByShell)) continue;
    seen.add(pid);
    options.push({ pid, name, packageName });
  }
  return options.sort((left, right) => left.name.localeCompare(right.name) || left.pid - right.pid);
}

/** Reads exactly the process and package-capability data the Method picker needs. */
export async function discoverMethodTraceProcesses(
  adb: MethodTraceAdb,
): Promise<StudioResult<readonly MethodTraceProcessOption[]>> {
  try {
    const packageDetails = await adb.shell(['dumpsys', 'package', 'packages'], { timeoutMs: COMMAND_TIMEOUT_MS });
    const processes = await adb.shell(['ps', '-A', '-o', 'PID,NAME'], { timeoutMs: COMMAND_TIMEOUT_MS });
    return ok(parseMethodTraceProcessOptions(processes.stdout, packageDetails.stdout));
  } catch (error) {
    return fail(
      'PROCESS_EXIT',
      'METHOD_TRACE_PROCESS_DISCOVERY_FAILED',
      'Failed to discover debuggable or profileable app processes. ' +
        (error instanceof Error ? error.message : 'ADB command failed'),
    );
  }
}

/**
 * Re-reads the selected PID immediately before capture so a stale picker entry
 * cannot profile an exited process or a PID which Android has reused.
 */
export async function verifyMethodTraceTarget(
  adb: MethodTraceAdb,
  input: Pick<MethodCaptureInput, 'packageName' | 'pid'>,
): Promise<StudioResult<MethodTraceProcessOption>> {
  try {
    const packageDetails = await adb.shell(['dumpsys', 'package', 'packages'], { timeoutMs: COMMAND_TIMEOUT_MS });
    const processes = await adb.shell(['ps', '-A', '-o', 'PID,NAME'], { timeoutMs: COMMAND_TIMEOUT_MS });
    const target = parseMethodTraceProcessOptions(processes.stdout, packageDetails.stdout).find(
      (process) => process.pid === input.pid && process.packageName === input.packageName.trim(),
    );
    if (target === undefined) {
      return fail(
        'DATA_VALIDATION',
        'METHOD_TRACE_TARGET_UNAVAILABLE',
        'The selected process is no longer a running debuggable or profileable instance of ' + input.packageName.trim(),
      );
    }
    return ok(target);
  } catch (error) {
    return fail(
      'PROCESS_EXIT',
      'METHOD_TRACE_TARGET_VERIFICATION_FAILED',
      'Failed to verify the selected process before method recording. ' +
        (error instanceof Error ? error.message : 'ADB command failed'),
    );
  }
}

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
  if (!Number.isInteger(input.pid) || input.pid <= 0) {
    return fail('CONFIGURATION', 'METHOD_TRACE_PID_INVALID', 'A positive process id is required');
  }
  if (!(input.durationSeconds > 0)) {
    return fail('CONFIGURATION', 'METHOD_TRACE_DURATION_INVALID', 'The capture duration must be positive');
  }
  const target = await verifyMethodTraceTarget(dependencies.adb, input);
  if (!target.ok) return target;
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
  const parsed = await parseTrace(dependencies.parseTrace, trace, input.serial);
  if (!parsed.ok) return parsed;
  if (parsed.value.analysis.events.length === 0) {
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
    analysis: parsed.value.analysis,
    table: parsed.value.table,
  });
}


/** Runs the direct parser through the same shape the worker-backed path returns. */
export async function parseMethodTraceDirect(trace: Uint8Array): Promise<StudioResult<ParsedMethodTrace>> {
  const parsed = parseArtTrace(trace);
  if (!parsed.ok) return parsed;
  return ok({ analysis: parsed.value, table: toCallStackTable(parsed.value) });
}

/** Parses an ART trace selected from disk without inventing capture-device metadata. */
export async function importMethodTraceAsync(
  dependencies: MethodTraceImportDependencies,
  input: MethodTraceImportInput,
): Promise<StudioResult<MethodTraceImportOutcome>> {
  const validated = validateMethodTraceImport(input);
  if (!validated.ok) return validated;
  const parsed = await parseTrace(dependencies.parseTrace, validated.value.trace);
  if (!parsed.ok) return parsed;
  return importedTraceOutcome(dependencies, validated.value, parsed.value);
}

/** Parses an ART trace selected from disk without inventing capture-device metadata. */
export function importMethodTrace(
  dependencies: MethodTraceImportDependencies,
  input: MethodTraceImportInput,
): StudioResult<MethodTraceImportOutcome> {
  const validated = validateMethodTraceImport(input);
  if (!validated.ok) return validated;
  const parsed = parseArtTrace(validated.value.trace);
  if (!parsed.ok) return parsed;
  return importedTraceOutcome(dependencies, validated.value, {
    analysis: parsed.value,
    table: toCallStackTable(parsed.value),
  });
}

function validateMethodTraceImport(
  input: MethodTraceImportInput,
): StudioResult<{ readonly sourceFileName: string; readonly trace: Uint8Array }> {
  const sourceFileName = input.fileName.trim();
  if (!sourceFileName.toLocaleLowerCase().endsWith(METHOD_TRACE_EXTENSION)) {
    return fail(
      'CONFIGURATION',
      'METHOD_TRACE_IMPORT_EXTENSION',
      'Select an ART method trace with a .trace file extension',
    );
  }
  if (input.trace.length === 0) {
    return fail('DATA_VALIDATION', 'METHOD_TRACE_IMPORT_EMPTY', 'The selected method trace is empty');
  }
  return ok({ sourceFileName, trace: new Uint8Array(input.trace) });
}

function importedTraceOutcome(
  dependencies: MethodTraceImportDependencies,
  input: { readonly sourceFileName: string; readonly trace: Uint8Array },
  parsed: ParsedMethodTrace,
): StudioResult<MethodTraceImportOutcome> {
  if (parsed.analysis.events.length === 0) {
    return fail(
      'DATA_VALIDATION',
      'METHOD_TRACE_NO_EVENTS',
      'The method trace contains no events; the app may not have run any traced code',
    );
  }
  return ok({
    id: dependencies.newId(),
    capturedAtEpochMillis: dependencies.now(),
    sourceFileName: input.sourceFileName,
    trace: input.trace,
    analysis: parsed.analysis,
    table: parsed.table,
  });
}

async function parseTrace(
  parser: MethodTraceParser | undefined,
  trace: Uint8Array,
  serial?: string,
): Promise<StudioResult<ParsedMethodTrace>> {
  try {
    return await (parser ?? parseMethodTraceDirect)(trace);
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Failed to parse ART method trace';
    return fail(
      'UNKNOWN',
      'METHOD_TRACE_PARSE_FAILED',
      serial === undefined ? message : message + ' (device ' + serial + ')',
    );
  }
}
