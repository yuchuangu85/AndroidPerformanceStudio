import type { ObjectGraph } from './graph.js';
import type { Identifier } from './hprof.js';
import { reachableFromRoots, type DominatorResult } from './dominators.js';

const VIRTUAL_ROOT: Identifier = -1n;

/**
 * The iterative Cooper-Harvey-Kennedy fixpoint that computeDominators replaced.
 *
 * Kept only so the property test can check the Semi-NCA implementation against
 * it on the same graphs. It is deliberately not used in production: on heaps
 * whose reference chains wrap into long cycles its `intersect` walks 80x further
 * than on the same graph without the cycles.
 */
export function computeDominatorsReference(
  graph: ObjectGraph,
  options: { readonly reachability?: ReturnType<typeof reachableFromRoots> } = {},
): DominatorResult {
  const reachability = options.reachability ?? reachableFromRoots(graph);
  const order: Identifier[] = [];
  const visited = new Set<Identifier>();
  const stack: { id: Identifier; expanded: boolean }[] = [{ id: VIRTUAL_ROOT, expanded: false }];
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
