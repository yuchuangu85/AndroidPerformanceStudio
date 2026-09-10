/**
 * Session model for the CPU Profiler: what is persisted, and what is sent to
 * the renderer.
 *
 * 64-bit values cross both boundaries as strings. Electron's structured clone
 * would carry a bigint, but JSON persistence would not, and a session must
 * survive a restart, so strings are used consistently.
 */
import type { ProfileMetadata } from './model.js';
import type {
  CallStackAnalysisQuery,
  CallStackDirection,
  CallStackTable,
  CallStackTransform,
  FlameGraphEmptyReason,
  FlameGraphStageCounts,
  FrameImplementation,
} from './analysis/contracts.js';
import { buildFlameGraphSnapshot } from './analysis/flame-graph.js';
import type { SimpleperfCaptureResult } from './capture.js';
import type { SamplingParameters } from './toolchain.js';

export interface CpuProfileSessionRecord {
  readonly id: string;
  readonly capturedAtEpochMillis: number;
  readonly serial: string;
  readonly packageName?: string;
  readonly simpleperfVersion?: string;
  /** Relative path of the retained protobuf report inside the session folder. */
  readonly reportFile: string;
  readonly perfDataBytes: number;
  readonly sampleCount: number;
  readonly lostCount: number;
  readonly eventTypes: readonly string[];
  readonly threadKeys: readonly string[];
  readonly metadata: CpuProfileMetadataRecord;
  readonly parameters: SamplingParametersRecord;
}

export interface CpuProfileMetadataRecord {
  readonly appPackageName?: string;
  readonly appType?: string;
  readonly androidSdkVersion?: string;
  readonly androidBuildType?: string;
  readonly traceOffCpu: boolean;
}

export interface SamplingParametersRecord {
  readonly target: string;
  readonly event: string;
  readonly rate: string;
  readonly durationSeconds?: number;
  readonly callGraph: string;
  readonly scope: string;
}

/** The in-memory half: the session record plus the reusable call-stack table. */
export interface CpuProfileSession {
  readonly record: CpuProfileSessionRecord;
  readonly table: CallStackTable;
}

export function samplingParametersRecord(parameters: SamplingParameters): SamplingParametersRecord {
  return {
    target: describeTarget(parameters.target),
    event: parameters.event,
    rate:
      parameters.rate.kind === 'FREQUENCY'
        ? String(parameters.rate.hertz) + ' Hz'
        : String(parameters.rate.events) + ' events',
    ...(parameters.durationSeconds !== undefined ? { durationSeconds: parameters.durationSeconds } : {}),
    callGraph: parameters.callGraph,
    scope: parameters.scope,
  };
}

function describeTarget(target: SamplingParameters['target']): string {
  switch (target.kind) {
    case 'APP':
      return target.packageName;
    case 'PROCESS':
      return 'pid ' + String(target.pid);
    case 'PROCESS_NAME':
      return target.name;
    case 'THREAD':
      return 'tid ' + String(target.tid);
    default:
      return 'system wide';
  }
}

export function metadataRecord(metadata: ProfileMetadata | undefined): CpuProfileMetadataRecord {
  return {
    ...(metadata?.appPackageName !== undefined ? { appPackageName: metadata.appPackageName } : {}),
    ...(metadata?.appType !== undefined ? { appType: metadata.appType } : {}),
    ...(metadata?.androidSdkVersion !== undefined ? { androidSdkVersion: metadata.androidSdkVersion } : {}),
    ...(metadata?.androidBuildType !== undefined ? { androidBuildType: metadata.androidBuildType } : {}),
    traceOffCpu: metadata?.traceOffCpu ?? false,
  };
}

