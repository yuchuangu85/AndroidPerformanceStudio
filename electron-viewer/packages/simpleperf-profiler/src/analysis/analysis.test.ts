import { describe, expect, it } from 'vitest';
import type { NormalizedSample, ProfileExecutionType } from '../model.js';
// Exercises the analysis layer through the simpleperf bridge: the pure units
// live in @aps/profile-analysis, and building real tables needs the models here.
import {
  CallStackTable,
  DEFAULT_CALL_STACK_QUERY,
  applyTransforms,
  buildFlameGraphSnapshot,
  deriveStableId,
  filterCallStacks,
  parseFlameSearchTerms,
  primaryHashStep,
  projectCallTree,
  projectCallTreeResult,
  projectFlameGraphRows,
  rowNormalizedWidthAt,
  secondaryHashStep,
  stableStringHash,
  weightedCallStack,
  type CallStackFrame,
  type FrameImplementation,
  type WeightedCallStack,
} from '@aps/profile-analysis';
import { implementationOf, samplesToCallStackTable, defaultThreadKey } from './table.js';

/** Real symbol tables give one name one id and one address. */
const symbolRegistry = new Map<string, number>();

function symbolIdOf(symbolName: string): number {
  const existing = symbolRegistry.get(symbolName);
  if (existing !== undefined) return existing;
  const allocated = symbolRegistry.size;
  symbolRegistry.set(symbolName, allocated);
  return allocated;
}

/** Builds a sample whose frames are given root to leaf. */
function stackSample(
  rootToLeaf: readonly string[],
  options: {
    readonly threadName?: string;
    readonly threadId?: number;
    readonly timestampNanos?: bigint;
    readonly eventCount?: bigint;
    readonly executionType?: ProfileExecutionType;
  } = {},
): NormalizedSample {
  const executionType = options.executionType ?? 'NATIVE';
  return {
    timestampNanos: options.timestampNanos ?? 0n,
    processId: 1,
    threadId: options.threadId ?? 10,
    threadName: options.threadName ?? 'main',
    eventType: 'cpu-cycles',
    eventCount: options.eventCount ?? 1n,
    frames: [...rootToLeaf].reverse().map((symbolName) => {
      const symbolId = symbolIdOf(symbolName);
      return {
        virtualAddress: BigInt(symbolId + 1),
        fileId: 0,
        symbolId,
        filePath: symbolName.startsWith('kernel:') ? '[kernel.kallsyms]' : '/system/lib64/libapp.so',
        symbolName,
        executionType: symbolName.startsWith('kernel:') ? 'KERNEL' : executionType,
      };
    }),
  };
}

function leafFirstSample(): NormalizedSample {
  return {
    timestampNanos: 5n,
    processId: 1,
    threadId: 7,
    threadName: 'RenderThread',
    eventType: 'cpu-cycles',
    eventCount: 9n,
    // simpleperf lists the innermost frame first.
    frames: [
      {
        virtualAddress: 0x200n,
        fileId: 1,
        symbolId: 11,
        filePath: '/system/lib64/libgui.so',
        symbolName: 'leafFirstDraw',
        executionType: 'NATIVE',
      },
      {
        virtualAddress: 0x100n,
        fileId: 0,
        symbolId: 10,
        filePath: '/system/lib64/libc.so',
        symbolName: 'leafFirstMain',
        executionType: 'NATIVE',
      },
    ],
  };
}

