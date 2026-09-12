import { describe, expect, it } from 'vitest';
import {
  DEMO_PACKAGE,
  demoDeviceWindowEntries,
  zipArchive,
} from '@aps/layout-inspector';
import {
  captureLayoutSnapshot,
  DEVICE_DUMP_PATH,
  readPngDimensions,
  type LayoutCaptureAdb,
} from './layout-capture-service.js';

const DUMP = [
  '<hierarchy rotation="0">',
  '  <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.example.app" bounds="[0,0][1080,1920]">',
  '    <node index="0" text="Hi" resource-id="com.example.app:id/title" class="android.widget.TextView" package="com.example.app" bounds="[0,0][200,100]" />',
  '  </node>',
  '</hierarchy>',
].join('');

const FOREGROUND = 'topResumedActivity=ActivityRecord{abc u0 ' + DEMO_PACKAGE + '/.MainActivity t1}';

/** A PNG header with real IHDR dimensions; the pane maps percentages onto these. */
function pngHeader(widthPx: number, heightPx: number): Buffer {
  const png = Buffer.alloc(24);
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).copy(png, 0);
  png.writeUInt32BE(13, 8);
  png.write('IHDR', 12, 'ascii');
  png.writeUInt32BE(widthPx, 16);
  png.writeUInt32BE(heightPx, 20);
  return png;
}

interface Harness {
  readonly adb: LayoutCaptureAdb;
  readonly shellCommands: string[][];
  readonly execOutCommands: string[][];
}

function harness(
  overrides: {
    dumpFails?: boolean;
    malformedDump?: boolean;
    screenshotFails?: boolean;
    nativeBytes?: Buffer;
    foreground?: string;
    nativeFails?: boolean;
  } = {},
): Harness {
  const shellCommands: string[][] = [];
  const execOutCommands: string[][] = [];
  const adb: LayoutCaptureAdb = {
    shell: async (args) => {
      shellCommands.push([...args]);
      if (args[0] === 'dumpsys') return { stdout: overrides.foreground ?? FOREGROUND };
      if (overrides.dumpFails === true && args[0] === 'uiautomator') throw new Error('uiautomator dump failed');
      return { stdout: '' };
    },
    execOut: async (args) => {
      execOutCommands.push([...args]);
      if (args[0] === 'cmd') {
        if (overrides.nativeFails === true) throw new Error('visible window views unavailable');
        return overrides.nativeBytes ?? Buffer.from(zipArchive(demoDeviceWindowEntries()));
      }
      if (args[0] === 'cat') return Buffer.from(overrides.malformedDump === true ? '<hierarchy>' : DUMP, 'utf8');
      if (overrides.screenshotFails === true) throw new Error('screencap failed');
      return pngHeader(1080, 2400);
    },
  };
  return { adb, shellCommands, execOutCommands };
}

