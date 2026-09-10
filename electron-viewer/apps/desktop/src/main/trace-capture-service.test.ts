import { describe, expect, it } from 'vitest';
import { capturePerfettoTrace, type CaptureDependencies } from './trace-capture-service.js';

interface Harness {
  readonly dependencies: CaptureDependencies;
  readonly steps: string[];
  readonly pushed: Array<{ local: string; remote: string }>;
  readonly shellCommands: string[][];
  readonly pulled: Array<{ remote: string; local: string }>;
}

function harness(overrides: { pushFails?: boolean; captureFails?: boolean; storeFails?: boolean } = {}): Harness {
  const steps: string[] = [];
  const pushed: Array<{ local: string; remote: string }> = [];
  const shellCommands: string[][] = [];
  const pulled: Array<{ remote: string; local: string }> = [];
  const dependencies: CaptureDependencies = {
    adb: {
      push: async (local, remote) => {
        if (overrides.pushFails === true) throw new Error('push failed');
        steps.push('push');
        pushed.push({ local, remote });
      },
      shell: async (args) => {
        steps.push('shell');
        shellCommands.push([...args]);
        if (overrides.captureFails === true && (args[0] ?? '').startsWith('perfetto')) {
          throw new Error('capture failed');
        }
      },
      pull: async (remote, local) => {
        steps.push('pull');
        pulled.push({ remote, local });
      },
    },
    store: {
      addTrace: async () => {
        if (overrides.storeFails === true) throw new Error('store failed');
        steps.push('store');
        return {
          id: '1-aaaaaaaa',
          path: '/traces/1-aaaaaaaa.pftrace',
          sha256: 'a'.repeat(64),
          capturedAtEpochMillis: 1,
          durationMillis: 5000,
        };
      },
    },
    createTempDirectory: async () => '/tmp/aps-capture',
    writeFile: async () => {
      steps.push('writeConfig');
    },
    removeDirectory: async () => {
      steps.push('cleanupHost');
    },
    sha256File: async () => {
      steps.push('hash');
      return 'a'.repeat(64);
    },
    now: () => 1,
  };
  return { dependencies, steps, pushed, shellCommands, pulled };
}

const REQUEST = {
  serial: 'emulator-5554',
  fileName: 'capture.pftrace',
  document: { durationMillis: 5000, bufferSizeKb: 2048, dataSources: [{ name: 'linux.ftrace' }] },
};

describe('capturePerfettoTrace', () => {
  it('pushes, captures, pulls, and stores in order, then cleans up', async () => {
    const test = harness();
    const result = await capturePerfettoTrace(test.dependencies, REQUEST);
    expect(result.ok).toBe(true);
    expect(test.steps.slice(0, 5)).toEqual(['writeConfig', 'push', 'shell', 'pull', 'hash']);
    expect(test.pushed[0]?.remote).toBe('/data/local/tmp/aps-capture.pftrace.pbtxt');
    expect(test.shellCommands[0]).toEqual([
      'perfetto',
      '-c',
      '/data/local/tmp/aps-capture.pftrace.pbtxt',
      '--txt',
      '-o',
      '/data/misc/perfetto-traces/capture.pftrace',
    ]);
    expect(test.pulled[0]?.remote).toBe('/data/misc/perfetto-traces/capture.pftrace');
    expect(test.steps).toContain('store');
    expect(test.steps).toContain('cleanupHost');
    expect(test.shellCommands.at(-1)).toEqual([
      'rm',
      '-f',
      '/data/local/tmp/aps-capture.pftrace.pbtxt',
      '/data/misc/perfetto-traces/capture.pftrace',
    ]);
  });

  it('reports each failure stage with a stable code', async () => {
    const push = await capturePerfettoTrace(harness({ pushFails: true }).dependencies, REQUEST);
    expect(push.ok).toBe(false);
    if (!push.ok) expect(push.error.code).toBe('CAPTURE_PUSH_FAILED');

    const capture = await capturePerfettoTrace(harness({ captureFails: true }).dependencies, REQUEST);
    expect(capture.ok).toBe(false);
    if (!capture.ok) expect(capture.error.code).toBe('CAPTURE_FAILED');

    const store = await capturePerfettoTrace(harness({ storeFails: true }).dependencies, REQUEST);
    expect(store.ok).toBe(false);
    if (!store.ok) expect(store.error.code).toBe('CAPTURE_STORE_FAILED');
  });
});
