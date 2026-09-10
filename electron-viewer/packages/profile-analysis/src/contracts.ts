/**
 * Port of CallStackContracts.kt: the call-stack table, the analysis query, the
 * projected call-node table, and the flame-graph rows.
 *
 * Kotlin uses defensive copies via private constructors. TypeScript relies on
 * readonly arrays plus the frozen tables built here; nothing is mutated after
 * construction, so the snapshot guarantee is the same.
 */
export type FlameFunctionId = bigint;
export type FlameCallNodeId = bigint;

export interface AnalysisTimeRange {
  readonly startNanosInclusive: bigint;
  readonly endNanosExclusive: bigint;
}

export type CallStackDirection = 'FORWARD' | 'INVERTED';

export const FRAME_IMPLEMENTATIONS = ['NATIVE', 'MANAGED', 'KERNEL', 'UNKNOWN'] as const;
export type FrameImplementation = (typeof FRAME_IMPLEMENTATIONS)[number];

export type ImplementationFilter = 'ALL' | 'SCRIPT' | 'NATIVE';

export interface CallStackFrame {
  readonly frameId: bigint;
  readonly functionId: FlameFunctionId;
  readonly symbolName: string;
  readonly resource: string;
  readonly virtualAddress: bigint;
  readonly implementation: FrameImplementation;
  readonly collapsedResource?: string;
}

export interface WeightedCallStack {
  readonly sampleId: bigint;
  readonly timestampNanos: bigint;
  readonly weight: bigint;
  readonly threadKey: string;
  readonly category?: string;
  readonly subcategory?: string;
  readonly frameIdsRootToLeaf: readonly bigint[];
  readonly categoriesRootToLeaf: readonly (string | undefined)[];
}

/** Builds a stack whose per-frame categories fall back to the stack category. */
export function weightedCallStack(
  input: Omit<WeightedCallStack, 'categoriesRootToLeaf'> & { readonly categoriesRootToLeaf?: readonly (string | undefined)[] },
): WeightedCallStack {
  const categories =
    input.categoriesRootToLeaf ?? input.frameIdsRootToLeaf.map(() => input.category);
  if (categories.length !== input.frameIdsRootToLeaf.length) {
    throw new Error('categoriesRootToLeaf must align with frameIdsRootToLeaf');
  }
  return {
    sampleId: input.sampleId,
    timestampNanos: input.timestampNanos,
    weight: input.weight,
    threadKey: input.threadKey,
    ...(input.category !== undefined ? { category: input.category } : {}),
    ...(input.subcategory !== undefined ? { subcategory: input.subcategory } : {}),
    frameIdsRootToLeaf: input.frameIdsRootToLeaf,
    categoriesRootToLeaf: categories,
  };
}

export class CallStackTable {
  readonly framesById: ReadonlyMap<bigint, CallStackFrame>;
  readonly stacks: readonly WeightedCallStack[];

  constructor(framesById: ReadonlyMap<bigint, CallStackFrame>, stacks: readonly WeightedCallStack[]) {
    this.framesById = framesById;
    this.stacks = stacks;
  }

  frame(frameId: bigint): CallStackFrame {
    const frame = this.framesById.get(frameId);
    if (frame === undefined) throw new Error('Unknown frame id ' + frameId.toString());
    return frame;
  }

  functions(stack: WeightedCallStack): FlameFunctionId[] {
    return stack.frameIdsRootToLeaf.map((frameId) => this.frame(frameId).functionId);
  }

  /** True when some stack starts with the given root-to-node function path. */
  containsPath(path: CallNodePath): boolean {
    if (path.length === 0) return false;
    return this.stacks.some((stack) => {
      const functions = this.functions(stack);
      return path.length <= functions.length && path.every((fn, index) => functions[index] === fn);
    });
  }

  withStacks(stacks: readonly WeightedCallStack[]): CallStackTable {
    if (sameInstances(this.stacks, stacks)) return this;
    return new CallStackTable(this.framesById, stacks);
  }

  withFrames(framesById: ReadonlyMap<bigint, CallStackFrame>): CallStackTable {
    return new CallStackTable(framesById, this.stacks);
  }
}

function sameInstances(
  left: readonly WeightedCallStack[],
  right: readonly WeightedCallStack[],
): boolean {
  if (left.length !== right.length) return false;
  return left.every((entry, index) => entry === right[index]);
}

export type CallNodePath = readonly FlameFunctionId[];

