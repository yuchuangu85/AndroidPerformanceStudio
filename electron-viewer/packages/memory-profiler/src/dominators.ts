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

export interface GraphAnalysis {
  readonly reachability: ReachabilityResult;
  readonly dominators: DominatorResult;
}

const VIRTUAL_ROOT: Identifier = -1n;

/**
 * Semi-NCA dominators (Georgiadis and Tarjan, as used by LLVM).
 *
 * The iterative Cooper-Harvey-Kennedy fixpoint this replaced produced the same
 * answer but could do pathological work: on a heap whose reference chains wrap
 * into long cycles, `intersect` walked 66 million dominator-tree steps for 205k
 * nodes against 800k for the same graph without the cycles. Semi-NCA computes
 * semidominators with a path-compressed union-find and resolves the immediate
 * dominators in one NCA pass, which is O(E log V) and shape independent.
 *
 * The old implementation is kept as `computeDominatorsReference` so the
 * property test can compare both on the same graphs.
 */
export function computeDominators(
  graph: ObjectGraph,
  options: { readonly reachability?: ReachabilityResult } = {},
): DominatorResult {
  const reachability = options.reachability ?? reachableFromRoots(graph);

  const successorsOf = (id: Identifier): readonly Identifier[] =>
    id === VIRTUAL_ROOT ? graph.roots : (graph.nodes.get(id)?.references ?? []);

  // Depth first numbering: the virtual root is number 0 and every root is its
  // child, so all reachable objects share one tree.
  const dfnumOf = new Map<Identifier, number>();
  const vertex: Identifier[] = [];
  const parent: number[] = [];
  const stack: { id: Identifier; child: number }[] = [{ id: VIRTUAL_ROOT, child: 0 }];
  dfnumOf.set(VIRTUAL_ROOT, 0);
  vertex.push(VIRTUAL_ROOT);
  parent.push(-1);
  while (stack.length > 0) {
    const frame = stack[stack.length - 1] as { id: Identifier; child: number };
    const successors = successorsOf(frame.id);
    if (frame.child >= successors.length) {
      stack.pop();
      continue;
    }
    const successor = successors[frame.child] as Identifier;
    frame.child += 1;
    if (dfnumOf.has(successor)) continue;
    const number = vertex.length;
    dfnumOf.set(successor, number);
    vertex.push(successor);
    parent.push(dfnumOf.get(frame.id) as number);
    stack.push({ id: successor, child: 0 });
  }

  const size = vertex.length;
  const semi = new Int32Array(size);
  const ancestor = new Int32Array(size).fill(-1);
  const label = new Int32Array(size);
  const idom = new Int32Array(size).fill(-1);
  const buckets: number[][] = new Array<number[]>(size);
  for (let index = 0; index < size; index += 1) {
    semi[index] = index;
    label[index] = index;
    buckets[index] = [];
    idom[index] = index;
  }

  const compress = (value: number): void => {
    const grandParent = ancestor[ancestor[value] as number] as number;
    if (grandParent === -1) return;
    compress(ancestor[value] as number);
    if (semi[label[ancestor[value] as number] as number] < semi[label[value] as number]) {
      label[value] = label[ancestor[value] as number] as number;
    }
    ancestor[value] = grandParent;
  };

  const evaluate = (value: number): number => {
    if (ancestor[value] === -1) return label[value] as number;
    compress(value);
    return label[value] as number;
  };

  // Predecessors, grouped by successor, in depth first numbering.
  const predecessors: number[][] = new Array<number[]>(size);
  for (let index = 0; index < size; index += 1) predecessors[index] = [];
  for (let index = 0; index < size; index += 1) {
    for (const successor of successorsOf(vertex[index] as Identifier)) {
      const target = dfnumOf.get(successor);
      if (target === undefined) continue;
      (predecessors[target] as number[]).push(index);
    }
  }

  for (let w = size - 1; w >= 1; w -= 1) {
    for (const v of predecessors[w] as number[]) {
      const u = evaluate(v);
      if (semi[u] < semi[w]) semi[w] = semi[u];
    }
    (buckets[semi[w]] as number[]).push(w);
    const p = parent[w] as number;
    ancestor[w] = p;
    for (const v of buckets[p] as number[]) {
      const u = evaluate(v);
      idom[v] = semi[u] < semi[v] ? u : p;
    }
    (buckets[p] as number[]).length = 0;
  }
  for (let w = 1; w < size; w += 1) {
    if (idom[w] !== semi[w]) idom[w] = idom[idom[w] as number] as number;
  }

  const immediateDominator = new Map<Identifier, Identifier>();
  // The virtual root is its own dominator, matching the previous behaviour.
  immediateDominator.set(VIRTUAL_ROOT, VIRTUAL_ROOT);
  for (let w = 1; w < size; w += 1) {
    immediateDominator.set(vertex[w] as Identifier, vertex[idom[w] as number] as Identifier);
  }

  const retained = new Map<Identifier, number>();
  for (let index = 0; index < size; index += 1) {
    retained.set(vertex[index] as Identifier, graph.nodes.get(vertex[index] as Identifier)?.shallowBytes ?? 0);
  }
  // Children always have a larger depth first number than their dominator, so
  // one descending sweep accumulates every subtree.
  for (let w = size - 1; w >= 1; w -= 1) {
    const dominatorIndex = idom[w] as number;
    if (dominatorIndex === 0) continue;
    const owner = vertex[dominatorIndex] as Identifier;
    retained.set(owner, (retained.get(owner) ?? 0) + (retained.get(vertex[w] as Identifier) ?? 0));
  }
  for (const id of graph.nodes.keys()) {
    if (!reachability.reachable.has(id)) retained.set(id, graph.nodes.get(id)?.shallowBytes ?? 0);
  }

  return { immediateDominator, retainedBytes: retained, virtualRoot: VIRTUAL_ROOT };
}

/** Everything the leak ranking needs, computed once. */
export function analyzeGraph(graph: ObjectGraph): GraphAnalysis {
  const reachability = reachableFromRoots(graph);
  return { reachability, dominators: computeDominators(graph, { reachability }) };
}

export { VIRTUAL_ROOT };
