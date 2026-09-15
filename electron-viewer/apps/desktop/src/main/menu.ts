import { app, Menu, type MenuItemConstructorOptions } from 'electron';
import { basename, resolve } from 'node:path';
import { translate, type UiLanguage } from '../shared/i18n.js';
import {
  viewerActionEntries,
  viewerMenuTitles,
  viewerViewOptionEntries,
  type ViewerMenuCommand,
  type ViewerMenuEntry,
  type ViewerMenuState,
} from '../shared/viewer-menu.js';

/**
 * The Layout Inspector's actions live in the native menu bar, the way the
 * reference's NativeViewerMenuBar puts them there — its HeaderMenuPlacementTest
 * keeps them out of the page header. Main owns the menu, the renderer owns the
 * state behind it and answers the commands it produces.
 *
 * Settings is one of the nine actions the reference keeps inside its Actions
 * menu on every platform, so it stays there here too. macOS also gives the
 * application menu a Settings item of its own, the way AWT's PreferencesHandler
 * does for the reference.
 */
const isMac = process.platform === 'darwin';

/** The menu before the renderer has reported: everything except Settings is dormant. */
export function idleViewerMenuState(language: UiLanguage): ViewerMenuState {
  return {
    language,
    available: false,
    hasSnapshot: false,
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
  };
}

function toMenuItem(
  entry: ViewerMenuEntry,
  dispatch: (command: ViewerMenuCommand) => void,
): MenuItemConstructorOptions {
  if (entry.kind === 'separator') return { type: 'separator' };
  return {
    id: entry.id,
    label: entry.label,
    ...(entry.accelerator === null ? {} : { accelerator: entry.accelerator }),
    type: entry.type,
    checked: entry.checked,
    enabled: entry.enabled,
    click: () => dispatch(entry.command),
  };
}

export interface ViewerMenuRecentOptions {
  readonly recentEntries: readonly string[];
  readonly openRecent: (path: string) => void;
  readonly clearRecent: () => void;
  /** False while main is importing a recent archive. */
  readonly enabled?: boolean;
}

export interface RecentArchiveMenuItem {
  readonly label: string;
  readonly path: string;
}

/**
 * Match Kotlin's NativeViewerMenuBar: show a concise basename unless multiple
 * entries share it, in which case the full normalized path disambiguates them.
 */
export function recentArchiveMenuItems(paths: readonly string[]): readonly RecentArchiveMenuItem[] {
  const normalized = [...new Set(paths.map((path) => resolve(path)))];
  const names = new Map<string, number>();
  for (const path of normalized) {
    const name = basename(path);
    names.set(name, (names.get(name) ?? 0) + 1);
  }
  return normalized.map((path) => {
    const name = basename(path);
    return { label: name.length > 0 && names.get(name) === 1 ? name : path, path };
  });
}

export function buildViewerMenu(
  state: ViewerMenuState,
  dispatch: (command: ViewerMenuCommand) => void,
  recent?: ViewerMenuRecentOptions,
): Menu {
  const titles = viewerMenuTitles(state.language);
  const actions = viewerActionEntries(state).map((entry) => toMenuItem(entry, dispatch));
  const view = viewerViewOptionEntries(state).map((entry) => toMenuItem(entry, dispatch));
  const settings: MenuItemConstructorOptions = {
    label: translate('shell.settings', state.language),
    accelerator: 'CommandOrControl+,',
    click: () => dispatch({ kind: 'action', action: 'OPEN_SETTINGS' }),
  };
  const appMenu: MenuItemConstructorOptions[] = isMac
    ? [
        {
          label: app.name,
          submenu: [
            { role: 'about' },
            { type: 'separator' },
            settings,
            { type: 'separator' },
            { role: 'services' },
            { type: 'separator' },
            { role: 'hide' },
            { role: 'hideOthers' },
            { role: 'unhide' },
            { type: 'separator' },
            { role: 'quit' },
          ],
        },
      ]
    : [];
  const recentEntries = recentArchiveMenuItems(recent?.recentEntries ?? []);
  const recentEnabled = state.available && !state.archiveOperationInProgress && (recent?.enabled ?? true);
  const openRecentSubmenu: MenuItemConstructorOptions[] =
    recentEntries.length === 0
      ? [{ label: translate('menu.noRecentArchives', state.language), enabled: false }]
      : [
          ...recentEntries.map((item) => ({
            label: item.label,
            click: () => recent?.openRecent(item.path),
          })),
          { type: 'separator' },
          {
            label: translate('menu.clearRecent', state.language),
            click: () => recent?.clearRecent(),
          },
        ];
  const file: MenuItemConstructorOptions = {
    label: translate('menu.file', state.language),
    submenu: [
      {
        label: translate('menu.importArchive', state.language),
        accelerator: 'CommandOrControl+I',
        enabled: state.available && !state.archiveOperationInProgress,
        click: () => dispatch({ kind: 'action', action: 'IMPORT_ARCHIVE' }),
      },
      {
        label: translate('menu.openRecent', state.language),
        enabled: recentEnabled,
        submenu: openRecentSubmenu,
      },
      { type: 'separator' },
      {
        label: translate('menu.exportArchive', state.language),
        accelerator: 'CommandOrControl+E',
        enabled: state.available && state.hasSnapshot && !state.archiveOperationInProgress,
        click: () => dispatch({ kind: 'action', action: 'EXPORT_ARCHIVE' }),
      },
    ],
  };
  return Menu.buildFromTemplate([
    ...appMenu,
    file,
    { label: titles.actions, submenu: actions },
    { label: titles.view, submenu: view },
    // The pages are full of text fields, so the standard editing roles stay.
    { role: 'editMenu' },
    { role: 'windowMenu' },
  ]);
}

export function installViewerMenu(
  state: ViewerMenuState,
  dispatch: (command: ViewerMenuCommand) => void,
  recent?: ViewerMenuRecentOptions,
): void {
  Menu.setApplicationMenu(buildViewerMenu(state, dispatch, recent));
}
