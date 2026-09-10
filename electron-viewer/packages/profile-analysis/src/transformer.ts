/**
 * Port of CallStackTransformer.kt: applies focus, merge, drop, and collapse
 * transforms to a call-stack table. Invalid call-node transforms are reported
 * instead of applied, so a stale UI selection cannot corrupt the view.
 */
import { stableStringHash } from './stable-id.js';
import {
  CallStackTable,
  type CallNodePath,
  type CallStackFrame,
  type CallStackTransform,
  type FlameFunctionId,
  type WeightedCallStack,
} from './contracts.js';

export interface TransformResult {
  readonly table: CallStackTable;
  readonly appliedTransforms: readonly CallStackTransform[];
  readonly invalidTransforms: readonly CallStackTransform[];
  readonly inputStackCount: number;
  readonly outputStackCount: number;
  readonly incompleteOutputStackCount: number;
}

export function applyTransforms(
  table: CallStackTable,
  transforms: readonly CallStackTransform[],
): TransformResult {
  let current = table;
  const applied: CallStackTransform[] = [];
  const invalid: CallStackTransform[] = [];
  transforms.forEach((transform) => {
    if (!isValidTransform(current, transform)) {
      invalid.push(transform);
      return;
    }
    current = applyTransform(current, transform);
    applied.push(transform);
  });
  return {
    table: current,
    appliedTransforms: applied,
    invalidTransforms: invalid,
    inputStackCount: table.stacks.length,
    outputStackCount: current.stacks.length,
    incompleteOutputStackCount: current.stacks.filter((stack) => stack.frameIdsRootToLeaf.length === 0).length,
  };
}

function isValidTransform(table: CallStackTable, transform: CallStackTransform): boolean {
  if (transform.kind === 'FOCUS_CALL_NODE' || transform.kind === 'MERGE_CALL_NODE') {
    return table.containsPath(transform.path);
  }
  return true;
}

function applyTransform(table: CallStackTable, transform: CallStackTransform): CallStackTable {
  switch (transform.kind) {
    case 'FOCUS_CALL_NODE':
      return table.withStacks(focusCallNode(table, transform.path));
    case 'FOCUS_FUNCTION':
      return table.withStacks(focusFunction(table, transform.function));
    case 'FOCUS_FUNCTION_SELF':
      return table.withStacks(focusFunctionSelf(table, transform.function));
    case 'MERGE_CALL_NODE':
      return table.withStacks(mergeCallNode(table, transform.path));
    case 'MERGE_FUNCTION':
      return table.withStacks(mergeFunction(table, transform.function));
    case 'DROP_FUNCTION':
      return table.withStacks(dropFunction(table, transform.function));
    case 'COLLAPSE_RESOURCE':
      return collapseResource(table, transform.resource);
    case 'COLLAPSE_RECURSION':
      return table.withStacks(collapseRecursion(table, transform.function));
    case 'COLLAPSE_DIRECT_RECURSION':
      return table.withStacks(collapseDirectRecursion(table, transform.function));
    case 'COLLAPSE_FUNCTION_SUBTREE':
      return table.withStacks(collapseFunctionSubtree(table, transform.function));
    case 'FOCUS_CATEGORY':
      return table.withStacks(focusCategory(table.stacks, transform.category));
  }
}

interface StackNode {
  readonly frameId: bigint;
  readonly category: string | undefined;
}

function nodesOf(stack: WeightedCallStack): StackNode[] {
  return stack.frameIdsRootToLeaf.map((frameId, index) => ({
    frameId,
    category: stack.categoriesRootToLeaf[index],
  }));
}

/** Returns undefined when every frame is gone, which drops the stack. */
function withNodes(stack: WeightedCallStack, nodes: readonly StackNode[]): WeightedCallStack | undefined {
  if (nodes.length === 0) return undefined;
  const frameIds = nodes.map((node) => node.frameId);
  const categories = nodes.map((node) => node.category);
  const unchanged =
    frameIds.length === stack.frameIdsRootToLeaf.length &&
    frameIds.every((frameId, index) => frameId === stack.frameIdsRootToLeaf[index]) &&
    categories.every((category, index) => category === stack.categoriesRootToLeaf[index]);
  if (unchanged) return stack;
  return {
    ...stack,
    frameIdsRootToLeaf: frameIds,
    categoriesRootToLeaf: categories,
  };
}

function startsWith<T>(values: readonly T[], prefix: readonly T[]): boolean {
  return prefix.length <= values.length && prefix.every((value, index) => values[index] === value);
}

function focusCallNode(table: CallStackTable, path: CallNodePath): WeightedCallStack[] {
  return mapDefined(table.stacks, (stack) => {
    if (!startsWith(table.functions(stack), path)) return undefined;
    return withNodes(stack, nodesOf(stack).slice(path.length - 1));
  });
}

function focusFunction(table: CallStackTable, fn: FlameFunctionId): WeightedCallStack[] {
  return mapDefined(table.stacks, (stack) => {
    const index = table.functions(stack).indexOf(fn);
    return index === -1 ? undefined : withNodes(stack, nodesOf(stack).slice(index));
  });
}

