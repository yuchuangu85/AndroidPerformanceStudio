import { fail, ok, type StudioResult } from '@aps/contracts';
import { createMemorySession, parseHprof, type HprofParseResult, type MemorySession } from '@aps/memory-profiler';
import {
  EMPTY_NATIVE_HEAP_ANALYSIS,
  analyzeBitmapDump,
  parseBitmapDump,
  parseNativeHeapTraceStrict,
  type BitmapDumpParseResult,
  type BitmapDumpSession,
  type NativeHeapAnalysis,
  type NativeHeapEvidenceSource,
  type ProcessMemorySnapshot,
} from '@aps/memory-profiler/node';

export interface MemoryCaptureAdb {
  shell(args: readonly string[], options: { readonly timeoutMs: number }): Promise<{ stdout: string }>;
  pull(remote: string, local: string, options: { readonly timeoutMs: number }): Promise<void>;
}

export interface MemoryCaptureDependencies {
  readonly adb: MemoryCaptureAdb;
  readonly sizeOf: (path: string) => Promise<number>;
  readonly readFile: (path: string) => Promise<Uint8Array>;
  readonly removeFile: (path: string) => Promise<void>;
  readonly temporaryPath: (name: string) => string;
  readonly now: () => number;
  readonly newId: () => string;
}

export const MAX_HEAP_DUMP_BYTES = 2 * 1024 * 1024 * 1024;
const DEFAULT_DEVICE_DIRECTORY = '/data/local/tmp';
const DUMP_TIMEOUT_MS = 10 * 60 * 1000;
const PULL_TIMEOUT_MS = 10 * 60 * 1000;

/**
 * Captures a heap dump through am dumpheap, pulls it, and turns it into a
 * session holding the histogram and ranked leak suspects. The raw dump is not
 * persisted: it is reproducible evidence and can be gigabytes.
 */
export async function captureHeapDump(
  dependencies: MemoryCaptureDependencies,
  options: {
    readonly serial: string;
    readonly packageName: string;
    readonly deviceDirectory?: string;
    /**
     * Hands the parsed heap to the caller before the raw dump is deleted, so
     * instance browsing can work on the session that was just captured.
     */
    readonly onParsed?: (result: HprofParseResult) => void;
  },
): Promise<StudioResult<MemorySession>> {
  if (options.packageName.trim().length === 0) {
    return fail('DATA_VALIDATION', 'MEMORY_PACKAGE_REQUIRED', 'A package name is required');
  }
  const id = dependencies.newId();
  const directory = (options.deviceDirectory ?? DEFAULT_DEVICE_DIRECTORY).replace(/\/+$/, '');
  const devicePath = directory + '/aps-' + id + '.hprof';
  const localPath = dependencies.temporaryPath('aps-' + id + '.hprof');

  try {
    try {
      await dependencies.adb.shell(['am', 'dumpheap', options.packageName, devicePath], {
        timeoutMs: DUMP_TIMEOUT_MS,
      });
    } catch (error) {
      return fail('PROCESS_EXIT', 'MEMORY_DUMP_FAILED', describe(error, options.serial));
    }
    try {
      await dependencies.adb.pull(devicePath, localPath, { timeoutMs: PULL_TIMEOUT_MS });
    } catch (error) {
      return fail('IO', 'MEMORY_PULL_FAILED', describe(error, options.serial));
    }
    const size = await dependencies.sizeOf(localPath);
    if (size > MAX_HEAP_DUMP_BYTES) {
      return fail(
        'DATA_VALIDATION',
        'MEMORY_DUMP_TOO_LARGE',
        'Heap dump exceeds ' + MAX_HEAP_DUMP_BYTES + ' bytes',
      );
    }
    let session: MemorySession;
    try {
      const bytes = await dependencies.readFile(localPath);
      const parsed = parseHprof(bytes);
      session = createMemorySession(parsed, {
        id,
        capturedAtEpochMillis: dependencies.now(),
        deviceSerial: options.serial,
        packageName: options.packageName,
      });
      options.onParsed?.(parsed);
    } catch (error) {
      return fail('DATA_VALIDATION', 'MEMORY_DUMP_MALFORMED', describe(error, options.serial));
    }
    return ok(session);
  } finally {
    // Device storage is scarce, so the dump is removed even on failure.
    await dependencies.adb.shell(['rm', '-f', devicePath], { timeoutMs: 30_000 }).catch(() => undefined);
    await dependencies.removeFile(localPath).catch(() => undefined);
  }
}

