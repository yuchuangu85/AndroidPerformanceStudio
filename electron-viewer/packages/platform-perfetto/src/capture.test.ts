import { describe, expect, it } from 'vitest';
import { buildPerfettoCaptureArgs, captureTimeoutMs, planPerfettoCapture } from './capture.js';

const DOCUMENT = {
  durationMillis: 5000,
  bufferSizeKb: 2048,
  dataSources: [{ name: 'linux.ftrace' }],
};

describe('Perfetto capture plan', () => {
  it('derives device paths and the capture arguments', () => {
    const plan = planPerfettoCapture({ document: DOCUMENT, fileName: 'aps-123.pftrace' });
    expect(plan.deviceConfigPath).toBe('/data/local/tmp/aps-aps-123.pftrace.pbtxt');
    expect(plan.deviceOutputPath).toBe('/data/misc/perfetto-traces/aps-123.pftrace');
    expect(plan.configText).toContain('duration_ms: 5000');
    expect(buildPerfettoCaptureArgs(plan)).toEqual([
      'perfetto',
      '-c',
      plan.deviceConfigPath,
      '--txt',
      '-o',
      plan.deviceOutputPath,
    ]);
  });

  it('honours custom directories and strips trailing slashes', () => {
    const plan = planPerfettoCapture({
      document: DOCUMENT,
      fileName: 'trace.pftrace',
      deviceDirectory: '/sdcard/traces/',
      deviceConfigDirectory: '/data/local/tmp/',
    });
    expect(plan.deviceOutputPath).toBe('/sdcard/traces/trace.pftrace');
    expect(plan.deviceConfigPath).toBe('/data/local/tmp/aps-trace.pftrace.pbtxt');
  });

  it('rejects unsafe file names', () => {
    expect(() => planPerfettoCapture({ document: DOCUMENT, fileName: '../escape' })).toThrow();
    expect(() => planPerfettoCapture({ document: DOCUMENT, fileName: 'a b' })).toThrow();
  });

  it('adds a margin to the capture timeout', () => {
    expect(captureTimeoutMs(5000)).toBe(20_000);
    expect(captureTimeoutMs(5000, 1000)).toBe(6000);
  });
});
