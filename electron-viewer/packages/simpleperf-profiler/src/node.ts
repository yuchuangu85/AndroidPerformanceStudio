/**
 * Node-only half of the CPU Profiler: locating a host simpleperf and running
 * the perf.data to protobuf conversion with it.
 */
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, rmSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fail, ok, type StudioResult } from '@aps/contracts';
import { runHostProcessText } from '@aps/platform-host';
import { reportSampleArguments, hostSimpleperfExecutableName } from './toolchain.js';

export const HOST_SIMPLEPERF_SOURCES = ['CONFIGURED', 'BUNDLED', 'PATH'] as const;
export type HostSimpleperfSource = (typeof HOST_SIMPLEPERF_SOURCES)[number];

export interface HostSimpleperf {
  readonly executable: string;
  readonly version: string;
  readonly sha256: string;
  readonly source: HostSimpleperfSource;
}

export interface HostSimpleperfCandidate {
  readonly executable: string;
  readonly source: HostSimpleperfSource;
  readonly expectedSha256?: string;
}

export interface HostSimpleperfLocatorDependencies {
  readonly configuredExecutable?: string;
  readonly bundledExecutable?: { readonly executable: string; readonly expectedSha256: string };
  readonly pathDirectories: readonly string[];
  readonly platform: string;
  readonly join: (...parts: readonly string[]) => string;
  readonly isRegularFile: (path: string) => boolean;
  readonly sha256Of: (path: string) => string;
  readonly runVersion: (executable: string) => Promise<{ readonly stdout: string; readonly stderr: string }>;
}

export interface HostSimpleperfLocatorOptions {
  readonly configuredExecutable?: string;
  readonly bundledExecutable?: { readonly executable: string; readonly expectedSha256: string };
  readonly pathDirectories: readonly string[];
  readonly platform?: string;
}

export function defaultHostSimpleperfLocatorDependencies(
  options: HostSimpleperfLocatorOptions,
): HostSimpleperfLocatorDependencies {
  return {
    ...options,
    platform: options.platform ?? process.platform,
    join: (...parts) => join(...parts),
    isRegularFile: (path) => {
      try {
        return statSync(path).isFile();
      } catch {
        return false;
      }
    },
    sha256Of: (path) => createHash('sha256').update(readFileSync(path)).digest('hex'),
    runVersion: async (executable) => {
      const result = await runHostProcessText({ executable, args: ['--version'], timeoutMs: 15_000 });
      return { stdout: result.stdout, stderr: result.stderr };
    },
  };
}

/**
 * Search order matches the Kotlin locator: an explicitly configured binary, a
 * bundled one whose digest must match, then the PATH.
 */
export function hostSimpleperfCandidates(
  dependencies: HostSimpleperfLocatorDependencies,
): HostSimpleperfCandidate[] {
  const executableName = hostSimpleperfExecutableName(dependencies.platform);
  const candidates: HostSimpleperfCandidate[] = [];
  if (dependencies.configuredExecutable !== undefined) {
    candidates.push({ executable: dependencies.configuredExecutable, source: 'CONFIGURED' });
  }
  if (dependencies.bundledExecutable !== undefined) {
    candidates.push({
      executable: dependencies.bundledExecutable.executable,
      source: 'BUNDLED',
      expectedSha256: dependencies.bundledExecutable.expectedSha256,
    });
  }
  dependencies.pathDirectories.forEach((directory) => {
    candidates.push({ executable: dependencies.join(directory, executableName), source: 'PATH' });
  });
  const seen = new Set<string>();
  return candidates.filter((candidate) => {
    if (!dependencies.isRegularFile(candidate.executable)) return false;
    if (seen.has(candidate.executable)) return false;
    seen.add(candidate.executable);
    return true;
  });
}

export async function locateHostSimpleperf(
  dependencies: HostSimpleperfLocatorDependencies,
): Promise<StudioResult<HostSimpleperf>> {
  const candidate = hostSimpleperfCandidates(dependencies)[0];
  if (candidate === undefined) {
    return fail(
      'CONFIGURATION',
      'HOST_SIMPLEPERF_NOT_FOUND',
      'Host simpleperf was not found in configured, bundled, or PATH locations',
    );
  }
  let digest: string;
  try {
    digest = dependencies.sha256Of(candidate.executable);
  } catch (error) {
    return fail(
      'IO',
      'HOST_SIMPLEPERF_HASH_READ_FAILED',
      'Failed to read host simpleperf: ' + candidate.executable,
      error instanceof Error ? error.message : undefined,
    );
  }
  if (candidate.expectedSha256 !== undefined && candidate.expectedSha256 !== digest) {
    return fail(
      'DATA_VALIDATION',
      'HOST_SIMPLEPERF_HASH_MISMATCH',
      'Host simpleperf hash mismatch: expected ' + candidate.expectedSha256 + ', actual ' + digest,
    );
  }
  let version: string;
  try {
    const result = await dependencies.runVersion(candidate.executable);
    version = (result.stdout.trim() || result.stderr.trim()).trim();
  } catch (error) {
    return fail(
      'PROCESS_EXIT',
      'HOST_SIMPLEPERF_VERSION_FAILED',
      'Host simpleperf --version failed: ' + candidate.executable,
      error instanceof Error ? error.message : undefined,
    );
  }
  if (version.length === 0) {
    return fail(
      'DATA_VALIDATION',
      'HOST_SIMPLEPERF_VERSION_EMPTY',
      'Host simpleperf returned an empty version: ' + candidate.executable,
    );
  }
  return ok({
    executable: candidate.executable,
    version,
    sha256: digest,
    source: candidate.source,
  });
}

