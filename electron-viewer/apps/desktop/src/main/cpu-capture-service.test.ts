import { describe, expect, it } from 'vitest';
import { captureCpuProfile, samplingParametersFor, type CpuCaptureDependencies } from './cpu-capture-service.js';
// The report builder is a test-only entry point: production code only reads
// these streams.
import {
  fileEntry,
  fileRecord,
  metaInfoEntry,
  metaInfoRecord,
  sample,
  sampleRecord,
  stream,
  threadEntry,
  threadRecord,
} from '@aps/simpleperf-profiler/testing';

function reportBytes(): Uint8Array {
  return stream([
    metaInfoEntry(metaInfoRecord({ eventTypes: ['cpu-clock'], appPackageName: 'com.example.app' })),
    fileEntry(fileRecord({ id: 0, path: '/system/lib64/libc.so', symbols: ['memcpy'] })),
    threadEntry(threadRecord({ threadId: 42, processId: 7, threadName: 'main' })),
    sampleRecord(
      sample({ time: 1n, threadId: 42, eventCount: 100n, eventTypeId: 0, callchain: [{ fileId: 0, symbolId: 0 }] }),
    ),
  ]);
}

interface Harness {
  readonly dependencies: CpuCaptureDependencies;
  readonly commands: string[][];
  readonly conversions: string[][];
  readonly removed: string[];
}

function harness(options: { hostMissing?: boolean; convertFails?: boolean } = {}): Harness {
  const commands: string[][] = [];
  const conversions: string[][] = [];
  const removed: string[] = [];
  const bytes = reportBytes();
  return {
    commands,
    conversions,
    removed,
    dependencies: {
      adb: {
        shell: async (args) => {
          commands.push([...args]);
          if (args[1] === '--version') return { stdout: 'simpleperf 35.0.0' };
          if (args[0] === 'stat') return { stdout: '8192' };
          return { stdout: '' };
        },
        pull: async () => undefined,
      },
      locateHostSimpleperf: async () =>
        options.hostMissing === true
          ? { ok: false, error: { category: 'CONFIGURATION', code: 'HOST_SIMPLEPERF_NOT_FOUND', message: 'not found' } }
          : { ok: true, value: { executable: '/bundle/simpleperf' } },
      convert: async (input) => {
        conversions.push([input.executable, input.perfData, input.protobufTrace]);
        return options.convertFails === true
          ? { ok: false, error: { category: 'PROCESS_EXIT', code: 'SIMPLEPERF_REPORT_FAILED', message: 'boom' } }
          : { ok: true, value: undefined };
      },
      temporaryPath: (name) => '/tmp/' + name,
      sizeOf: async () => bytes.length,
      readFile: async () => bytes,
      removeFile: async (path) => {
        removed.push(path);
      },
      now: () => 777,
      newId: () => 'cpu-9',
    },
  };
}

const INPUT = {
  serial: 'emulator-5554',
  packageName: 'com.example.app',
  target: 'APP' as const,
  event: 'cpu-clock',
  frequencyHertz: 999,
  durationSeconds: 5,
  callGraph: 'DWARF' as const,
  scope: 'BOTH' as const,
};

describe('samplingParametersFor', () => {
  it('maps the captured form onto simpleperf parameters', () => {
    const parameters = samplingParametersFor(INPUT);
    expect(parameters.target).toEqual({ kind: 'APP', packageName: 'com.example.app' });
    expect(parameters.rate).toEqual({ kind: 'FREQUENCY', hertz: 999 });
    expect(parameters.durationSeconds).toBe(5);
    expect(parameters.callGraph).toBe('DWARF');
    expect(parameters.outputPath).toBe('/data/local/tmp/aps/perf.data');
  });

  it('falls back to system wide when no package is given', () => {
    expect(samplingParametersFor({ ...INPUT, target: 'SYSTEM_WIDE' }).target).toEqual({ kind: 'SYSTEM_WIDE' });
    expect(samplingParametersFor({ ...INPUT, packageName: '   ' }).target).toEqual({ kind: 'SYSTEM_WIDE' });
    expect(samplingParametersFor({ ...INPUT, packageName: undefined }).target).toEqual({ kind: 'SYSTEM_WIDE' });
  });

  it('rejects parameters simpleperf cannot accept', () => {
    expect(() => samplingParametersFor({ ...INPUT, frequencyHertz: 0 })).toThrow(/frequency must be positive/);
    expect(() => samplingParametersFor({ ...INPUT, durationSeconds: -1 })).toThrow(/duration must be positive/);
    expect(() => samplingParametersFor({ ...INPUT, event: 'two words' })).toThrow(/event must be a non-blank/);
    expect(() => samplingParametersFor({ ...INPUT, packageName: 'com.example app' })).toThrow(/packageName/);
  });
});

describe('captureCpuProfile', () => {
  it('runs the whole pipeline and reads the report back before cleanup', async () => {
    const test = harness();
    const result = await captureCpuProfile(test.dependencies, INPUT);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.id).toBe('cpu-9');
    expect(result.value.capturedAtEpochMillis).toBe(777);
    expect(result.value.perfDataBytes).toBe(8192);
    expect(result.value.simpleperfVersion).toBe('simpleperf 35.0.0');
    expect(result.value.profile.samples).toHaveLength(1);
    expect(result.value.parameters.target).toEqual({ kind: 'APP', packageName: 'com.example.app' });
    expect(result.value.report.length).toBeGreaterThan(0);
    expect(test.conversions).toEqual([['/bundle/simpleperf', '/tmp/aps-cpu-9.perf.data', '/tmp/aps-cpu-9.pb']]);
    expect(test.commands.some((args) => args[0] === 'stat')).toBe(true);
    expect(test.removed).toContain('/tmp/aps-cpu-9.perf.data');
  });

  it('fails before touching the device when the host tool is missing', async () => {
    const test = harness({ hostMissing: true });
    const result = await captureCpuProfile(test.dependencies, INPUT);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error.code).toBe('HOST_SIMPLEPERF_NOT_FOUND');
    expect(test.commands).toEqual([]);
  });

  it('propagates a conversion failure and still cleans up', async () => {
    const test = harness({ convertFails: true });
    const result = await captureCpuProfile(test.dependencies, INPUT);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error.code).toBe('SIMPLEPERF_REPORT_FAILED');
    expect(test.removed).toContain('/tmp/aps-cpu-9.perf.data');
    expect(test.commands).toContainEqual(['rm', '-f', '/data/local/tmp/aps/perf.data']);
  });

  it('reports invalid parameters without spawning anything', async () => {
    const test = harness();
    const result = await captureCpuProfile(test.dependencies, { ...INPUT, frequencyHertz: 0 });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error.code).toBe('CPU_SAMPLING_PARAMETERS_INVALID');
    expect(test.commands).toEqual([]);
  });
});
