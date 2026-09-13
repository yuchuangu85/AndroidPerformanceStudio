import { describe, expect, it } from 'vitest';
import type { LayoutTreeRow } from './tree';
import {
  NO_ISOLATION,
  clearIsolation,
  isolate,
  isolatedRows,
  isolationActive,
  isolationParent,
} from './hierarchy-isolation';

function row(id: string, depth: number, hasChildren = false): LayoutTreeRow {
  return {
    node: { type: 'view', id, className: 'android.widget.View', bounds: { left: 0, top: 0, right: 1, bottom: 1 }, visible: true, alpha: 1, children: [], attributes: { rawProperties: {} } },
    depth,
    number: id,
    label: id,
    visible: true,
    hasChildren,
  };
}

const ROWS = [row('root', 0, true), row('parent', 1, true), row('child', 2), row('sibling', 1)];

describe('hierarchy isolation', () => {
  it('returns only the subtree and moves the parent one level up', () => {
    // The reference's HierarchyIsolationStateTest, assertion for assertion.
    const isolated = isolate(NO_ISOLATION, 'child', ROWS);

    expect(isolatedRows(isolated, ROWS).map((entry) => entry.node.id)).toEqual(['child']);
    expect(isolatedRows(isolated, ROWS)[0]?.depth).toBe(0);
    expect(isolationParent(isolated, ROWS)).toEqual({ rootNodeId: 'parent' });
    expect(isolatedRows(isolationParent(isolated, ROWS), ROWS).map((entry) => entry.node.id)).toEqual([
      'parent',
      'child',
    ]);
  });

  it('rebases the depths of the isolated subtree on its root', () => {
    const isolated = isolate(NO_ISOLATION, 'parent', ROWS);
    expect(isolatedRows(isolated, ROWS).map((entry) => [entry.node.id, entry.depth])).toEqual([
      ['parent', 0],
      ['child', 1],
    ]);
    const whole = isolate(NO_ISOLATION, 'root', ROWS);
    expect(isolatedRows(whole, ROWS).map((entry) => entry.depth)).toEqual([0, 1, 2, 1]);
  });

  it('cannot isolate a node the rows do not hold', () => {
    expect(isolate(NO_ISOLATION, 'missing', ROWS)).toEqual(NO_ISOLATION);
    expect(isolate(NO_ISOLATION, undefined, ROWS)).toEqual(NO_ISOLATION);
    expect(isolationActive(isolate(NO_ISOLATION, 'child', ROWS))).toBe(true);
    expect(isolationActive(NO_ISOLATION)).toBe(false);
  });

  it('parent stops at the top and clearing forgets the root', () => {
    const root = isolate(NO_ISOLATION, 'root', ROWS);
    expect(isolationParent(root, ROWS)).toEqual(NO_ISOLATION);
    expect(isolationParent(NO_ISOLATION, ROWS)).toEqual(NO_ISOLATION);
    expect(clearIsolation()).toEqual(NO_ISOLATION);
    expect(isolatedRows(NO_ISOLATION, ROWS).map((entry) => entry.node.id)).toEqual([
      'root',
      'parent',
      'child',
      'sibling',
    ]);
  });
});
