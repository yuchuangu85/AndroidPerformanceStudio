/**
 * Port of JavaHeapTraceParser.kt: Perfetto's android.java_hprof heap graph.
 *
 * The graph arrives as TracePacket messages that may be split across several
 * packets, each chunk carrying an index. Objects also delta-encode their ids and
 * their references against a base, which is why assembly happens before the
 * graph is handed to the analysis.
 *
 * The parser is deliberately forgiving about unknown fields (skip by wire type)
 * and strict about the two things that must hold: chunk indexes are contiguous
 * and the last chunk is not marked as continued.
 */
import type { Identifier } from './hprof.js';
import { ProtoReader, WIRE_LENGTH_DELIMITED, WIRE_VARINT, readPackedVarints } from './proto.js';

const TRACE_PACKET = 1;
const TRACE_PACKET_HEAP_GRAPH = 56;
const TRUSTED_PACKET_SEQUENCE_ID = 10;
const HEAP_GRAPH_PID = 1;
const HEAP_GRAPH_OBJECTS = 2;
const HEAP_GRAPH_FIELD_NAMES = 4;
const HEAP_GRAPH_CONTINUED = 5;
const HEAP_GRAPH_INDEX = 6;
const HEAP_GRAPH_ROOTS = 7;
const HEAP_GRAPH_LOCATION_NAMES = 8;
const HEAP_GRAPH_TYPES = 9;
const HEAP_GRAPH_BYTES_ALLOCATED = 10;
const TYPE_ID = 1;
const TYPE_LOCATION_ID = 2;
const TYPE_CLASS_NAME = 3;
const TYPE_OBJECT_SIZE = 4;
const TYPE_SUPERCLASS_ID = 5;
const TYPE_REFERENCE_FIELD_ID = 6;
const TYPE_KIND = 7;
const TYPE_CLASSLOADER_ID = 8;
const OBJECT_ID = 1;
const OBJECT_TYPE_ID = 2;
const OBJECT_SELF_SIZE = 3;
const OBJECT_REFERENCE_FIELD_ID = 4;
const OBJECT_REFERENCE_OBJECT_ID = 5;
const OBJECT_REFERENCE_OBJECT_ID_BASE = 6;
const OBJECT_ID_DELTA = 7;
const OBJECT_NATIVE_ALLOCATION_SIZE = 8;
const OBJECT_HEAP_TYPE_DELTA = 9;
const OBJECT_RUNTIME_INTERNAL_ID = 10;
const OBJECT_BITMAP_ID = 11;
const OBJECT_BITMAP_SOURCE_ID = 12;
const OBJECT_BITMAP_WIDTH = 13;
const OBJECT_BITMAP_HEIGHT = 14;
const OBJECT_APPLICATION_VERSION = 15;
const ROOT_OBJECT_IDS = 1;
const ROOT_TYPE = 2;
const INTERNED_ID = 1;
const INTERNED_STRING = 2;
export interface HeapGraphType {
  readonly id: Identifier;
  readonly className: string;
  readonly objectSize: number;
  readonly superclassId: Identifier;
  readonly kind: number;
  readonly referenceFieldIds: readonly Identifier[];
  readonly locationId: Identifier;
  readonly classLoaderId: Identifier;
}

export interface HeapGraphObject {
  readonly id: Identifier;
  readonly typeId: Identifier;
  readonly selfSize: number;
  readonly referenceFieldIds: readonly Identifier[];
  readonly referenceObjectIds: readonly Identifier[];
  readonly heapType: number;
  readonly nativeAllocationRegistrySize?: bigint;
  readonly bitmapId?: bigint;
  readonly bitmapSourceId?: bigint;
  readonly bitmapWidth?: number;
  readonly bitmapHeight?: number;
  readonly applicationInfoLongVersionCode?: bigint;
}

export interface HeapGraphRoot {
  readonly objectIds: readonly Identifier[];
  readonly rootType: number;
}

export interface HeapGraphData {
  readonly types: readonly HeapGraphType[];
  readonly objects: readonly HeapGraphObject[];
  readonly roots: readonly HeapGraphRoot[];
  readonly fieldNames: ReadonlyMap<Identifier, string>;
  readonly locationNames: ReadonlyMap<Identifier, string>;
  readonly pid: number | undefined;
  readonly heapBytesAllocated: bigint | undefined;
  readonly sequenceId: Identifier;
}

export type JavaHeapParseResult =
  | { readonly ok: true; readonly graph: HeapGraphData }
  | { readonly ok: false; readonly error: string };

