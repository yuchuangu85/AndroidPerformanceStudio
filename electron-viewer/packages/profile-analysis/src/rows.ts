/**
 * Port of FlameGraphRowProjector.kt: lays every visible call node out on a
 * normalized 0..1 row per depth. Children are laid out inside their parent's
 * span; a node whose weight exceeds the sum of its children still fills the
 * parent span, which is what makes self time visible in the flame graph.
 */
import type { CallNodeTable, CallStackDirection, FlameGraphRows } from './contracts.js';

export function projectFlameGraphRows(
  callNodes: CallNodeTable,
  direction: CallStackDirection = 'FORWARD',
): FlameGraphRows {
  const startsAtBottom = direction === 'FORWARD';
  const size = callNodes.size;
  const starts = new Array<number>(size).fill(0);
  const ends = new Array<number>(size).fill(0);
  const rows: number[][] = [];

  if (size > 0) {
    const children = childrenByParent(callNodes.parentIndexes);
    const roots = callNodes.parentIndexes
      .map((_parent, index) => index)
      .filter((index) => callNodes.parentIndexes[index] === -1 && (callNodes.inclusiveWeights[index] ?? 0n) > 0n);
    layoutRoots(roots, callNodes.inclusiveWeights, starts, ends);
    const pending: number[] = [];
    [...roots].reverse().forEach((root) => {
      if ((ends[root] as number) > (starts[root] as number)) pending.push(root);
    });
    while (pending.length > 0) {
      const node = pending.pop() as number;
      const depth = callNodes.depths[node] as number;
      while (rows.length <= depth) rows.push([]);
      (rows[depth] as number[]).push(node);
      const visibleChildren = (children[node] as number[]).filter(
        (child) => (callNodes.inclusiveWeights[child] ?? 0n) > 0n,
      );
      layoutChildren(node, visibleChildren, callNodes.inclusiveWeights, starts, ends);
      [...visibleChildren].reverse().forEach((child) => {
        if ((ends[child] as number) > (starts[child] as number)) pending.push(child);
      });
    }
  }

  return {
    nodeIndexesByRow: rows,
    starts,
    ends,
    startsAtBottom,
    rowCount: rows.length,
  };
}

export function rowStartAt(rows: FlameGraphRows, nodeIndex: number): number | undefined {
  return rows.starts[nodeIndex];
}

export function rowEndAt(rows: FlameGraphRows, nodeIndex: number): number | undefined {
  return rows.ends[nodeIndex];
}

export function rowNormalizedWidthAt(rows: FlameGraphRows, nodeIndex: number): number | undefined {
  const start = rows.starts[nodeIndex];
  const end = rows.ends[nodeIndex];
  if (start === undefined || end === undefined) return undefined;
  const width = end - start;
  return Number.isFinite(width) ? width : undefined;
}

function childrenByParent(parentIndexes: readonly number[]): number[][] {
  const children = parentIndexes.map(() => [] as number[]);
  parentIndexes.forEach((parent, index) => {
    if (parent >= index || parent < -1) {
      throw new Error('parent indexes must precede their children');
    }
    if (parent >= 0) (children[parent] as number[]).push(index);
  });
  return children;
}

function layoutRoots(
  roots: readonly number[],
  weights: readonly bigint[],
  starts: number[],
  ends: number[],
): void {
  const total = roots.reduce((sum, root) => sum + Number(weights[root] ?? 0n), 0);
  let cumulative = 0;
  roots.forEach((root, position) => {
    const start = normalized(cumulative, total);
    cumulative += Number(weights[root] ?? 0n);
    const end = position === roots.length - 1 ? 1 : normalized(cumulative, total);
    if (end > start) {
      starts[root] = start;
      ends[root] = end;
    }
  });
}

function layoutChildren(
  parent: number,
  children: readonly number[],
  weights: readonly bigint[],
  starts: number[],
  ends: number[],
): void {
  if (children.length === 0) return;
  const childWeight = children.reduce((sum, child) => sum + Number(weights[child] ?? 0n), 0);
  const denominator = Math.max(Number(weights[parent] ?? 0n), childWeight);
  const parentStart = starts[parent] as number;
  const parentEnd = ends[parent] as number;
  const parentWidth = parentEnd - parentStart;
  let cumulative = 0;
  children.forEach((child) => {
    const start = parentStart + parentWidth * normalized(cumulative, denominator);
    cumulative += Number(weights[child] ?? 0n);
    const end = Math.min(parentEnd, parentStart + parentWidth * normalized(cumulative, denominator));
    if (end > start && Number.isFinite(start) && Number.isFinite(end)) {
      starts[child] = start;
      ends[child] = end;
    }
  });
}

function normalized(numerator: number, denominator: number): number {
  if (denominator > 0 && Number.isFinite(denominator)) {
    return Math.min(1, Math.max(0, numerator / denominator));
  }
  return 0;
}
