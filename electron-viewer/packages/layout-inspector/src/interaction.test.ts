import { describe, expect, it } from 'vitest';
import { computeHiddenSubtree, hideLayer, sanitizeHiddenLayers, showLayer, toggleLayer } from './hidden-layers.js';
import { cycleHitCandidate, hitTestCandidates } from './hit-test.js';
import { flattenVisibleTree, sanitizeExpanded } from './tree.js';
import type { UiNode, ViewNode } from './model.js';

function view(id: string, bounds: [number, number, number, number], children: UiNode[] = []): ViewNode {
  return {
    type: 'view',
    id,
    className: 'android.view.View',
    bounds: { left: bounds[0], top: bounds[1], right: bounds[2], bottom: bounds[3] },
    visible: true,
    alpha: 1,
    children,
    attributes: { rawProperties: {} },
  };
}

// root(0,0,100,100) > container(0,0,100,100) > [big(0,0,100,100), small(10,10,20,20)]
const ROOT = view('0', [0, 0, 100, 100], [view('0.0', [0, 0, 100, 100], [view('0.0.0', [0, 0, 100, 100]), view('0.0.1', [10, 10, 20, 20])])]);

describe('hidden layers', () => {
  it('hides, shows, toggles, and clears', () => {
    let hidden = new Set<string>();
    hidden = toggleLayer(hidden, '0.0.0');
    expect(hidden.has('0.0.0')).toBe(true);
    hidden = toggleLayer(hidden, '0.0.0');
    expect(hidden.size).toBe(0);
    expect(hideLayer(hidden, '0.0').has('0.0')).toBe(true);
    expect(showLayer(new Set(['0.0']), '0.0').size).toBe(0);
  });

  it('expands a hidden parent into its whole subtree', () => {
    const subtree = computeHiddenSubtree(new Set(['0.0']), ROOT);
    expect([...subtree].sort()).toEqual(['0.0', '0.0.0', '0.0.1']);
    expect([...computeHiddenSubtree(new Set(['0.0.1']), ROOT)]).toEqual(['0.0.1']);
  });

  it('drops stale ids for a new snapshot', () => {
    expect([...sanitizeHiddenLayers(new Set(['0.0', 'gone']), ROOT)]).toEqual(['0.0']);
  });
});

describe('hit testing', () => {
  it('prefers the deepest node in z-order mode', () => {
    const candidates = hitTestCandidates(ROOT, { x: 15, y: 15 });
    expect(candidates.map((node) => node.id)).toEqual(['0.0.1', '0.0.0', '0.0', '0']);
  });

  it('prefers the smallest area in smallest-area mode', () => {
    const candidates = hitTestCandidates(ROOT, { x: 15, y: 15, order: 'smallest-area' });
    expect(candidates[0]?.id).toBe('0.0.1');
    expect(candidates.at(-1)?.id).toBe('0');
  });

  it('passes through hidden layers', () => {
    const hidden = computeHiddenSubtree(new Set(['0.0.0']), ROOT);
    const candidates = hitTestCandidates(ROOT, { x: 15, y: 15, hiddenSubtree: hidden });
    expect(candidates.map((node) => node.id)).toEqual(['0.0.1', '0.0', '0']);
  });

  it('returns nothing outside every bound', () => {
    expect(hitTestCandidates(ROOT, { x: 500, y: 500 })).toEqual([]);
  });

  it('cycles through candidates at the same point', () => {
    const candidates = hitTestCandidates(ROOT, { x: 15, y: 15 });
    expect(cycleHitCandidate(candidates, undefined)?.id).toBe('0.0.1');
    expect(cycleHitCandidate(candidates, '0.0.1')?.id).toBe('0.0.0');
    expect(cycleHitCandidate(candidates, '0')?.id).toBe('0.0.1');
    expect(cycleHitCandidate([], '0')).toBeUndefined();
  });
});

describe('tree flattening', () => {
  it('lists the root and honors expansion', () => {
    const collapsed = flattenVisibleTree(ROOT, { expanded: new Set() });
    expect(collapsed.map((row) => row.node.id)).toEqual(['0']);

    const expanded = flattenVisibleTree(ROOT, { expanded: new Set(['0', '0.0']) });
    expect(expanded.map((row) => [row.node.id, row.depth])).toEqual([
      ['0', 0],
      ['0.0', 1],
      ['0.0.0', 2],
      ['0.0.1', 2],
    ]);
  });

  it('marks hidden rows without removing them', () => {
    const subtree = computeHiddenSubtree(new Set(['0.0']), ROOT);
    const rows = flattenVisibleTree(ROOT, { expanded: new Set(['0', '0.0']), hiddenSubtree: subtree });
    expect(rows).toHaveLength(4);
    expect(rows.find((row) => row.node.id === '0.0')?.hidden).toBe(true);
    expect(rows.find((row) => row.node.id === '0.0.1')?.hiddenByAncestor).toBe(true);
  });

  it('drops invisible children only when the display option is on', () => {
    const invisible = view('0.0.2', [0, 0, 1, 1]);
    const root = view('0', [0, 0, 10, 10], [{ ...invisible, visible: false }]);
    expect(flattenVisibleTree(root, { expanded: new Set(['0']) })).toHaveLength(2);
    expect(flattenVisibleTree(root, { expanded: new Set(['0']), hideInvisibleViews: true })).toHaveLength(1);
  });

  it('sanitizes expansion for a new snapshot', () => {
    expect([...sanitizeExpanded(new Set(['0', 'stale']), ROOT)]).toEqual(['0']);
  });
});