describe('samplesToCallStackTable', () => {
  it('reverses simpleperf leaf-first frames into root-to-leaf stacks', () => {
    const table = samplesToCallStackTable([leafFirstSample()]);
    const stack = table.stacks[0];
    expect(stack?.weight).toBe(9n);
    expect(stack?.threadKey).toBe('RenderThread (tid 7)');
    expect(stack?.frameIdsRootToLeaf.map((frameId) => table.frame(frameId).symbolName)).toEqual([
      'leafFirstMain',
      'leafFirstDraw',
    ]);
    expect(stack?.categoriesRootToLeaf).toEqual([undefined, undefined]);
  });

  it('classifies execution types and keeps unknown frames', () => {
    expect(implementationOf('NATIVE')).toBe<FrameImplementation>('NATIVE');
    expect(implementationOf('ART')).toBe('MANAGED');
    expect(implementationOf('JIT_JVM')).toBe('MANAGED');
    expect(implementationOf('INTERPRETED_JVM')).toBe('MANAGED');
    expect(implementationOf('KERNEL')).toBe('KERNEL');
    expect(implementationOf('UNKNOWN')).toBe('UNKNOWN');
    const table = samplesToCallStackTable([stackSample(['kernel:schedule', 'main'])]);
    const kinds = table.stacks[0]?.frameIdsRootToLeaf.map((frameId) => table.frame(frameId).implementation);
    expect(kinds).toEqual(['KERNEL', 'NATIVE']);
  });

  it('drops samples without frames and honours overrides', () => {
    const empty = stackSample([]);
    const table = samplesToCallStackTable([empty, leafFirstSample()], {
      weightOf: () => 4n,
      threadKeyOf: (sample) => 'tid:' + String(sample.threadId),
    });
    expect(table.stacks).toHaveLength(1);
    expect(table.stacks[0]?.weight).toBe(4n);
    expect(table.stacks[0]?.threadKey).toBe('tid:7');
    expect(defaultThreadKey(leafFirstSample())).toBe('RenderThread (tid 7)');
  });
});

describe('projectCallTree', () => {
  it('accumulates inclusive, self, and per-thread weights', () => {
    const samples = [
      stackSample(['main', 'render', 'draw'], { timestampNanos: 1n, eventCount: 10n }),
      stackSample(['main', 'render', 'measure'], { timestampNanos: 2n, eventCount: 5n }),
      stackSample(['main', 'render'], { threadName: 'worker', threadId: 11, timestampNanos: 3n, eventCount: 7n }),
    ];
    const table = samplesToCallStackTable(samples);
    const nodes = projectCallTree(table);

    const rootIndex = nodes.depths.indexOf(0);
    expect(nodes.depths.every((depth, index) => index === rootIndex || depth > 0)).toBe(true);
    const names = nodes.ids.map((_id, index) => nodes.framesById.get(nodes.frameIds[index] as bigint)?.symbolName);
    expect(names[0]).toBe('main');
    expect(nodes.inclusiveWeights[0]).toBe(22n);
    expect(nodes.sampleCounts[0]).toBe(3n);
    // Two threads reached main: main and worker.
    expect(nodes.threadCounts[0]).toBe(2);

    const renderIndex = names.indexOf('render');
    expect(nodes.inclusiveWeights[renderIndex]).toBe(22n);
    // Only the stack that ended at render contributes self weight.
    expect(nodes.selfWeights[renderIndex]).toBe(7n);
    const drawIndex = names.indexOf('draw');
    expect(nodes.inclusiveWeights[drawIndex]).toBe(10n);
    expect(nodes.selfWeights[drawIndex]).toBe(10n);
    expect(nodes.parentIndexes[drawIndex]).toBe(renderIndex);
    expect(nodes.depths[drawIndex]).toBe(2);
  });

  it('sorts siblings alphabetically and builds the adjacency links', () => {
    const table = samplesToCallStackTable([
      stackSample(['main', 'zeta'], { eventCount: 1n }),
      stackSample(['main', 'alpha'], { eventCount: 1n }),
    ]);
    const nodes = projectCallTree(table);
    const names = nodes.ids.map((_id, index) => nodes.framesById.get(nodes.frameIds[index] as bigint)?.symbolName);
    expect(names).toEqual(['main', 'alpha', 'zeta']);
    expect(nodes.firstChildIndexAt(0)).toBe(1);
    expect(nodes.nextSiblingIndexAt(1)).toBe(2);
    expect(nodes.nextSiblingIndexAt(2)).toBeUndefined();
    expect(nodes.parentIndexAt(0)).toBeUndefined();
    expect(nodes.indexOf(nodes.ids[0] as bigint)).toBe(0);
    expect(nodes.nodeIdAt(0)).toBe(nodes.ids[0]);
  });

  it('inverts the tree when the direction is INVERTED', () => {
    const table = samplesToCallStackTable([
      stackSample(['main', 'render', 'draw'], { eventCount: 3n }),
    ]);
    const inverted = projectCallTree(table, 'INVERTED');
    const names = inverted.ids.map((_id, index) => inverted.framesById.get(inverted.frameIds[index] as bigint)?.symbolName);
    expect(names).toEqual(['draw', 'render', 'main']);
    expect(inverted.inclusiveWeights[0]).toBe(3n);
  });

  it('reports a projection failure instead of throwing, and rejects negative weights', () => {
    const good = samplesToCallStackTable([stackSample(['main'], { eventCount: 1n })]);
    expect(projectCallTreeResult(good).failureDetail).toBeUndefined();

    const firstStack = good.stacks[0] as WeightedCallStack;
    const negative = new CallStackTable(good.framesById, [{ ...firstStack, weight: -1n }]);
    expect(() => projectCallTree(negative)).toThrow(/Negative call-stack weight/);
    const result = projectCallTreeResult(negative);
    expect(result.callNodes.size).toBe(0);
    expect(result.failureDetail).toMatch(/Negative call-stack weight/);
  });
});

