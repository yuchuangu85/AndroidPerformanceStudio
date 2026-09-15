import { describe, expect, it } from 'vitest';
import { TRACE_MAGIC, TraceWriter, concatBytes, streamingTrace } from '@aps/art-trace/testing';
import {
  captureMethodRecording,
  discoverMethodTraceProcesses,
  importMethodTrace,
  parseMethodTraceProcessOptions,
  parseMethodTraceDirect,
  readDeviceSupport,
  type MethodCaptureDependencies,
} from './method-capture-service.js';
import type { MethodTraceAdb } from '@aps/art-trace';

interface Harness {
  readonly dependencies: MethodCaptureDependencies;
  readonly commands: string[][];
  readonly removed: string[];
}

function harness(
  options: {
    sdk?: string;
    root?: boolean;
    dumpsys?: string;
    startFails?: boolean;
    bytes?: Uint8Array;
    processes?: string;
  } = {},
): Harness {
  const commands: string[][] = [];
  const removed: string[] = [];
  const trace = options.bytes ?? streamingTrace();
  const adb: MethodTraceAdb = {
    shell: async (args) => {
      commands.push([...args]);
      if (args[0] === 'getprop') return { stdout: options.sdk ?? '34\n' };
      if (args[0] === 'id') return { stdout: options.root === true ? '0\n' : '2000\n' };
      if (args[0] === 'dumpsys') return { stdout: options.dumpsys ?? dumpsysPackage() };
      if (args[0] === 'ps') return { stdout: options.processes ?? 'PID NAME\n4242 com.example.app' };
      if (args[0] === 'am' && args[2] === 'start' && options.startFails === true) {
        throw new Error('Security exception: not debuggable');
      }
      return { stdout: '' };
    },
    pull: async () => undefined,
  };
  return {
    commands,
    removed,
    dependencies: {
      adb,
      temporaryPath: (name) => '/tmp/' + name,
      sizeOf: async () => trace.length,
      readFile: async () => trace,
      removeFile: async (path) => {
        removed.push(path);
      },
      sleep: async () => undefined,
      now: () => 999,
      newId: () => 'mt-9',
    },
  };
}

function dumpsysPackage(): string {
  return [
    'Packages:',
    '  Package [com.example.app] (1a2b3c):',
    '    userId=10123',
    '    flags=[ DEBUGGABLE HAS_CODE ]',
  ].join('\n');
}

const INPUT = { serial: 'emulator-5554', packageName: 'com.example.app', pid: 4242, durationSeconds: 1 };

describe('readDeviceSupport', () => {
  it('accepts a debuggable app on a modern device', async () => {
    const test = harness();
    const support = await readDeviceSupport(test.dependencies.adb, 'SER', 'com.example.app');
    expect(support.ok).toBe(true);
    if (!support.ok) return;
    expect(support.value.supported).toBe(true);
    expect(support.value.sdkApiLevel).toBe(34);
  });

  it('accepts any app on a rooted device', async () => {
    const test = harness({ root: true, dumpsys: 'Packages:\n  Package [com.example.plain] (1):\n    flags=[ HAS_CODE ]' });
    const support = await readDeviceSupport(test.dependencies.adb, 'SER', 'com.example.plain');
    expect(support.ok).toBe(true);
    if (!support.ok) return;
    expect(support.value.supported).toBe(true);
  });

  it('explains an unsupported device or app', async () => {
    const oldApi = harness({ sdk: '19' });
    const oldApiSupport = await readDeviceSupport(oldApi.dependencies.adb, 'SER', 'com.example.app');
    expect(oldApiSupport.ok).toBe(true);
    if (!oldApiSupport.ok) return;
    expect(oldApiSupport.value.supported).toBe(false);
    expect(oldApiSupport.value.reason).toContain('API 21');

    const plain = harness({ dumpsys: 'Packages:\n  Package [com.example.plain] (1):\n    flags=[ HAS_CODE ]' });
    const plainSupport = await readDeviceSupport(plain.dependencies.adb, 'SER', 'com.example.plain');
    if (!plainSupport.ok) return;
    expect(plainSupport.value.supported).toBe(false);
    expect(plainSupport.value.reason).toContain('not debuggable');
  });
});

