/**
 * JSON-safe flame graph payload shared by every analyser that produces a
 * CallStackTable (CPU sampling, ART method traces). 64-bit values cross the IPC
 * and persistence boundaries as strings.
 */
import type {
  CallStackAnalysisQuery,
  CallStackTable,
  CallStackTransform,
  FlameGraphEmptyReason,
  FlameGraphStageCounts,
  FrameImplementation,
} from './contracts.js';
import { buildFlameGraphSnapshot } from './flame-graph.js';

export interface FlameGraphPayloadNode {
  readonly index: number;
  /** Stable call-node id; identifies this exact position in the tree. */
  readonly id: string;
  /** Function id of the node's frame; used to build transform paths. */
  readonly functionId: string;
  readonly parent: number;
  readonly depth: number;
  readonly symbolName: string;
  readonly resource: string;
  readonly implementation: FrameImplementation;
  readonly inclusiveWeight: string;
  readonly selfWeight: string;
  readonly sampleCount: string;
  readonly threadCount: number;
  readonly category?: string;
  readonly start: number;
  readonly end: number;
}

export interface FlameGraphPayload {
  readonly threadKey?: string;
  readonly totalWeight: string;
  readonly nodeCount: number;
  readonly rowCount: number;
  readonly startsAtBottom: boolean;
  readonly emptyReason?: FlameGraphEmptyReason;
  readonly stageCounts: FlameGraphStageCounts;
  readonly invalidTransforms: readonly string[];
  readonly nodes: readonly FlameGraphPayloadNode[];
  /** Row index to node indexes, ready for drawing. */
  readonly rows: readonly (readonly number[])[];
  readonly sourceStackCount: number;
}

export function describeTransform(transform: CallStackTransform): string {
  switch (transform.kind) {
    case 'FOCUS_CALL_NODE':
      return 'FOCUS_CALL_NODE(' + transform.path.map((value) => value.toString()).join('>') + ')';
    case 'COLLAPSE_RESOURCE':
      return 'COLLAPSE_RESOURCE(' + transform.resource + ')';
    case 'FOCUS_CATEGORY':
      return 'FOCUS_CATEGORY(' + transform.category + ')';
    case 'FOCUS_FUNCTION':
    case 'FOCUS_FUNCTION_SELF':
    case 'MERGE_FUNCTION':
    case 'DROP_FUNCTION':
    case 'COLLAPSE_RECURSION':
    case 'COLLAPSE_DIRECT_RECURSION':
    case 'COLLAPSE_FUNCTION_SUBTREE':
      return transform.kind + '(' + transform.function.toString() + ')';
    default:
      return transform.kind + '(' + transform.path.map((value) => value.toString()).join('>') + ')';
  }
}

export function directionOf(value: string): 'FORWARD' | 'INVERTED' {
  return value === 'INVERTED' ? 'INVERTED' : 'FORWARD';
}

export function buildFlameGraphPayload(
  table: CallStackTable,
  query: CallStackAnalysisQuery,
  options: {
    readonly threadKey?: string;
    readonly selectedThreadHasNoSamples?: boolean;
    readonly committedRangeExcludedSamples?: boolean;
  } = {},
): FlameGraphPayload {
  const snapshot = buildFlameGraphSnapshot(table, query, options);
  const nodes: FlameGraphPayloadNode[] = [];
  for (let index = 0; index < snapshot.callNodes.size; index += 1) {
    const frame = snapshot.callNodes.frameAt(index);
    if (frame === undefined) continue;
    const category = snapshot.callNodes.categories[index];
    nodes.push({
      index,
      id: (snapshot.callNodes.ids[index] as bigint).toString(),
      functionId: frame.functionId.toString(),
      parent: snapshot.callNodes.parentIndexes[index] as number,
      depth: snapshot.callNodes.depths[index] as number,
      symbolName: frame.symbolName,
      resource: frame.resource,
      implementation: frame.implementation,
      inclusiveWeight: (snapshot.callNodes.inclusiveWeights[index] as bigint).toString(),
      selfWeight: (snapshot.callNodes.selfWeights[index] as bigint).toString(),
      sampleCount: (snapshot.callNodes.sampleCounts[index] as bigint).toString(),
      threadCount: snapshot.callNodes.threadCounts[index] as number,
      ...(category !== undefined ? { category } : {}),
      start: snapshot.rows.starts[index] as number,
      end: snapshot.rows.ends[index] as number,
    });
  }
  return {
    ...(options.threadKey !== undefined ? { threadKey: options.threadKey } : {}),
    totalWeight: snapshot.totalWeight.toString(),
    nodeCount: snapshot.callNodes.size,
    rowCount: snapshot.rows.rowCount,
    startsAtBottom: snapshot.rows.startsAtBottom,
    ...(snapshot.emptyReason !== undefined ? { emptyReason: snapshot.emptyReason } : {}),
    stageCounts: snapshot.stageCounts,
    invalidTransforms: snapshot.invalidTransforms.map(describeTransform),
    nodes,
    rows: snapshot.rows.nodeIndexesByRow,
    sourceStackCount: table.stacks.length,
  };
}
