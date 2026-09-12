import { fail, ok, type StudioResult } from '@aps/contracts';
import {
  CURRENT_PROTOCOL_VERSION,
  containsComposeHost,
  parseForegroundPackage,
  parseUiAutomatorHierarchy,
  parseVisibleWindowViewsArchive,
  selectDefaultWindow,
  type LayoutSnapshot,
  type UiNode,
  type WindowSnapshot,
} from '@aps/layout-inspector';
import type { DeviceDisplay } from './display-parsers.js';

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
const MAX_HIERARCHY_BYTES = 64 * 1024 * 1024;
/** The device refuses the dump while a window is animating; retry, then fall back. */
const VISIBLE_WINDOW_VIEW_ATTEMPTS = 3;
const VISIBLE_WINDOW_VIEW_RETRY_MS = 75;

const VISIBLE_WINDOW_VIEWS_COMMAND = ['cmd', 'window', 'dump-visible-window-views'] as const;
const FOREGROUND_ACTIVITY_COMMAND = ['dumpsys', 'activity', 'activities'] as const;

interface CapturedHierarchy {
  readonly packageName: string;
  readonly windows: readonly WindowSnapshot[];
  readonly defaultWindowId: string;
  readonly root: UiNode;
  readonly composeSemantics: boolean;
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

/**
 * Captures a layout snapshot the way the reference application does: the Visible
 * Window Views dump describes the real View tree of every visible window, and
 * uiautomator is only the fallback for a device that refuses that dump.
 *
 * The accessibility tree uiautomator returns is a different, pruned tree with a
 * dozen attributes; the View tree carries the layout, drawing, scrolling and
 * layout-params attributes the details pane renders.
 */
export async function captureLayoutSnapshot(
  dependencies: LayoutCaptureDependencies,
  serial: string,
  packageName?: string,
): Promise<StudioResult<LayoutCaptureResult>> {
  const { adb } = dependencies;
  const foreground = packageName ?? (await readForegroundPackage(adb));

  let captured =
    foreground === undefined ? undefined : await captureVisibleWindowViews(adb, foreground);
  if (captured === undefined) {
    const fallback = await captureUiAutomatorHierarchy(adb, serial, foreground);
    if (!fallback.ok) return fallback;
    captured = fallback.value;
  }

  const screenshotPng = await captureScreenshot(adb);
  // The screenshot is the source of truth for canvas geometry: bounds are drawn
  // as a percentage of these pixels, so they have to match the image the pane shows.
  const display = readPngDimensions(screenshotPng) ?? inferDisplay(captured.windows);

  const snapshot: LayoutSnapshot = {
    protocolVersion: CURRENT_PROTOCOL_VERSION,
    packageName: captured.packageName,
    capturedAtEpochMillis: dependencies.now(),
    display,
    capabilities: {
      viewHierarchy: true,
      composeSemantics: captured.composeSemantics,
      screenshots: screenshotPng.length > 0,
      timeline: false,
    },
    root: captured.root,
    windows: captured.windows,
    defaultWindowId: captured.defaultWindowId,
  };
  return ok({ snapshot, screenshotPng, display });
}

async function readForegroundPackage(adb: LayoutCaptureAdb): Promise<string | undefined> {
  try {
    const output = await adb.shell([...FOREGROUND_ACTIVITY_COMMAND], { timeoutMs: SHELL_TIMEOUT_MS });
    return parseForegroundPackage(output.stdout);
  } catch {
    return undefined;
  }
}

async function captureVisibleWindowViews(
  adb: LayoutCaptureAdb,
  packageName: string,
): Promise<CapturedHierarchy | undefined> {
  for (let attempt = 0; attempt < VISIBLE_WINDOW_VIEW_ATTEMPTS; attempt += 1) {
    if (attempt > 0) await delay(VISIBLE_WINDOW_VIEW_RETRY_MS);
    try {
      const bytes = await adb.execOut([...VISIBLE_WINDOW_VIEWS_COMMAND], {
        timeoutMs: SHELL_TIMEOUT_MS,
        maxOutputBytesPerStream: MAX_HIERARCHY_BYTES,
      });
      const windows = await parseVisibleWindowViewsArchive(bytes, packageName);
      const preferred = selectDefaultWindow(windows);
      return {
        packageName,
        windows,
        defaultWindowId: preferred.id,
        root: preferred.root,
        composeSemantics: windows.some(
          (window) => window.root.type === 'view' && containsComposeHost(window.root),
        ),
      };
    } catch {
      // Try again, then let the caller fall back to uiautomator.
    }
  }
  return undefined;
}

/** The fallback path: uiautomator's accessibility dump, one synthetic window. */
async function captureUiAutomatorHierarchy(
  adb: LayoutCaptureAdb,
  serial: string,
  packageName: string | undefined,
): Promise<StudioResult<CapturedHierarchy>> {
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

  let root: UiNode;
  try {
    root = parseUiAutomatorHierarchy(xml).root;
  } catch (error) {
    return fail('DATA_VALIDATION', 'LAYOUT_DUMP_MALFORMED', describe(error, serial));
  }

  const resolvedPackage = packageName ?? packageOf(root) ?? 'unknown';
  const window: WindowSnapshot = {
    id: 'window:uiautomator',
    title: resolvedPackage.split('.').pop() ?? resolvedPackage,
    type: 'ACTIVITY',
    bounds: root.bounds,
    root,
  };
  return ok({
    packageName: resolvedPackage,
    windows: [window],
    defaultWindowId: window.id,
    root,
    composeSemantics: containsComposeHostOnAnyNode(root),
  });
}

function containsComposeHostOnAnyNode(node: UiNode): boolean {
  if (node.className.endsWith('.ComposeView') || node.className.endsWith('.AndroidComposeView')) return true;
  return node.children.some(containsComposeHostOnAnyNode);
}

/** Pixels are optional: a capture without a screenshot is still a usable tree. */
async function captureScreenshot(adb: LayoutCaptureAdb): Promise<Buffer> {
  try {
    return await adb.execOut(['screencap', '-p'], {
      timeoutMs: SCREENSHOT_TIMEOUT_MS,
      maxOutputBytesPerStream: MAX_SCREENSHOT_BYTES,
    });
  } catch {
    return Buffer.alloc(0);
  }
}

/** Reads the IHDR size, which is what the canvas maps percentages onto. */
export function readPngDimensions(png: Buffer): DeviceDisplay | undefined {
  if (png.length < 24) return undefined;
  const signature = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  for (let index = 0; index < signature.length; index += 1) {
    if (png[index] !== signature[index]) return undefined;
  }
  if (png.toString('ascii', 12, 16) !== 'IHDR') return undefined;
  const widthPx = png.readUInt32BE(16);
  const heightPx = png.readUInt32BE(20);
  if (widthPx === 0 || heightPx === 0) return undefined;
  return { widthPx, heightPx, density: 1 };
}

function inferDisplay(windows: readonly WindowSnapshot[]): DeviceDisplay {
  let widthPx = 1;
  let heightPx = 1;
  for (const window of windows) {
    widthPx = Math.max(widthPx, window.bounds.right, window.root.bounds.right);
    heightPx = Math.max(heightPx, window.bounds.bottom, window.root.bounds.bottom);
  }
  return { widthPx, heightPx, density: 1 };
}

function packageOf(root: UiNode): string | undefined {
  let found: string | undefined;
  const visit = (node: UiNode): void => {
    if (found !== undefined) return;
    if (node.type === 'view') {
      const value = node.attributes.rawProperties['package'];
      if (value !== undefined && value.length > 0) {
        found = value;
        return;
      }
    }
    for (const child of node.children) visit(child);
  };
  visit(root);
  return found;
}