interface RawObject {
  id: Identifier;
  typeId: Identifier;
  selfSize: number;
  referenceFieldIds: Identifier[];
  referenceObjectIds: Identifier[];
  referenceObjectIdBase: bigint;
  idDelta: bigint;
  heapType: number;
  nativeAllocationRegistrySize?: bigint;
  bitmapId?: bigint;
  bitmapSourceId?: bigint;
  bitmapWidth?: number;
  bitmapHeight?: number;
  applicationInfoLongVersionCode?: bigint;
}

function parseType(bytes: Uint8Array): HeapGraphType {
  const reader = new ProtoReader(bytes);
  let id = 0n;
  let locationId = 0n;
  let className = '';
  let objectSize = 0;
  let superclassId = 0n;
  let kind = 0;
  let classLoaderId = 0n;
  const referenceFieldIds: Identifier[] = [];
  while (!reader.atEnd) {
    const tag = reader.readTag();
    const fieldNumber = tag >>> 3;
    const wireType = tag & 7;
    switch (fieldNumber) {
      case TYPE_ID:
        id = reader.readVarint();
        break;
      case TYPE_LOCATION_ID:
        locationId = reader.readVarint();
        break;
      case TYPE_CLASS_NAME:
        className = new TextDecoder().decode(reader.readLengthDelimited());
        break;
      case TYPE_OBJECT_SIZE:
        objectSize = Number(reader.readVarint());
        break;
      case TYPE_SUPERCLASS_ID:
        superclassId = reader.readVarint();
        break;
      case TYPE_REFERENCE_FIELD_ID:
        readPackedVarints(reader, wireType, referenceFieldIds);
        break;
      case TYPE_KIND:
        kind = Number(reader.readVarint());
        break;
      case TYPE_CLASSLOADER_ID:
        classLoaderId = reader.readVarint();
        break;
      default:
        reader.skip(wireType);
        break;
    }
  }
  return { id, className, objectSize, superclassId, kind, referenceFieldIds, locationId, classLoaderId };
}

function parseObject(bytes: Uint8Array): RawObject {
  const reader = new ProtoReader(bytes);
  const raw: RawObject = {
    id: 0n,
    typeId: 0n,
    selfSize: 0,
    referenceFieldIds: [],
    referenceObjectIds: [],
    referenceObjectIdBase: 0n,
    idDelta: 0n,
    heapType: 0,
  };
  while (!reader.atEnd) {
    const tag = reader.readTag();
    const fieldNumber = tag >>> 3;
    const wireType = tag & 7;
    switch (fieldNumber) {
      case OBJECT_ID:
        raw.id = reader.readVarint();
        break;
      case OBJECT_TYPE_ID:
        raw.typeId = reader.readVarint();
        break;
      case OBJECT_SELF_SIZE:
        raw.selfSize = Number(reader.readVarint());
        break;
      case OBJECT_REFERENCE_FIELD_ID:
        readPackedVarints(reader, wireType, raw.referenceFieldIds);
        break;
      case OBJECT_REFERENCE_OBJECT_ID:
        readPackedVarints(reader, wireType, raw.referenceObjectIds);
        break;
      case OBJECT_REFERENCE_OBJECT_ID_BASE:
        raw.referenceObjectIdBase = reader.readVarint();
        break;
      case OBJECT_ID_DELTA:
        raw.idDelta = reader.readVarint();
        break;
      case OBJECT_NATIVE_ALLOCATION_SIZE:
        raw.nativeAllocationRegistrySize = reader.readVarint();
        break;
      case OBJECT_HEAP_TYPE_DELTA:
        raw.heapType = Number(reader.readVarint());
        break;
      case OBJECT_RUNTIME_INTERNAL_ID:
        readPackedVarints(reader, wireType, []);
        break;
      case OBJECT_BITMAP_ID:
        raw.bitmapId = reader.readVarint();
        break;
      case OBJECT_BITMAP_SOURCE_ID:
        raw.bitmapSourceId = reader.readVarint();
        break;
      case OBJECT_BITMAP_WIDTH:
        raw.bitmapWidth = Number(reader.readVarint());
        break;
      case OBJECT_BITMAP_HEIGHT:
        raw.bitmapHeight = Number(reader.readVarint());
        break;
      case OBJECT_APPLICATION_VERSION:
        raw.applicationInfoLongVersionCode = reader.readVarint();
        break;
      default:
        reader.skip(wireType);
        break;
    }
  }
  // Reference ids are stored relative to the base; a zero target stays null.
  raw.referenceObjectIds = raw.referenceObjectIds.map((target) =>
    target === 0n ? 0n : raw.referenceObjectIdBase + target,
  );
  return raw;
}

