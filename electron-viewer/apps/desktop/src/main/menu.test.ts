import { describe, expect, it, vi } from 'vitest';

const electronMock = vi.hoisted(() => ({ setApplicationMenu: vi.fn() }));
vi.mock('electron', () => ({
  app: { name: 'Android Performance Studio' },
  Menu: {
    buildFromTemplate: (template: unknown) => template,
    setApplicationMenu: electronMock.setApplicationMenu,
  },
}));

import { buildViewerMenu, recentArchiveMenuItems, type ViewerMenuRecentOptions } from './menu.js';
import type { ViewerMenuState } from '../shared/viewer-menu.js';

type MenuEntry = {
  readonly label?: string;
  readonly enabled?: boolean;
  readonly submenu?: readonly MenuEntry[];
  readonly click?: () => void;
  readonly type?: string;
};

function state(overrides: Partial<ViewerMenuState> = {}): ViewerMenuState {
  return {
    language: 'en',
    available: true,
    hasSnapshot: true,
    hasSelection: false,
    autoScan: false,
    archiveOperationInProgress: false,
    panels: { hierarchy: true, details: true, findings: true },
    view: {
      hideInvisibleHierarchyViews: false,
      hideInvisibleFindings: false,
      hideHierarchyIndices: false,
      showHierarchyLayerVisibilityButtons: false,
      showVisibleViewBounds: true,
      showHierarchyIds: true,
    },
    ...overrides,
  };
}

function fileMenu(menu: unknown): readonly MenuEntry[] {
  const root = menu as readonly MenuEntry[];
  const file = root.find((entry) => entry.label === 'File' || entry.label === '文件');
  if (file?.submenu === undefined) throw new Error('File menu missing');
  return file.submenu;
}

describe('recent archive File menu', () => {
  it('uses basenames except when duplicate names need full-path disambiguation', () => {
    expect(recentArchiveMenuItems([
      '/captures/a/layout.apinspect',
      '/captures/b/layout.apinspect',
      '/captures/other.apinspect',
      '/captures/other.apinspect',
    ])).toEqual([
      { label: '/captures/a/layout.apinspect', path: '/captures/a/layout.apinspect' },
      { label: '/captures/b/layout.apinspect', path: '/captures/b/layout.apinspect' },
      { label: 'other.apinspect', path: '/captures/other.apinspect' },
    ]);
  });

  it('renders localized empty history and hides it while the archive operation is busy', () => {
    const recent: ViewerMenuRecentOptions = {
      recentEntries: [],
      openRecent: vi.fn(),
      clearRecent: vi.fn(),
      enabled: false,
    };
    const items = fileMenu(buildViewerMenu(state({ language: 'zh', archiveOperationInProgress: true }), vi.fn(), recent));
    const openRecent = items.find((entry) => entry.label === '最近打开');
    expect(items.find((entry) => entry.label === '导入采集归档…')).toMatchObject({ enabled: false });
    expect(items.find((entry) => entry.label === '导出采集归档…')).toMatchObject({ enabled: false });
    expect(openRecent).toMatchObject({ enabled: false });
    expect(openRecent?.submenu).toEqual([{ label: '暂无最近归档', enabled: false }]);
  });

  it('opens the selected path and clears the persisted history through main-owned callbacks', () => {
    const openRecent = vi.fn();
    const clearRecent = vi.fn();
    const items = fileMenu(buildViewerMenu(state(), vi.fn(), {
      recentEntries: ['/captures/layout.apinspect'], openRecent, clearRecent,
    }));
    const submenu = items.find((entry) => entry.label === 'Open Recent')?.submenu;
    expect(submenu?.map((entry) => entry.label)).toEqual(['layout.apinspect', undefined, 'Clear Menu']);
    submenu?.[0]?.click?.();
    submenu?.[2]?.click?.();
    expect(openRecent).toHaveBeenCalledWith('/captures/layout.apinspect');
    expect(clearRecent).toHaveBeenCalledTimes(1);
  });
});
