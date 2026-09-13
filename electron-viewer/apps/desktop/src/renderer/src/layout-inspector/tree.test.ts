import { describe, expect, it } from 'vitest';
import type { UiNode } from '@aps/layout-inspector';
import {
  adjacentNodeId,
  buildLayoutTreeRows,
  hierarchyLabel,
  revealedCollapsed,
  toggledCollapsed,
  visibleTreeRows,
} from './tree';

function view(
  id: string,
  className: string,
  bounds: [number, number, number, number],
  children: UiNode[] = [],
  extra: { resourceName?: string; visible?: boolean; alpha?: number } = {},
): UiNode {
  return {
    type: 'view',
    id,
    className,
    bounds: { left: bounds[0], top: bounds[1], right: bounds[2], bottom: bounds[3] },
    visible: extra.visible ?? true,
    alpha: extra.alpha ?? 1,
    children,
    ...(extra.resourceName !== undefined ? { resourceName: extra.resourceName } : {}),
    attributes: { rawProperties: {} },
  };
}

const ROOT = view('root', 'android.widget.FrameLayout', [0, 0, 100, 100], [
  view('root/0', 'android.widget.TextView', [0, 0, 50, 20], [], {
    resourceName: 'com.example:id/title',
  }),
  view('root/1', 'android.widget.FrameLayout', [0, 20, 100, 100], [
    view('root/1/0', 'android.widget.Button', [0, 20, 50, 40], [], { resourceName: 'com.example:id/submit' }),
    view('root/1/1', 'android.view.View', [50, 20, 100, 40], [], { visible: false }),
  ]),
]);

describe('layout tree rows', () => {
  it('numbers rows by depth and position and names the layout id', () => {
    const rows = buildLayoutTreeRows(ROOT);
    expect(rows.map((row) => row.number)).toEqual(['0-0', '1-0', '1-1', '2-0', '2-1']);
    expect(rows.map((row) => row.label)).toEqual(['FrameLayout', 'TextView', 'FrameLayout', 'Button', 'View']);
    expect(rows[1]?.resourceLabel).toBe('id/title');
    expect(rows[3]?.resourceLabel).toBe('id/submit');
    expect(rows[0]?.hasChildren).toBe(true);
    expect(rows[1]?.hasChildren).toBe(false);
    expect(rows[4]?.visible).toBe(false);
  });

  it('renders the reference label: index, layout id, class, joined by two spaces', () => {
    const rows = buildLayoutTreeRows(ROOT);
    const row = rows[1];
    expect(row).toBeDefined();
    if (row === undefined) return;
    expect(hierarchyLabel(row, { hideIndex: false, showId: true })).toBe('1-0  id/title  TextView');
    expect(hierarchyLabel(row, { hideIndex: true, showId: true })).toBe('id/title  TextView');
    expect(hierarchyLabel(row, { hideIndex: false, showId: false })).toBe('1-0  TextView');
    const withoutId = rows[0];
    expect(withoutId === undefined ? '' : hierarchyLabel(withoutId, { hideIndex: false, showId: true })).toBe(
      '0-0  FrameLayout',
    );
  });

  it('is fully expanded until a node is collapsed', () => {
    const rows = buildLayoutTreeRows(ROOT);
    expect(visibleTreeRows(rows, new Set(), false).map((row) => row.number)).toEqual([
      '0-0',
      '1-0',
      '1-1',
      '2-0',
      '2-1',
    ]);
    expect(visibleTreeRows(rows, new Set(['root/1']), false).map((row) => row.number)).toEqual([
      '0-0',
      '1-0',
      '1-1',
    ]);
  });

  it('drops invisible subtrees only when the view option asks for it', () => {
    const rows = buildLayoutTreeRows(ROOT);
    expect(visibleTreeRows(rows, new Set(), true).map((row) => row.number)).toEqual([
      '0-0',
      '1-0',
      '1-1',
      '2-0',
    ]);
  });

  it('walks the keyboard along the visible rows and stops at both edges', () => {
    // HierarchyTreeStateTest: the neighbour in the visible order, clamped.
    const rows = buildLayoutTreeRows(ROOT);
    const collapsed = new Set(['root/1']);
    const visible = visibleTreeRows(rows, collapsed, false);
    expect(visible.map((row) => row.node.id)).toEqual(['root', 'root/0', 'root/1']);
    expect(adjacentNodeId(visible, 'root', 'down')).toBe('root/0');
    expect(adjacentNodeId(visible, 'root/0', 'down')).toBe('root/1');
    expect(adjacentNodeId(visible, 'root/1', 'down')).toBe('root/1');
    expect(adjacentNodeId(visible, 'root/1', 'up')).toBe('root/0');
    expect(adjacentNodeId(visible, 'root', 'up')).toBe('root');
    // A selection that is not on screen lands on the first row.
    expect(adjacentNodeId(visible, 'root/1/0', 'down')).toBe('root');
  });

  it('toggles only rows that have children', () => {
    const rows = buildLayoutTreeRows(ROOT);
    expect([...toggledCollapsed(new Set(), rows, 'root/1')]).toEqual(['root/1']);
    expect([...toggledCollapsed(new Set(['root/1']), rows, 'root/1')]).toEqual([]);
    expect([...toggledCollapsed(new Set(), rows, 'root/0')]).toEqual([]);
    expect([...toggledCollapsed(new Set(), rows, 'missing')]).toEqual([]);
  });

  it('reveals a node by expanding every collapsed ancestor', () => {
    const rows = buildLayoutTreeRows(ROOT);
    const collapsed = new Set(['root', 'root/1']);
    // Both ancestors are expanded, so nothing is left collapsed on the path.
    expect([...revealedCollapsed(collapsed, rows, 'root/1/0')]).toEqual([]);
    expect([...revealedCollapsed(new Set(['root/1']), rows, 'root/1/0')]).toEqual([]);
    // A node outside the tree leaves the collapsed set exactly as it was.
    expect([...revealedCollapsed(collapsed, rows, 'missing')]).toEqual(['root', 'root/1']);
  });
});