export function createCpuProfileSession(
  result: SimpleperfCaptureResult,
  options: { readonly reportFile: string; readonly table: CallStackTable; readonly packageName?: string },
): CpuProfileSession {
  const threadKeys: string[] = [];
  result.profile.samples.forEach((sample) => {
    const key = sample.threadName + ' (tid ' + String(sample.threadId) + ')';
    if (!threadKeys.includes(key)) threadKeys.push(key);
  });
  const packageName = options.packageName ?? result.profile.metadata?.appPackageName;
  return {
    record: {
      id: result.id,
      capturedAtEpochMillis: result.capturedAtEpochMillis,
      serial: result.serial,
      ...(packageName !== undefined ? { packageName } : {}),
      ...(result.simpleperfVersion !== undefined ? { simpleperfVersion: result.simpleperfVersion } : {}),
      reportFile: options.reportFile,
      perfDataBytes: result.perfDataBytes,
      sampleCount: result.profile.samples.length,
      lostCount: Number(result.profile.summary.lostCount),
      eventTypes: [...(result.profile.metadata?.eventTypes ?? [])],
      threadKeys,
      metadata: metadataRecord(result.profile.metadata),
      parameters: samplingParametersRecord(result.parameters),
    },
    table: options.table,
  };
}

export interface CpuProfileFlameNode {
  readonly index: number;
  readonly id: string;
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

export interface CpuProfileFlameGraph {
  readonly threadKey?: string;
  readonly totalWeight: string;
  readonly nodeCount: number;
  readonly rowCount: number;
  readonly startsAtBottom: boolean;
  readonly emptyReason?: FlameGraphEmptyReason;
  readonly stageCounts: FlameGraphStageCounts;
  readonly invalidTransforms: readonly string[];
  readonly nodes: readonly CpuProfileFlameNode[];
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

export function buildFlameGraphPayload(
  table: CallStackTable,
  query: CallStackAnalysisQuery,
  options: {
    readonly threadKey?: string;
    readonly selectedThreadHasNoSamples?: boolean;
    readonly committedRangeExcludedSamples?: boolean;
  } = {},
): CpuProfileFlameGraph {
  const snapshot = buildFlameGraphSnapshot(table, query, options);
  const nodes: CpuProfileFlameNode[] = [];
  for (let index = 0; index < snapshot.callNodes.size; index += 1) {
    const frame = snapshot.callNodes.frameAt(index);
    if (frame === undefined) continue;
    const category = snapshot.callNodes.categories[index];
    nodes.push({
      index,
      id: (snapshot.callNodes.ids[index] as bigint).toString(),
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

/** Descending inclusive weight; used for the top-functions and top-self lists. */
export function topFunctions(
  table: CallStackTable,
  options: { readonly threadKey?: string; readonly limit?: number; readonly by?: 'INCLUSIVE' | 'SELF' } = {},
): readonly {
  readonly functionId: string;
  readonly symbolName: string;
  readonly resource: string;
  readonly implementation: FrameImplementation;
  readonly weight: string;
  readonly sampleCount: string;
}[] {
  const by = options.by ?? 'INCLUSIVE';
  const limit = options.limit ?? 20;
  const grouped = new Map<
    string,
    {
      functionId: string;
      symbolName: string;
      resource: string;
      implementation: FrameImplementation;
      weight: bigint;
      sampleCount: bigint;
    }
  >();
  const stacks = options.threadKey === undefined
    ? table.stacks
    : table.stacks.filter((stack) => stack.threadKey === options.threadKey);
  stacks.forEach((stack) => {
    const leafIndex = stack.frameIdsRootToLeaf.length - 1;
    stack.frameIdsRootToLeaf.forEach((frameId, index) => {
      if (by === 'SELF' && index !== leafIndex) return;
      const frame = table.frame(frameId);
      const key = frame.functionId.toString();
      const existing = grouped.get(key);
      if (existing === undefined) {
        grouped.set(key, {
          functionId: key,
          symbolName: frame.symbolName,
          resource: frame.resource,
          implementation: frame.implementation,
          weight: stack.weight,
          sampleCount: 1n,
        });
        return;
      }
      existing.weight += stack.weight;
      existing.sampleCount += 1n;
    });
  });
  return [...grouped.values()]
    .sort((left, right) => (left.weight === right.weight ? 0 : left.weight > right.weight ? -1 : 1))
    .slice(0, limit)
    .map((entry) => ({
      functionId: entry.functionId,
      symbolName: entry.symbolName,
      resource: entry.resource,
      implementation: entry.implementation,
      weight: entry.weight.toString(),
      sampleCount: entry.sampleCount.toString(),
    }));
}

export function directionOf(value: string): CallStackDirection {
  return value === 'INVERTED' ? 'INVERTED' : 'FORWARD';
}
