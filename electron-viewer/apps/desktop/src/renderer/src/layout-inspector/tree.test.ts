import { describe, expect, it } from 'vitest';
import type { UiNode } from '@aps/layout-inspector';
import { buildLayoutTreeRows, hierarchyLabel, treeMetrics, visibleTreeRows } from './tree';

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

  it('summarises nodes, depth, and widest level', () => {
    expect(treeMetrics(buildLayoutTreeRows(ROOT))).toEqual({ nodeCount: 5, maxDepth: 3, widestLevel: 2 });
  });
});
