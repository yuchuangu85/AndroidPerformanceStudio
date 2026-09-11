import { describe, expect, it } from 'vitest';
import { computeDominators } from './dominators.js';
import { computeDominatorsReference } from './dominators-reference.js';
import type { GraphNode, ObjectGraph } from './graph.js';

/** Deterministic PRNG so a failure can be reproduced from the seed. */
function random(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
    return state / 0x1_0000_0000;
  };
}

function randomGraph(seed: number, nodeCount: number, edgeChance: number): ObjectGraph {
  const next = random(seed);
  const nodes = new Map<bigint, GraphNode>();
  const roots: bigint[] = [];
  for (let index = 0; index < nodeCount; index += 1) {
    nodes.set(BigInt(index), {
      objectId: BigInt(index),
      kind: 'instance',
      className: 'Node',
      shallowBytes: 8 + (index % 5) * 8,
      references: [],
      isRoot: false,
    });
  }
  // Forward edges keep most nodes reachable; a few back edges create cycles.
  for (let index = 0; index < nodeCount; index += 1) {
    const node = nodes.get(BigInt(index)) as GraphNode;
    const references: bigint[] = [];
    for (let target = index + 1; target < nodeCount; target += 1) {
      if (target === index + 1 || next() < edgeChance) references.push(BigInt(target));
    }
    if (index > 3 && next() < 0.1) references.push(BigInt(Math.floor(next() * index)));
    nodes.set(BigInt(index), { ...node, references });
  }
  // A few roots, and some nodes deliberately left unreachable.
  for (let index = 0; index < nodeCount; index += 7) {
    if (next() < 0.5) {
      roots.push(BigInt(index));
      nodes.set(BigInt(index), { ...(nodes.get(BigInt(index)) as GraphNode), isRoot: true });
    }
  }
  if (roots.length === 0) {
    roots.push(0n);
    nodes.set(0n, { ...(nodes.get(0n) as GraphNode), isRoot: true });
  }
  return { nodes, roots, danglingRoots: [], warnings: [] };
}

describe('dominators', () => {
  it('agrees with the iterative reference on random graphs', () => {
    for (let seed = 1; seed <= 40; seed += 1) {
      const graph = randomGraph(seed, 40 + (seed % 20), 0.05);
      const actual = computeDominators(graph);
      const expected = computeDominatorsReference(graph);
      const actualIdom = [...actual.immediateDominator.entries()].sort((left, right) =>
        left[0] < right[0] ? -1 : left[0] > right[0] ? 1 : 0,
      );
      const expectedIdom = [...expected.immediateDominator.entries()].sort((left, right) =>
        left[0] < right[0] ? -1 : left[0] > right[0] ? 1 : 0,
      );
      expect(actualIdom, 'seed ' + seed + ' idom').toEqual(expectedIdom);

      const keys = [...new Set([...actual.retainedBytes.keys(), ...expected.retainedBytes.keys()])].sort((left, right) =>
        left < right ? -1 : left > right ? 1 : 0,
      );
      const actualRetained = keys.map((key) => actual.retainedBytes.get(key) ?? 0);
      const expectedRetained = keys.map((key) => expected.retainedBytes.get(key) ?? 0);
      expect(actualRetained, 'seed ' + seed + ' retained').toEqual(expectedRetained);
    }
  });

  it('agrees with the reference on chains, cycles, and diamonds', () => {
    const shapes: ObjectGraph[] = [];
    const chain = (size: number, wrap: boolean): ObjectGraph => {
      const nodes = new Map<bigint, GraphNode>();
      for (let index = 0; index < size; index += 1) {
        const references: bigint[] = [];
        if (index + 1 < size) references.push(BigInt(index + 1));
        else if (wrap) references.push(0n);
        nodes.set(BigInt(index), { objectId: BigInt(index), kind: 'instance', className: 'Node', shallowBytes: 16, references, isRoot: index === 0 });
      }
      return { nodes, roots: [0n], danglingRoots: [], warnings: [] };
    };
    shapes.push(chain(64, false), chain(64, true));

    const diamond = new Map<bigint, GraphNode>();
    diamond.set(0n, { objectId: 0n, kind: 'instance', className: 'Node', shallowBytes: 16, references: [1n, 2n], isRoot: true });
    diamond.set(1n, { objectId: 1n, kind: 'instance', className: 'Node', shallowBytes: 16, references: [3n], isRoot: false });
    diamond.set(2n, { objectId: 2n, kind: 'instance', className: 'Node', shallowBytes: 16, references: [3n], isRoot: false });
    diamond.set(3n, { objectId: 3n, kind: 'instance', className: 'Node', shallowBytes: 16, references: [3n], isRoot: false });
    diamond.set(4n, { objectId: 4n, kind: 'instance', className: 'Node', shallowBytes: 16, references: [], isRoot: false });
    shapes.push({ nodes: diamond, roots: [0n], danglingRoots: [], warnings: [] });

    const sorted = (entries: Iterable<[bigint, bigint]>): [bigint, bigint][] =>
      [...entries].sort((left, right) => (left[0] < right[0] ? -1 : left[0] > right[0] ? 1 : 0));
    for (const graph of shapes) {
      // Only the mapping matters; the two implementations insert in different
      // orders (depth first numbering versus reverse postorder).
      expect(sorted(computeDominators(graph).immediateDominator.entries())).toEqual(
        sorted(computeDominatorsReference(graph).immediateDominator.entries()),
      );
    }
  });
});