describe('captureLayoutSnapshot', () => {
  it('captures the Visible Window Views tree the reference reads', async () => {
    const test = harness();
    const result = await captureLayoutSnapshot({ adb: test.adb, now: () => 1234 }, 'emulator-5554');
    expect(result.ok).toBe(true);
    if (!result.ok) return;

    const snapshot = result.value.snapshot;
    expect(snapshot.packageName).toBe(DEMO_PACKAGE);
    expect(snapshot.protocolVersion).toEqual({ major: 1, minor: 1 });
    expect(snapshot.capturedAtEpochMillis).toBe(1234);
    expect(snapshot.capabilities).toEqual({
      viewHierarchy: true,
      composeSemantics: false,
      screenshots: true,
      timeline: false,
    });
    // The window token names the window, and its nodes are namespaced under it.
    expect(snapshot.windows).toHaveLength(1);
    expect(snapshot.windows[0]?.id).toBe('window:42177c9');
    expect(snapshot.windows[0]?.title).toBe('MainActivity');
    expect(snapshot.windows[0]?.type).toBe('ACTIVITY');
    expect(snapshot.defaultWindowId).toBe('window:42177c9');
    expect(snapshot.root.id).toBe('window:42177c9/root');
    // The canvas maps percentages onto the screenshot, so display comes from it.
    expect(snapshot.display).toEqual({ widthPx: 1080, heightPx: 2400, density: 1 });
    expect(result.value.display).toEqual(snapshot.display);

    expect(test.shellCommands[0]?.[0]).toBe('dumpsys');
    expect(test.execOutCommands[0]).toEqual(['cmd', 'window', 'dump-visible-window-views']);
    expect(test.execOutCommands[1]).toEqual(['screencap', '-p']);
    expect(test.shellCommands.some((command) => command[0] === 'uiautomator')).toBe(false);
  });

  it('falls back to uiautomator when the device refuses the View dump', async () => {
    const test = harness({ nativeFails: true });
    const result = await captureLayoutSnapshot({ adb: test.adb, now: () => 1 }, 'SER');
    expect(result.ok).toBe(true);
    if (!result.ok) return;

    const snapshot = result.value.snapshot;
    expect(snapshot.packageName).toBe(DEMO_PACKAGE);
    expect(test.execOutCommands.filter((command) => command[0] === 'cmd')).toHaveLength(3);
    expect(test.shellCommands).toContainEqual(['uiautomator', 'dump', DEVICE_DUMP_PATH]);
    expect(test.execOutCommands).toContainEqual(['cat', DEVICE_DUMP_PATH]);
    expect(test.shellCommands).toContainEqual(['rm', '-f', DEVICE_DUMP_PATH]);
    // The fallback keeps the same id scheme as the reference parser.
    expect(snapshot.windows[0]?.id).toBe('window:uiautomator');
    expect(snapshot.root.id).toBe('root');
    // The fallback titles the synthetic window after the package, as the reference does.
    expect(snapshot.windows[0]?.title).toBe('anrdemo');
    expect(snapshot.defaultWindowId).toBe('window:uiautomator');
  });

  it('skips the View dump when the device has no foreground application', async () => {
    const test = harness({ foreground: 'No activities in the system' });
    const result = await captureLayoutSnapshot({ adb: test.adb, now: () => 1 }, 'SER');
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(test.execOutCommands.some((command) => command[0] === 'cmd')).toBe(false);
    expect(test.shellCommands).toContainEqual(['uiautomator', 'dump', DEVICE_DUMP_PATH]);
    // With no foreground app to trust, the package comes from the dump itself.
    expect(result.value.snapshot.packageName).toBe('com.example.app');
  });

  it('uses an explicit package name without asking the device', async () => {
    const test = harness();
    const result = await captureLayoutSnapshot({ adb: test.adb, now: () => 1 }, 'SER', 'com.other.app');
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.snapshot.packageName).toBe('com.other.app');
    expect(test.shellCommands.some((command) => command[0] === 'dumpsys')).toBe(false);
  });

  it('keeps the capture when the screenshot fails, and reports it as absent', async () => {
    const test = harness({ screenshotFails: true });
    const result = await captureLayoutSnapshot({ adb: test.adb, now: () => 1 }, 'SER');
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.screenshotPng.length).toBe(0);
    expect(result.value.snapshot.capabilities.screenshots).toBe(false);
    // Without pixels, the display falls back to the widest window bounds.
    expect(result.value.snapshot.display).toEqual({ widthPx: 1090, heightPx: 2420, density: 1 });
  });

  it('reports dump and dump-read failures with stable codes', async () => {
    const dump = await captureLayoutSnapshot(
      { adb: harness({ nativeFails: true, dumpFails: true }).adb, now: () => 1 },
      'SER',
    );
    expect(dump.ok).toBe(false);
    if (!dump.ok) expect(dump.error.code).toBe('LAYOUT_DUMP_FAILED');

    const malformed = await captureLayoutSnapshot(
      { adb: harness({ nativeFails: true, malformedDump: true }).adb, now: () => 1 },
      'SER',
    );
    expect(malformed.ok).toBe(false);
    if (!malformed.ok) expect(malformed.error.code).toBe('LAYOUT_DUMP_MALFORMED');
  });
});

describe('readPngDimensions', () => {
  it('reads the IHDR size and rejects anything that is not a PNG', () => {
    expect(readPngDimensions(pngHeader(1080, 2400))).toEqual({ widthPx: 1080, heightPx: 2400, density: 1 });
    expect(readPngDimensions(Buffer.alloc(0))).toBeUndefined();
    expect(readPngDimensions(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]))).toBeUndefined();
  });
});
