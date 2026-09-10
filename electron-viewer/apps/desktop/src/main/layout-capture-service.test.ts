import { describe, expect, it } from 'vitest';
import { captureLayoutSnapshot, DEVICE_DUMP_PATH, type LayoutCaptureAdb } from './layout-capture-service.js';

const DUMP = [
  '<hierarchy rotation="0">',
  '  <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.example.app" bounds="[0,0][1080,1920]">',
  '    <node index="0" text="Hi" resource-id="com.example.app:id/title" class="android.widget.TextView" package="com.example.app" bounds="[0,0][200,100]" />',
  '  </node>',
  '</hierarchy>',
].join('');

interface Harness {
  readonly adb: LayoutCaptureAdb;
  readonly shellCommands: string[][];
  readonly execOutCommands: string[][];
}

function harness(overrides: { dumpFails?: boolean; screenshotFails?: boolean } = {}): Harness {
  const shellCommands: string[][] = [];
  const execOutCommands: string[][] = [];
  const adb: LayoutCaptureAdb = {
    shell: async (args) => {
      shellCommands.push([...args]);
      if (overrides.dumpFails === true && args[0] === 'uiautomator') throw new Error('uiautomator dump failed');
      if (args[0] === 'wm' && args[1] === 'size') return { stdout: 'Physical size: 1080x1920' };
      if (args[0] === 'wm' && args[1] === 'density') return { stdout: 'Physical density: 440' };
      return { stdout: '' };
    },
    execOut: async (args) => {
      execOutCommands.push([...args]);
      if (args[0] === 'cat') return Buffer.from(DUMP, 'utf8');
      if (overrides.screenshotFails === true) throw new Error('screencap failed');
      return Buffer.from([0x89, 0x50, 0x4e, 0x47]);
    },
  };
  return { adb, shellCommands, execOutCommands };
}

describe('captureLayoutSnapshot', () => {
  it('dumps, reads, screenshots, and builds a protocol snapshot', async () => {
    const test = harness();
    const result = await captureLayoutSnapshot({ adb: test.adb, now: () => 1234 }, 'emulator-5554');
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.snapshot.packageName).toBe('com.example.app');
    expect(result.value.snapshot.protocolVersion).toEqual({ major: 1, minor: 1 });
    expect(result.value.snapshot.display).toEqual({ widthPx: 1080, heightPx: 1920, density: 2.75 });
    expect(result.value.snapshot.capabilities.viewHierarchy).toBe(true);
    expect(result.value.snapshot.capturedAtEpochMillis).toBe(1234);
    expect(result.value.screenshotPng.length).toBe(4);

    expect(test.shellCommands[0]).toEqual(['uiautomator', 'dump', DEVICE_DUMP_PATH]);
    expect(test.execOutCommands[0]).toEqual(['cat', DEVICE_DUMP_PATH]);
    expect(test.execOutCommands[1]).toEqual(['screencap', '-p']);
    expect(test.shellCommands).toContainEqual(['rm', '-f', DEVICE_DUMP_PATH]);
  });

  it('reports dump and screenshot failures with stable codes', async () => {
    const dump = await captureLayoutSnapshot({ adb: harness({ dumpFails: true }).adb, now: () => 1 }, 'SER');
    expect(dump.ok).toBe(false);
    if (!dump.ok) expect(dump.error.code).toBe('LAYOUT_DUMP_FAILED');

    const screenshot = await captureLayoutSnapshot({ adb: harness({ screenshotFails: true }).adb, now: () => 1 }, 'SER');
    expect(screenshot.ok).toBe(false);
    if (!screenshot.ok) expect(screenshot.error.code).toBe('LAYOUT_SCREENSHOT_FAILED');
  });
});
