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
  CallStackTable,
  CallStackTransform,
  FlameGraphPayload,
  FlameGraphPayloadNode,
  FrameImplementation,
} from '@aps/profile-analysis';

// The payload builder is shared with every other analyser; re-exported here
// under the CPU Profiler's names so existing callers keep working.
export {
  buildFlameGraphPayload,
  describeTransform,
  directionOf,
} from '@aps/profile-analysis';
import type { SimpleperfCaptureResult } from './capture.js';
import type { OfflineProfileFormat } from './offline.js';
import type { SamplingParameters } from './toolchain.js';

export interface CpuProfileSessionRecord {
  readonly id: string;
  readonly capturedAtEpochMillis: number;
  readonly serial: string;
  readonly packageName?: string;
  readonly simpleperfVersion?: string;
  /** Relative path of the retained source inside the session folder. */
  readonly reportFile: string;
  /**
   * What the retained source is. Absent means a simpleperf protobuf report, which
   * is what every captured session holds; imports may keep the original bytes.
   */
  readonly sourceFormat?: OfflineProfileFormat;
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

export type CpuProfileFlameNode = FlameGraphPayloadNode;
export type CpuProfileFlameGraph = FlameGraphPayload;

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

/**
 * Renderer-facing transform request. Function ids travel as strings so the IPC
 * payload stays JSON-safe, and the main process maps them back to bigints.
 */
export type CpuTransformRequest =
  | { readonly kind: 'FOCUS_CALL_NODE'; readonly path: readonly string[] }
  | { readonly kind: 'FOCUS_FUNCTION'; readonly functionId: string }
  | { readonly kind: 'DROP_FUNCTION'; readonly functionId: string }
  | { readonly kind: 'COLLAPSE_RECURSION'; readonly functionId: string }
  | { readonly kind: 'COLLAPSE_RESOURCE'; readonly resource: string };

export function transformFromRequest(request: CpuTransformRequest): CallStackTransform {
  switch (request.kind) {
    case 'FOCUS_CALL_NODE':
      return { kind: 'FOCUS_CALL_NODE', path: request.path.map(toFunctionId) };
    case 'FOCUS_FUNCTION':
      return { kind: 'FOCUS_FUNCTION', function: toFunctionId(request.functionId) };
    case 'DROP_FUNCTION':
      return { kind: 'DROP_FUNCTION', function: toFunctionId(request.functionId) };
    case 'COLLAPSE_RECURSION':
      return { kind: 'COLLAPSE_RECURSION', function: toFunctionId(request.functionId) };
    case 'COLLAPSE_RESOURCE':
      return { kind: 'COLLAPSE_RESOURCE', resource: request.resource };
  }
}

function toFunctionId(value: string): bigint {
  try {
    return BigInt(value);
  } catch {
    throw new Error('Invalid function id: ' + value);
  }
}