describe('method process discovery', () => {
  const packages = [
    'Packages:',
    '  Package [com.example.debug] (1):',
    '    flags=[ DEBUGGABLE HAS_CODE ]',
    '  Package [com.example.profileable] (2):',
    '    privateFlags=[ PROFILEABLE_BY_SHELL ]',
    '  Package [com.example.plain] (3):',
    '    flags=[ HAS_CODE ]',
  ].join('\n');

  const processes = [
    'PID NAME',
    '321 com.example.profileable:worker',
    '123 com.example.debug',
    '456 com.example.plain',
    'not-a-pid com.example.debug',
    '123 com.example.debug',
  ].join('\n');

  it('keeps only debuggable or profileable package processes, preserves remote-process package identity, and sorts them', () => {
    expect(parseMethodTraceProcessOptions(processes, packages)).toEqual([
      { pid: 123, name: 'com.example.debug', packageName: 'com.example.debug' },
      { pid: 321, name: 'com.example.profileable:worker', packageName: 'com.example.profileable' },
    ]);
  });

  it('uses the Kotlin-equivalent static ADB vectors and turns discovery errors into a typed result', async () => {
    const commands: string[][] = [];
    const success = await discoverMethodTraceProcesses({
      shell: async (args) => {
        commands.push([...args]);
        return { stdout: args[0] === 'dumpsys' ? packages : processes };
      },
      pull: async () => undefined,
    });
    expect(success).toEqual({
      ok: true,
      value: [
        { pid: 123, name: 'com.example.debug', packageName: 'com.example.debug' },
        { pid: 321, name: 'com.example.profileable:worker', packageName: 'com.example.profileable' },
      ],
    });
    expect(commands).toEqual([
      ['dumpsys', 'package', 'packages'],
      ['ps', '-A', '-o', 'PID,NAME'],
    ]);

    const failed = await discoverMethodTraceProcesses({
      shell: async () => { throw new Error('device offline'); },
      pull: async () => undefined,
    });
    expect(failed.ok).toBe(false);
    if (!failed.ok) expect(failed.error.code).toBe('METHOD_TRACE_PROCESS_DISCOVERY_FAILED');
  });
});

describe('captureMethodRecording', () => {
  it('gates, records, parses, and cleans up', async () => {
    const test = harness();
    const result = await captureMethodRecording(test.dependencies, INPUT);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.id).toBe('mt-9');
    expect(result.value.capturedAtEpochMillis).toBe(999);
    expect(result.value.packageName).toBe('com.example.app');
    expect(result.value.deviceSdkApiLevel).toBe(34);
    expect(result.value.warnings).toEqual([]);
    expect(result.value.trace.length).toBeGreaterThan(0);
    expect(result.value.analysis.events).toHaveLength(2);
    expect(result.value.analysis.header.version).toBe(5);
    // The parsed table is what the flame graph consumes.
    expect(result.value.table.stacks.length).toBeGreaterThan(0);
    expect(result.value.table.stacks[0]?.threadKey).toBe('main (tid 7)');
    expect(test.commands).toContainEqual(['ps', '-A', '-o', 'PID,NAME']);
    expect(test.commands).toContainEqual(['am', 'profile', 'start', '4242', '/data/local/tmp/aps-mt-9.trace']);
    expect(test.commands).toContainEqual(['rm', '-f', '/data/local/tmp/aps-mt-9.trace']);
    expect(test.removed).toEqual(['/tmp/mt-9.trace']);
  });

  it('does not profile a stale PID after the process picker has changed', async () => {
    const test = harness({ processes: 'PID NAME\n4242 com.example.other' });
    const result = await captureMethodRecording(test.dependencies, INPUT);

    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error.code).toBe('METHOD_TRACE_TARGET_UNAVAILABLE');
    expect(test.commands.some((command) => command[0] === 'am')).toBe(false);
  });

  it('observes a main-process stop signal but still completes normal trace finalization', async () => {
    const test = harness();
    const result = await captureMethodRecording(
      {
        ...test.dependencies,
        shouldStop: () => true,
      },
      { ...INPUT, durationSeconds: 30 },
    );

    expect(result.ok).toBe(true);
    expect(test.commands).toContainEqual(['am', 'profile', 'stop', '4242']);
    expect(test.removed).toEqual(['/tmp/mt-9.trace']);
  });

  it('uses an injected asynchronous parser for the captured trace', async () => {
    const test = harness();
    let parseCalls = 0;
    const result = await captureMethodRecording(
      {
        ...test.dependencies,
        parseTrace: async (trace) => {
          parseCalls += 1;
          return parseMethodTraceDirect(trace);
        },
      },
      INPUT,
    );
    expect(result.ok).toBe(true);
    expect(parseCalls).toBe(1);
  });

  it('refuses to record on an unsupported target', async () => {
    const test = harness({ sdk: '19' });
    const result = await captureMethodRecording(test.dependencies, INPUT);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error.code).toBe('METHOD_TRACE_UNSUPPORTED');
    expect(test.commands.some((args) => args[0] === 'am')).toBe(false);
  });

  it('requires a package name', async () => {
    const test = harness();
    const result = await captureMethodRecording(test.dependencies, { ...INPUT, packageName: '  ' });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error.code).toBe('METHOD_TRACE_PACKAGE_REQUIRED');
    expect(test.commands).toHaveLength(0);
  });

  it('propagates a start failure and still cleans up', async () => {
    const test = harness({ startFails: true });
    const result = await captureMethodRecording(test.dependencies, INPUT);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error.code).toBe('METHOD_TRACE_START_FAILED');
    expect(test.commands).toContainEqual(['rm', '-f', '/data/local/tmp/aps-mt-9.trace']);
  });

  it('rejects a trace with no events', async () => {
    const empty = new Uint8Array([...'SLOW'].map((character) => character.charCodeAt(0)));
    const test = harness({ bytes: empty });
    const result = await captureMethodRecording(test.dependencies, INPUT);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error.code).toBe('ART_TRACE_MALFORMED');
  });
});


