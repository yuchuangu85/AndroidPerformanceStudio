/**
 * Port of CallStackFilter.kt: preview range, search terms, and implementation
 * filtering. Search terms are ANDed across a stack, so "render,skia" keeps the
 * stacks that contain both.
 */
import {
  CallStackTable,
  parseFlameSearchTerms,
  type CallStackAnalysisQuery,
  type CallStackFrame,
  type FrameImplementation,
  type ImplementationFilter,
  type WeightedCallStack,
} from './contracts.js';

export interface FilteredCallStacks {
  readonly table: CallStackTable;
  readonly inputStackCount: number;
  readonly afterPreviewCount: number;
  readonly afterSearchCount: number;
  readonly afterImplementationCount: number;
  readonly incompleteInputStackCount: number;
}

export function filterCallStacks(table: CallStackTable, query: CallStackAnalysisQuery): FilteredCallStacks {
  const afterPreview = filterPreview(table.stacks, query.previewRange);
  const terms = parseFlameSearchTerms(query.searchText);
  const afterSearch = filterSearch(table, afterPreview, terms);
  const afterImplementation = filterImplementation(table, afterSearch, query.implementation);
  return {
    table: table.withStacks(afterImplementation),
    inputStackCount: table.stacks.length,
    afterPreviewCount: afterPreview.length,
    afterSearchCount: afterSearch.length,
    afterImplementationCount: afterImplementation.length,
    incompleteInputStackCount: table.stacks.filter((stack) => stack.frameIdsRootToLeaf.length === 0).length,
  };
}

function filterPreview(
  stacks: readonly WeightedCallStack[],
  range: CallStackAnalysisQuery['previewRange'],
): readonly WeightedCallStack[] {
  if (range === undefined) return stacks;
  return stacks.filter(
    (stack) =>
      stack.timestampNanos >= range.startNanosInclusive && stack.timestampNanos < range.endNanosExclusive,
  );
}

function filterSearch(
  table: CallStackTable,
  stacks: readonly WeightedCallStack[],
  terms: readonly string[],
): readonly WeightedCallStack[] {
  if (terms.length === 0) return stacks;
  return stacks.filter((stack) =>
    terms.every((term) => stack.frameIdsRootToLeaf.some((frameId) => frameMatches(table.frame(frameId), term))),
  );
}

function filterImplementation(
  table: CallStackTable,
  stacks: readonly WeightedCallStack[],
  implementation: ImplementationFilter,
): readonly WeightedCallStack[] {
  if (implementation === 'ALL') return stacks;
  const result: WeightedCallStack[] = [];
  stacks.forEach((stack) => {
    const matchingIndexes = stack.frameIdsRootToLeaf
      .map((_frameId, index) => index)
      .filter((index) =>
        implementationMatches(
          table.frame(stack.frameIdsRootToLeaf[index] as bigint).implementation,
          implementation,
        ),
      );
    if (matchingIndexes.length === 0) return;
    if (matchingIndexes.length === stack.frameIdsRootToLeaf.length) {
      result.push(stack);
      return;
    }
    result.push({
      ...stack,
      frameIdsRootToLeaf: matchingIndexes.map((index) => stack.frameIdsRootToLeaf[index] as bigint),
      categoriesRootToLeaf: matchingIndexes.map((index) => stack.categoriesRootToLeaf[index]),
    });
  });
  return result;
}

export function frameMatches(frame: CallStackFrame, term: string): boolean {
  const needle = term.toLowerCase();
  return (
    frame.symbolName.toLowerCase().includes(needle) || frame.resource.toLowerCase().includes(needle)
  );
}

export function implementationMatches(
  implementation: FrameImplementation,
  filter: ImplementationFilter,
): boolean {
  switch (filter) {
    case 'ALL':
      return true;
    case 'SCRIPT':
      return implementation === 'MANAGED';
    case 'NATIVE':
      return implementation === 'NATIVE' || implementation === 'KERNEL';
  }
}
