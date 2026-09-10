import { describe, expect, it } from 'vitest';
import {
  convertPerfDataToProtobuf,
  hostSimpleperfCandidates,
  locateHostSimpleperf,
  type HostSimpleperfLocatorDependencies,
  type SimpleperfConversionDependencies,
} from './node.js';

function locatorDependencies(
  overrides: Partial<HostSimpleperfLocatorDependencies> = {},
): HostSimpleperfLocatorDependencies {
  return {
    pathDirectories: ['/usr/bin', '/opt/sdk'],
    platform: 'darwin',
    join: (...parts) => parts.join('/'),
    isRegularFile: () => true,
    sha256Of: () => 'a'.repeat(64),
    runVersion: async () => ({ stdout: 'simpleperf 35.0.0', stderr: '' }),
    ...overrides,
  };
}

describe('host simpleperf location', () => {
  it('prefers configured, then bundled, then PATH, and deduplicates', () => {
    const candidates = hostSimpleperfCandidates(
      locatorDependencies({
        configuredExecutable: '/custom/simpleperf',
        bundledExecutable: { executable: '/bundle/simpleperf', expectedSha256: 'b'.repeat(64) },
      }),
    );
    expect(candidates.map((candidate) => candidate.source)).toEqual(['CONFIGURED', 'BUNDLED', 'PATH', 'PATH']);
    expect(candidates.map((candidate) => candidate.executable)).toEqual([
      '/custom/simpleperf',
      '/bundle/simpleperf',
      '/usr/bin/simpleperf',
      '/opt/sdk/simpleperf',
    ]);

    const duplicated = hostSimpleperfCandidates(
      locatorDependencies({
        configuredExecutable: '/usr/bin/simpleperf',
        pathDirectories: ['/usr/bin'],
      }),
    );
    expect(duplicated.map((candidate) => candidate.executable)).toEqual(['/usr/bin/simpleperf']);
  });

  it('uses the platform executable name and skips missing files', () => {
    const names: string[] = [];
    hostSimpleperfCandidates(
      locatorDependencies({
        platform: 'win32',
        pathDirectories: ['C:/sdk'],
        isRegularFile: (path) => {
          names.push(path);
          return path.endsWith('simpleperf.exe');
        },
      }),
    );
    expect(names).toEqual(['C:/sdk/simpleperf.exe']);
  });

  it('verifies the bundled digest before trusting the binary', async () => {
    const mismatch = await locateHostSimpleperf(
      locatorDependencies({
        bundledExecutable: { executable: '/bundle/simpleperf', expectedSha256: 'c'.repeat(64) },
      }),
    );
    expect(mismatch.ok).toBe(false);
    if (!mismatch.ok) {
      expect(mismatch.error.code).toBe('HOST_SIMPLEPERF_HASH_MISMATCH');
      expect(mismatch.error.message).toContain('c'.repeat(64));
    }

    const ok = await locateHostSimpleperf(
      locatorDependencies({
        bundledExecutable: { executable: '/bundle/simpleperf', expectedSha256: 'a'.repeat(64) },
      }),
    );
    expect(ok.ok).toBe(true);
    if (ok.ok) {
      expect(ok.value.executable).toBe('/bundle/simpleperf');
      expect(ok.value.source).toBe('BUNDLED');
      expect(ok.value.version).toBe('simpleperf 35.0.0');
    }
  });

  it('reports not found, unreadable binaries, and empty versions', async () => {
    const notFound = await locateHostSimpleperf(locatorDependencies({ isRegularFile: () => false }));
    expect(notFound.ok).toBe(false);
    if (!notFound.ok) expect(notFound.error.code).toBe('HOST_SIMPLEPERF_NOT_FOUND');

    const unreadable = await locateHostSimpleperf(
      locatorDependencies({
        sha256Of: () => {
          throw new Error('EACCES');
        },
      }),
    );
    expect(unreadable.ok).toBe(false);
    if (!unreadable.ok) expect(unreadable.error.code).toBe('HOST_SIMPLEPERF_HASH_READ_FAILED');

    const empty = await locateHostSimpleperf(
      locatorDependencies({ runVersion: async () => ({ stdout: '  ', stderr: '' }) }),
    );
    expect(empty.ok).toBe(false);
    if (!empty.ok) expect(empty.error.code).toBe('HOST_SIMPLEPERF_VERSION_EMPTY');

    const failed = await locateHostSimpleperf(
      locatorDependencies({
        runVersion: async () => {
          throw new Error('spawn failed');
        },
      }),
    );
    expect(failed.ok).toBe(false);
    if (!failed.ok) expect(failed.error.code).toBe('HOST_SIMPLEPERF_VERSION_FAILED');

    const fromStderr = await locateHostSimpleperf(
      locatorDependencies({ runVersion: async () => ({ stdout: '', stderr: 'simpleperf 34' }) }),
    );
    expect(fromStderr.ok).toBe(true);
    if (fromStderr.ok) expect(fromStderr.value.version).toBe('simpleperf 34');
  });
});