describe('projectFlameGraphRows', () => {
  it('lays roots across the full width and children inside their parent', () => {
    const table = samplesToCallStackTable([
      stackSample(['main', 'render'], { eventCount: 3n }),
      stackSample(['main', 'io'], { eventCount: 1n }),
    ]);
    const nodes = projectCallTree(table);
    const rows = projectFlameGraphRows(nodes);
    expect(rows.startsAtBottom).toBe(true);
    expect(rows.rowCount).toBe(2);
    expect(rows.starts[0]).toBe(0);
    expect(rows.ends[0]).toBe(1);
    // Siblings are ordered alphabetically, so io sits left of render.
    const symbolAt = (index: number): string | undefined =>
      nodes.framesById.get(nodes.frameIds[index] as bigint)?.symbolName;
    const row = rows.nodeIndexesByRow[1] ?? [];
    expect(row.map(symbolAt)).toEqual(['io', 'render']);
    const ioIndex = row[0] as number;
    const renderIndex = row[1] as number;
    expect(rowNormalizedWidthAt(rows, ioIndex)).toBeCloseTo(0.25, 6);
    expect(rowNormalizedWidthAt(rows, renderIndex)).toBeCloseTo(0.75, 6);
    expect(rows.starts[ioIndex]).toBe(0);
    expect(rows.ends[renderIndex]).toBe(1);
    expect(rows.starts[renderIndex]).toBeCloseTo(0.25, 6);
    expect(rows.nodeIndexesByRow.flat()).toHaveLength(3);
  });

  it('gives a self-time node the whole parent span and never overflows it', () => {
    // render has 10 units, its child only 2: the remaining 8 is self time.
    const table = samplesToCallStackTable([
      stackSample(['main', 'render', 'draw'], { eventCount: 2n }),
      stackSample(['main', 'render'], { eventCount: 8n }),
    ]);
    const rows = projectFlameGraphRows(projectCallTree(table));
    const drawIndex = rows.nodeIndexesByRow[2]?.[0] as number;
    const renderIndex = rows.nodeIndexesByRow[1]?.[0] as number;
    // The child covers only the child weight, not the parent's self time.
    expect(rowNormalizedWidthAt(rows, drawIndex)).toBeCloseTo(0.2, 6);
    expect(rows.starts[drawIndex]).toBe(rows.starts[renderIndex]);
    expect(rows.ends[drawIndex]).toBeLessThanOrEqual(rows.ends[renderIndex] as number);
  });

  it('marks inverted layouts as starting at the top', () => {
    const table = samplesToCallStackTable([stackSample(['main', 'draw'], { eventCount: 1n })]);
    expect(projectFlameGraphRows(projectCallTree(table, 'INVERTED'), 'INVERTED').startsAtBottom).toBe(false);
  });
});