function focusFunctionSelf(table: CallStackTable, fn: FlameFunctionId): WeightedCallStack[] {
  return mapDefined(table.stacks, (stack) => {
    const leaf = nodesOf(stack).at(-1);
    if (leaf === undefined) return undefined;
    if (table.frame(leaf.frameId).functionId !== fn) return undefined;
    return withNodes(stack, [leaf]);
  });
}

function mergeCallNode(table: CallStackTable, path: CallNodePath): WeightedCallStack[] {
  return mapDefined(table.stacks, (stack) => {
    if (!startsWith(table.functions(stack), path)) return stack;
    const removeAt = path.length - 1;
    return withNodes(
      stack,
      nodesOf(stack).filter((_node, index) => index !== removeAt),
    );
  });
}

function mergeFunction(table: CallStackTable, fn: FlameFunctionId): WeightedCallStack[] {
  return mapDefined(table.stacks, (stack) =>
    withNodes(
      stack,
      nodesOf(stack).filter((node) => table.frame(node.frameId).functionId !== fn),
    ),
  );
}

function dropFunction(table: CallStackTable, fn: FlameFunctionId): WeightedCallStack[] {
  return table.stacks.filter(
    (stack) => !stack.frameIdsRootToLeaf.some((frameId) => table.frame(frameId).functionId === fn),
  );
}

function collapseResource(table: CallStackTable, resource: string): CallStackTable {
  const matching = [...table.framesById.values()].filter((frame) => frame.resource === resource);
  if (matching.length === 0) return table;
  const pseudoFunction = collapsedResourceFunctionId(resource, matching);
  const remapped = new Map<bigint, CallStackFrame>();
  for (const [frameId, frame] of table.framesById) {
    remapped.set(
      frameId,
      frame.resource === resource
        ? { ...frame, functionId: pseudoFunction, collapsedResource: resource }
        : frame,
    );
  }
  const remappedTable = table.withFrames(remapped);
  return remappedTable.withStacks(
    mapDefined(remappedTable.stacks, (stack) =>
      withNodes(
        stack,
        collapseConsecutive(nodesOf(stack), (node) => remappedTable.frame(node.frameId).resource === resource),
      ),
    ),
  );
}

function collapsedResourceFunctionId(resource: string, frames: readonly CallStackFrame[]): FlameFunctionId {
  const reusable = [
    ...new Set(
      frames
        .filter((frame) => frame.collapsedResource === resource)
        .map((frame) => frame.functionId),
    ),
  ];
  if (reusable.length === 1) {
    const candidate = reusable[0] as FlameFunctionId;
    if (!frames.some((frame) => frame.functionId === candidate && frame.collapsedResource !== resource)) {
      return candidate;
    }
  }
  const occupied = new Set(frames.map((frame) => frame.functionId));
  let candidate = stableStringHash('collapsed-resource:' + resource);
  while (occupied.has(candidate)) candidate += 1n;
  return candidate;
}

function collapseRecursion(table: CallStackTable, fn: FlameFunctionId): WeightedCallStack[] {
  return mapDefined(table.stacks, (stack) => {
    const functions = table.functions(stack);
    const first = functions.indexOf(fn);
    const last = functions.lastIndexOf(fn);
    const nodes = nodesOf(stack);
    if (first === -1 || first === last) return nodes.length === 0 ? undefined : stack;
    return withNodes(stack, [...nodes.slice(0, first), ...nodes.slice(last)]);
  });
}

function collapseDirectRecursion(table: CallStackTable, fn: FlameFunctionId): WeightedCallStack[] {
  return mapDefined(table.stacks, (stack) =>
    withNodes(
      stack,
      collapseConsecutive(nodesOf(stack), (node) => table.frame(node.frameId).functionId === fn),
    ),
  );
}

function collapseFunctionSubtree(table: CallStackTable, fn: FlameFunctionId): WeightedCallStack[] {
  return mapDefined(table.stacks, (stack) => {
    const index = table.functions(stack).indexOf(fn);
    const nodes = nodesOf(stack);
    return withNodes(stack, index === -1 ? nodes : nodes.slice(0, index + 1));
  });
}

function focusCategory(stacks: readonly WeightedCallStack[], category: string): WeightedCallStack[] {
  return mapDefined(stacks, (stack) =>
    withNodes(
      stack,
      nodesOf(stack).filter((node) => node.category === category),
    ),
  );
}

/** Keeps the last element of each run of matching entries. */
function collapseConsecutive<T>(values: readonly T[], matches: (value: T) => boolean): T[] {
  const collapsed: T[] = [];
  let previousMatched = false;
  values.forEach((value) => {
    const valueMatches = matches(value);
    if (valueMatches && previousMatched) collapsed[collapsed.length - 1] = value;
    else collapsed.push(value);
    previousMatched = valueMatches;
  });
  return collapsed;
}

function mapDefined<T, R>(values: readonly T[], transform: (value: T) => R | undefined): R[] {
  const result: R[] = [];
  values.forEach((value) => {
    const mapped = transform(value);
    if (mapped !== undefined) result.push(mapped);
  });
  return result;
}
