import { describe, expect, it } from 'vitest';
import { evaluateMethodTraceSupport, parsePackageCapabilities, parseSdkApiLevel } from './device-gate.js';
import { captureMethodTrace, deviceTracePath, type MethodTraceCaptureDependencies } from './capture.js';

describe('device gate parsing', () => {
  it('reads the API level', () => {
    expect(parseSdkApiLevel('34\n')).toBe(34);
    expect(parseSdkApiLevel('')).toBeUndefined();
    expect(parseSdkApiLevel('unknown')).toBeUndefined();
  });

  it('reads debuggable and profileable flags out of dumpsys package', () => {
    const output = [
      'Packages:',
      '  Package [com.example.debug] (1a2b3c):',
      '    userId=10123',
      '    pkg=Package{abc com.example.debug}',
      '    flags=[ DEBUGGABLE HAS_CODE ]',
      '  Package [com.example.profileable] (4d5e6f):',
      '    userId=10124',
      '    privateFlags=[ PRIVATE_FLAG_PROFILEABLE_BY_SHELL ]',
      '  Package [com.example.plain] (7g8h9i):',
      '    userId=10125',
      '    flags=[ HAS_CODE ]',
    ].join('\n');
    const capabilities = parsePackageCapabilities(output);
    expect(capabilities.get('com.example.debug')).toEqual({ debuggable: true, profileableByShell: false });
    expect(capabilities.get('com.example.profileable')).toEqual({ debuggable: false, profileableByShell: true });
    expect(capabilities.get('com.example.plain')).toEqual({ debuggable: false, profileableByShell: false });
    expect(capabilities.size).toBe(3);
    expect(parsePackageCapabilities('')).toEqual(new Map());
  });
});

describe('evaluateMethodTraceSupport', () => {
  const capabilities = new Map([
    ['com.example.debug', { debuggable: true, profileableByShell: false }],
    ['com.example.plain', { debuggable: false, profileableByShell: false }],
  ]);

  it('requires API 21', () => {
    expect(evaluateMethodTraceSupport({ packageName: 'com.example.debug', sdkApiLevel: 20, isRoot: false }).supported).toBe(
      false,
    );
    const unsupported = evaluateMethodTraceSupport({
      packageName: 'com.example.debug',
      sdkApiLevel: 19,
      isRoot: false,
    });
    expect(unsupported.reason).toContain('API 21');
    expect(
      evaluateMethodTraceSupport({
        packageName: 'com.example.debug',
        sdkApiLevel: 21,
        isRoot: false,
        capabilities,
      }).supported,
    ).toBe(true);
  });

  it('accepts a debuggable app, a rooted device, and nothing else', () => {
    expect(
      evaluateMethodTraceSupport({
        packageName: 'com.example.debug',
        sdkApiLevel: 34,
        isRoot: false,
        capabilities,
      }).supported,
    ).toBe(true);
    expect(
      evaluateMethodTraceSupport({ packageName: 'com.example.plain', sdkApiLevel: 34, isRoot: false, capabilities })
        .supported,
    ).toBe(false);
    expect(
      evaluateMethodTraceSupport({ packageName: 'com.example.plain', sdkApiLevel: 34, isRoot: true, capabilities })
        .supported,
    ).toBe(true);
    expect(evaluateMethodTraceSupport({ packageName: 'x', isRoot: false }).reason).toContain('API level');
  });
});

interface Harness {
  readonly dependencies: MethodTraceCaptureDependencies;
  readonly commands: string[][];
  readonly pulled: string[];
  readonly removed: string[];
  readonly sleeps: number[];
}

function harness(
  options: { sdk?: string; startFails?: boolean; stopFails?: boolean; fileAppears?: boolean; pullFails?: boolean; size?: number } = {},
): Harness {
  const commands: string[][] = [];
  const pulled: string[] = [];
  const removed: string[] = [];
  const sleeps: number[] = [];
  let listed = false;
  return {
    commands,
    pulled,
    removed,
    sleeps,
    dependencies: {
      adb: {
        shell: async (args) => {
          commands.push([...args]);
          if (args[0] === 'getprop') return { stdout: options.sdk ?? '34\n' };
          if (args[0] === 'am' && args[1] === 'profile' && args[2] === 'start' && options.startFails === true) {
            throw new Error('Security exception: not debuggable');
          }
          if (args[0] === 'am' && args[1] === 'profile' && args[2] === 'stop' && options.stopFails === true) {
            throw new Error('profile stop failed');
          }
          if (args[0] === 'ls') {
            if (options.fileAppears === false) throw new Error('No such file');
            listed = true;
            return { stdout: '-rw-rw-rw- 1 shell shell 4096 trace' };
          }
          return { stdout: '' };
        },
        pull: async (remote) => {
          if (options.pullFails === true) throw new Error('pull failed');
          pulled.push(remote);
        },
      },
      temporaryPath: (name) => '/tmp/' + name,
      sizeOf: async () => {
        void listed;
        return options.size ?? 4096;
      },
      removeFile: async (path) => {
        removed.push(path);
      },
      sleep: async (milliseconds) => {
        sleeps.push(milliseconds);
      },
      now: () => 555,
      newId: () => 'mt-1',
    },
  };
}

