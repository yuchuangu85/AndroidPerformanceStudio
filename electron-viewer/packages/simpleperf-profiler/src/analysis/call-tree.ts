/**
 * Port of CallTreeProjector.kt. Stacks are folded into a tree of call nodes;
 * each node carries an inclusive weight (itself plus every descendant), a self
 * weight (leaf occurrences only), and the set of threads that reached it.
 *
 * Frame identity is canonical per function: when several frames resolve to the
 * same function, the alphabetically first frame represents it, so the tree does
 * not split on incidental address differences.
 */
import { PRIMARY_HASH_OFFSET, deriveStableId, primaryHashStep, saturatingNonNegativeAdd, secondaryHashStep } from './stable-id.js';
import {
  CallNodeTable,
  type CallStackDirection,
  type CallStackFrame,
  type CallStackTable,
  type FlameFunctionId,
  type FrameImplementation,
} from './contracts.js';

const IMPLEMENTATION_ORDER: Record<FrameImplementation, number> = {
  NATIVE: 0,
  MANAGED: 1,
  KERNEL: 2,
  UNKNOWN: 3,
};

export interface CallTreeProjectionResult {
  readonly callNodes: CallNodeTable;
  readonly inputStackCount: number;
  readonly projectedStackCount: number;
  readonly incompleteStackCount: number;
  readonly failureDetail?: string;
}

export const EMPTY_CALL_NODE_TABLE = new CallNodeTable({
  ids: [],
  parentIndexes: [],
  frameIds: [],
  depths: [],
  inclusiveWeights: [],
  selfWeights: [],
  sampleCounts: [],
  threadCounts: [],
  categories: [],
  framesById: new Map(),
});

export function projectCallTree(
  table: CallStackTable,
  direction: CallStackDirection = 'FORWARD',
): CallNodeTable {
  const invalid = table.stacks.find((stack) => stack.weight < 0n);
  if (invalid !== undefined) {
    throw new Error(
      'Negative call-stack weight is unsupported: sampleId=' +
        invalid.sampleId.toString() +
        ', weight=' +
        invalid.weight.toString(),
    );
  }
  const canonicalFrames = canonicalReferencedFrames(table);
  const roots = new Map<FlameFunctionId, MutableCallNode>();
  table.stacks.forEach((stack) => {
    addStack(table, stack, direction, canonicalFrames, roots);
  });
  return flatten([...roots.values()], canonicalFrames);
}

export function projectCallTreeResult(
  table: CallStackTable,
  direction: CallStackDirection = 'FORWARD',
): CallTreeProjectionResult {
  const incompleteStackCount = table.stacks.filter((stack) => stack.frameIdsRootToLeaf.length === 0).length;
  try {
    const callNodes = projectCallTree(table, direction);
    return {
      callNodes,
      inputStackCount: table.stacks.length,
      projectedStackCount: table.stacks.length - incompleteStackCount,
      incompleteStackCount,
    };
  } catch (error) {
    return {
      callNodes: EMPTY_CALL_NODE_TABLE,
      inputStackCount: table.stacks.length,
      projectedStackCount: 0,
      incompleteStackCount,
      failureDetail: (error instanceof Error ? error.name : 'Error') + ': ' + String(error instanceof Error ? error.message : ''),
    };
  }
}

class MutableCallNode {
  readonly functionId: FlameFunctionId;
  readonly parent: MutableCallNode | undefined;
  readonly primaryHash: bigint;
  readonly secondaryHash: bigint;
  readonly stableId: bigint;
  readonly children = new Map<FlameFunctionId, MutableCallNode>();
  readonly threads = new Set<string>();
  readonly categoryWeights = new Map<string | undefined, bigint>();
  inclusiveWeight = 0n;
  selfWeight = 0n;
  sampleCount = 0n;

  constructor(functionId: FlameFunctionId, parent: MutableCallNode | undefined) {
    this.functionId = functionId;
    this.parent = parent;
    this.primaryHash = primaryHashStep(parent?.primaryHash ?? PRIMARY_HASH_OFFSET, functionId);
    this.secondaryHash = secondaryHashStep(parent?.secondaryHash, functionId);
    this.stableId = deriveStableId(this.primaryHash, this.secondaryHash);
  }

