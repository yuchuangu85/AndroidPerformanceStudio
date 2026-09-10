import { computeDominators, reachableFromRoots } from './dominators.js';
import type { ObjectGraph } from './graph.js';
import type { Identifier } from './hprof.js';

export interface LeakSuspect {
  readonly className: string;
  readonly objectId: Identifier;
  readonly retainedBytes: number;
  readonly shallowBytes: number;
  /** Shortest reference chain from a GC root to the suspect, root first. */
  readonly referenceChain: readonly Identifier[];
}

export interface LeakSuspicionReport {
  readonly suspects: readonly LeakSuspect[];
  readonly totalRetainedBytes: number;
}

/** Shortest path from any root to the target, used as the evidence chain. */
export function referenceChainTo(graph: ObjectGraph, target: Identifier): Identifier[] | undefined {
  if (graph.roots.includes(target)) return [target];
  const previous = new Map<Identifier, Identifier>();
  const visited = new Set<Identifier>(graph.roots);
  let frontier: Identifier[] = [...graph.roots];
  while (frontier.length > 0) {
    const next: Identifier[] = [];
    for (const id of frontier) {
      const node = graph.nodes.get(id);
      if (node === undefined) continue;
      for (const reference of node.references) {
        if (visited.has(reference)) continue;
        visited.add(reference);
        previous.set(reference, id);
        if (reference === target) {
          const chain: Identifier[] = [target];
          let cursor = id;
          while (true) {
            chain.unshift(cursor);
            const parent = previous.get(cursor);
            if (parent === undefined) break;
            cursor = parent;
          }
          return chain;
        }
        next.push(reference);
      }
    }
    frontier = next;
  }
  return undefined;
}

/**
 * Ranks reachable objects by retained size. A suspect is a hypothesis with an
 * evidence chain, not a confirmed leak (CONTEXT.md: 泄漏嫌疑人).
 */
export function findLeakSuspects(graph: ObjectGraph, options: { readonly top?: number } = {}): LeakSuspicionReport {
  const top = options.top ?? 10;
  const { retainedBytes } = computeDominators(graph);
  const reachability = reachableFromRoots(graph);
  const candidates = [...graph.nodes.values()]
    // An unreachable object is collected garbage, not a leak suspect; only
    // objects still held from a GC root can be one.
    .filter((node) => !node.isRoot && reachability.reachable.has(node.objectId))
    .map((node) => ({
      node,
      retained: retainedBytes.get(node.objectId) ?? node.shallowBytes,
    }))
    .sort((left, right) => right.retained - left.retained)
    .slice(0, top);

  const suspects: LeakSuspect[] = [];
  for (const candidate of candidates) {
    const chain = referenceChainTo(graph, candidate.node.objectId);
    if (chain === undefined) continue;
    suspects.push({
      className: candidate.node.className,
      objectId: candidate.node.objectId,
      retainedBytes: candidate.retained,
      shallowBytes: candidate.node.shallowBytes,
      referenceChain: chain,
    });
  }
  const totalRetainedBytes = [...retainedBytes.values()].reduce((total, value) => total + value, 0);
  return { suspects, totalRetainedBytes };
}
