import { describe, expect, it } from 'vitest';
import { captureSimpleperfProfile, probeDeviceSimpleperf, recordTimeoutMs, type SimpleperfCaptureAdb, type SimpleperfCaptureDependencies } from './capture.js';
import {
  commandPreview,
  hostSimpleperfExecutableName,
  recordCommandPreview,
  recordShellArguments,
  reportSampleArguments,
  samplingParameters,
  shellQuote,
} from './toolchain.js';
import { fileEntry, fileRecord, metaInfoEntry, metaInfoRecord, sample, sampleRecord, stream, threadEntry, threadRecord } from './report-builder.js';

describe('sampling parameters', () => {
  it('builds the record vector for each target kind', () => {
    expect(recordShellArguments(samplingParameters())).toEqual([
      'record',
      '-e',
      'cpu-clock',
      '-f',
      '1000',
      '--duration',
      '10',
      '-g',
      '-a',
      '-o',
      '/data/local/tmp/aps/perf.data',
    ]);
    expect(
      recordShellArguments(
        samplingParameters({ target: { kind: 'APP', packageName: 'com.example.app' }, scope: 'USER' }),
      ),
    ).toContain('cpu-clock:u');
    expect(
      recordShellArguments(samplingParameters({ target: { kind: 'APP', packageName: 'com.example.app' } })),
    ).toEqual(expect.arrayContaining(['--app', 'com.example.app']));
    expect(
      recordShellArguments(samplingParameters({ target: { kind: 'PROCESS', pid: 42 } })),
    ).toEqual(expect.arrayContaining(['-p', '42']));
    expect(
      recordShellArguments(
        samplingParameters({ target: { kind: 'THREAD', tid: 7, appPackage: 'com.example.app' } }),
      ),
    ).toEqual(expect.arrayContaining(['--app', 'com.example.app', '-t', '7']));
    expect(
      recordShellArguments(samplingParameters({ target: { kind: 'PROCESS_NAME', name: 'surfaceflinger' } })),
    ).toEqual(expect.arrayContaining(['-p', 'surfaceflinger']));
  });

  it('switches rate, call graph, and duration', () => {
    const period = recordShellArguments(
      samplingParameters({ rate: { kind: 'PERIOD', events: 100000n }, callGraph: 'FRAME_POINTER' }),
    );
    expect(period).toEqual(expect.arrayContaining(['-c', '100000']));
    expect(period).toEqual(expect.arrayContaining(['--call-graph', 'fp']));
    const openEnded = recordShellArguments(samplingParameters({ durationSeconds: undefined, callGraph: 'NONE' }));
    expect(openEnded).not.toContain('--duration');
    expect(openEnded).not.toContain('-g');
    const fractional = recordShellArguments(samplingParameters({ durationSeconds: 2.5 }));
    expect(fractional).toEqual(expect.arrayContaining(['--duration', '2.5']));
  });

  it('rejects malformed parameters and quotes the preview', () => {
    expect(() => samplingParameters({ event: 'cpu clock' })).toThrow(/event must be a non-blank command token/);
    expect(() => samplingParameters({ durationSeconds: 0 })).toThrow(/duration must be positive/);
    expect(() => samplingParameters({ rate: { kind: 'FREQUENCY', hertz: 0 } })).toThrow(/frequency must be positive/);
    expect(() => samplingParameters({ target: { kind: 'PROCESS', pid: 0 } })).toThrow(/pid must be positive/);
    expect(() => samplingParameters({ target: { kind: 'APP', packageName: 'a b' } })).toThrow(/packageName/);

    expect(shellQuote('plain-token')).toBe('plain-token');
    expect(shellQuote('two words')).toBe("'two words'");
    expect(commandPreview('adb', ['-s', 'SER', 'shell'])).toBe('adb -s SER shell');
    expect(recordCommandPreview(samplingParameters({ target: { kind: 'APP', packageName: 'com.example.app' } }))).toContain(
      'simpleperf record',
    );
    expect(hostSimpleperfExecutableName('Windows 11')).toBe('simpleperf.exe');
    expect(hostSimpleperfExecutableName('win32')).toBe('simpleperf.exe');
    expect(hostSimpleperfExecutableName('Mac OS X')).toBe('simpleperf');
    expect(hostSimpleperfExecutableName('darwin')).toBe('simpleperf');
  });

  it('builds the host conversion vector', () => {
    expect(reportSampleArguments({ perfData: 'in.data', protobufTrace: 'out.pb' })).toEqual([
      'report-sample',
      '--protobuf',
      '--show-callchain',
      '-i',
      'in.data',
      '-o',
      'out.pb',
    ]);
    expect(
      reportSampleArguments({ perfData: 'in.data', protobufTrace: 'out.pb', symbolDirectory: '/sym' }),
    ).toEqual(expect.arrayContaining(['--symdir', '/sym']));
  });

  it('gives the recorder time beyond the requested duration', () => {
    expect(recordTimeoutMs(samplingParameters({ durationSeconds: 30 }))).toBe(90_000);
    expect(recordTimeoutMs(samplingParameters({ durationSeconds: undefined }))).toBe(180_000);
  });
});

