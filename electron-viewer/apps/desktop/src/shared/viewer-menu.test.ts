import { describe, expect, it } from 'vitest';
import {
  VIEWER_MENU_ACTIONS,
  VIEWER_MENU_VIEW_OPTIONS,
  viewerActionChecked,
  viewerActionEnabled,
  viewerActionEntries,
  viewerMenuTitles,
  viewerViewOptionEntries,
  type ViewerMenuState,
} from './viewer-menu.js';

function state(overrides: Partial<ViewerMenuState> = {}): ViewerMenuState {
  return {
    language: 'en',
    available: true,
    hasSnapshot: true,
    hasSelection: true,
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

const commandsOf = (entries: readonly { kind: string }[]): readonly string[] =>
  entries.flatMap((entry) => (entry.kind === 'command' ? [entry.kind] : []));

describe('viewer menu', () => {
  it('keeps the reference action order and groups', () => {
    expect(VIEWER_MENU_ACTIONS.map((item) => item.action)).toEqual([
      'TOGGLE_AUTO_SCAN',
      'PREVIOUS_NODE',
      'NEXT_NODE',
      'TOGGLE_SELECTED_NODE',
      'TOGGLE_HIERARCHY',
      'TOGGLE_FINDINGS',
      'TOGGLE_DETAILS',
      'TOGGLE_HIERARCHY_IDS',
      'OPEN_SETTINGS',
    ]);
    expect(VIEWER_MENU_ACTIONS.map((item) => item.group)).toEqual([0, 1, 1, 1, 2, 2, 2, 3, 4]);
  });

  it('turns every group change into a separator', () => {
    const entries = viewerActionEntries(state());
    expect(entries.filter((entry) => entry.kind === 'separator')).toHaveLength(4);
    expect(entries[0]?.kind).toBe('command');
    expect(entries.at(-1)?.kind).toBe('command');
  });

  it('carries the reference accelerators', () => {
    const accelerators = new Map(
      viewerActionEntries(state()).flatMap((entry) =>
        entry.kind === 'command' ? [[entry.id, entry.accelerator] as const] : [],
      ),
    );
    expect(accelerators.get('TOGGLE_AUTO_SCAN')).toBe('CommandOrControl+R');
    expect(accelerators.get('TOGGLE_HIERARCHY')).toBe('CommandOrControl+1');
    expect(accelerators.get('TOGGLE_FINDINGS')).toBe('CommandOrControl+2');
    expect(accelerators.get('TOGGLE_DETAILS')).toBe('CommandOrControl+3');
    expect(accelerators.get('OPEN_SETTINGS')).toBe('CommandOrControl+,');
    expect(accelerators.get('PREVIOUS_NODE')).toBeNull();
    expect(accelerators.get('NEXT_NODE')).toBeNull();
    expect(accelerators.get('TOGGLE_SELECTED_NODE')).toBeNull();
    expect(accelerators.get('TOGGLE_HIERARCHY_IDS')).toBeNull();
  });

  it('labels the menu with the reference wording in both languages', () => {
    expect(viewerMenuTitles('zh')).toEqual({ actions: '操作', view: '视图' });
    expect(viewerMenuTitles('en')).toEqual({ actions: 'Actions', view: 'View' });
    const zh = new Map(
      viewerActionEntries(state({ language: 'zh' })).flatMap((entry) =>
        entry.kind === 'command' ? [[entry.id, entry.label] as const] : [],
      ),
    );
    expect(zh.get('TOGGLE_AUTO_SCAN')).toBe('自动扫描');
    expect(zh.get('TOGGLE_HIERARCHY')).toBe('隐藏左侧栏');
    expect(zh.get('TOGGLE_SELECTED_NODE')).toBe('折叠/展开节点');
    expect(zh.get('TOGGLE_HIERARCHY_IDS')).toBe('显示布局 ID');
    expect(zh.get('OPEN_SETTINGS')).toBe('设置');
  });

  it('checks the hide toggles when the pane is hidden', () => {
    const hidden = state({ panels: { hierarchy: false, details: true, findings: false } });
    expect(viewerActionChecked('TOGGLE_HIERARCHY', hidden)).toBe(true);
    expect(viewerActionChecked('TOGGLE_FINDINGS', hidden)).toBe(true);
    expect(viewerActionChecked('TOGGLE_DETAILS', hidden)).toBe(false);
    expect(viewerActionChecked('TOGGLE_AUTO_SCAN', state({ autoScan: true }))).toBe(true);
    expect(viewerActionChecked('TOGGLE_HIERARCHY_IDS', state({ view: { ...state().view, showHierarchyIds: false } }))).toBe(false);
  });

  it('marks the toggles as checkboxes and the node actions as plain items', () => {
    const types = new Map(
      viewerActionEntries(state()).flatMap((entry) =>
        entry.kind === 'command' ? [[entry.id, entry.type] as const] : [],
      ),
    );
    expect(types.get('TOGGLE_AUTO_SCAN')).toBe('checkbox');
    expect(types.get('TOGGLE_HIERARCHY')).toBe('checkbox');
    expect(types.get('TOGGLE_HIERARCHY_IDS')).toBe('checkbox');
    expect(types.get('PREVIOUS_NODE')).toBe('normal');
    expect(types.get('NEXT_NODE')).toBe('normal');
    expect(types.get('TOGGLE_SELECTED_NODE')).toBe('normal');
    expect(types.get('OPEN_SETTINGS')).toBe('normal');
  });

  it('needs a node for the node actions', () => {
    const withoutSelection = state({ hasSelection: false });
    expect(viewerActionEnabled('PREVIOUS_NODE', withoutSelection)).toBe(false);
    expect(viewerActionEnabled('NEXT_NODE', withoutSelection)).toBe(false);
    expect(viewerActionEnabled('TOGGLE_SELECTED_NODE', withoutSelection)).toBe(false);
    expect(viewerActionEnabled('TOGGLE_AUTO_SCAN', withoutSelection)).toBe(true);
  });

  it('holds every action back while another page is on screen, settings excepted', () => {
    const elsewhere = state({ available: false });
    for (const item of VIEWER_MENU_ACTIONS) {
      expect(viewerActionEnabled(item.action, elsewhere)).toBe(item.action === 'OPEN_SETTINGS');
    }
  });

  it('keeps settings inside the actions menu on every platform', () => {
    // The reference's ViewerActionMenu puts OPEN_SETTINGS in its one list and
    // NativeViewerMenuBar renders that list whole; its test pins the nine
    // actions with isMacOs = true, so macOS must not drop the last one.
    const ids = viewerActionEntries(state()).flatMap((entry) =>
      entry.kind === 'command' ? [entry.id] : [],
    );
    expect(ids).toEqual([...VIEWER_MENU_ACTIONS.map((item) => item.action)]);
    expect(ids.at(-1)).toBe('OPEN_SETTINGS');
  });

  it('mirrors the reference view options in order, groups and checkmarks', () => {
    expect(VIEWER_MENU_VIEW_OPTIONS.map((item) => item.field)).toEqual([
      'hideInvisibleHierarchyViews',
      'hideInvisibleFindings',
      'hideHierarchyIndices',
      'showHierarchyLayerVisibilityButtons',
      'showVisibleViewBounds',
    ]);
    expect(VIEWER_MENU_VIEW_OPTIONS.map((item) => item.group)).toEqual([0, 0, 0, 1, 2]);
    const entries = viewerViewOptionEntries(state());
    expect(entries.filter((entry) => entry.kind === 'separator')).toHaveLength(2);
    expect(commandsOf(entries)).toHaveLength(5);
    const checked = entries.flatMap((entry) =>
      entry.kind === 'command' ? [[entry.id, entry.checked] as const] : [],
    );
    expect(checked).toEqual([
      ['hideInvisibleHierarchyViews', false],
      ['hideInvisibleFindings', false],
      ['hideHierarchyIndices', false],
      ['showHierarchyLayerVisibilityButtons', false],
      ['showVisibleViewBounds', true],
    ]);
  });

  it('labels the view menu in the reference wording', () => {
    const zh = viewerViewOptionEntries(state({ language: 'zh' })).flatMap((entry) =>
      entry.kind === 'command' ? [[entry.id, entry.label] as const] : [],
    );
    expect(zh).toEqual([
      ['hideInvisibleHierarchyViews', '隐藏层级结构中的不可见视图'],
      ['hideInvisibleFindings', '隐藏问题列表中的不可见视图内容'],
      ['hideHierarchyIndices', '隐藏层级索引'],
      ['showHierarchyLayerVisibilityButtons', '显示层级结构中的显示按钮'],
      ['showVisibleViewBounds', '显示全部可见视图边缘'],
    ]);
  });

  it('tells the renderer which state to move', () => {
    const commands = viewerActionEntries(state()).flatMap((entry) =>
      entry.kind === 'command' ? [entry.command] : [],
    );
    expect(commands[0]).toEqual({ kind: 'action', action: 'TOGGLE_AUTO_SCAN' });
    expect(viewerViewOptionEntries(state())[0]?.kind === 'command').toBe(true);
    const viewCommand = viewerViewOptionEntries(state())[0];
    expect(viewCommand?.kind === 'command' ? viewCommand.command : null).toEqual({
      kind: 'viewOption',
      field: 'hideInvisibleHierarchyViews',
    });
  });
});
