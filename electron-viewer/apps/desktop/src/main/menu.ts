import { app, Menu, type MenuItemConstructorOptions } from 'electron';
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

export function buildViewerMenu(
  state: ViewerMenuState,
  dispatch: (command: ViewerMenuCommand) => void,
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
  return Menu.buildFromTemplate([
    ...appMenu,
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
): void {
  Menu.setApplicationMenu(buildViewerMenu(state, dispatch));
}