function reportBytes(): Uint8Array {
  return stream([
    metaInfoEntry(metaInfoRecord({ eventTypes: ['cpu-cycles'], appPackageName: 'com.example.app' })),
    fileEntry(fileRecord({ id: 0, path: '/system/lib64/libc.so', symbols: ['memcpy'] })),
    threadEntry(threadRecord({ threadId: 42, processId: 7, threadName: 'main' })),
    sampleRecord(
      sample({ time: 10n, threadId: 42, eventCount: 1000n, eventTypeId: 0, callchain: [{ fileId: 0, symbolId: 0 }] }),
    ),
  ]);
}

interface Harness {
  readonly dependencies: SimpleperfCaptureDependencies;
  readonly commands: string[][];
  readonly pulled: string[];
  readonly removed: string[];
  readonly stages: string[];
  fail?: (stage: string) => void;
}

function harness(options: { deviceSimpleperf?: boolean; perfDataSize?: number; convertFails?: boolean; bytes?: Uint8Array } = {}): Harness {
  const commands: string[][] = [];
  const pulled: string[] = [];
  const removed: string[] = [];
  const stages: string[] = [];
  const adb: SimpleperfCaptureAdb = {
    shell: async (args) => {
      commands.push([...args]);
      if (args[1] === '--version') {
        if (options.deviceSimpleperf === false) throw new Error('simpleperf: not found');
        return { stdout: 'simpleperf 35.0.0\n' };
      }
      if (args[0] === 'stat') return { stdout: String(options.perfDataSize ?? 2048) + '\n' };
      return { stdout: '' };
    },
    pull: async (remote) => {
      pulled.push(remote);
    },
  };
  return {
    commands,
    pulled,
    removed,
    stages,
    dependencies: {
      adb,
      convert: async () => (options.convertFails === true ? { ok: false, error: { category: 'PROCESS_EXIT', code: 'SIMPLEPERF_REPORT_FAILED', message: 'boom' } } : { ok: true, value: undefined }),
      temporaryPath: (name) => '/tmp/' + name,
      sizeOf: async () => options.perfDataSize ?? 2048,
      readFile: async () => options.bytes ?? reportBytes(),
      removeFile: async (path) => {
        removed.push(path);
      },
      now: () => 4242,
      newId: () => 'cpu-1',
      onStage: (stage) => stages.push(stage),
    },
  };
}

const REQUEST = {
  serial: 'emulator-5554',
  parameters: samplingParameters({ target: { kind: 'APP', packageName: 'com.example.app' } as const }),
};

describe('probeDeviceSimpleperf', () => {
  it('prefers the device binary on PATH and reports its version', async () => {
    const test = harness();
    const commands: string[][] = [];
    const dependencies: SimpleperfCaptureDependencies = {
      ...test.dependencies,
      adb: {
        ...test.dependencies.adb,
        shell: async (args) => {
          commands.push([...args]);
          return { stdout: args[1] === '--version' ? 'simpleperf 35.0.0\n' : '' };
        },
      },
    };
    const result = await probeDeviceSimpleperf(dependencies, 'SER');
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.path).toBe('simpleperf');
    expect(result.value.version).toBe('simpleperf 35.0.0');
    expect(commands[0]).toEqual(['simpleperf', '--version']);
  });

  it('falls back to the deployed path and then fails with a stable code', async () => {
    const attempts: string[][] = [];
    const dependencies: SimpleperfCaptureDependencies = {
      ...harness().dependencies,
      adb: {
        shell: async (args) => {
          attempts.push([...args]);
          if (args[0] === 'simpleperf') throw new Error('not found');
          if (args[0] === '/data/local/tmp/aps/simpleperf') return { stdout: 'simpleperf 34.0.0' };
          return { stdout: '' };
        },
        pull: async () => undefined,
      },
    };
    const deployed = await probeDeviceSimpleperf(dependencies, 'SER');
    expect(deployed.ok).toBe(true);
    if (deployed.ok) expect(deployed.value.path).toBe('/data/local/tmp/aps/simpleperf');
    expect(attempts).toHaveLength(2);

    const missing = await probeDeviceSimpleperf(harness({ deviceSimpleperf: false }).dependencies, 'SER');
    expect(missing.ok).toBe(false);
    if (!missing.ok) {
      expect(missing.error.code).toBe('DEVICE_SIMPLEPERF_NOT_FOUND');
      expect(missing.error.message).toContain('device SER');
    }
  });
});

