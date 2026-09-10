import { describe, expect, it } from 'vitest';
import { captureFrameSession, type FrameCaptureAdb } from './frame-capture-service.js';

const HEADER =
  'Flags,IntendedVsync,Vsync,OldestInputEvent,NewestInputEvent,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,FrameDeadline,FrameInterval,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted,GpuCompleted,SwapBuffersCompleted,DisplayPresentTime,DequeueBufferDuration,QueueBufferDuration,';

function row(intendedVsync: number, completed: number): string {
  return [0, intendedVsync, intendedVsync, 0, 0, intendedVsync + 1_000_000, intendedVsync + 2_000_000, intendedVsync + 4_000_000, intendedVsync + 10_000_000, intendedVsync + 16_666_666, 16_666_666, intendedVsync + 12_000_000, intendedVsync + 13_000_000, intendedVsync + 14_000_000, intendedVsync + 15_000_000, completed, completed + 500_000, completed, completed + 200_000, 0, 0, ''].join(',');
}

const OUTPUT = [
  'Window: com.example.app/com.example.app.MainActivity',
  '---PROFILEDATA---',
  HEADER,
  row(1_000_000_000, 1_010_000_000),
  row(1_016_666_666, 1_040_000_000),
  '---PROFILEDATA---',
].join('\n');

function harness(overrides: { resetFails?: boolean; dumpFails?: boolean; output?: string } = {}) {
  const commands: string[][] = [];
  const adb: FrameCaptureAdb = {
    shell: async (args) => {
      commands.push([...args]);
      if (overrides.resetFails === true && args.at(-1) === 'reset') throw new Error('reset failed');
      if (overrides.dumpFails === true && args.at(-1) === 'framestats') throw new Error('dump failed');
      return { stdout: overrides.output ?? OUTPUT };
    },
  };
  let session = 0;
  return {
    commands,
    dependencies: { adb, now: () => 1234, newSessionId: () => 'session-' + String((session += 1)) },
  };
}

describe('captureFrameSession', () => {
  it('resets counters, dumps framestats, and builds a session', async () => {
    const test = harness();
    const result = await captureFrameSession(test.dependencies, {
      serial: 'emulator-5554',
      packageName: 'com.example.app',
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.packageName).toBe('com.example.app');
    expect(result.value.frames).toHaveLength(2);
    expect(result.value.capturedAtEpochMillis).toBe(1234);
    expect(test.commands[0]).toEqual(['dumpsys', 'gfxinfo', 'com.example.app', 'reset']);
    expect(test.commands[1]).toEqual(['dumpsys', 'gfxinfo', 'com.example.app', 'framestats']);
    expect(result.value.frames.every((frame) => frame.sessionId === 'session-1')).toBe(true);
  });

  it('skips the reset when asked to', async () => {
    const test = harness();
    await captureFrameSession(test.dependencies, {
      serial: 'SER',
      packageName: 'com.example.app',
      resetDeviceStats: false,
    });
    expect(test.commands).toHaveLength(1);
    expect(test.commands[0]?.at(-1)).toBe('framestats');
  });

  it('reports each failure stage with a stable code', async () => {
    const reset = await captureFrameSession(harness({ resetFails: true }).dependencies, {
      serial: 'SER',
      packageName: 'com.example.app',
    });
    expect(reset.ok).toBe(false);
    if (!reset.ok) expect(reset.error.code).toBe('FRAME_RESET_FAILED');

    const dump = await captureFrameSession(harness({ dumpFails: true }).dependencies, {
      serial: 'SER',
      packageName: 'com.example.app',
    });
    expect(dump.ok).toBe(false);
    if (!dump.ok) expect(dump.error.code).toBe('FRAME_DUMP_FAILED');

    const empty = await captureFrameSession(harness({ output: 'nothing here' }).dependencies, {
      serial: 'SER',
      packageName: 'com.example.app',
    });
    expect(empty.ok).toBe(false);
    if (!empty.ok) expect(empty.error.code).toBe('FRAME_NO_FRAMES');

    const noPackage = await captureFrameSession(harness().dependencies, { serial: 'SER', packageName: '  ' });
    expect(noPackage.ok).toBe(false);
    if (!noPackage.ok) expect(noPackage.error.code).toBe('FRAME_PACKAGE_REQUIRED');
  });
});
