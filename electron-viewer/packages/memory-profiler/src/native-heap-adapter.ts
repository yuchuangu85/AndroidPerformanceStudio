/**
 * Port of NativeHeapTraceProcessorAdapter.kt: the allocation summary read
 * through the pinned Trace Processor, which resolves callsites to symbol names
 * (including unsymbolized mapping+offset frames) that the wire parser cannot.
 */
import type { StudioResult } from '@aps/contracts';
import { TraceColumn, TraceQuery, traceQuerySchemaV57_2 } from '@aps/platform-perfetto';
import type { TraceQueryRunner } from './trace-query-runner.js';
import { NATIVE_HEAP_CAPABILITIES, type NativeHeapAnalysis, type NativeHeapSample } from './native-heap-trace.js';

const UNKNOWN_FRAME = 'unknown';

export interface AllocationRow {
  readonly callsiteId: bigint;
  readonly allocatedBytes: number;
  readonly freedBytes: number;
  readonly allocCount: number;
  readonly freeCount: number;
}

export interface CallStackRow {
  readonly callsiteId: bigint;
  readonly parentId?: bigint;
  readonly frameName: string;
  readonly symbolized: boolean;
}

function bigIntegerOf(value: string | undefined): bigint | undefined {
  if (value === undefined) return undefined;
  const trimmed = value.trim();
  if (!/^-?[0-9]+$/.test(trimmed)) return undefined;
  return BigInt(trimmed);
}

function numberOf(value: string | undefined): number {
  if (value === undefined) return 0;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

export const NATIVE_HEAP_ALLOCATION_QUERY = new TraceQuery<AllocationRow>(
  [
    'SELECT callsite_id,',
    '       SUM(CASE WHEN size > 0 THEN size ELSE 0 END) AS allocated_bytes,',
    '       SUM(CASE WHEN size < 0 THEN -size ELSE 0 END) AS freed_bytes,',
    '       SUM(CASE WHEN count > 0 THEN count ELSE 0 END) AS alloc_count,',
    '       SUM(CASE WHEN count < 0 THEN -count ELSE 0 END) AS free_count',
    'FROM heap_profile_allocation',
    'GROUP BY callsite_id',
    'ORDER BY allocated_bytes DESC',
  ].join('\n'),
  traceQuerySchemaV57_2(
    TraceColumn.string('callsite_id'),
    TraceColumn.string('allocated_bytes'),
    TraceColumn.string('freed_bytes'),
    TraceColumn.string('alloc_count'),
    TraceColumn.string('free_count'),
  ),
  (row) => ({
    callsiteId: bigIntegerOf(row.string('callsite_id')) ?? 0n,
    allocatedBytes: numberOf(row.string('allocated_bytes')),
    freedBytes: numberOf(row.string('freed_bytes')),
    allocCount: numberOf(row.string('alloc_count')),
    freeCount: numberOf(row.string('free_count')),
  }),
);

export const NATIVE_HEAP_CALLSTACK_QUERY = new TraceQuery<CallStackRow>(
  [
    'SELECT callsite.id AS callsite_id,',
    '       callsite.parent_id AS parent_id,',
    "       COALESCE(NULLIF(frame.name, ''), mapping.name || '+0x' || printf('%x', frame.rel_pc)) AS frame_name,",
    "       CASE WHEN frame.name IS NOT NULL AND frame.name != '' THEN 1 ELSE 0 END AS symbolized",
    'FROM stack_profile_callsite AS callsite',
    'JOIN stack_profile_frame AS frame ON frame.id = callsite.frame_id',
    'LEFT JOIN stack_profile_mapping AS mapping ON mapping.id = frame.mapping',
    'ORDER BY callsite.id',
  ].join('\n'),
  traceQuerySchemaV57_2(
    TraceColumn.string('callsite_id'),
    TraceColumn.string('parent_id'),
    TraceColumn.string('frame_name'),
    TraceColumn.string('symbolized'),
  ),
  (row) => {
    const parentId = bigIntegerOf(row.string('parent_id'));
    return {
      callsiteId: bigIntegerOf(row.string('callsite_id')) ?? 0n,
      ...(parentId !== undefined ? { parentId } : {}),
      frameName: row.string('frame_name') ?? UNKNOWN_FRAME,
      symbolized: numberOf(row.string('symbolized')) === 1,
    };
  },
);

export interface NativeHeapProcessorResult {
  readonly analysis: NativeHeapAnalysis;
  readonly availableCapabilities: readonly string[];
}

/** Walks parent links from a leaf callsite, guarding against cycles. */
export function resolveCallStack(
  leaf: bigint,
  frames: ReadonlyMap<string, CallStackRow>,
): string[] {
  const names: string[] = [];
  const visited = new Set<string>();
  let current: bigint | undefined = leaf;
  while (current !== undefined && !visited.has(current.toString())) {
    visited.add(current.toString());
    const frame = frames.get(current.toString());
    if (frame === undefined) break;
    names.push(frame.frameName);
    current = frame.parentId;
  }
  return names.reverse();
}

export function mapNativeHeapRows(
  allocations: readonly AllocationRow[],
  callStacks: readonly CallStackRow[],
): NativeHeapProcessorResult {
  const frames = new Map<string, CallStackRow>();
  for (const row of callStacks) frames.set(row.callsiteId.toString(), row);

  const samples: NativeHeapSample[] = allocations.map((allocation) => {
    const stack = resolveCallStack(allocation.callsiteId, frames);
    return {
      functionName: stack.at(-1) ?? UNKNOWN_FRAME,
      allocatedBytes: allocation.allocatedBytes,
      freedBytes: allocation.freedBytes,
      allocCount: allocation.allocCount,
      freeCount: allocation.freeCount,
      callStack: stack.length > 0 ? stack : [UNKNOWN_FRAME],
    };
  });

  const capabilities: string[] = [
    NATIVE_HEAP_CAPABILITIES.ALLOCATIONS,
    NATIVE_HEAP_CAPABILITIES.DEALLOCATIONS,
    NATIVE_HEAP_CAPABILITIES.COUNTS,
    NATIVE_HEAP_CAPABILITIES.CALL_STACKS,
  ];
  if (callStacks.some((row) => row.symbolized)) capabilities.push(NATIVE_HEAP_CAPABILITIES.SYMBOLS);

  const allocCount = samples.reduce((total, sample) => total + sample.allocCount, 0);
  const sorted = [...samples].sort((left, right) => right.allocatedBytes - left.allocatedBytes);
  return {
    analysis: {
      totalAllocatedBytes: samples.reduce((total, sample) => total + sample.allocatedBytes, 0),
      totalFreedBytes: samples.reduce((total, sample) => total + sample.freedBytes, 0),
      sampleCount: Math.min(allocCount, Number.MAX_SAFE_INTEGER),
      topAllocations: sorted,
    },
    availableCapabilities: capabilities,
  };
}

/** Runs both queries and returns the same shape the wire parser produces. */
export async function analyzeNativeHeapTrace(
  runner: TraceQueryRunner,
): Promise<StudioResult<NativeHeapProcessorResult>> {
  const allocations = await runner.query(NATIVE_HEAP_ALLOCATION_QUERY);
  if (!allocations.ok) return allocations;
  const callStacks = await runner.query(NATIVE_HEAP_CALLSTACK_QUERY);
  if (!callStacks.ok) return callStacks;
  return { ok: true, value: mapNativeHeapRows(allocations.value, callStacks.value) };
}