describe('filterCallStacks', () => {
  const samples = [
    stackSample(['main', 'render', 'drawSkia'], { timestampNanos: 100n, eventCount: 1n }),
    stackSample(['main', 'io', 'readFile'], { timestampNanos: 200n, eventCount: 1n }),
  ];

  it('applies the preview range and ANDed search terms', () => {
    const table = samplesToCallStackTable(samples);
    const ranged = filterCallStacks(table, {
      ...DEFAULT_CALL_STACK_QUERY,
      previewRange: { startNanosInclusive: 150n, endNanosExclusive: 300n },
    });
    expect(ranged.afterPreviewCount).toBe(1);
    expect(ranged.afterSearchCount).toBe(1);

    const searched = filterCallStacks(table, { ...DEFAULT_CALL_STACK_QUERY, searchText: ' render , skia ' });
    expect(searched.afterSearchCount).toBe(1);
    const impossible = filterCallStacks(table, { ...DEFAULT_CALL_STACK_QUERY, searchText: 'render,readFile' });
    expect(impossible.afterSearchCount).toBe(0);
    const byResource = filterCallStacks(table, { ...DEFAULT_CALL_STACK_QUERY, searchText: 'libapp.so' });
    expect(byResource.afterSearchCount).toBe(2);
    expect(parseFlameSearchTerms(' a, ,b ')).toEqual(['a', 'b']);
  });

  it('trims frames that do not match the implementation filter', () => {
    const mixed: NormalizedSample = {
      ...stackSample(['main', 'draw']),
      frames: [
        { virtualAddress: 2n, fileId: 0, symbolId: 1, filePath: '/system/lib64/libgui.so', symbolName: 'draw', executionType: 'NATIVE' },
        { virtualAddress: 1n, fileId: 0, symbolId: 0, filePath: '/apex/libart.so', symbolName: 'main', executionType: 'ART' },
      ],
    };
    const table = samplesToCallStackTable([mixed]);
    const scriptOnly = filterCallStacks(table, { ...DEFAULT_CALL_STACK_QUERY, implementation: 'SCRIPT' });
    expect(scriptOnly.afterImplementationCount).toBe(1);
    const stack = scriptOnly.table.stacks[0];
    expect(stack?.frameIdsRootToLeaf.map((frameId) => scriptOnly.table.frame(frameId).symbolName)).toEqual(['main']);

    const nativeOnly = filterCallStacks(table, { ...DEFAULT_CALL_STACK_QUERY, implementation: 'NATIVE' });
    expect(nativeOnly.table.stacks[0]?.frameIdsRootToLeaf.map((frameId) => nativeOnly.table.frame(frameId).symbolName)).toEqual([
      'draw',
    ]);

    const none = filterCallStacks(
      table,
      { ...DEFAULT_CALL_STACK_QUERY, implementation: 'SCRIPT', searchText: 'readFile' },
    );
    expect(none.afterImplementationCount).toBe(0);
  });
});

