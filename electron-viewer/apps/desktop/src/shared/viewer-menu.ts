/**
 * The Layout Inspector's action surface, ported from the reference's
 * ViewerActionMenu and its native menu bar.
 *
 * The reference keeps these actions in the native menu bar and deliberately not
 * in the page header — HeaderMenuPlacementTest pins that — so the menu bar is
 * the surface, and this module is the one place that knows how a menu item maps
 * to viewer state. The main process builds the menu from it and the renderer
 * answers the commands it produces.
 */
import { translate, type UiLanguage } from './i18n.js';
import type { LayoutInspectorSettings } from './settings-contract.js';

/** The nine actions of the reference's Actions menu, in its order. */
export type ViewerMenuAction =
  | 'IMPORT_ARCHIVE'
  | 'EXPORT_ARCHIVE'
  | 'TOGGLE_AUTO_SCAN'
  | 'PREVIOUS_NODE'
  | 'NEXT_NODE'
  | 'TOGGLE_SELECTED_NODE'
  | 'TOGGLE_HIERARCHY'
  | 'TOGGLE_FINDINGS'
  | 'TOGGLE_DETAILS'
  | 'TOGGLE_HIERARCHY_IDS'
  | 'OPEN_SETTINGS';

/** The five display options of the reference's View menu, in its order. */
export type ViewerMenuViewField =
  | 'hideInvisibleHierarchyViews'
  | 'hideInvisibleFindings'
  | 'hideHierarchyIndices'
  | 'showHierarchyLayerVisibilityButtons'
  | 'showVisibleViewBounds';

export type ViewerMenuCommand =
  | { readonly kind: 'action'; readonly action: ViewerMenuAction }
  | { readonly kind: 'viewOption'; readonly field: ViewerMenuViewField };

/**
 * Everything the menu's enabled and checked state depends on. The renderer owns
 * this state — the panel holds the panes, the settings store holds the display
 * options — and reports it whenever any of it changes.
 */
export interface ViewerMenuState {
  readonly language: UiLanguage;
  /** False while another destination is on screen: only Settings stays live. */
  readonly available: boolean;
  readonly hasSnapshot: boolean;
  readonly hasSelection: boolean;
  readonly autoScan: boolean;
  /** Disables only File-menu archive actions while an import/export is running. */
  readonly archiveOperationInProgress: boolean;
  /** Pane visibility. The menu's checkboxes read the opposite: "hide left panel". */
  readonly panels: {
    readonly hierarchy: boolean;
    readonly details: boolean;
    readonly findings: boolean;
  };
  readonly view: Pick<LayoutInspectorSettings, ViewerMenuViewField | 'showHierarchyIds'>;
}

interface ActionSpec {
  readonly action: ViewerMenuAction;
  readonly labelKey: string;
  readonly group: number;
  readonly accelerator: string | null;
}

/**
 * Groups become separators, exactly as the reference's group field does: the
 * scan toggle, the node row, the panel toggles, the ID toggle, settings.
 */
export const VIEWER_MENU_ACTIONS: readonly ActionSpec[] = [
  { action: 'TOGGLE_AUTO_SCAN', labelKey: 'menu.autoScan', group: 0, accelerator: 'CommandOrControl+R' },
  { action: 'PREVIOUS_NODE', labelKey: 'menu.previousNode', group: 1, accelerator: null },
  { action: 'NEXT_NODE', labelKey: 'menu.nextNode', group: 1, accelerator: null },
  { action: 'TOGGLE_SELECTED_NODE', labelKey: 'menu.toggleSelectedNode', group: 1, accelerator: null },
  { action: 'TOGGLE_HIERARCHY', labelKey: 'menu.toggleHierarchy', group: 2, accelerator: 'CommandOrControl+1' },
  { action: 'TOGGLE_FINDINGS', labelKey: 'menu.toggleFindings', group: 2, accelerator: 'CommandOrControl+2' },
  { action: 'TOGGLE_DETAILS', labelKey: 'menu.toggleDetails', group: 2, accelerator: 'CommandOrControl+3' },
  { action: 'TOGGLE_HIERARCHY_IDS', labelKey: 'menu.showLayoutIds', group: 3, accelerator: null },
  { action: 'OPEN_SETTINGS', labelKey: 'shell.settings', group: 4, accelerator: 'CommandOrControl+,' },
];

interface ViewOptionSpec {
  readonly field: ViewerMenuViewField;
  readonly labelKey: string;
  readonly group: number;
}

export const VIEWER_MENU_VIEW_OPTIONS: readonly ViewOptionSpec[] = [
  { field: 'hideInvisibleHierarchyViews', labelKey: 'menu.hideInvisibleHierarchyViews', group: 0 },
  { field: 'hideInvisibleFindings', labelKey: 'menu.hideInvisibleFindings', group: 0 },
  { field: 'hideHierarchyIndices', labelKey: 'menu.hideHierarchyIndices', group: 0 },
  { field: 'showHierarchyLayerVisibilityButtons', labelKey: 'menu.showLayerVisibilityButtons', group: 1 },
  { field: 'showVisibleViewBounds', labelKey: 'menu.showVisibleViewBounds', group: 2 },
];

