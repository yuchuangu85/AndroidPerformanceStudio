import { deflateRawSync } from 'node:zlib';
import { describe, expect, it } from 'vitest';
import {
  DEMO_PACKAGE,
  demoHierarchyPayload,
  demoTwoWindowEntries,
  demoWindowEntries,
  zipArchive,
  type ArchiveEntry,
} from './fixtures/encoded-hierarchy.js';
import {
  parseVisibleWindowViews,
  parseVisibleWindowViewsArchive,
  readZipEntries,
  selectDefaultWindow,
} from './visible-window-views.js';

/**
 * Parity tests: the payload and the assertions are ports of the Kotlin
 * VisibleWindowHierarchyParserTest and its EncodedHierarchyFixture, so the
 * TypeScript reader is checked against the exact bytes and expectations the
 * Kotlin reader was checked against.
 */

/** The device deflates every entry; the reader has to inflate them itself. */
function deflated(entries: readonly ArchiveEntry[]): ArchiveEntry[] {
  return entries.map((entry) => ({
    name: entry.name,
    data: new Uint8Array(deflateRawSync(entry.data)),
    method: 8,
    uncompressedSize: entry.data.length,
  }));
}

const APP = DEMO_PACKAGE;

describe('visible window views', () => {
  it('reads the deflated archive the device writes', async () => {
    const windows = await parseVisibleWindowViewsArchive(
      zipArchive(deflated(demoWindowEntries())),
      APP,
    );
    expect(windows).toHaveLength(1);
  });

  it('decodes actual runtime classes and screen bounds', async () => {
    const windows = await parseVisibleWindowViewsArchive(zipArchive(demoWindowEntries()), APP);
    const root = windows[0]?.root;
    expect(root).toBeDefined();
    if (root === undefined || root.type !== 'view') return;
    const title = root.children[0];
    expect(root.className).toBe('com.codemx.ui.RealRootLayout');
    expect(root.id).toBe('window:' + APP + '/root');
    expect(root.bounds).toEqual({ left: 10, top: 20, right: 1090, bottom: 2420 });
    expect(root.attributes.visibility).toBe('VISIBLE');
    expect(root.attributes.layoutBounds).toEqual({ left: 0, top: 0, right: 1080, bottom: 2400 });
    expect(root.attributes.elevation).toBe(8);
    expect(root.attributes.z).toBe(10);
    expect(root.attributes.padding).toEqual({ left: 16, top: 24, right: 16, bottom: 24 });
    expect(root.attributes.margin).toEqual({ left: 8, top: 12, right: 8, bottom: 12 });
    expect(root.attributes.layoutWidth).toBe(-1);
    expect(root.attributes.layoutHeight).toBe(-2);
    expect(root.attributes.layoutParamsClass).toBe('android.widget.FrameLayout.LayoutParams');
    expect(root.attributes.clipBounds).toEqual({ left: 0, top: 0, right: 1080, bottom: 2300 });
    expect(root.attributes.clipChildren).toBe(true);
    expect(root.attributes.clipToPadding).toBe(false);
    expect(root.attributes.layerType).toBe('HARDWARE');
    expect(root.attributes.hardwareAccelerated).toBe(true);
    expect(root.attributes.clickable).toBe(true);
    expect(root.attributes.longClickable).toBe(true);
    expect(root.attributes.contentDescription).toBe('Root container');
    // Kotlin renders a Float with its decimal point, exactly as the raw pane shows it.
    expect(root.attributes.rawProperties['layout:left']).toBe('0');
    expect(root.attributes.rawProperties['layout:right']).toBe('1080');
    expect(root.attributes.rawProperties['drawing:elevation']).toBe('8.0');
    expect(root.attributes.rawProperties['layoutParams:class']).toBe(
      'android.widget.FrameLayout.LayoutParams',
    );
    if (title === undefined || title.type !== 'view') throw new Error('missing title node');
    expect(title.className).toBe('com.codemx.ui.RealTitleView');
    expect(title.id).toBe('window:' + APP + '/root/0');
    expect(title.resourceName).toBe('com.codemx.anrdemo:id/title');
    expect(title.text).toBe('Title');
    expect(title.bounds).toEqual({ left: 50, top: 100, right: 610, bottom: 180 });
    expect(title.attributes.layoutBounds).toEqual({ left: 40, top: 80, right: 600, bottom: 160 });
  });

  it('parses every decodable window for the target package', async () => {
    const windows = parseVisibleWindowViews(await readZipEntries(zipArchive(demoTwoWindowEntries())), APP);
    expect(windows.map((window) => window.title)).toEqual(['MainActivity', 'ConfirmDialog']);
    expect(windows.every((window) => window.root.id.startsWith(window.id + '/root'))).toBe(true);
    expect(windows.map((window) => window.type)).toEqual(['ACTIVITY', 'DIALOG']);
  });

  it('skips windows the device could not encode instead of failing the capture', async () => {
    const archive = zipArchive([
      { name: 'a3db2af com.android.systemui.wallpapers.ImageWallpaper', data: new Uint8Array() },
      ...demoWindowEntries(),
      { name: 'a3db2af StatusBar', data: new TextEncoder().encode('broken hierarchy') },
    ]);
    const windows = await parseVisibleWindowViewsArchive(archive, APP);
    expect(windows).toHaveLength(1);
    expect(selectDefaultWindow(windows).title).toBe('MainActivity');
  });

  it('parses systemui windows whose dump entries omit the package name', async () => {
    const archive = zipArchive([
      { name: 'a3db2af StatusBar', data: demoHierarchyPayload() },
      { name: 'b473c9d NavigationBar', data: demoHierarchyPayload() },
      ...demoWindowEntries(),
    ]);
    const windows = parseVisibleWindowViews(await readZipEntries(archive), 'com.android.systemui');
    expect(windows.map((window) => window.title)).toEqual(['StatusBar', 'NavigationBar']);
  });

  it('parses launcher taskbar and bare taskbar windows as systemui navigation', async () => {
    const launcher = zipArchive([
      { name: 'a3db2af StatusBar', data: demoHierarchyPayload() },
      {
        name: 'b473c9d com.google.android.apps.nexuslauncher/com.android.launcher3.taskbar.Taskbar',
        data: demoHierarchyPayload(),
      },
    ]);
    const launcherWindows = parseVisibleWindowViews(await readZipEntries(launcher), 'com.android.systemui');
    expect(launcherWindows.map((window) => window.title)).toEqual(['StatusBar', 'Taskbar']);

    const bare = zipArchive([
      { name: 'a3db2af StatusBar', data: demoHierarchyPayload() },
      { name: 'b473c9d Taskbar', data: demoHierarchyPayload() },
    ]);
    const bareWindows = parseVisibleWindowViews(await readZipEntries(bare), 'com.android.systemui');
    expect(bareWindows.map((window) => window.title)).toEqual(['StatusBar', 'Taskbar']);
  });
});