const REQUEST = { serial: 'emulator-5554', packageName: 'com.example.debug', pid: 4242, durationSeconds: 3 };

describe('captureMethodTrace', () => {
  it('starts, waits, stops, verifies, pulls, and cleans up', async () => {
    const test = harness();
    const result = await captureMethodTrace(test.dependencies, REQUEST);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.id).toBe('mt-1');
    expect(result.value.capturedAtEpochMillis).toBe(555);
    expect(result.value.deviceSdkApiLevel).toBe(34);
    expect(result.value.traceFile).toBe('/tmp/mt-1.trace');
    expect(result.value.traceBytes).toBe(4096);
    expect(result.value.warnings).toEqual([]);
    expect(test.commands).toContainEqual([
      'am',
      'profile',
      'start',
      '4242',
      '/data/local/tmp/aps-mt-1.trace',
    ]);
    expect(test.commands).toContainEqual(['am', 'profile', 'stop', '4242']);
    expect(test.pulled).toEqual(['/data/local/tmp/aps-mt-1.trace']);
    expect(test.commands).toContainEqual(['rm', '-f', '/data/local/tmp/aps-mt-1.trace']);
    expect(test.removed).toEqual(['/tmp/mt-1.trace']);
    expect(deviceTracePath('mt-1')).toBe('/data/local/tmp/aps-mt-1.trace');
  });

  it('stops early when the UI asks it to', async () => {
    const test = harness();
    let stopped = false;
    const result = await captureMethodTrace(
      {
        ...test.dependencies,
        shouldStop: () => {
          const value = stopped;
          stopped = true;
          return value;
        },
      },
      REQUEST,
    );
    expect(result.ok).toBe(true);
    // The second poll sees the stop request and returns without sleeping the
    // whole duration.
    expect(test.sleeps.length).toBeLessThan(3);
  });

  it('reports each failing stage with a stable code', async () => {
    const noSdk = harness({ sdk: 'unknown' });
    const noSdkResult = await captureMethodTrace(noSdk.dependencies, REQUEST);
    expect(noSdkResult.ok).toBe(false);
    if (!noSdkResult.ok) expect(noSdkResult.error.code).toBe('METHOD_TRACE_SDK_UNKNOWN');

    const oldApi = harness({ sdk: '19' });
    const oldApiResult = await captureMethodTrace(oldApi.dependencies, REQUEST);
    expect(oldApiResult.ok).toBe(false);
    if (!oldApiResult.ok) expect(oldApiResult.error.code).toBe('METHOD_TRACE_UNSUPPORTED_API');
    expect(oldApi.commands.some((args) => args[0] === 'am')).toBe(false);

    const startFails = harness({ startFails: true });
    const startResult = await captureMethodTrace(startFails.dependencies, REQUEST);
    expect(startResult.ok).toBe(false);
    if (!startResult.ok) {
      expect(startResult.error.code).toBe('METHOD_TRACE_START_FAILED');
      expect(startResult.error.message).toContain('debuggable');
    }
    expect(startFails.commands).toContainEqual(['rm', '-f', '/data/local/tmp/aps-mt-1.trace']);

    const stopFails = harness({ stopFails: true });
    const stopResult = await captureMethodTrace(stopFails.dependencies, REQUEST);
    expect(stopResult.ok).toBe(false);
    if (!stopResult.ok) expect(stopResult.error.code).toBe('METHOD_TRACE_STOP_FAILED');

    const missing = harness({ fileAppears: false });
    const missingResult = await captureMethodTrace(missing.dependencies, REQUEST);
    expect(missingResult.ok).toBe(false);
    if (!missingResult.ok) expect(missingResult.error.code).toBe('METHOD_TRACE_EMPTY');
    // The stop command flushes asynchronously, so the file is polled 20 times.
    expect(missing.commands.filter((args) => args[0] === 'ls')).toHaveLength(20);

    const pullFails = harness({ pullFails: true });
    const pullResult = await captureMethodTrace(pullFails.dependencies, REQUEST);
    expect(pullResult.ok).toBe(false);
    if (!pullResult.ok) expect(pullResult.error.code).toBe('METHOD_TRACE_PULL_FAILED');

    const empty = harness({ size: 0 });
    const emptyResult = await captureMethodTrace(empty.dependencies, REQUEST);
    expect(emptyResult.ok).toBe(false);
    if (!emptyResult.ok) expect(emptyResult.error.code).toBe('METHOD_TRACE_EMPTY');
  });

  it('rejects an invalid pid or duration before touching the device', async () => {
    const test = harness();
    const badPid = await captureMethodTrace(test.dependencies, { ...REQUEST, pid: 0 });
    expect(badPid.ok).toBe(false);
    if (!badPid.ok) expect(badPid.error.code).toBe('METHOD_TRACE_PID_INVALID');
    const badDuration = await captureMethodTrace(test.dependencies, { ...REQUEST, durationSeconds: 0 });
    expect(badDuration.ok).toBe(false);
    if (!badDuration.ok) expect(badDuration.error.code).toBe('METHOD_TRACE_DURATION_INVALID');
    expect(test.commands).toHaveLength(0);
  });
});
