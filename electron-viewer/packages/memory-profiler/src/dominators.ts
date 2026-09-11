import type { ObjectGraph } from './graph.js';
import type { Identifier } from './hprof.js';

export interface ReachabilityResult {
  readonly reachable: ReadonlySet<Identifier>;
  readonly unreachable: readonly Identifier[];
}

/** Objects reachable from any GC root are live; everything else is garbage. */
export function reachableFromRoots(graph: ObjectGraph): ReachabilityResult {
  const reachable = new Set<Identifier>();
  const stack: Identifier[] = [...graph.roots];
  while (stack.length > 0) {
    const id = stack.pop() as Identifier;
    if (reachable.has(id)) continue;
    reachable.add(id);
    const node = graph.nodes.get(id);
    if (node === undefined) continue;
    for (const reference of node.references) {
      if (!reachable.has(reference)) stack.push(reference);
    }
  }
  const unreachable: Identifier[] = [];
  for (const id of graph.nodes.keys()) {
    if (!reachable.has(id)) unreachable.push(id);
  }
  return { reachable, unreachable };
}

export interface DominatorResult {
  /** Immediate dominator per node; the virtual root has none. */
  readonly immediateDominator: ReadonlyMap<Identifier, Identifier>;
  /** Retained size per node: its shallow size plus everything it dominates. */
  readonly retainedBytes: ReadonlyMap<Identifier, number>;
  readonly virtualRoot: Identifier;
}

const VIRTUAL_ROOT: Identifier = -1n;

/**
 * Iterative Cooper-Harvey-Kennedy dominator construction over the reference
 * graph. Retained size is the dominator-subtree sum, which is the figure leak
 * suspects are ranked by.
 */
export function computeDominators(
  graph: ObjectGraph,
  options: { readonly reachability?: ReachabilityResult } = {},
): DominatorResult {
  // Reachability is reusable: callers that already walked the graph pass it in
  // rather than paying for a second traversal.
  const reachability = options.reachability ?? reachableFromRoots(graph);
  const order: Identifier[] = [];
  const visited = new Set<Identifier>();
  // Depth-first post-order over reachable nodes, then reversed.
  const stack: Array<{ id: Identifier; expanded: boolean }> = [{ id: VIRTUAL_ROOT, expanded: false }];
  const successorsOf = (id: Identifier): readonly Identifier[] =>
    id === VIRTUAL_ROOT ? graph.roots : (graph.nodes.get(id)?.references ?? []);
  while (stack.length > 0) {
    const frame = stack.pop() as { id: Identifier; expanded: boolean };
    if (frame.expanded) {
      order.push(frame.id);
      continue;
    }
    if (visited.has(frame.id)) continue;
    visited.add(frame.id);
    stack.push({ id: frame.id, expanded: true });
    for (const successor of successorsOf(frame.id)) {
      if (!visited.has(successor)) stack.push({ id: successor, expanded: false });
    }
  }
  const reversePostOrder = order.reverse();
  const index = new Map<Identifier, number>();
  reversePostOrder.forEach((id, position) => index.set(id, position));

  const predecessors = new Map<Identifier, Identifier[]>();
  for (const id of reversePostOrder) {
    for (const successor of successorsOf(id)) {
      if (!index.has(successor)) continue;
      const list = predecessors.get(successor) ?? [];
      list.push(id);
      predecessors.set(successor, list);
    }
  }

  const dominator = new Map<Identifier, Identifier>();
  dominator.set(VIRTUAL_ROOT, VIRTUAL_ROOT);

  const intersect = (left: Identifier, right: Identifier): Identifier => {
    let a = left;
    let b = right;
    while (a !== b) {
      while ((index.get(a) ?? 0) > (index.get(b) ?? 0)) a = dominator.get(a) ?? VIRTUAL_ROOT;
      while ((index.get(b) ?? 0) > (index.get(a) ?? 0)) b = dominator.get(b) ?? VIRTUAL_ROOT;
    }
    return a;
  };

  let changed = true;
  while (changed) {
    changed = false;
    for (const id of reversePostOrder) {
      if (id === VIRTUAL_ROOT) continue;
      const incoming = (predecessors.get(id) ?? []).filter((predecessor) => dominator.has(predecessor));
      if (incoming.length === 0) continue;
      let newIdom = incoming[0] as Identifier;
      for (const predecessor of incoming.slice(1)) {
        newIdom = intersect(predecessor, newIdom);
      }
      if (dominator.get(id) !== newIdom) {
        dominator.set(id, newIdom);
        changed = true;
      }
    }
  }

  const retained = new Map<Identifier, number>();
  for (const id of reversePostOrder) {
    if (id === VIRTUAL_ROOT) continue;
    retained.set(id, graph.nodes.get(id)?.shallowBytes ?? 0);
  }
  // Reverse post-order guarantees children are accumulated before their parent.
  for (const id of [...reversePostOrder].reverse()) {
    if (id === VIRTUAL_ROOT) continue;
    const parent = dominator.get(id);
    if (parent === undefined || parent === VIRTUAL_ROOT) continue;
    retained.set(parent, (retained.get(parent) ?? 0) + (retained.get(id) ?? 0));
  }
  for (const id of graph.nodes.keys()) {
    if (!reachability.reachable.has(id)) retained.set(id, graph.nodes.get(id)?.shallowBytes ?? 0);
  }

  return { immediateDominator: dominator, retainedBytes: retained, virtualRoot: VIRTUAL_ROOT };
}

export interface GraphAnalysis {
  readonly reachability: ReachabilityResult;
  readonly dominators: DominatorResult;
}

/**
 * Everything the leak ranking needs, computed once. A session used to derive
 * reachability and dominators twice, which roughly doubled the cost of opening
 * a large dump.
 */
export function analyzeGraph(graph: ObjectGraph): GraphAnalysis {
  const reachability = reachableFromRoots(graph);
  return { reachability, dominators: computeDominators(graph, { reachability }) };
}

export { VIRTUAL_ROOT };