  record(weight: bigint, threadKey: string, category: string | undefined): void {
    this.inclusiveWeight = saturatingNonNegativeAdd(this.inclusiveWeight, weight);
    this.sampleCount = saturatingNonNegativeAdd(this.sampleCount, 1n);
    this.threads.add(threadKey);
    this.categoryWeights.set(
      category,
      saturatingNonNegativeAdd(this.categoryWeights.get(category) ?? 0n, weight),
    );
  }
}

interface PendingNode {
  readonly node: MutableCallNode;
  readonly parentIndex: number;
  readonly depth: number;
}

function canonicalReferencedFrames(table: CallStackTable): Map<FlameFunctionId, CallStackFrame> {
  const referenced = new Map<bigint, CallStackFrame>();
  table.stacks.forEach((stack) => {
    stack.frameIdsRootToLeaf.forEach((frameId) => {
      if (!referenced.has(frameId)) referenced.set(frameId, table.frame(frameId));
    });
  });
  const byFunction = new Map<FlameFunctionId, CallStackFrame>();
  for (const frame of referenced.values()) {
    const existing = byFunction.get(frame.functionId);
    if (existing === undefined || compareFrames(frame, existing) < 0) {
      byFunction.set(frame.functionId, frame);
    }
  }
  return byFunction;
}

function compareFrames(left: CallStackFrame, right: CallStackFrame): number {
  return (
    compareStrings(left.symbolName.toLowerCase(), right.symbolName.toLowerCase()) ||
    compareStrings(left.symbolName, right.symbolName) ||
    compareStrings(left.resource, right.resource) ||
    IMPLEMENTATION_ORDER[left.implementation] - IMPLEMENTATION_ORDER[right.implementation] ||
    compareBigInt(left.virtualAddress, right.virtualAddress) ||
    compareBigInt(left.frameId, right.frameId)
  );
}

function callNodeComparator(frames: ReadonlyMap<FlameFunctionId, CallStackFrame>) {
  return (left: MutableCallNode, right: MutableCallNode): number => {
    const leftFrame = frameOf(frames, left.functionId);
    const rightFrame = frameOf(frames, right.functionId);
    return (
      compareStrings(leftFrame.symbolName.toLowerCase(), rightFrame.symbolName.toLowerCase()) ||
      compareBigInt(left.functionId, right.functionId) ||
      compareStrings(leftFrame.symbolName, rightFrame.symbolName) ||
      compareStrings(leftFrame.resource, rightFrame.resource) ||
      compareBigInt(leftFrame.virtualAddress, rightFrame.virtualAddress) ||
      IMPLEMENTATION_ORDER[leftFrame.implementation] - IMPLEMENTATION_ORDER[rightFrame.implementation] ||
      compareBigInt(leftFrame.frameId, rightFrame.frameId)
    );
  };
}

function frameOf(frames: ReadonlyMap<FlameFunctionId, CallStackFrame>, functionId: FlameFunctionId): CallStackFrame {
  const frame = frames.get(functionId);
  if (frame === undefined) throw new Error('Missing canonical frame for function ' + functionId.toString());
  return frame;
}

function addStack(
  table: CallStackTable,
  stack: CallStackTable['stacks'][number],
  direction: CallStackDirection,
  canonicalFrames: ReadonlyMap<FlameFunctionId, CallStackFrame>,
  roots: Map<FlameFunctionId, MutableCallNode>,
): void {
  if (stack.frameIdsRootToLeaf.length === 0) return;
  const indexes = stack.frameIdsRootToLeaf.map((_frameId, index) => index);
  if (direction === 'INVERTED') indexes.reverse();
  let parent: MutableCallNode | undefined;
  let siblings = roots;
  let terminal: MutableCallNode | undefined;
  indexes.forEach((sourceIndex) => {
    const frame = table.frame(stack.frameIdsRootToLeaf[sourceIndex] as bigint);
    if (!canonicalFrames.has(frame.functionId)) {
      throw new Error('Frame ' + frame.frameId.toString() + ' is missing from the canonical frame map');
    }
    let node = siblings.get(frame.functionId);
    if (node === undefined) {
      node = new MutableCallNode(frame.functionId, parent);
      siblings.set(frame.functionId, node);
    }
    node.record(stack.weight, stack.threadKey, stack.categoriesRootToLeaf[sourceIndex]);
    terminal = node;
    parent = node;
    siblings = node.children;
  });
  if (terminal !== undefined) {
    terminal.selfWeight = saturatingNonNegativeAdd(terminal.selfWeight, stack.weight);
  }
}