function parseRoot(bytes: Uint8Array): HeapGraphRoot {
  const reader = new ProtoReader(bytes);
  const objectIds: Identifier[] = [];
  let rootType = 0;
  while (!reader.atEnd) {
    const tag = reader.readTag();
    const fieldNumber = tag >>> 3;
    const wireType = tag & 7;
    if (fieldNumber === ROOT_OBJECT_IDS) readPackedVarints(reader, wireType, objectIds);
    else if (fieldNumber === ROOT_TYPE) rootType = Number(reader.readVarint());
    else reader.skip(wireType);
  }
  return { objectIds, rootType };
}

function parseInternedString(bytes: Uint8Array, target: Map<Identifier, string>): void {
  const reader = new ProtoReader(bytes);
  let iid = 0n;
  let value = '';
  while (!reader.atEnd) {
    const tag = reader.readTag();
    const fieldNumber = tag >>> 3;
    const wireType = tag & 7;
    if (fieldNumber === INTERNED_ID && wireType === WIRE_VARINT) iid = reader.readVarint();
    else if (fieldNumber === INTERNED_STRING && wireType === WIRE_LENGTH_DELIMITED) {
      value = new TextDecoder().decode(reader.readLengthDelimited());
    } else reader.skip(wireType);
  }
  if (iid !== 0n) target.set(iid, value);
}

interface Chunk {
  pid?: number;
  objects: RawObject[];
  fieldNames: Map<Identifier, string>;
  locationNames: Map<Identifier, string>;
  types: HeapGraphType[];
  roots: HeapGraphRoot[];
  bytesAllocated?: bigint;
  continued: boolean;
  index: bigint;
}

function parseHeapGraphChunk(bytes: Uint8Array): Chunk {
  const graph = new ProtoReader(bytes);
  const chunk: Chunk = {
    objects: [],
    fieldNames: new Map(),
    locationNames: new Map(),
    types: [],
    roots: [],
    continued: false,
    index: 0n,
  };
  while (!graph.atEnd) {
    const tag = graph.readTag();
    const fieldNumber = tag >>> 3;
    const wireType = tag & 7;
    switch (fieldNumber) {
      case HEAP_GRAPH_PID:
        chunk.pid = Number(graph.readVarint());
        break;
      case HEAP_GRAPH_OBJECTS:
        chunk.objects.push(parseObject(graph.readLengthDelimited()));
        break;
      case HEAP_GRAPH_FIELD_NAMES:
        parseInternedString(graph.readLengthDelimited(), chunk.fieldNames);
        break;
      case HEAP_GRAPH_CONTINUED:
        chunk.continued = graph.readVarint() !== 0n;
        break;
      case HEAP_GRAPH_INDEX:
        chunk.index = graph.readVarint();
        break;
      case HEAP_GRAPH_ROOTS:
        chunk.roots.push(parseRoot(graph.readLengthDelimited()));
        break;
      case HEAP_GRAPH_LOCATION_NAMES:
        parseInternedString(graph.readLengthDelimited(), chunk.locationNames);
        break;
      case HEAP_GRAPH_TYPES:
        chunk.types.push(parseType(graph.readLengthDelimited()));
        break;
      case HEAP_GRAPH_BYTES_ALLOCATED:
        chunk.bytesAllocated = graph.readVarint();
        break;
      default:
        graph.skip(wireType);
        break;
    }
  }
  return chunk;
}

interface Assembly {
  readonly sequenceId: Identifier;
  pid?: number;
  heapBytesAllocated?: bigint;
  types: HeapGraphType[];
  roots: HeapGraphRoot[];
  objects: HeapGraphObject[];
  fieldNames: Map<Identifier, string>;
  locationNames: Map<Identifier, string>;
  lastObjectId: bigint;
  lastHeapType: number;
  nextIndex: bigint;
  complete: boolean;
}

