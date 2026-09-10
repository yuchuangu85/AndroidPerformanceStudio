import { describe, expect, it } from 'vitest';
import {
  CallStackTable,
  DEFAULT_CALL_STACK_QUERY,
  applyTransforms,
  buildFlameGraphSnapshot,
  filterCallStacks,
  parseFlameSearchTerms,
  projectCallTree,
  projectFlameGraphRows,
  weightedCallStack,
  type CallStackFrame,
  type FrameImplementation,
} from './index.js';

const symbolIds = new Map<string, bigint>();

function idOf(symbolName: string): bigint {
  const existing = symbolIds.get(symbolName);
  if (existing !== undefined) return existing;
  const allocated = BigInt(symbolIds.size + 1);
  symbolIds.set(symbolName, allocated);
  return allocated;
}

interface StackSpec {
  readonly frames: readonly string[];
  readonly weight: bigint;
  readonly threadKey?: string;
  readonly resource?: string;
  readonly implementation?: FrameImplementation;
}

/** Builds a table directly, without going through any parser. */
function tableOf(stacks: readonly StackSpec[]): CallStackTable {
  const framesById = new Map<bigint, CallStackFrame>();
  const built = stacks.map((stack, index) => {
    const frameIds = stack.frames.map((symbolName) => {
      const frameId = idOf(symbolName);
      if (!framesById.has(frameId)) {
        framesById.set(frameId, {
          frameId,
          functionId: frameId,
          symbolName,
          resource: stack.resource ?? '/system/lib64/libapp.so',
          virtualAddress: frameId,
          implementation: stack.implementation ?? 'NATIVE',
        });
      }
      return frameId;
    });
    return weightedCallStack({
      sampleId: BigInt(index + 1),
      timestampNanos: BigInt((index + 1) * 100),
      weight: stack.weight,
      threadKey: stack.threadKey ?? 'main (tid 1)',
      frameIdsRootToLeaf: frameIds,
    });
  });
  return new CallStackTable(framesById, built);
}

function symbolsOf(table: CallStackTable): string[] {
  return table.stacks.map((stack) =>
    stack.frameIdsRootToLeaf.map((frameId) => table.frame(frameId).symbolName).join('>'),
  );
}

function demo(): CallStackTable {
  return tableOf([
    { frames: ['main', 'render', 'draw'], weight: 6n },
    { frames: ['main', 'io'], weight: 4n },
  ]);
}

describe('call stack table', () => {
  it('resolves functions and paths', () => {
    const table = demo();
    const main = table.frame(table.stacks[0]?.frameIdsRootToLeaf[0] as bigint).functionId;
    const render = table.frame(table.stacks[0]?.frameIdsRootToLeaf[1] as bigint).functionId;
    expect(table.functions(table.stacks[0] as never)).toEqual([main, render, table.frame(table.stacks[0]?.frameIdsRootToLeaf[2] as bigint).functionId]);
    expect(table.containsPath([main, render])).toBe(true);
    expect(table.containsPath([render, main])).toBe(false);
    expect(table.containsPath([])).toBe(false);
    expect(() => table.frame(999n)).toThrow(/Unknown frame id/);
    expect(table.withStacks(table.stacks)).toBe(table);
  });

  it('validates category alignment', () => {
    expect(() =>
      weightedCallStack({
        sampleId: 1n,
        timestampNanos: 0n,
        weight: 1n,
        threadKey: 'main',
        frameIdsRootToLeaf: [1n, 2n],
        categoriesRootToLeaf: ['only-one'],
      }),
    ).toThrow(/must align/);
    const stack = weightedCallStack({
      sampleId: 1n,
      timestampNanos: 0n,
      weight: 1n,
      threadKey: 'main',
      category: 'render',
      frameIdsRootToLeaf: [1n, 2n],
    });
    expect(stack.categoriesRootToLeaf).toEqual(['render', 'render']);
  });
});