interface ConversionHarness {
  readonly dependencies: SimpleperfConversionDependencies;
  readonly runs: { executable: string; args: readonly string[] }[];
  readonly removed: string[];
}

function conversionHarness(
  options: { exitCode?: number; timedOut?: boolean; outputExists?: boolean; outputSize?: number } = {},
): ConversionHarness {
  const runs: { executable: string; args: readonly string[] }[] = [];
  const removed: string[] = [];
  return {
    runs,
    removed,
    dependencies: {
      run: async (input) => {
        runs.push({ executable: input.executable, args: input.args });
        return {
          exitCode: options.exitCode ?? 0,
          stdout: '',
          stderr: options.exitCode === undefined || options.exitCode === 0 ? '' : 'report failed',
          timedOut: options.timedOut ?? false,
        };
      },
      isRegularFile: () => true,
      isDirectory: () => true,
      sizeOf: () => options.outputSize ?? 4096,
      removeFile: (path) => {
        removed.push(path);
      },
      ensureParentDirectory: () => undefined,
    },
  };
}

describe('convertPerfDataToProtobuf', () => {
  it('runs report-sample with the conversion arguments', async () => {
    const test = conversionHarness();
    const result = await convertPerfDataToProtobuf(test.dependencies, {
      simpleperf: '/bundle/simpleperf',
      perfData: '/tmp/perf.data',
      protobufTrace: '/tmp/out.pb',
      symbolDirectory: '/sym',
    });
    expect(result.ok).toBe(true);
    expect(test.runs[0]?.executable).toBe('/bundle/simpleperf');
    expect(test.runs[0]?.args).toEqual([
      'report-sample',
      '--protobuf',
      '--show-callchain',
      '-i',
      '/tmp/perf.data',
      '-o',
      '/tmp/out.pb',
      '--symdir',
      '/sym',
    ]);
    expect(test.removed).toEqual(['/tmp/out.pb']);
  });

  it('rejects missing inputs before spawning anything', async () => {
    const test = conversionHarness();
    const missingPerfData = await convertPerfDataToProtobuf(
      { ...test.dependencies, isRegularFile: () => false },
      { simpleperf: 'simpleperf', perfData: '/tmp/perf.data', protobufTrace: '/tmp/out.pb' },
    );
    expect(missingPerfData.ok).toBe(false);
    if (!missingPerfData.ok) expect(missingPerfData.error.code).toBe('PERF_DATA_NOT_FOUND');

    const missingSymbols = await convertPerfDataToProtobuf(
      { ...test.dependencies, isDirectory: () => false },
      {
        simpleperf: 'simpleperf',
        perfData: '/tmp/perf.data',
        protobufTrace: '/tmp/out.pb',
        symbolDirectory: '/missing',
      },
    );
    expect(missingSymbols.ok).toBe(false);
    if (!missingSymbols.ok) expect(missingSymbols.error.code).toBe('SYMBOL_DIRECTORY_NOT_FOUND');

    const missingMapping = await convertPerfDataToProtobuf(
      { ...test.dependencies, isRegularFile: (path) => path !== '/tmp/missing.txt' },
      {
        simpleperf: 'simpleperf',
        perfData: '/tmp/perf.data',
        protobufTrace: '/tmp/out.pb',
        proguardMapping: '/tmp/missing.txt',
      },
    );
    expect(missingMapping.ok).toBe(false);
    if (!missingMapping.ok) expect(missingMapping.error.code).toBe('PROGUARD_MAPPING_NOT_FOUND');
    expect(test.runs).toHaveLength(0);
  });

  it('maps a failed, timed out, or empty conversion to a stable code', async () => {
    const failed = await convertPerfDataToProtobuf(conversionHarness({ exitCode: 2 }).dependencies, {
      simpleperf: 'simpleperf',
      perfData: '/tmp/perf.data',
      protobufTrace: '/tmp/out.pb',
    });
    expect(failed.ok).toBe(false);
    if (!failed.ok) expect(failed.error.code).toBe('SIMPLEPERF_REPORT_FAILED');

    const timedOut = await convertPerfDataToProtobuf(
      conversionHarness({ timedOut: true, exitCode: -1 }).dependencies,
      { simpleperf: 'simpleperf', perfData: '/tmp/perf.data', protobufTrace: '/tmp/out.pb' },
    );
    expect(timedOut.ok).toBe(false);
    if (!timedOut.ok) expect(timedOut.error.code).toBe('SIMPLEPERF_REPORT_TIMEOUT');

    const emptyOutput = await convertPerfDataToProtobuf(
      conversionHarness({ outputExists: false, outputSize: 0 }).dependencies,
      { simpleperf: 'simpleperf', perfData: '/tmp/perf.data', protobufTrace: '/tmp/out.pb' },
    );
    expect(emptyOutput.ok).toBe(false);
    if (!emptyOutput.ok) expect(emptyOutput.error.code).toBe('PROTOBUF_OUTPUT_MISSING');
  });
});
