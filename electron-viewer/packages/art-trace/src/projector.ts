/**
 * Converts a parsed ART trace into the canonical CallStackTable consumed by the
 * shared flame graph pipeline.
 *
 * Each method-trace event is replayed per thread in time order. For every
 * interval during which the stack is constant, one WeightedCallStack is emitted
 * with the interval duration in nanoseconds as its weight. That resampling is
 * what makes the projection correct: the projector adds the weight to every
 * frame on the path (inclusive time) and to the leaf (self time), which is what
 * Android Studio reports for method tracing.
 *
 * Deviation from the Kotlin projector: threads that appear in events but not in
 * the thread table are kept rather than dropped, so a trace without thread info
 * packets still produces stacks instead of silently losing data.
 */
import {
  CallStackTable,
  buildFlameGraphSnapshot,
  weightedCallStack,
  type CallStackAnalysisQuery,
  type CallStackFrame,
  type FlameGraphSnapshot,
  type WeightedCallStack,
} from '@aps/profile-analysis';
import {
  methodDisplayName,
  type ArtMethod,
  type ArtTraceAction,
  type ArtTraceAnalysis,
  type ArtTraceEvent,
} from './model.js';

export function toCallStackTable(analysis: ArtTraceAnalysis): CallStackTable {
  const framesById = new Map<bigint, CallStackFrame>();
  const frameOf = (methodId: bigint): CallStackFrame => {
    const existing = framesById.get(methodId);
    if (existing !== undefined) return existing;
    const method = analysis.methods.get(methodId);
    const frame: CallStackFrame = {
      frameId: methodId,
      functionId: methodId,
      symbolName: methodDisplayName(method, methodId),
      resource: method?.className ?? '',
      virtualAddress: methodId,
      implementation: 'MANAGED',
    };
    framesById.set(methodId, frame);
    return frame;
  };
  analysis.methods.forEach((_method: ArtMethod, methodId: bigint) => {
    frameOf(methodId);
  });

  const stacks: WeightedCallStack[] = [];
  let sampleId = 0n;
  // Grouped once: filtering every event per thread is threads x events, which
  // on a 64 thread trace is 6.5M predicate calls before any projection runs.
  // The order inside a bucket is the trace order, so the replay is unchanged.
  const eventsByThread = new Map<number, ArtTraceEvent[]>();
  analysis.events.forEach((event) => {
    const bucket = eventsByThread.get(event.threadId);
    if (bucket === undefined) eventsByThread.set(event.threadId, [event]);
    else bucket.push(event);
  });
  threadIds(analysis, eventsByThread).forEach((threadId) => {
    const threadEvents = eventsByThread.get(threadId);
    if (threadEvents === undefined || threadEvents.length === 0) return;
    const threadKey = threadKeyOf(analysis, threadId);
    const stack: bigint[] = [];
    let cursor = (threadEvents[0] as { timeNanos: bigint }).timeNanos;
    threadEvents.forEach((event) => {
      sampleId = emitInterval(stack, cursor, event.timeNanos, threadKey, stacks, sampleId);
      applyEvent(stack, event.action, event.methodId);
      cursor = event.timeNanos;
    });
    // The last stack runs until the trace ends.
    emitInterval(stack, cursor, analysis.endTimeNanos, threadKey, stacks, sampleId);
  });
  // Frames for events whose methods never appeared in the method table.
  analysis.events.forEach((event) => {
    frameOf(event.methodId);
  });
  return new CallStackTable(framesById, stacks);
}

function threadIds(analysis: ArtTraceAnalysis, eventsByThread: ReadonlyMap<number, ArtTraceEvent[]>): number[] {
  const ids: number[] = [...analysis.threads.keys()];
  const known = new Set(ids);
  const orphans = [...eventsByThread.keys()].filter((threadId) => !known.has(threadId));
  orphans.sort((left, right) => left - right);
  return [...ids, ...orphans];
}

export function threadKeyOf(analysis: ArtTraceAnalysis, threadId: number): string {
  const name = analysis.threads.get(threadId)?.name ?? '';
  return name.trim().length === 0 ? 'tid ' + String(threadId) : name + ' (tid ' + String(threadId) + ')';
}

function emitInterval(
  stack: readonly bigint[],
  start: bigint,
  end: bigint,
  threadKey: string,
  stacks: WeightedCallStack[],
  sampleId: bigint,
): bigint {
  // The id advances even for skipped intervals, matching the Kotlin projector.
  const nextId = sampleId + 1n;
  if (end <= start || stack.length === 0) return nextId;
  stacks.push(
    weightedCallStack({
      sampleId,
      timestampNanos: start,
      weight: end - start,
      threadKey,
      frameIdsRootToLeaf: [...stack],
    }),
  );
  return nextId;
}