/**
 * Whether an action can run. Settings is always available; everything else
 * belongs to the Layout Inspector, and the three node actions need a node.
 */
export function viewerActionEnabled(action: ViewerMenuAction, state: ViewerMenuState): boolean {
  if (action === 'OPEN_SETTINGS') return true;
  if (action === 'IMPORT_ARCHIVE') return state.available;
  if (action === 'EXPORT_ARCHIVE') return state.available && state.hasSnapshot;
  if (!state.available) return false;
  if (action === 'PREVIOUS_NODE' || action === 'NEXT_NODE' || action === 'TOGGLE_SELECTED_NODE') {
    return state.hasSelection;
  }
  return true;
}

/**
 * The checkmark. The reference labels the panel toggles "Hide ...", so their
 * checked state is the hidden one; the others read their own value.
 */
export function viewerActionChecked(action: ViewerMenuAction, state: ViewerMenuState): boolean {
  switch (action) {
    case 'TOGGLE_AUTO_SCAN':
      return state.autoScan;
    case 'TOGGLE_HIERARCHY':
      return !state.panels.hierarchy;
    case 'TOGGLE_FINDINGS':
      return !state.panels.findings;
    case 'TOGGLE_DETAILS':
      return !state.panels.details;
    case 'TOGGLE_HIERARCHY_IDS':
      return state.view.showHierarchyIds;
    default:
      return false;
  }
}

/** The toggles carry a checkmark; the three node actions do not. */
function isToggle(action: ViewerMenuAction): boolean {
  return (
    action === 'TOGGLE_AUTO_SCAN' ||
    action === 'TOGGLE_HIERARCHY' ||
    action === 'TOGGLE_FINDINGS' ||
    action === 'TOGGLE_DETAILS' ||
    action === 'TOGGLE_HIERARCHY_IDS'
  );
}

export interface ViewerMenuCommandItem {
  readonly kind: 'command';
  readonly id: string;
  readonly label: string;
  readonly accelerator: string | null;
  readonly type: 'checkbox' | 'normal';
  readonly checked: boolean;
  readonly enabled: boolean;
  readonly command: ViewerMenuCommand;
}

export interface ViewerMenuSeparator {
  readonly kind: 'separator';
}

export type ViewerMenuEntry = ViewerMenuCommandItem | ViewerMenuSeparator;

/**
 * The Actions menu as entries, separators included.
 *
 * Settings stays inside this menu on every platform, which is what the
 * reference's ViewerActionMenu does: OPEN_SETTINGS is the ninth item of its one
 * list (group 4, ⌘,), and NativeViewerMenuBar renders that list into the
 * Actions menu whole — its own test pins the nine actions with isMacOs = true.
 * The macOS application menu carries a Settings item of its own as well, the way
 * AWT's PreferencesHandler gives the reference one; it is not a substitute for
 * this one.
 */
export function viewerActionEntries(state: ViewerMenuState): readonly ViewerMenuEntry[] {
  const entries: ViewerMenuEntry[] = [];
  let group: number | null = null;
  for (const spec of VIEWER_MENU_ACTIONS) {
    if (group !== null && spec.group !== group) entries.push({ kind: 'separator' });
    group = spec.group;
    entries.push({
      kind: 'command',
      id: spec.action,
      label: translate(spec.labelKey as never, state.language),
      accelerator: spec.accelerator,
      type: isToggle(spec.action) ? 'checkbox' : 'normal',
      checked: viewerActionChecked(spec.action, state),
      enabled: viewerActionEnabled(spec.action, state),
      command: { kind: 'action', action: spec.action },
    });
  }
  return entries;
}

/** The View menu as entries, separators included. */
export function viewerViewOptionEntries(state: ViewerMenuState): readonly ViewerMenuEntry[] {
  const entries: ViewerMenuEntry[] = [];
  let group: number | null = null;
  for (const spec of VIEWER_MENU_VIEW_OPTIONS) {
    if (group !== null && spec.group !== group) entries.push({ kind: 'separator' });
    group = spec.group;
    entries.push({
      kind: 'command',
      id: spec.field,
      label: translate(spec.labelKey as never, state.language),
      accelerator: null,
      type: 'checkbox',
      checked: state.view[spec.field],
      enabled: state.available,
      command: { kind: 'viewOption', field: spec.field },
    });
  }
  return entries;
}

/** The menu titles, so main and the tests name them the same way. */
export function viewerMenuTitles(language: UiLanguage): { readonly actions: string; readonly view: string } {
  return { actions: translate('menu.actions', language), view: translate('menu.view', language) };
}