function flatten(
  roots: readonly MutableCallNode[],
  canonicalFrames: ReadonlyMap<FlameFunctionId, CallStackFrame>,
): CallNodeTable {
  const comparator = callNodeComparator(canonicalFrames);
  const orderedRoots = [...roots].sort(comparator);
  const ids: bigint[] = [];
  const parentIndexes: number[] = [];
  const frameIds: bigint[] = [];
  const depths: number[] = [];
  const inclusiveWeights: bigint[] = [];
  const selfWeights: bigint[] = [];
  const sampleCounts: bigint[] = [];
  const threadCounts: number[] = [];
  const categories: (string | undefined)[] = [];
  const projectedFrames = new Map<bigint, CallStackFrame>();
  const nodesByStableId = new Map<bigint, MutableCallNode>();
  const pending: PendingNode[] = [];
  [...orderedRoots].reverse().forEach((root) => pending.push({ node: root, parentIndex: -1, depth: 0 }));

  while (pending.length > 0) {
    const current = pending.pop() as PendingNode;
    const node = current.node;
    const previous = nodesByStableId.get(node.stableId);
    if (previous !== undefined && previous !== node) {
      throw new Error('Stable call-node ID collision for distinct ordered function paths: id=' + node.stableId.toString());
    }
    nodesByStableId.set(node.stableId, node);
    const canonicalFrame = frameOf(canonicalFrames, node.functionId);
    const index = ids.length;
    ids.push(node.stableId);
    parentIndexes.push(current.parentIndex);
    frameIds.push(canonicalFrame.frameId);
    depths.push(current.depth);
    inclusiveWeights.push(node.inclusiveWeight);
    selfWeights.push(node.selfWeight);
    sampleCounts.push(node.sampleCount);
    threadCounts.push(node.threads.size);
    categories.push(dominantCategory(node.categoryWeights));
    if (!projectedFrames.has(canonicalFrame.frameId)) projectedFrames.set(canonicalFrame.frameId, canonicalFrame);
    const orderedChildren = node.children.size <= 1 ? [...node.children.values()] : [...node.children.values()].sort(comparator);
    [...orderedChildren].reverse().forEach((child) => {
      pending.push({ node: child, parentIndex: index, depth: current.depth + 1 });
    });
  }

  return new CallNodeTable({
    ids,
    parentIndexes,
    frameIds,
    depths,
    inclusiveWeights,
    selfWeights,
    sampleCounts,
    threadCounts,
    categories,
    framesById: projectedFrames,
  });
}

/** Highest weight wins; ties prefer an unnamed category, then alphabetical. */
export function dominantCategory(weights: ReadonlyMap<string | undefined, bigint>): string | undefined {
  const entries = [...weights.entries()].sort((left, right) => {
    if (left[1] !== right[1]) return left[1] > right[1] ? -1 : 1;
    const leftNull = left[0] === undefined ? 0 : 1;
    const rightNull = right[0] === undefined ? 0 : 1;
    if (leftNull !== rightNull) return leftNull - rightNull;
    const leftKey = (left[0] ?? '').toLowerCase();
    const rightKey = (right[0] ?? '').toLowerCase();
    if (leftKey !== rightKey) return compareStrings(leftKey, rightKey);
    return compareStrings(left[0] ?? '', right[0] ?? '');
  });
  return entries[0]?.[0];
}

function compareStrings(left: string, right: string): number {
  if (left === right) return 0;
  return left < right ? -1 : 1;
}

function compareBigInt(left: bigint, right: bigint): number {
  if (left === right) return 0;
  return left < right ? -1 : 1;
}
