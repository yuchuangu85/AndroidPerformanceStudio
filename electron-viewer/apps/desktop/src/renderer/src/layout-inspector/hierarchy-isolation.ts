import type { LayoutTreeRow } from './tree';

/**
 * Port of HierarchyIsolationState: the subtree the hierarchy and the canvas are
 * cut to. The rows keep their own ids, and their depth is rebased on the
 * isolated root so the tree redraws as a tree of its own.
 */
export interface HierarchyIsolationState {
  readonly rootNodeId?: string;
}

export const NO_ISOLATION: HierarchyIsolationState = {};

export function isolationActive(state: HierarchyIsolationState): boolean {
  return state.rootNodeId !== undefined;
}

/** A node that is not in the rows cannot be isolated, the way takeIf guards it. */
export function isolate(
  state: HierarchyIsolationState,
  nodeId: string | undefined,
  rows: readonly LayoutTreeRow[],
): HierarchyIsolationState {
  if (nodeId === undefined) return NO_ISOLATION;
  return rows.some((row) => row.node.id === nodeId) ? { ...state, rootNodeId: nodeId } : NO_ISOLATION;
}

/** One level up: the nearest row above the root that is less deep than it. */
export function isolationParent(
  state: HierarchyIsolationState,
  rows: readonly LayoutTreeRow[],
): HierarchyIsolationState {
  const index = rows.findIndex((row) => row.node.id === state.rootNodeId);
  if (index <= 0) return NO_ISOLATION;
  const depth = rows[index]!.depth;
  for (let candidate = index - 1; candidate >= 0; candidate -= 1) {
    if (rows[candidate]!.depth < depth) return { rootNodeId: rows[candidate]!.node.id };
  }
  return NO_ISOLATION;
}

export function clearIsolation(): HierarchyIsolationState {
  return NO_ISOLATION;
}

/** The isolated subtree, or every row when nothing is isolated. */
export function isolatedRows(
  state: HierarchyIsolationState,
  rows: readonly LayoutTreeRow[],
): LayoutTreeRow[] {
  const index = rows.findIndex((row) => row.node.id === state.rootNodeId);
  if (index < 0) return [...rows];
  const depth = rows[index]!.depth;
  let end = rows.length;
  for (let candidate = index + 1; candidate < rows.length; candidate += 1) {
    if (rows[candidate]!.depth <= depth) {
      end = candidate;
      break;
    }
  }
  return rows.slice(index, end).map((row) => ({ ...row, depth: row.depth - depth }));
}
