/**
 * Port of NativeHeapTraceParser.kt: a best-effort summary of a heapprofd
 * (Perfetto native heap) `.pb` trace.
 *
 * The wire format is read directly and the pre-aggregated ProfilePacket data is
 * used: allocation samples grouped by callstack plus the interned function
 * names that give them a symbol. The raw trace stays authoritative — sequence
 * state, symbolization, call trees, and guardrail diagnostics belong to
 * Perfetto, and this only answers "which functions allocated the most".
 */
import type { Identifier } from './hprof.js';
import {
  ProtoReader,
  WIRE_LENGTH_DELIMITED,
  WIRE_VARINT,
  toByteCount,
} from './proto.js';

const UNKNOWN_SYMBOL = '<unknown>';
const MAX_TOP_ALLOCATIONS = 50;

const TRACE_PACKET_FIELD = 1;
const PROFILE_PACKET_FIELD = 37;
const INTERNED_DATA_FIELD = 12;
const TRUSTED_PACKET_SEQUENCE_ID_FIELD = 10;
const INCREMENTAL_STATE_CLEARED_FIELD = 41;
const INTERNED_FUNCTION_NAMES_FIELD = 5;
const INTERNED_FRAMES_FIELD = 6;
const INTERNED_CALLSTACKS_FIELD = 7;
const INTERNED_STRINGS_FIELD = 1;
const FRAMES_FIELD = 2;
const CALLSTACKS_FIELD = 3;
const PROCESS_DUMPS_FIELD = 5;
const PROCESS_SAMPLES_FIELD = 2;
const SAMPLE_CALLSTACK_ID = 1;
const SAMPLE_SELF_ALLOCATED = 2;
const SAMPLE_SELF_FREED = 3;
const SAMPLE_ALLOC_COUNT = 5;
const SAMPLE_FREE_COUNT = 6;

export interface NativeHeapSample {
  readonly functionName: string;
  readonly allocatedBytes: number;
  readonly freedBytes: number;
  readonly allocCount: number;
  readonly freeCount: number;
  readonly callStack: readonly string[];
}

export interface NativeHeapAnalysis {
  readonly totalAllocatedBytes: number;
  readonly totalFreedBytes: number;
  readonly sampleCount: number;
  readonly topAllocations: readonly NativeHeapSample[];
}

export const EMPTY_NATIVE_HEAP_ANALYSIS: NativeHeapAnalysis = {
  totalAllocatedBytes: 0,
  totalFreedBytes: 0,
  sampleCount: 0,
  topAllocations: [],
};

export const NATIVE_HEAP_CAPABILITIES = {
  ALLOCATIONS: 'native_heap.allocations',
  DEALLOCATIONS: 'native_heap.deallocations',
  COUNTS: 'native_heap.counts',
  CALL_STACKS: 'native_heap.call_stacks',
  SYMBOLS: 'native_heap.symbols',
} as const;

interface InterningState {
  readonly strings: Map<Identifier, string>;
  readonly frameFunctions: Map<Identifier, Identifier>;
  readonly callstackFrames: Map<Identifier, Identifier[]>;
}

interface RawSample {
  callstackId: Identifier;
  allocated: number;
  freed: number;
  allocCount: number;
  freeCount: number;
}

interface ResolvedSample {
  readonly functionName: string;
  readonly callStack: readonly string[];
  readonly raw: RawSample;
}

function newInterningState(): InterningState {
  return { strings: new Map(), frameFunctions: new Map(), callstackFrames: new Map() };
}

function clearState(state: InterningState): void {
  state.strings.clear();
  state.frameFunctions.clear();
  state.callstackFrames.clear();
}

function parseInternedString(bytes: Uint8Array, strings: Map<Identifier, string>): void {
  const reader = new ProtoReader(bytes);
  let iid = 0n;
  let value = '';
  while (!reader.atEnd) {
    const tag = reader.readTag();
    const fieldNumber = tag >>> 3;
    const wireType = tag & 7;
    if (fieldNumber === 1 && wireType === WIRE_VARINT) iid = reader.readVarint();
    else if (fieldNumber === 2 && wireType === WIRE_LENGTH_DELIMITED) {
      value = new TextDecoder().decode(reader.readLengthDelimited());
    } else reader.skip(wireType);
  }
  if (iid !== 0n) strings.set(iid, value);
}

function parseFrame(bytes: Uint8Array, frameFunctions: Map<Identifier, Identifier>): void {
  const reader = new ProtoReader(bytes);
  let iid = 0n;
  let functionNameId = 0n;
  while (!reader.atEnd) {
    const tag = reader.readTag();
    const fieldNumber = tag >>> 3;
    const wireType = tag & 7;
    if (fieldNumber === 1 && wireType === WIRE_VARINT) iid = reader.readVarint();
    else if (fieldNumber === 2 && wireType === WIRE_VARINT) functionNameId = reader.readVarint();
    else reader.skip(wireType);
  }
  if (iid !== 0n) frameFunctions.set(iid, functionNameId);
}