function describe(error: unknown, serial: string): string {
  const message = error instanceof Error ? error.message : 'heap dump capture failed';
  return message + ' (device ' + serial + ')';
}
/**
 * Bitmap heap dumps need the API 35 `am dumpheap -b png` extension, and the
 * heapprofd capture needs Android 10. Both are checked before anything is
 * pushed to the device so the failure names the reason.
 */
export const MINIMUM_BITMAP_DUMP_API = 35;
export const MINIMUM_HEAPROFD_API = 29;

const HEAPROFD_SAMPLING_INTERVAL_BYTES = 4096;
const HEAPROFD_BUFFER_KB = 8192;
const HEAPROFD_DURATION_MS = 20_000;
const SDK_TIMEOUT_MS = 30_000;

export interface ExtendedMemoryCaptureDependencies extends MemoryCaptureDependencies {
  /** Pushes a local file to the device; needed for the heapprofd config. */
  readonly push: (local: string, remote: string, options: { readonly timeoutMs: number }) => Promise<void>;
  readonly writeTextFile: (path: string, contents: string) => Promise<void>;
  readonly directoryOf: (path: string) => string;
}

async function deviceSdkLevel(dependencies: MemoryCaptureDependencies): Promise<number | undefined> {
  try {
    const result = await dependencies.adb.shell(["getprop", "ro.build.version.sdk"], {
      timeoutMs: SDK_TIMEOUT_MS,
    });
    const parsed = Number.parseInt(result.stdout.trim(), 10);
    return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : undefined;
  } catch {
    return undefined;
  }
}

async function processIdOf(dependencies: MemoryCaptureDependencies, packageName: string): Promise<number | undefined> {
  try {
    const result = await dependencies.adb.shell(["pidof", packageName], { timeoutMs: SDK_TIMEOUT_MS });
    const parsed = Number.parseInt(result.stdout.trim().split(/\s+/)[0] ?? "", 10);
    return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : undefined;
  } catch {
    return undefined;
  }
}

/** dumpsys meminfo in KiB; the three lines the ratio needs. */
export function parseProcessMemorySnapshot(output: string): ProcessMemorySnapshot | undefined {
  const values = new Map<string, number>();
  for (const line of output.split("\n")) {
    const columns = line.trim().split(/\s+/);
    const label = columns[0] ?? "";
    const keyword = columns[1] ?? "";
    const value = Number.parseInt(columns[2] ?? "", 10);
    if (!Number.isSafeInteger(value)) continue;
    if (label === "Java" && keyword === "Heap:") values.set("java", value * 1024);
    else if (label === "Native" && keyword === "Heap:") values.set("native", value * 1024);
    else if (label === "TOTAL" && keyword === "PSS:") values.set("total", value * 1024);
  }
  const total = values.get("total");
  const java = values.get("java");
  const native = values.get("native");
  if (total === undefined || java === undefined || native === undefined) return undefined;
  return { totalPssBytes: total, javaHeapPssBytes: java, nativeHeapPssBytes: native };
}

export interface BitmapCaptureOptions {
  readonly serial: string;
  readonly packageName: string;
  readonly deviceDirectory?: string;
  readonly onProgress?: (percent: number) => void;
}

/**
 * Captures a bitmap-annotated heap dump: every Bitmap payload comes back as a
 * PNG next to the HPROF, which is what makes "which image is this" answerable.
 */