describe('captureSimpleperfProfile', () => {
  it('records, pulls, converts, parses, and cleans up', async () => {
    const test = harness();
    const result = await captureSimpleperfProfile(test.dependencies, REQUEST);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.id).toBe('cpu-1');
    expect(result.value.capturedAtEpochMillis).toBe(4242);
    expect(result.value.perfDataBytes).toBe(2048);
    expect(result.value.profile.samples).toHaveLength(1);
    expect(result.value.profile.metadata?.appPackageName).toBe('com.example.app');
    expect(result.value.simpleperfVersion).toBe('simpleperf 35.0.0');

    const recordCommand = test.commands.find((args) => args[1] === 'record');
    expect(recordCommand?.slice(0, 4)).toEqual(['simpleperf', 'record', '-e', 'cpu-clock']);
    expect(test.pulled).toEqual(['/data/local/tmp/aps/perf.data']);
    expect(test.commands).toContainEqual(['rm', '-f', '/data/local/tmp/aps/perf.data']);
    expect(test.removed).toEqual(['/tmp/aps-cpu-1.perf.data']);
    expect(test.stages).toEqual(['PROBE', 'RECORD', 'PULL', 'CONVERT', 'PARSE', 'CLEANUP']);
  });

  it('reports each failing stage and still cleans the device', async () => {
    const recordFails = harness();
    const failing: SimpleperfCaptureDependencies = {
      ...recordFails.dependencies,
      adb: {
        shell: async (args) => {
          recordFails.commands.push([...args]);
          if (args[1] === '--version') return { stdout: 'simpleperf 35.0.0' };
          if (args[0] === 'simpleperf' && args[1] === 'record') throw new Error('permission denied');
          return { stdout: '' };
        },
        pull: async () => undefined,
      },
    };
    const recordResult = await captureSimpleperfProfile(failing, REQUEST);
    expect(recordResult.ok).toBe(false);
    if (!recordResult.ok) expect(recordResult.error.code).toBe('SIMPLEPERF_RECORD_FAILED');
    expect(recordFails.commands).toContainEqual(['rm', '-f', '/data/local/tmp/aps/perf.data']);

    const empty = harness({ perfDataSize: 0 });
    const emptyResult = await captureSimpleperfProfile(empty.dependencies, REQUEST);
    expect(emptyResult.ok).toBe(false);
    if (!emptyResult.ok) expect(emptyResult.error.code).toBe('SIMPLEPERF_PERF_DATA_EMPTY');

    const huge = harness({ perfDataSize: Number.MAX_SAFE_INTEGER });
    const hugeResult = await captureSimpleperfProfile(huge.dependencies, REQUEST);
    expect(hugeResult.ok).toBe(false);
    if (!hugeResult.ok) expect(hugeResult.error.code).toBe('SIMPLEPERF_PERF_DATA_TOO_LARGE');

    const convert = harness({ convertFails: true });
    const convertResult = await captureSimpleperfProfile(convert.dependencies, REQUEST);
    expect(convertResult.ok).toBe(false);
    if (!convertResult.ok) expect(convertResult.error.code).toBe('SIMPLEPERF_REPORT_FAILED');

    const noSamples = harness({
      bytes: stream([metaInfoEntry(metaInfoRecord({ eventTypes: ['cpu-cycles'] }))]),
    });
    const noSamplesResult = await captureSimpleperfProfile(noSamples.dependencies, REQUEST);
    expect(noSamplesResult.ok).toBe(false);
    if (!noSamplesResult.ok) expect(noSamplesResult.error.code).toBe('SIMPLEPERF_NO_SAMPLES');

    const malformed = harness({ bytes: new Uint8Array([1, 2, 3]) });
    const malformedResult = await captureSimpleperfProfile(malformed.dependencies, REQUEST);
    expect(malformedResult.ok).toBe(false);
    if (!malformedResult.ok) expect(malformedResult.error.code).toBe('SIMPLEPERF_MAGIC_INVALID');
  });
});