function applyEvent(stack: bigint[], action: ArtTraceAction, methodId: bigint): void {
  if (action === 'ENTER') {
    stack.push(methodId);
    return;
  }
  // EXIT and UNROLL both pop, and an unbalanced pop is ignored.
  if (stack.length > 0) stack.pop();
}

/** Reuses the shared projection pipeline; weights are nanoseconds here. */
export function buildArtTraceFlameGraph(
  table: CallStackTable,
  query: CallStackAnalysisQuery,
): FlameGraphSnapshot {
  return buildFlameGraphSnapshot(table, query);
}

export interface MethodTopMethod {
  readonly functionId: string;
  readonly symbolName: string;
  readonly resource: string;
  readonly selfMicros: number;
  readonly totalMicros: number;
  readonly callCount: number;
  readonly threadCount: number;
}

export const METHOD_TOP_SORTS = ['SYMBOL', 'SELF_MICROS', 'TOTAL_MICROS', 'CALL_COUNT'] as const;
export type MethodTopMethodSort = (typeof METHOD_TOP_SORTS)[number];

const NANOS_PER_MICRO = 1000n;

/**
 * Aggregates the table into per-method self and inclusive durations. Call counts
 * are the true enter-event counts, not the number of interval samples.
 */
export function topMethods(
  table: CallStackTable,
  analysis: ArtTraceAnalysis,
  options: {
    readonly search?: string;
    readonly limit?: number;
    readonly sort?: MethodTopMethodSort;
    readonly descending?: boolean;
  } = {},
): MethodTopMethod[] {
  const selfMicros = new Map<bigint, bigint>();
  const totalMicros = new Map<bigint, bigint>();
  const callCounts = new Map<bigint, number>();
  const threads = new Map<bigint, Set<string>>();

  analysis.events.forEach((event) => {
    if (event.action !== 'ENTER') return;
    callCounts.set(event.methodId, (callCounts.get(event.methodId) ?? 0) + 1);
  });

  table.stacks.forEach((stack) => {
    if (stack.frameIdsRootToLeaf.length === 0) return;
    stack.frameIdsRootToLeaf.forEach((frameId) => {
      const frame = table.framesById.get(frameId);
      if (frame === undefined) return;
      totalMicros.set(frame.functionId, (totalMicros.get(frame.functionId) ?? 0n) + stack.weight);
      const set = threads.get(frame.functionId) ?? new Set<string>();
      set.add(stack.threadKey);
      threads.set(frame.functionId, set);
    });
    const leaf = table.framesById.get(stack.frameIdsRootToLeaf[stack.frameIdsRootToLeaf.length - 1] as bigint);
    if (leaf === undefined) return;
    selfMicros.set(leaf.functionId, (selfMicros.get(leaf.functionId) ?? 0n) + stack.weight);
  });

  const search = (options.search ?? '').toLowerCase();
  const sort = options.sort ?? 'SELF_MICROS';
  const descending = options.descending ?? true;
  const limit = options.limit ?? 200;

  const rows = [...totalMicros.keys()]
    .map((functionId) => {
      const frame = frameForFunction(table, functionId);
      return {
        functionId: functionId.toString(),
        symbolName: frame?.symbolName ?? '0x' + functionId.toString(16),
        resource: frame?.resource ?? '',
        selfMicros: Number((selfMicros.get(functionId) ?? 0n) / NANOS_PER_MICRO),
        totalMicros: Number((totalMicros.get(functionId) ?? 0n) / NANOS_PER_MICRO),
        callCount: callCounts.get(functionId) ?? 0,
        threadCount: threads.get(functionId)?.size ?? 0,
      };
    })
    .filter((row) => search.length === 0 || row.symbolName.toLowerCase().includes(search));

  rows.sort((left, right) => {
    const primary = compareBySort(left, right, sort);
    if (primary !== 0) return descending ? -primary : primary;
    const byName = left.symbolName === right.symbolName ? 0 : left.symbolName < right.symbolName ? -1 : 1;
    return descending ? -byName : byName;
  });
  return rows.slice(0, limit);
}

function compareBySort(left: MethodTopMethod, right: MethodTopMethod, sort: MethodTopMethodSort): number {
  switch (sort) {
    case 'SYMBOL':
      return left.symbolName === right.symbolName ? 0 : left.symbolName < right.symbolName ? -1 : 1;
    case 'TOTAL_MICROS':
      return left.totalMicros - right.totalMicros;
    case 'CALL_COUNT':
      return left.callCount - right.callCount;
    default:
      return left.selfMicros - right.selfMicros;
  }
}

function frameForFunction(table: CallStackTable, functionId: bigint): CallStackFrame | undefined {
  for (const frame of table.framesById.values()) {
    if (frame.functionId === functionId) return frame;
  }
  return undefined;
}