describe('applyTransforms', () => {
  function demoTable(): CallStackTable {
    return samplesToCallStackTable([
      stackSample(['main', 'render', 'draw', 'draw'], { eventCount: 4n }),
      stackSample(['main', 'io', 'read'], { eventCount: 2n }),
    ]);
  }

  function symbolsOf(table: CallStackTable): string[] {
    return table.stacks.map((stack) =>
      stack.frameIdsRootToLeaf.map((frameId) => table.frame(frameId).symbolName).join('>'),
    );
  }

  it('focuses a call node and drops the stacks that do not contain it', () => {
    const table = demoTable();
    const main = table.frame(table.stacks[0]?.frameIdsRootToLeaf[0] as bigint).functionId;
    const render = table.frame(table.stacks[0]?.frameIdsRootToLeaf[1] as bigint).functionId;
    const result = applyTransforms(table, [{ kind: 'FOCUS_CALL_NODE', path: [main, render] }]);
    expect(result.outputStackCount).toBe(1);
    expect(symbolsOf(result.table)).toEqual(['render>draw>draw']);
    expect(result.invalidTransforms).toEqual([]);
  });

  it('reports an unknown call-node path as invalid instead of applying it', () => {
    const table = demoTable();
    const result = applyTransforms(table, [{ kind: 'FOCUS_CALL_NODE', path: [999n] }]);
    expect(result.appliedTransforms).toEqual([]);
    expect(result.invalidTransforms).toHaveLength(1);
    expect(result.outputStackCount).toBe(2);
  });

  it('drops, merges, and focuses functions', () => {
    const table = demoTable();
    const read = table.frame(table.stacks[1]?.frameIdsRootToLeaf[2] as bigint).functionId;
    expect(symbolsOf(applyTransforms(table, [{ kind: 'DROP_FUNCTION', function: read }]).table)).toEqual([
      'main>render>draw>draw',
    ]);
    expect(symbolsOf(applyTransforms(table, [{ kind: 'MERGE_FUNCTION', function: read }]).table)).toEqual([
      'main>render>draw>draw',
      'main>io',
    ]);
    expect(symbolsOf(applyTransforms(table, [{ kind: 'FOCUS_FUNCTION', function: read }]).table)).toEqual(['read']);
    // Focusing the leaf function keeps only the stacks whose leaf it is.
    expect(symbolsOf(applyTransforms(table, [{ kind: 'FOCUS_FUNCTION_SELF', function: read }]).table)).toEqual(['read']);
  });

  it('collapses recursion, direct recursion, and subtrees', () => {
    const table = demoTable();
    const draw = table.frame(table.stacks[0]?.frameIdsRootToLeaf[2] as bigint).functionId;
    expect(symbolsOf(applyTransforms(table, [{ kind: 'COLLAPSE_DIRECT_RECURSION', function: draw }]).table)).toEqual([
      'main>render>draw',
      'main>io>read',
    ]);
    expect(symbolsOf(applyTransforms(table, [{ kind: 'COLLAPSE_RECURSION', function: draw }]).table)).toEqual([
      'main>render>draw',
      'main>io>read',
    ]);
    // The subtree keeps the first occurrence of the function itself.
    expect(symbolsOf(applyTransforms(table, [{ kind: 'COLLAPSE_FUNCTION_SUBTREE', function: draw }]).table)).toEqual([
      'main>render>draw',
      'main>io>read',
    ]);
  });

  it('collapses consecutive frames of one resource into a pseudo function', () => {
    const frames = new Map<bigint, CallStackFrame>();
    const frame = (id: bigint, symbolName: string, resource: string): void => {
      frames.set(id, { frameId: id, functionId: id, symbolName, resource, virtualAddress: id, implementation: 'NATIVE' });
    };
    frame(1n, 'main', '/system/lib64/libapp.so');
    frame(2n, 'render', '/system/lib64/libgui.so');
    frame(3n, 'draw', '/system/lib64/libgui.so');
    const table = new CallStackTable(frames, [
      weightedCallStack({
        sampleId: 1n,
        timestampNanos: 0n,
        weight: 3n,
        threadKey: 'main',
        frameIdsRootToLeaf: [1n, 2n, 3n],
      }),
    ]);

    const collapsed = applyTransforms(table, [
      { kind: 'COLLAPSE_RESOURCE', resource: '/system/lib64/libgui.so' },
    ]).table;
    // The two libgui frames merge; main is untouched.
    expect(symbolsOf(collapsed)).toEqual(['main>draw']);
    const collapsedFrames = [...collapsed.framesById.values()];
    expect(collapsedFrames.map((entry) => entry.collapsedResource)).toEqual([
      undefined,
      '/system/lib64/libgui.so',
      '/system/lib64/libgui.so',
    ]);
    // Both libgui frames are remapped onto one pseudo function.
    const collapsedFunctions = new Set(
      collapsedFrames
        .filter((entry) => entry.collapsedResource !== undefined)
        .map((entry) => entry.functionId),
    );
    expect(collapsedFunctions.size).toBe(1);
    expect(collapsedFunctions.has(1n)).toBe(false);
    expect(symbolsOf(applyTransforms(table, [{ kind: 'COLLAPSE_RESOURCE', resource: 'missing.so' }]).table)).toEqual([
      'main>render>draw',
    ]);
    const untouched = applyTransforms(table, [{ kind: 'COLLAPSE_RESOURCE', resource: 'missing.so' }]);
    expect(untouched.table).toBe(table);
  });

  it('filters frames by category', () => {
    const frames = new Map<bigint, CallStackFrame>([
      [1n, { frameId: 1n, functionId: 1n, symbolName: 'a', resource: 'r', virtualAddress: 1n, implementation: 'NATIVE' }],
      [2n, { frameId: 2n, functionId: 2n, symbolName: 'b', resource: 'r', virtualAddress: 2n, implementation: 'NATIVE' }],
    ]);
    const table = new CallStackTable(frames, [
      weightedCallStack({
        sampleId: 1n,
        timestampNanos: 0n,
        weight: 1n,
        threadKey: 'main',
        category: 'render',
        frameIdsRootToLeaf: [1n, 2n],
        categoriesRootToLeaf: ['render', 'io'],
      }),
    ]);
    const result = applyTransforms(table, [{ kind: 'FOCUS_CATEGORY', category: 'io' }]);
    expect(symbolsOf(result.table)).toEqual(['b']);
  });
});