export interface SimpleperfConversionDependencies {
  readonly run: (input: {
    readonly executable: string;
    readonly args: readonly string[];
    readonly timeoutMs: number;
  }) => Promise<{ readonly exitCode: number; readonly stdout: string; readonly stderr: string; readonly timedOut: boolean }>;
  readonly isRegularFile: (path: string) => boolean;
  readonly isDirectory: (path: string) => boolean;
  readonly sizeOf: (path: string) => number;
  readonly removeFile: (path: string) => void;
  readonly ensureParentDirectory: (path: string) => void;
}

export const CONVERSION_TIMEOUT_MS = 30 * 60 * 1000;

export function defaultConversionDependencies(): SimpleperfConversionDependencies {
  return {
    run: async (input) => {
      try {
        const result = await runHostProcessText({
          executable: input.executable,
          args: [...input.args],
          timeoutMs: input.timeoutMs,
        });
        return {
          exitCode: result.exitCode,
          stdout: result.stdout,
          stderr: result.stderr,
          timedOut: false,
        };
      } catch (error) {
        return {
          exitCode: -1,
          stdout: '',
          stderr: error instanceof Error ? error.message : 'conversion failed',
          timedOut: error instanceof Error && error.name === 'HostProcessTimeoutError',
        };
      }
    },
    isRegularFile: (path) => {
      try {
        return statSync(path).isFile();
      } catch {
        return false;
      }
    },
    isDirectory: (path) => {
      try {
        return statSync(path).isDirectory();
      } catch {
        return false;
      }
    },
    sizeOf: (path) => statSync(path).size,
    removeFile: (path) => {
      try {
        rmSync(path, { force: true });
      } catch {
        // The output is recreated below; a stale file only matters if it stays.
      }
    },
    ensureParentDirectory: (path) => {
      mkdirSync(dirname(path), { recursive: true });
    },
  };
}

/** Converts perf.data into the protobuf report the parser reads. */
export async function convertPerfDataToProtobuf(
  dependencies: SimpleperfConversionDependencies,
  input: {
    readonly simpleperf: string;
    readonly perfData: string;
    readonly protobufTrace: string;
    readonly symbolDirectory?: string;
    readonly proguardMapping?: string;
  },
): Promise<StudioResult<void>> {
  if (!dependencies.isRegularFile(input.perfData)) {
    return fail('IO', 'PERF_DATA_NOT_FOUND', 'perf.data does not exist: ' + input.perfData);
  }
  if (input.symbolDirectory !== undefined && !dependencies.isDirectory(input.symbolDirectory)) {
    return fail('IO', 'SYMBOL_DIRECTORY_NOT_FOUND', 'Symbol directory does not exist: ' + input.symbolDirectory);
  }
  if (input.proguardMapping !== undefined && !dependencies.isRegularFile(input.proguardMapping)) {
    return fail('IO', 'PROGUARD_MAPPING_NOT_FOUND', 'Proguard mapping file does not exist: ' + input.proguardMapping);
  }
  try {
    dependencies.ensureParentDirectory(input.protobufTrace);
    dependencies.removeFile(input.protobufTrace);
  } catch (error) {
    return fail(
      'IO',
      'PROTOBUF_OUTPUT_PREPARE_FAILED',
      'Failed to prepare the protobuf output',
      error instanceof Error ? error.message : undefined,
    );
  }
  const result = await dependencies.run({
    executable: input.simpleperf,
    args: reportSampleArguments(input),
    timeoutMs: CONVERSION_TIMEOUT_MS,
  });
  if (result.timedOut) {
    return fail('PROCESS_TIMEOUT', 'SIMPLEPERF_REPORT_TIMEOUT', 'simpleperf report-sample timed out');
  }
  if (result.exitCode !== 0) {
    return fail(
      'PROCESS_EXIT',
      'SIMPLEPERF_REPORT_FAILED',
      'simpleperf report-sample failed with exit code ' + String(result.exitCode),
      result.stderr.trim().slice(0, 2000),
    );
  }
  if (!dependencies.isRegularFile(input.protobufTrace) || dependencies.sizeOf(input.protobufTrace) <= 0) {
    return fail(
      'IO',
      'PROTOBUF_OUTPUT_MISSING',
      'simpleperf completed without producing a non-empty protobuf trace',
    );
  }
  return ok(undefined);
}
