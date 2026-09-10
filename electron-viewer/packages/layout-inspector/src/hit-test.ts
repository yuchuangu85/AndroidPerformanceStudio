import { boundsArea, type UiNode } from './model.js';

export type HitTestOrder = 'z-order' | 'smallest-area';

export interface HitTestOptions {
  readonly x: number;
  readonly y: number;
  /** Effective hidden subtree; hidden nodes take no hits and neither do descendants. */
  readonly hiddenSubtree?: ReadonlySet<string>;
  readonly order?: HitTestOrder;
}

interface Collected {
  readonly node: UiNode;
  readonly order: number;
}

function contains(node: UiNode, x: number, y: number): boolean {
  const bounds = node.bounds;
  return x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom;
}

/**
 * Candidate nodes at a point, best-first. Hidden layers are skipped entirely so
 * pointer input passes through to the layers underneath (DESIGN.md).
 */
export function hitTestCandidates(root: UiNode, options: HitTestOptions): UiNode[] {
  const hidden = options.hiddenSubtree ?? new Set<string>();
  const order = options.order ?? 'z-order';
  const collected: Collected[] = [];
  let counter = 0;
  const visit = (node: UiNode): void => {
    if (hidden.has(node.id)) return;
    if (contains(node, options.x, options.y)) {
      collected.push({ node, order: counter });
      counter += 1;
    }
    for (const child of node.children) visit(child);
  };
  visit(root);

  const sorted = [...collected];
  if (order === 'smallest-area') {
    sorted.sort((left, right) => {
      const difference = boundsArea(left.node.bounds) - boundsArea(right.node.bounds);
      return difference !== 0 ? difference : right.order - left.order;
    });
  } else {
    // Reverse pre-order: later siblings and deeper nodes win, like z-order.
    sorted.sort((left, right) => right.order - left.order);
  }
  return sorted.map((entry) => entry.node);
}

/** Cycles through the candidates at the same point, like repeated canvas clicks. */
export function cycleHitCandidate(candidates: readonly UiNode[], currentId: string | undefined): UiNode | undefined {
  if (candidates.length === 0) return undefined;
  const index = currentId === undefined ? -1 : candidates.findIndex((candidate) => candidate.id === currentId);
  return candidates[(index + 1) % candidates.length];
}