describe('stable call node ids', () => {
  it('is deterministic and distinguishes sibling order', () => {
    const root = primaryHashStep(-3750763034362895579n, 1n);
    const child = primaryHashStep(root, 2n);
    expect(child).toBe(primaryHashStep(root, 2n));
    const secondary = secondaryHashStep(primaryHashStep(-3750763034362895579n, 1n), 2n);
    const idA = deriveStableId(child, secondary);
    const idB = deriveStableId(child, secondaryHashStep(primaryHashStep(-3750763034362895579n, 2n), 1n));
    expect(idA).not.toBe(idB);
    expect(stableStringHash('collapsed-resource:x')).toBe(stableStringHash('collapsed-resource:x'));
    expect(stableStringHash('a')).not.toBe(stableStringHash('b'));
  });
});

describe('buildFlameGraphSnapshot', () => {
  const samples = [
    stackSample(['main', 'render', 'draw'], { timestampNanos: 10n, eventCount: 4n }),
    stackSample(['main', 'io', 'read'], { timestampNanos: 20n, eventCount: 2n }),
  ];

  it('projects the full pipeline and counts every stage', () => {
    const table = samplesToCallStackTable(samples);
    const snapshot = buildFlameGraphSnapshot(table, DEFAULT_CALL_STACK_QUERY);
    expect(snapshot.emptyReason).toBeUndefined();
    expect(snapshot.totalWeight).toBe(6n);
    expect(snapshot.callNodes.size).toBe(5);
    expect(snapshot.stageCounts.afterSearchCount).toBe(2);
    expect(snapshot.stageCounts.projectedNodeCount).toBe(5);
    expect(snapshot.rows.rowCount).toBe(3);
  });

  it('explains an empty graph by the stage that emptied it', () => {
    const table = samplesToCallStackTable(samples);
    const filtered = buildFlameGraphSnapshot(table, {
      ...DEFAULT_CALL_STACK_QUERY,
      searchText: 'nothingMatchesThis',
    });
    expect(filtered.emptyReason).toBe('SEARCH_FILTERED_ALL');
    expect(filtered.totalWeight).toBe(0n);

    const ranged = buildFlameGraphSnapshot(
      table,
      { ...DEFAULT_CALL_STACK_QUERY, previewRange: { startNanosInclusive: 100n, endNanosExclusive: 200n } },
      { committedRangeExcludedSamples: false },
    );
    expect(ranged.emptyReason).toBe('PREVIEW_RANGE_EMPTY');

    const noThread = buildFlameGraphSnapshot(table, DEFAULT_CALL_STACK_QUERY, {
      selectedThreadHasNoSamples: true,
    });
    expect(noThread.emptyReason).toBe('THREAD_HAS_NO_SAMPLES');
  });

  it('applies transforms and reports the ones it rejected', () => {
    const table = samplesToCallStackTable(samples);
    const snapshot = buildFlameGraphSnapshot(table, {
      ...DEFAULT_CALL_STACK_QUERY,
      transforms: [
        { kind: 'FOCUS_CALL_NODE', path: [table.frame(table.stacks[0]?.frameIdsRootToLeaf[0] as bigint).functionId] },
        { kind: 'FOCUS_CALL_NODE', path: [1234n] },
      ],
    });
    expect(snapshot.invalidTransforms).toHaveLength(1);
    expect(snapshot.stageCounts.afterTransformCount).toBe(2);
    expect(snapshot.totalWeight).toBe(6n);
  });
});
