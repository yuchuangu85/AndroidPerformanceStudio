/**
 * The analysis pipeline used by the CPU Profiler view: filter the stacks for
 * the query, apply the view transforms, project the call tree, and lay it out
 * as flame-graph rows. The stage counts are kept so an empty graph can explain
 * which stage removed everything.
 */
import {
  flameGraphEmptyReason,
  type CallStackAnalysisQuery,
  type CallStackTable,
  type FlameGraphSnapshot,
  type FlameGraphStageCounts,
} from './contracts.js';
import { projectCallTreeResult } from './call-tree.js';
import { filterCallStacks } from './filter.js';
import { projectFlameGraphRows } from './rows.js';
import { applyTransforms } from './transformer.js';

export function buildFlameGraphSnapshot(
  table: CallStackTable,
  query: CallStackAnalysisQuery,
  options: { readonly selectedThreadHasNoSamples?: boolean; readonly committedRangeExcludedSamples?: boolean } = {},
): FlameGraphSnapshot {
  const filtered = filterCallStacks(table, query);
  const transformed = applyTransforms(filtered.table, query.transforms);
  // A projection failure must not lose the whole view: it degrades to an empty
  // graph whose emptyReason explains itself.
  const projection = projectCallTreeResult(transformed.table, query.direction);
  const callNodes = projection.callNodes;
  const projectionFailure = projection.failureDetail;
  const rows = projectFlameGraphRows(callNodes, query.direction);
  const stageCounts: FlameGraphStageCounts = {
    sourceStackCount: table.stacks.length,
    selectedThreadHasNoSamples: options.selectedThreadHasNoSamples ?? false,
    committedRangeExcludedSamples: options.committedRangeExcludedSamples ?? false,
    afterPreviewCount: filtered.afterPreviewCount,
    afterSearchCount: filtered.afterSearchCount,
    afterImplementationCount: filtered.afterImplementationCount,
    afterTransformCount: transformed.outputStackCount,
    incompleteStackCount: filtered.incompleteInputStackCount,
    projectedNodeCount: callNodes.size,
    ...(projectionFailure !== undefined ? { projectionFailure } : {}),
  };
  const totalWeight = callNodes.inclusiveWeights
    .filter((_weight, index) => callNodes.parentIndexes[index] === -1)
    .reduce((sum, weight) => sum + weight, 0n);
  const emptyReason = flameGraphEmptyReason(stageCounts);
  return {
    query,
    callNodes,
    rows,
    totalWeight,
    ...(emptyReason !== undefined ? { emptyReason } : {}),
    invalidTransforms: transformed.invalidTransforms,
    stageCounts,
  };
}