describe('call tree and rows', () => {
  it('accumulates weights and orders siblings alphabetically', () => {
    const nodes = projectCallTree(demo());
    const names = nodes.ids.map((_id, index) => nodes.framesById.get(nodes.frameIds[index] as bigint)?.symbolName);
    // Depth first, siblings alphabetical: io before render, then render's child.
    expect(names).toEqual(['main', 'io', 'render', 'draw']);
    expect(nodes.inclusiveWeights[0]).toBe(10n);
    expect(nodes.depths).toEqual([0, 1, 1, 2]);
    // Only stacks that end at a node contribute self weight: main is never a leaf.
    expect(nodes.selfWeights[0]).toBe(0n);
    expect(nodes.selfWeights[1]).toBe(4n);
    const rows = projectFlameGraphRows(nodes);
    expect(rows.rowCount).toBe(3);
    expect(rows.startsAtBottom).toBe(true);
    expect(rows.starts[0]).toBe(0);
    expect(rows.ends[0]).toBe(1);
  });

  it('shares the root between stacks that start with the same function', () => {
    const nodes = projectCallTree(demo());
    expect(nodes.size).toBe(4);
    expect(nodes.firstChildIndexAt(0)).toBe(1);
    expect(nodes.nextSiblingIndexAt(3)).toBeUndefined();
  });
});

describe('transforms and filters', () => {
  it('focuses, drops, and reports invalid transforms', () => {
    const table = demo();
    const render = table.frame(table.stacks[0]?.frameIdsRootToLeaf[1] as bigint).functionId;
    expect(symbolsOf(applyTransforms(table, [{ kind: 'COLLAPSE_FUNCTION_SUBTREE', function: render }]).table)).toEqual([
      'main>render',
      'main>io',
    ]);
    // Dropping a function drops every stack that used it.
    expect(symbolsOf(applyTransforms(table, [{ kind: 'DROP_FUNCTION', function: render }]).table)).toEqual(['main>io']);
    const result = applyTransforms(table, [{ kind: 'FOCUS_CALL_NODE', path: [4242n] }]);
    expect(result.invalidTransforms).toHaveLength(1);
    expect(result.outputStackCount).toBe(2);
  });

  it('filters by search terms and implementation', () => {
    const table = tableOf([
      { frames: ['main', 'drawSkia'], weight: 3n, resource: '/system/lib64/libgui.so' },
      { frames: ['main', 'readFile'], weight: 1n, resource: '/system/lib64/libc.so', implementation: 'MANAGED' },
    ]);
    expect(filterCallStacks(table, { ...DEFAULT_CALL_STACK_QUERY, searchText: 'skia' }).afterSearchCount).toBe(1);
    expect(parseFlameSearchTerms('a, ,b')).toEqual(['a', 'b']);
    // A stack keeps only the frames that match the implementation filter.
    const native = filterCallStacks(table, { ...DEFAULT_CALL_STACK_QUERY, implementation: 'NATIVE' });
    expect(symbolsOf(native.table)).toEqual(['main>drawSkia', 'main']);
    const script = filterCallStacks(table, { ...DEFAULT_CALL_STACK_QUERY, implementation: 'SCRIPT' });
    expect(symbolsOf(script.table)).toEqual(['readFile']);
  });
});

describe('flame graph snapshot', () => {
  it('projects the pipeline and explains an empty result', () => {
    const table = demo();
    const snapshot = buildFlameGraphSnapshot(table, DEFAULT_CALL_STACK_QUERY);
    expect(snapshot.totalWeight).toBe(10n);
    expect(snapshot.emptyReason).toBeUndefined();
    expect(snapshot.stageCounts.projectedNodeCount).toBe(4);

    const empty = buildFlameGraphSnapshot(table, { ...DEFAULT_CALL_STACK_QUERY, searchText: 'missing' });
    expect(empty.emptyReason).toBe('SEARCH_FILTERED_ALL');
    expect(empty.callNodes.size).toBe(0);
    expect(empty.rows.rowCount).toBe(0);

    const noThread = buildFlameGraphSnapshot(table, DEFAULT_CALL_STACK_QUERY, { selectedThreadHasNoSamples: true });
    expect(noThread.emptyReason).toBe('THREAD_HAS_NO_SAMPLES');
  });
});