function parseCallstack(bytes: Uint8Array, callstackFrames: Map<Identifier, Identifier[]>): void {
  const reader = new ProtoReader(bytes);
  let iid = 0n;
  const frames: Identifier[] = [];
  while (!reader.atEnd) {
    const tag = reader.readTag();
    const fieldNumber = tag >>> 3;
    const wireType = tag & 7;
    if (fieldNumber === 1 && wireType === WIRE_VARINT) iid = reader.readVarint();
    else if (fieldNumber === 2 && wireType === WIRE_VARINT) frames.push(reader.readVarint());
    else reader.skip(wireType);
  }
  if (iid !== 0n) callstackFrames.set(iid, frames);
}

function parseInternedData(bytes: Uint8Array, state: InterningState): void {
  const data = new ProtoReader(bytes);
  while (!data.atEnd) {
    const tag = data.readTag();
    const fieldNumber = tag >>> 3;
    const wireType = tag & 7;
    if (wireType !== WIRE_LENGTH_DELIMITED) {
      data.skip(wireType);
      continue;
    }
    if (fieldNumber === INTERNED_FUNCTION_NAMES_FIELD) {
      parseInternedString(data.readLengthDelimited(), state.strings);
    } else if (fieldNumber === INTERNED_FRAMES_FIELD) {
      parseFrame(data.readLengthDelimited(), state.frameFunctions);
    } else if (fieldNumber === INTERNED_CALLSTACKS_FIELD) {
      parseCallstack(data.readLengthDelimited(), state.callstackFrames);
    } else {
      data.skip(wireType);
    }
  }
}

function parseSample(bytes: Uint8Array, samples: RawSample[]): void {
  const reader = new ProtoReader(bytes);
  const sample: RawSample = { callstackId: 0n, allocated: 0, freed: 0, allocCount: 0, freeCount: 0 };
  while (!reader.atEnd) {
    const tag = reader.readTag();
    const fieldNumber = tag >>> 3;
    const wireType = tag & 7;
    if (wireType !== WIRE_VARINT) {
      reader.skip(wireType);
      continue;
    }
    switch (fieldNumber) {
      case SAMPLE_CALLSTACK_ID:
        sample.callstackId = reader.readVarint();
        break;
      case SAMPLE_SELF_ALLOCATED:
        sample.allocated = toByteCount(reader.readVarint(), 'self_allocated');
        break;
      case SAMPLE_SELF_FREED:
        sample.freed = toByteCount(reader.readVarint(), 'self_freed');
        break;
      case SAMPLE_ALLOC_COUNT:
        sample.allocCount = toByteCount(reader.readVarint(), 'alloc_count');
        break;
      case SAMPLE_FREE_COUNT:
        sample.freeCount = toByteCount(reader.readVarint(), 'free_count');
        break;
      default:
        reader.readVarint();
        break;
    }
  }
  samples.push(sample);
}

function parseProcessDump(bytes: Uint8Array, samples: RawSample[]): void {
  const reader = new ProtoReader(bytes);
  while (!reader.atEnd) {
    const tag = reader.readTag();
    const fieldNumber = tag >>> 3;
    const wireType = tag & 7;
    if (fieldNumber === PROCESS_SAMPLES_FIELD && wireType === WIRE_LENGTH_DELIMITED) {
      parseSample(reader.readLengthDelimited(), samples);
    } else {
      reader.skip(wireType);
    }
  }
}

function parseProfilePacket(bytes: Uint8Array, state: InterningState, samples: ResolvedSample[]): void {
  const profile = new ProtoReader(bytes);
  const internedStrings: Uint8Array[] = [];
  const frames: Uint8Array[] = [];
  const callstacks: Uint8Array[] = [];
  const processDumps: Uint8Array[] = [];
  while (!profile.atEnd) {
    const tag = profile.readTag();
    const fieldNumber = tag >>> 3;
    const wireType = tag & 7;
    if (wireType !== WIRE_LENGTH_DELIMITED) {
      profile.skip(wireType);
      continue;
    }
    if (fieldNumber === INTERNED_STRINGS_FIELD) internedStrings.push(profile.readLengthDelimited());
    else if (fieldNumber === FRAMES_FIELD) frames.push(profile.readLengthDelimited());
    else if (fieldNumber === CALLSTACKS_FIELD) callstacks.push(profile.readLengthDelimited());
    else if (fieldNumber === PROCESS_DUMPS_FIELD) processDumps.push(profile.readLengthDelimited());
    else profile.skip(wireType);
  }
  for (const value of internedStrings) parseInternedString(value, state.strings);
  for (const value of frames) parseFrame(value, state.frameFunctions);
  for (const value of callstacks) parseCallstack(value, state.callstackFrames);

  const rawSamples: RawSample[] = [];
  for (const value of processDumps) parseProcessDump(value, rawSamples);
  for (const sample of rawSamples) {
    const frameIds = state.callstackFrames.get(sample.callstackId) ?? [];
    const names = frameIds.map((frameId) => {
      const functionId = state.frameFunctions.get(frameId);
      return functionId === undefined ? UNKNOWN_SYMBOL : (state.strings.get(functionId) ?? UNKNOWN_SYMBOL);
    });
    samples.push({
      functionName: names.at(-1) ?? UNKNOWN_SYMBOL,
      callStack: names.length > 0 ? names : [UNKNOWN_SYMBOL],
      raw: sample,
    });
  }
}

