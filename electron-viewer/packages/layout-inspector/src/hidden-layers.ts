import type { UiNode } from './model.js';

export type HiddenLayerIds = ReadonlySet<string>;

/**
 * Hidden layers are a temporary, local debugging view state (DESIGN.md):
 * they never mutate the snapshot, and they must be reset when the snapshot
 * changes so stale node ids cannot leak across captures.
 */
export function hideLayer(hidden: HiddenLayerIds, nodeId: string): Set<string> {
  const next = new Set(hidden);
  next.add(nodeId);
  return next;
}

export function showLayer(hidden: HiddenLayerIds, nodeId: string): Set<string> {
  const next = new Set(hidden);
  next.delete(nodeId);
  return next;
}

export function toggleLayer(hidden: HiddenLayerIds, nodeId: string): Set<string> {
  return hidden.has(nodeId) ? showLayer(hidden, nodeId) : hideLayer(hidden, nodeId);
}

export function clearHiddenLayers(): Set<string> {
  return new Set<string>();
}

/** Drops ids that no longer exist in the snapshot and keeps the rest. */
export function sanitizeHiddenLayers(hidden: HiddenLayerIds, root: UiNode): Set<string> {
  const present = new Set<string>();
  collectIds(root, present);
  const next = new Set<string>();
  for (const id of hidden) {
    if (present.has(id)) next.add(id);
  }
  return next;
}

function collectIds(node: UiNode, into: Set<string>): void {
  into.add(node.id);
  for (const child of node.children) collectIds(child, into);
}

/**
 * Expands directly hidden ids into the full hidden subtree, so a hidden parent
 * hides every descendant from canvas hit testing and overlays.
 */
export function computeHiddenSubtree(hidden: HiddenLayerIds, root: UiNode): Set<string> {
  const subtree = new Set<string>();
  const visit = (node: UiNode, inherited: boolean): void => {
    const isHidden = inherited || hidden.has(node.id);
    if (isHidden) subtree.add(node.id);
    for (const child of node.children) visit(child, isHidden);
  };
  visit(root, false);
  return subtree;
}

export function isEffectivelyHidden(hiddenSubtree: HiddenLayerIds, nodeId: string): boolean {
  return hiddenSubtree.has(nodeId);
}