export async function captureBitmapDump(
  dependencies: ExtendedMemoryCaptureDependencies,
  options: BitmapCaptureOptions,
): Promise<StudioResult<BitmapCaptureOutcome>> {
  if (options.packageName.trim().length === 0) {
    return fail("DATA_VALIDATION", "MEMORY_PACKAGE_REQUIRED", "A package name is required");
  }
  const sdkLevel = await deviceSdkLevel(dependencies);
  if (sdkLevel === undefined) {
    return fail("CONFIGURATION", "BITMAP_SDK_UNKNOWN", "Unable to read the Android API level.");
  }
  if (sdkLevel < MINIMUM_BITMAP_DUMP_API) {
    return fail(
      "CONFIGURATION",
      "BITMAP_UNSUPPORTED_API",
      "Bitmap dump requires Android API " + String(MINIMUM_BITMAP_DUMP_API) + "; the device is API " + String(sdkLevel) + ".",
    );
  }
  const pid = await processIdOf(dependencies, options.packageName);
  if (pid === undefined) {
    return fail("DATA_VALIDATION", "BITMAP_PROCESS_NOT_RUNNING", "No running process for " + options.packageName);
  }

  const id = dependencies.newId();
  const directory = (options.deviceDirectory ?? DEFAULT_DEVICE_DIRECTORY).replace(/\/+$/, "");
  const devicePath = directory + "/aps-bitmap-" + id + ".hprof";
  const localPath = dependencies.temporaryPath("aps-bitmap-" + id + ".hprof");
  const imagesDirectory = dependencies.directoryOf(localPath) + "/aps-bitmap-" + id;
  const warnings: string[] = [];
  try {
    options.onProgress?.(10);
    try {
      await dependencies.adb.shell(["am", "dumpheap", "-b", "png", String(pid), devicePath], {
        timeoutMs: DUMP_TIMEOUT_MS,
      });
    } catch (error) {
      return fail("PROCESS_EXIT", "BITMAP_DUMP_FAILED", describe(error, options.serial));
    }
    options.onProgress?.(40);
    try {
      await dependencies.adb.pull(devicePath, localPath, { timeoutMs: PULL_TIMEOUT_MS });
    } catch (error) {
      return fail("IO", "BITMAP_PULL_FAILED", describe(error, options.serial));
    }
    const size = await dependencies.sizeOf(localPath).catch(() => 0);
    if (size === 0) {
      return fail("IO", "BITMAP_PULL_EMPTY", "Bitmap HPROF pull completed but the local file is empty.");
    }
    if (size > MAX_HEAP_DUMP_BYTES) {
      return fail("DATA_VALIDATION", "BITMAP_DUMP_TOO_LARGE", "Bitmap HPROF exceeds " + String(MAX_HEAP_DUMP_BYTES) + " bytes");
    }

    let memorySnapshot: ProcessMemorySnapshot | undefined;
    try {
      const meminfo = await dependencies.adb.shell(["dumpsys", "meminfo", String(pid)], { timeoutMs: SDK_TIMEOUT_MS });
      memorySnapshot = parseProcessMemorySnapshot(meminfo.stdout);
    } catch {
      memorySnapshot = undefined;
    }
    if (memorySnapshot === undefined) {
      warnings.push("MEMINFO_UNAVAILABLE: the process memory snapshot was unavailable.");
    }

    let parsed: BitmapDumpParseResult;
    try {
      parsed = parseBitmapDump(localPath, imagesDirectory, options.onProgress);
    } catch (error) {
      return fail("DATA_VALIDATION", "BITMAP_DUMP_MALFORMED", describe(error, options.serial));
    }
    const session = analyzeBitmapDump({
      id,
      packageName: options.packageName,
      pid,
      deviceSerial: options.serial,
      sdkLevel,
      capturedAtEpochMillis: dependencies.now(),
      hprofFile: localPath,
      imagesDirectory,
      parsed,
      ...(memorySnapshot !== undefined ? { memorySnapshot } : {}),
    });
    return ok({ session, warnings });
  } finally {
    await dependencies.adb.shell(["rm", "-f", devicePath], { timeoutMs: 30_000 }).catch(() => undefined);
  }
}

/** The session plus what the capture could not provide. */
export interface BitmapCaptureOutcome {
  readonly session: BitmapDumpSession;
  readonly warnings: readonly string[];
}

export interface NativeHeapCaptureOptions {
  readonly serial: string;
  readonly packageName: string;
}

export interface NativeHeapCaptureResult {
  readonly id: string;
  readonly traceFile: string;
  readonly fileName: string;
  readonly fileSizeBytes: number;
  readonly sdkLevel: number;
  readonly deviceSerial: string;
  readonly packageName: string;
  readonly capturedAtEpochMillis: number;
  readonly analysis: NativeHeapAnalysis;
  readonly evidenceSource: NativeHeapEvidenceSource;
  readonly warnings: readonly string[];
}

/**
 * Captures a heapprofd profile. The raw trace is the authoritative artifact;
 * the in-app summary is best effort and says so when the wire parse fails.
 */