function traceWithoutEvents(): Uint8Array {
  const header = new TraceWriter()
    .u32(TRACE_MAGIC)
    .u16(5)
    .u64(1_000_000n)
    .u32(0)
    .u32(0)
    .u32(0)
    .u32(0)
    .u16(0)
    .bytes();
  return concatBytes([header, new TraceWriter().u8(3).u16(0).bytes()]);
}

describe('importMethodTrace', () => {
  const dependencies = { now: () => 1_234, newId: () => 'imported-1' };

  it('parses an offline ART trace without claiming a device or process', () => {
    const source = streamingTrace();
    const result = importMethodTrace(dependencies, { fileName: 'startup.trace', trace: source });

    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value).toMatchObject({
      id: 'imported-1',
      capturedAtEpochMillis: 1_234,
      sourceFileName: 'startup.trace',
    });
    expect(result.value.trace).toEqual(source);
    expect(result.value.trace).not.toBe(source);
    expect(result.value.analysis.events).toHaveLength(2);
    expect(result.value.analysis.methods.size).toBe(2);
    expect(result.value.analysis.threads.size).toBe(1);
    expect(result.value.table.stacks).not.toHaveLength(0);
  });

  it('rejects non-trace, empty, malformed, and eventless imports', () => {
    const invalidExtension = importMethodTrace(dependencies, { fileName: 'profile.txt', trace: streamingTrace() });
    expect(invalidExtension.ok).toBe(false);
    if (!invalidExtension.ok) expect(invalidExtension.error.code).toBe('METHOD_TRACE_IMPORT_EXTENSION');

    const empty = importMethodTrace(dependencies, { fileName: 'empty.trace', trace: new Uint8Array() });
    expect(empty.ok).toBe(false);
    if (!empty.ok) expect(empty.error.code).toBe('METHOD_TRACE_IMPORT_EMPTY');

    const malformed = importMethodTrace(dependencies, { fileName: 'broken.trace', trace: new Uint8Array([1, 2, 3]) });
    expect(malformed.ok).toBe(false);
    if (!malformed.ok) expect(malformed.error.code).toBe('ART_TRACE_MALFORMED');

    const eventless = importMethodTrace(dependencies, { fileName: 'idle.trace', trace: traceWithoutEvents() });
    expect(eventless.ok).toBe(false);
    if (!eventless.ok) expect(eventless.error.code).toBe('METHOD_TRACE_NO_EVENTS');
  });
});