function parsePacket(bytes: Uint8Array, sequences: Map<string, InterningState>, samples: ResolvedSample[]): void {
  const packet = new ProtoReader(bytes);
  let sequenceId = 0n;
  let clearIncrementalState = false;
  const internedData: Uint8Array[] = [];
  const profilePackets: Uint8Array[] = [];
  while (!packet.atEnd) {
    const tag = packet.readTag();
    const fieldNumber = tag >>> 3;
    const wireType = tag & 7;
    if (fieldNumber === PROFILE_PACKET_FIELD && wireType === WIRE_LENGTH_DELIMITED) {
      profilePackets.push(packet.readLengthDelimited());
    } else if (fieldNumber === INTERNED_DATA_FIELD && wireType === WIRE_LENGTH_DELIMITED) {
      internedData.push(packet.readLengthDelimited());
    } else if (fieldNumber === TRUSTED_PACKET_SEQUENCE_ID_FIELD && wireType === WIRE_VARINT) {
      sequenceId = packet.readVarint();
    } else if (fieldNumber === INCREMENTAL_STATE_CLEARED_FIELD && wireType === WIRE_VARINT) {
      clearIncrementalState = packet.readVarint() !== 0n;
    } else {
      packet.skip(wireType);
    }
  }
  const key = sequenceId.toString();
  let state = sequences.get(key);
  if (state === undefined) {
    state = newInterningState();
    sequences.set(key, state);
  }
  if (clearIncrementalState) clearState(state);
  for (const value of internedData) parseInternedData(value, state);
  for (const value of profilePackets) parseProfilePacket(value, state, samples);
}

function buildAnalysis(samples: readonly ResolvedSample[]): NativeHeapAnalysis {
  const aggregates = new Map<string, { allocated: number; freed: number; allocCount: number; freeCount: number; callStack: readonly string[] }>();
  let totalAllocated = 0;
  let totalFreed = 0;
  for (const sample of samples) {
    totalAllocated += sample.raw.allocated;
    totalFreed += sample.raw.freed;
    const existing = aggregates.get(sample.functionName);
    if (existing === undefined) {
      aggregates.set(sample.functionName, {
        allocated: sample.raw.allocated,
        freed: sample.raw.freed,
        allocCount: sample.raw.allocCount,
        freeCount: sample.raw.freeCount,
        callStack: sample.callStack,
      });
    } else {
      existing.allocated += sample.raw.allocated;
      existing.freed += sample.raw.freed;
      existing.allocCount += sample.raw.allocCount;
      existing.freeCount += sample.raw.freeCount;
    }
  }
  const topAllocations: NativeHeapSample[] = [...aggregates].map(([functionName, aggregate]) => ({
    functionName,
    allocatedBytes: aggregate.allocated,
    freedBytes: aggregate.freed,
    allocCount: aggregate.allocCount,
    freeCount: aggregate.freeCount,
    callStack: aggregate.callStack,
  }));
  topAllocations.sort((left, right) => {
    if (right.allocatedBytes !== left.allocatedBytes) return right.allocatedBytes - left.allocatedBytes;
    return left.functionName < right.functionName ? -1 : left.functionName > right.functionName ? 1 : 0;
  });
  return {
    totalAllocatedBytes: totalAllocated,
    totalFreedBytes: totalFreed,
    sampleCount: samples.length,
    topAllocations: topAllocations.slice(0, MAX_TOP_ALLOCATIONS),
  };
}

/** Strict parse: malformed bytes are an error, never an empty result. */
export function parseNativeHeapTraceStrict(bytes: Uint8Array): NativeHeapAnalysis {
  const sequences = new Map<string, InterningState>();
  const samples: ResolvedSample[] = [];
  const trace = new ProtoReader(bytes);
  while (!trace.atEnd) {
    const tag = trace.readTag();
    const fieldNumber = tag >>> 3;
    const wireType = tag & 7;
    if (fieldNumber === TRACE_PACKET_FIELD && wireType === WIRE_LENGTH_DELIMITED) {
      parsePacket(trace.readLengthDelimited(), sequences, samples);
    } else {
      trace.skip(wireType);
    }
  }
  return buildAnalysis(samples);
}

/** Lenient parse used when the summary is best-effort. */
export function parseNativeHeapTrace(bytes: Uint8Array): NativeHeapAnalysis {
  try {
    return parseNativeHeapTraceStrict(bytes);
  } catch {
    return EMPTY_NATIVE_HEAP_ANALYSIS;
  }
}

export type NativeHeapEvidenceSource = 'TRACE_PROCESSOR' | 'WIRE_FALLBACK';
