import { fail, ok, type StudioResult } from '@aps/contracts';
import {
  CURRENT_PROTOCOL_VERSION,
  parseUiAutomatorHierarchy,
  walkNode,
  type LayoutSnapshot,
} from '@aps/layout-inspector';
import { parseDeviceDisplay, type DeviceDisplay } from './display-parsers.js';

function describe(error: unknown, serial: string): string {
  const message = error instanceof Error ? error.message : 'layout capture failed';
  return message + ' (device ' + serial + ')';
}

export const DEVICE_DUMP_PATH = '/sdcard/aps-window-dump.xml';

export interface LayoutCaptureAdb {
  shell(args: readonly string[], options: { readonly timeoutMs: number }): Promise<{ stdout: string }>;
  execOut(
    args: readonly string[],
    options: { readonly timeoutMs: number; readonly maxOutputBytesPerStream?: number },
  ): Promise<Buffer>;
}

export interface LayoutCaptureDependencies {
  readonly adb: LayoutCaptureAdb;
  readonly now: () => number;
}

export interface LayoutCaptureResult {
  readonly snapshot: LayoutSnapshot;
  readonly screenshotPng: Buffer;
  readonly display: DeviceDisplay;
}

const SHELL_TIMEOUT_MS = 30_000;
const SCREENSHOT_TIMEOUT_MS = 60_000;
const MAX_SCREENSHOT_BYTES = 32 * 1024 * 1024;

/**
 * Captures a layout snapshot through the ADB fallback path: uiautomator dump for
 * the hierarchy and screencap for pixels. No device-side agent is required.
 */
export async function captureLayoutSnapshot(
  dependencies: LayoutCaptureDependencies,
  serial: string,
  packageName?: string,
): Promise<StudioResult<LayoutCaptureResult>> {
  const { adb } = dependencies;
  try {
    await adb.shell(['uiautomator', 'dump', DEVICE_DUMP_PATH], { timeoutMs: SHELL_TIMEOUT_MS });
  } catch (error) {
    return fail('PROCESS_EXIT', 'LAYOUT_DUMP_FAILED', describe(error, serial));
  }

  let xml: string;
  try {
    const bytes = await adb.execOut(['cat', DEVICE_DUMP_PATH], { timeoutMs: SHELL_TIMEOUT_MS });
    xml = bytes.toString('utf8');
  } catch (error) {
    return fail('PROCESS_EXIT', 'LAYOUT_DUMP_READ_FAILED', describe(error, serial));
  } finally {
    await adb.shell(['rm', '-f', DEVICE_DUMP_PATH], { timeoutMs: SHELL_TIMEOUT_MS }).catch(() => undefined);
  }

  let hierarchy;
  try {
    hierarchy = parseUiAutomatorHierarchy(xml);
  } catch (error) {
    return fail('DATA_VALIDATION', 'LAYOUT_DUMP_MALFORMED', describe(error, serial));
  }
  const root = hierarchy.root;
  const rotation = hierarchy.rotation;

  const display = await readDisplay(adb);
  let screenshotPng: Buffer;
  try {
    screenshotPng = await adb.execOut(['screencap', '-p'], {
      timeoutMs: SCREENSHOT_TIMEOUT_MS,
      maxOutputBytesPerStream: MAX_SCREENSHOT_BYTES,
    });
  } catch (error) {
    return fail('PROCESS_EXIT', 'LAYOUT_SCREENSHOT_FAILED', describe(error, serial));
  }

  const resolvedPackage = packageName ?? packageOf(root) ?? 'unknown';
  // Rotation is recorded on the root's raw properties for later correlation.
  const rootWithRotation: LayoutSnapshot['root'] =
    rotation !== 0 && root.type === 'view'
      ? {
          ...root,
          attributes: {
            ...root.attributes,
            rawProperties: { ...root.attributes.rawProperties, rotation: String(rotation) },
          },
        }
      : root;
  const snapshot: LayoutSnapshot = {
    protocolVersion: CURRENT_PROTOCOL_VERSION,
    packageName: resolvedPackage,
    capturedAtEpochMillis: dependencies.now(),
    display,
    capabilities: { viewHierarchy: true, composeSemantics: false, screenshots: true, timeline: false },
    root: rootWithRotation,
    windows: [],
  };
  return ok({ snapshot, screenshotPng, display });
}

async function readDisplay(adb: LayoutCaptureAdb): Promise<DeviceDisplay> {
  const size = await adb.shell(['wm', 'size'], { timeoutMs: SHELL_TIMEOUT_MS }).catch(() => ({ stdout: '' }));
  const density = await adb.shell(['wm', 'density'], { timeoutMs: SHELL_TIMEOUT_MS }).catch(() => ({ stdout: '' }));
  return parseDeviceDisplay(size.stdout, density.stdout) ?? { widthPx: 0, heightPx: 0, density: 1 };
}

function packageOf(root: LayoutSnapshot['root']): string | undefined {
  let found: string | undefined;
  walkNode(root, (node) => {
    if (found !== undefined) return;
    if (node.type !== 'view') return;
    const value = node.attributes.rawProperties['package'];
    if (value !== undefined && value.length > 0) found = value;
  });
  return found;
}