function acceptChunk(
  sequenceId: Identifier,
  chunk: Chunk,
  sequences: Map<string, Assembly>,
  complete: HeapGraphData[],
): void {
  const key = sequenceId.toString();
  if (chunk.index === 0n && sequences.get(key)?.complete === true) sequences.delete(key);
  let state = sequences.get(key);
  if (state === undefined) {
    state = {
      sequenceId,
      types: [],
      roots: [],
      objects: [],
      fieldNames: new Map(),
      locationNames: new Map(),
      lastObjectId: 0n,
      lastHeapType: 0,
      nextIndex: 0n,
      complete: false,
    };
    sequences.set(key, state);
  }
  if (chunk.index !== state.nextIndex) {
    throw new Error(
      'Missing java_hprof packet for sequence ' +
        sequenceId.toString() +
        ': expected index ' +
        state.nextIndex.toString() +
        ', got ' +
        chunk.index.toString() +
        '.',
    );
  }
  if (chunk.pid !== undefined) state.pid = chunk.pid;
  if (chunk.bytesAllocated !== undefined) state.heapBytesAllocated = chunk.bytesAllocated;
  state.types.push(...chunk.types);
  state.roots.push(...chunk.roots);
  for (const [id, name] of chunk.fieldNames) state.fieldNames.set(id, name);
  for (const [id, name] of chunk.locationNames) state.locationNames.set(id, name);
  for (const raw of chunk.objects) {
    const objectId = raw.idDelta !== 0n ? state.lastObjectId + raw.idDelta : raw.id;
    state.lastObjectId = objectId;
    if (raw.heapType !== 0) state.lastHeapType = raw.heapType;
    state.objects.push({
      id: objectId,
      typeId: raw.typeId,
      selfSize: raw.selfSize,
      referenceFieldIds: raw.referenceFieldIds,
      referenceObjectIds: raw.referenceObjectIds,
      heapType: state.lastHeapType,
      ...(raw.nativeAllocationRegistrySize !== undefined
        ? { nativeAllocationRegistrySize: raw.nativeAllocationRegistrySize }
        : {}),
      ...(raw.bitmapId !== undefined ? { bitmapId: raw.bitmapId } : {}),
      ...(raw.bitmapSourceId !== undefined ? { bitmapSourceId: raw.bitmapSourceId } : {}),
      ...(raw.bitmapWidth !== undefined ? { bitmapWidth: raw.bitmapWidth } : {}),
      ...(raw.bitmapHeight !== undefined ? { bitmapHeight: raw.bitmapHeight } : {}),
      ...(raw.applicationInfoLongVersionCode !== undefined
        ? { applicationInfoLongVersionCode: raw.applicationInfoLongVersionCode }
        : {}),
    });
  }
  state.nextIndex += 1n;
  state.complete = !chunk.continued;
  if (state.complete) {
    complete.push({
      types: state.types,
      objects: state.objects,
      roots: state.roots,
      fieldNames: state.fieldNames,
      locationNames: state.locationNames,
      pid: state.pid,
      heapBytesAllocated: state.heapBytesAllocated,
      sequenceId: state.sequenceId,
    });
  }
}

function parsePacket(
  bytes: Uint8Array,
  sequences: Map<string, Assembly>,
  complete: HeapGraphData[],
): boolean {
  const packet = new ProtoReader(bytes);
  let sequenceId = 0n;
  const chunks: Uint8Array[] = [];
  while (!packet.atEnd) {
    const tag = packet.readTag();
    const fieldNumber = tag >>> 3;
    const wireType = tag & 7;
    if (fieldNumber === TRUSTED_PACKET_SEQUENCE_ID && wireType === WIRE_VARINT) {
      sequenceId = packet.readVarint();
    } else if (fieldNumber === TRACE_PACKET_HEAP_GRAPH && wireType === WIRE_LENGTH_DELIMITED) {
      chunks.push(packet.readLengthDelimited());
    } else packet.skip(wireType);
  }
  for (const chunk of chunks) acceptChunk(sequenceId, parseHeapGraphChunk(chunk), sequences, complete);
  return chunks.length > 0;
}

/** Parses a Perfetto trace and returns the last complete heap graph it holds. */
export function parseJavaHeapTrace(bytes: Uint8Array): JavaHeapParseResult {
  try {
    const trace = new ProtoReader(bytes);
    const sequences = new Map<string, Assembly>();
    const complete: HeapGraphData[] = [];
    let sawHeapGraph = false;
    while (!trace.atEnd) {
      const tag = trace.readTag();
      const fieldNumber = tag >>> 3;
      const wireType = tag & 7;
      if (fieldNumber === TRACE_PACKET && wireType === WIRE_LENGTH_DELIMITED) {
        sawHeapGraph = parsePacket(trace.readLengthDelimited(), sequences, complete) || sawHeapGraph;
      } else if (fieldNumber === TRACE_PACKET_HEAP_GRAPH && wireType === WIRE_LENGTH_DELIMITED) {
        // Bare TracePacket fixtures, kept for parity with the Kotlin parser.
        sawHeapGraph = true;
        acceptChunk(0n, parseHeapGraphChunk(trace.readLengthDelimited()), sequences, complete);
      } else {
        trace.skip(wireType);
      }
    }
    const graph = complete.at(-1);
    if (graph !== undefined) return { ok: true, graph };
    if (sawHeapGraph) return { ok: false, error: 'Incomplete java_hprof heap graph.' };
    return { ok: false, error: 'No java_hprof heap graph found in the trace.' };
  } catch (error) {
    return { ok: false, error: error instanceof Error ? error.message : 'Malformed Java heap trace.' };
  }
}

export function javaHeapReadFailure(error: unknown): JavaHeapParseResult {
  return {
    ok: false,
    error: 'Unable to read Java heap trace: ' + (error instanceof Error ? error.message : ''),
  };
}
