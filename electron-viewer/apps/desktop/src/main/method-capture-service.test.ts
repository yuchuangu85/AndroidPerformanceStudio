import { describe, expect, it } from 'vitest';
import { streamingTrace } from '@aps/art-trace/testing';
import {
  captureMethodRecording,
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
    expect(test.commands).toContainEqual(['am', 'profile', 'start', '4242', '/data/local/tmp/aps-mt-9.trace']);
    expect(test.commands).toContainEqual(['rm', '-f', '/data/local/tmp/aps-mt-9.trace']);
    expect(test.removed).toEqual(['/tmp/mt-9.trace']);
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
