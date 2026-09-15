import { describe, expect, it } from 'vitest';
import { createReferenceChainFinder } from './deep-analysis.js';
import { analyzeGraph } from './dominators.js';
import { buildObjectGraph } from './graph.js';
import type { HprofClassRecord, HprofInstanceRecord, HprofParseResult, Identifier } from './hprof.js';

const header = { version: '1.0.3', identifierSize: 4 as const, timestampMillis: 0, headerBytes: 24 };

function heapClass(objectId: Identifier, nameId: Identifier, superClassId: Identifier = 0n): HprofClassRecord {
  return {
    objectId,
    nameId,
    superClassId,
    instanceFieldBytes: 0,
    instanceFields: [],
    staticFieldCount: 0,
    staticReferences: [],
  };
}

function instance(
  objectId: Identifier,
  classObjectId: Identifier,
  references: readonly Identifier[] = [],
  referenceNameIds: readonly Identifier[] = [],
): HprofInstanceRecord {
  return {
    objectId,
    classObjectId,
    fieldBytes: 0,
    shallowBytes: 10,
    references,
    referenceNameIds,
    primitiveNameIds: [],
    primitiveValues: [],
  };
}

function weakReferenceFixture(): HprofParseResult {
  return {
    header,
    strings: new Map([
      [1n, 'java.lang.ref.Reference'],
      [2n, 'com.example.CustomWeakReference'],
      [3n, 'java.lang.ref.WeakReference'],
      [4n, 'android.graphics.Bitmap'],
      [5n, 'com.example.Target'],
      [6n, 'com.example.Queue'],
      [7n, 'referent'],
      [8n, 'queue'],
    ]),
    classes: new Map([
      [100n, heapClass(100n, 1n)],
      [101n, heapClass(101n, 2n, 100n)],
      [102n, heapClass(102n, 3n)],
      [103n, heapClass(103n, 4n)],
      [104n, heapClass(104n, 5n)],
      [105n, heapClass(105n, 6n)],
    ]),
    instances: [
      instance(1n, 102n, [3n, 5n], [7n, 8n]),
      instance(2n, 101n, [4n], [7n]),
      instance(3n, 103n),
      instance(4n, 104n),
      instance(5n, 105n),
    ],
    arrays: [],
    roots: [1n, 2n],
    heapByObjectId: new Map(),
    warnings: [],
  };
}

describe('Kotlin-derived deep-analysis weak-reference behavior', () => {
  it('excludes referents from direct and inherited java.lang.ref.Reference holders', () => {
    const result = weakReferenceFixture();
    const graph = buildObjectGraph(result);
    const analysis = analyzeGraph(graph);
    const finder = createReferenceChainFinder(result, graph);

    // The non-referent queue edge stays strong; only `referent` must disappear.
    expect(graph.nodes.get(1n)?.references).toEqual([5n]);
    expect(graph.nodes.get(2n)?.references).toEqual([]);
    expect(analysis.reachability.reachable).toEqual(new Set([1n, 2n, 5n]));
    expect(finder.depthOf(3n)).toBeUndefined();
    expect(finder.chainTo(4n)).toEqual([]);
  });
});