export type CallStackTransform =
  | { readonly kind: 'FOCUS_CALL_NODE'; readonly path: CallNodePath }
  | { readonly kind: 'FOCUS_FUNCTION'; readonly function: FlameFunctionId }
  | { readonly kind: 'FOCUS_FUNCTION_SELF'; readonly function: FlameFunctionId }
  | { readonly kind: 'MERGE_CALL_NODE'; readonly path: CallNodePath }
  | { readonly kind: 'MERGE_FUNCTION'; readonly function: FlameFunctionId }
  | { readonly kind: 'DROP_FUNCTION'; readonly function: FlameFunctionId }
  | { readonly kind: 'COLLAPSE_RESOURCE'; readonly resource: string }
  | { readonly kind: 'COLLAPSE_RECURSION'; readonly function: FlameFunctionId }
  | { readonly kind: 'COLLAPSE_DIRECT_RECURSION'; readonly function: FlameFunctionId }
  | { readonly kind: 'COLLAPSE_FUNCTION_SUBTREE'; readonly function: FlameFunctionId }
  | { readonly kind: 'FOCUS_CATEGORY'; readonly category: string };

export interface CallStackAnalysisQuery {
  readonly previewRange?: AnalysisTimeRange;
  readonly searchText: string;
  readonly implementation: ImplementationFilter;
  readonly direction: CallStackDirection;
  readonly transforms: readonly CallStackTransform[];
}

export const DEFAULT_CALL_STACK_QUERY: CallStackAnalysisQuery = {
  searchText: '',
  implementation: 'ALL',
  direction: 'FORWARD',
  transforms: [],
};

/** Flat arrays; index i describes one call node. Parents always precede children. */
export class CallNodeTable {
  readonly ids: readonly bigint[];
  readonly parentIndexes: readonly number[];
  readonly frameIds: readonly bigint[];
  readonly depths: readonly number[];
  readonly inclusiveWeights: readonly bigint[];
  readonly selfWeights: readonly bigint[];
  readonly sampleCounts: readonly bigint[];
  readonly threadCounts: readonly number[];
  readonly categories: readonly (string | undefined)[];
  readonly framesById: ReadonlyMap<bigint, CallStackFrame>;

  private readonly indexById: ReadonlyMap<bigint, number>;
  private readonly firstChildIndexes: readonly number[];
  private readonly nextSiblingIndexes: readonly number[];

  constructor(input: {
    readonly ids: readonly bigint[];
    readonly parentIndexes: readonly number[];
    readonly frameIds: readonly bigint[];
    readonly depths: readonly number[];
    readonly inclusiveWeights: readonly bigint[];
    readonly selfWeights: readonly bigint[];
    readonly sampleCounts: readonly bigint[];
    readonly threadCounts: readonly number[];
    readonly categories: readonly (string | undefined)[];
    readonly framesById: ReadonlyMap<bigint, CallStackFrame>;
  }) {
    this.ids = input.ids;
    this.parentIndexes = input.parentIndexes;
    this.frameIds = input.frameIds;
    this.depths = input.depths;
    this.inclusiveWeights = input.inclusiveWeights;
    this.selfWeights = input.selfWeights;
    this.sampleCounts = input.sampleCounts;
    this.threadCounts = input.threadCounts;
    this.categories = input.categories;
    this.framesById = input.framesById;
    this.indexById = new Map(input.ids.map((id, index) => [id, index] as const));
    const firstChild = new Array<number>(input.ids.length).fill(NO_NODE_INDEX);
    const nextSibling = new Array<number>(input.ids.length).fill(NO_NODE_INDEX);
    const lastChild = new Array<number>(input.ids.length).fill(NO_NODE_INDEX);
    let lastRoot = NO_NODE_INDEX;
    input.parentIndexes.forEach((parentIndex, nodeIndex) => {
      if (parentIndex === NO_NODE_INDEX) {
        if (lastRoot !== NO_NODE_INDEX) nextSibling[lastRoot] = nodeIndex;
        lastRoot = nodeIndex;
        return;
      }
      if (parentIndex < 0 || parentIndex >= input.ids.length || parentIndex === nodeIndex) return;
      const previous = lastChild[parentIndex] as number;
      if (previous === NO_NODE_INDEX) firstChild[parentIndex] = nodeIndex;
      else nextSibling[previous] = nodeIndex;
      lastChild[parentIndex] = nodeIndex;
    });
    this.firstChildIndexes = firstChild;
    this.nextSiblingIndexes = nextSibling;
  }

  get size(): number {
    return this.ids.length;
  }

  nodeIdAt(nodeIndex: number): FlameCallNodeId | undefined {
    return this.ids[nodeIndex];
  }

  parentIndexAt(nodeIndex: number): number | undefined {
    return nodeIndexAt(this.parentIndexes, nodeIndex);
  }

  firstChildIndexAt(nodeIndex: number): number | undefined {
    return nodeIndexAt(this.firstChildIndexes, nodeIndex);
  }