export async function captureNativeHeapTrace(
  dependencies: ExtendedMemoryCaptureDependencies,
  options: NativeHeapCaptureOptions,
): Promise<StudioResult<NativeHeapCaptureResult>> {
  if (options.packageName.trim().length === 0) {
    return fail("DATA_VALIDATION", "MEMORY_PACKAGE_REQUIRED", "A package name is required");
  }
  const sdkLevel = await deviceSdkLevel(dependencies);
  if (sdkLevel === undefined) {
    return fail("CONFIGURATION", "NATIVE_HEAP_SDK_UNKNOWN", "Unable to read the Android API level.");
  }
  if (sdkLevel < MINIMUM_HEAPROFD_API) {
    return fail(
      "CONFIGURATION",
      "NATIVE_HEAP_UNSUPPORTED_API",
      "Native heap capture requires Android API " + String(MINIMUM_HEAPROFD_API) + "; the device is API " + String(sdkLevel) + ".",
    );
  }
  const pid = await processIdOf(dependencies, options.packageName);
  if (pid === undefined) {
    return fail("DATA_VALIDATION", "NATIVE_HEAP_PROCESS_NOT_RUNNING", "No running process for " + options.packageName);
  }

  const id = dependencies.newId();
  const directory = DEFAULT_DEVICE_DIRECTORY;
  const deviceConfigPath = directory + "/heapprofd-" + id + ".cfg";
  const deviceTracePath = directory + "/heapprofd-" + id + ".pb";
  const localConfigPath = dependencies.temporaryPath("heapprofd-" + id + ".cfg");
  const localTracePath = dependencies.temporaryPath("heapprofd-" + id + ".pb");
  const warnings: string[] = [];
  try {
    await dependencies.writeTextFile(localConfigPath, heapprofdConfig(pid));
    try {
      await dependencies.push(localConfigPath, deviceConfigPath, { timeoutMs: SDK_TIMEOUT_MS });
    } catch (error) {
      return fail("IO", "NATIVE_HEAP_CONFIG_PUSH_FAILED", describe(error, options.serial));
    }
    try {
      await dependencies.adb.shell(
        ["perfetto", "--txt", "-c", deviceConfigPath, "-o", deviceTracePath],
        { timeoutMs: PULL_TIMEOUT_MS },
      );
    } catch (error) {
      return fail("PROCESS_EXIT", "NATIVE_HEAP_CAPTURE_FAILED", describe(error, options.serial));
    }
    try {
      await dependencies.adb.pull(deviceTracePath, localTracePath, { timeoutMs: PULL_TIMEOUT_MS });
    } catch (error) {
      return fail("IO", "NATIVE_HEAP_PULL_FAILED", describe(error, options.serial));
    }
    const size = await dependencies.sizeOf(localTracePath).catch(() => 0);
    if (size === 0) {
      return fail("IO", "NATIVE_HEAP_PULL_EMPTY", "Native heap trace pull completed but the local file is empty.");
    }
    const bytes = await dependencies.readFile(localTracePath);
    let analysis: NativeHeapAnalysis;
    try {
      analysis = parseNativeHeapTraceStrict(bytes);
    } catch (error) {
      analysis = EMPTY_NATIVE_HEAP_ANALYSIS;
      warnings.push("NATIVE_HEAP_WIRE_FALLBACK: " + (error instanceof Error ? error.message : "the wire parse failed"));
    }
    return ok({
      id,
      traceFile: localTracePath,
      fileName: "heapprofd-" + id + ".pb",
      fileSizeBytes: size,
      sdkLevel,
      deviceSerial: options.serial,
      packageName: options.packageName,
      capturedAtEpochMillis: dependencies.now(),
      analysis,
      evidenceSource: "WIRE_FALLBACK",
      warnings,
    });
  } finally {
    await dependencies.adb
      .shell(["rm", "-f", deviceConfigPath, deviceTracePath], { timeoutMs: 30_000 })
      .catch(() => undefined);
    await dependencies.removeFile(localConfigPath).catch(() => undefined);
  }
}

export function heapprofdConfig(pid: number): string {
  return [
    "buffers {",
    "  size_kb: " + String(HEAPROFD_BUFFER_KB),
    "}",
    "data_sources {",
    "  config {",
    '    name: "android.heapprofd"',
    "    target_buffer: 0",
    "    heapprofd_config {",
    "      sampling_interval_bytes: " + String(HEAPROFD_SAMPLING_INTERVAL_BYTES),
    "      pid: " + String(pid),
    "    }",
    "  }",
    "}",
    "duration_ms: " + String(HEAPROFD_DURATION_MS),
  ].join("\n");
}

