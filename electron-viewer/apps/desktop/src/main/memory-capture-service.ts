import { fail, ok, type StudioResult } from '@aps/contracts';
import { createMemorySession, parseHprof, type MemorySession } from '@aps/memory-profiler';

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
  options: { readonly serial: string; readonly packageName: string; readonly deviceDirectory?: string },
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
      session = createMemorySession(parseHprof(bytes), {
        id,
        capturedAtEpochMillis: dependencies.now(),
        deviceSerial: options.serial,
        packageName: options.packageName,
      });
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