  nextSiblingIndexAt(nodeIndex: number): number | undefined {
    return nodeIndexAt(this.nextSiblingIndexes, nodeIndex);
  }

  frameAt(nodeIndex: number): CallStackFrame | undefined {
    const frameId = this.frameIds[nodeIndex];
    return frameId === undefined ? undefined : this.framesById.get(frameId);
  }

  indexOf(nodeId: FlameCallNodeId): number | undefined {
    return this.indexById.get(nodeId);
  }

  /** Finds the node for a root-to-node function path, if such a path exists. */
  findByPath(path: CallNodePath): FlameCallNodeId | undefined {
    if (path.length === 0) return undefined;
    let current = this.rootIndexes();
    let nodeIndex: number | undefined;
    for (const functionId of path) {
      nodeIndex = current.find(
        (candidate) =>
          this.framesById.get(this.frameIds[candidate] as bigint)?.functionId === functionId,
      );
      if (nodeIndex === undefined) return undefined;
      current = [];
      for (
        let child = this.firstChildIndexAt(nodeIndex);
        child !== undefined;
        child = this.nextSiblingIndexAt(child)
      ) {
        current.push(child);
      }
    }
    return nodeIndex === undefined ? undefined : this.ids[nodeIndex];
  }

  private rootIndexes(): number[] {
    const roots: number[] = [];
    for (let index = 0; index < this.parentIndexes.length; index += 1) {
      if (this.parentIndexes[index] === NO_NODE_INDEX) roots.push(index);
    }
    return roots;
  }
}

export const NO_NODE_INDEX = -1;

function nodeIndexAt(values: readonly number[], index: number): number | undefined {
  const value = values[index];
  return value === undefined || value === NO_NODE_INDEX ? undefined : value;
}

export interface FlameGraphRows {
  readonly nodeIndexesByRow: readonly (readonly number[])[];
  readonly starts: readonly number[];
  readonly ends: readonly number[];
  readonly startsAtBottom: boolean;
  readonly rowCount: number;
}

export const FLAME_GRAPH_EMPTY_REASONS = [
  'THREAD_HAS_NO_SAMPLES',
  'COMMITTED_RANGE_EMPTY',
  'PREVIEW_RANGE_EMPTY',
  'SEARCH_FILTERED_ALL',
  'IMPLEMENTATION_FILTERED_ALL',
  'TRANSFORMS_FILTERED_ALL',
  'PROFILE_INCOMPLETE',
  'PROJECTION_FAILED',
] as const;

export type FlameGraphEmptyReason = (typeof FLAME_GRAPH_EMPTY_REASONS)[number];

export interface FlameGraphStageCounts {
  readonly sourceStackCount: number;
  readonly selectedThreadHasNoSamples: boolean;
  readonly committedRangeExcludedSamples: boolean;
  readonly afterPreviewCount: number;
  readonly afterSearchCount: number;
  readonly afterImplementationCount: number;
  readonly afterTransformCount: number;
  readonly incompleteStackCount: number;
  readonly projectedNodeCount: number;
  readonly projectionFailure?: string;
}

export function flameGraphEmptyReason(counts: FlameGraphStageCounts): FlameGraphEmptyReason | undefined {
  if (counts.selectedThreadHasNoSamples) return 'THREAD_HAS_NO_SAMPLES';
  if (counts.sourceStackCount === 0 && counts.committedRangeExcludedSamples) return 'COMMITTED_RANGE_EMPTY';
  if (counts.sourceStackCount === 0) return 'THREAD_HAS_NO_SAMPLES';
  if (counts.afterPreviewCount === 0) return 'PREVIEW_RANGE_EMPTY';
  if (counts.afterSearchCount === 0) return 'SEARCH_FILTERED_ALL';
  if (counts.afterImplementationCount === 0) return 'IMPLEMENTATION_FILTERED_ALL';
  if (counts.afterTransformCount === 0) return 'TRANSFORMS_FILTERED_ALL';
  if (counts.incompleteStackCount > 0 && counts.projectedNodeCount === 0) return 'PROFILE_INCOMPLETE';
  if (counts.projectionFailure !== undefined) return 'PROJECTION_FAILED';
  return undefined;
}

export interface FlameGraphSnapshot {
  readonly query: CallStackAnalysisQuery;
  readonly callNodes: CallNodeTable;
  readonly rows: FlameGraphRows;
  readonly totalWeight: bigint;
  readonly emptyReason?: FlameGraphEmptyReason;
  readonly invalidTransforms: readonly CallStackTransform[];
  readonly stageCounts: FlameGraphStageCounts;
  readonly diagnosticDetails?: string;
}

export function parseFlameSearchTerms(searchText: string): string[] {
  return searchText
    .split(',')
    .map((term) => term.trim())
    .filter((term) => term.length > 0);
}
