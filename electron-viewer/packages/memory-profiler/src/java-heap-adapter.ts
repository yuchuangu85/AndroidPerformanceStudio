/**
 * Port of JavaHeapTraceProcessorAdapter.kt: the same heap graph, read through
 * the pinned Trace Processor instead of the trace bytes.
 *
 * Ids are read as strings and converted to bigint: a heap dump's object ids are
 * full 64-bit values, and routing them through a JavaScript number would round
 * them and silently merge objects.
 */
import type { StudioResult } from '@aps/contracts';
import { TraceColumn, TraceQuery, traceQuerySchemaV57_2 } from '@aps/platform-perfetto';
import type { TraceQueryRunner } from './trace-query-runner.js';
import type { HeapGraphData, HeapGraphObject, HeapGraphRoot, HeapGraphType } from './java-heap-trace.js';

const UNKNOWN_CLASS_NAME = '<unknown>';
const KIND_ARRAY = 4;

export interface JavaHeapClassRow {
  readonly id: bigint;
  readonly name?: string;
  readonly superclassId?: bigint;
  readonly classLoaderId?: bigint;
  readonly kind?: string;
}

export interface JavaHeapObjectRow {
  readonly id: bigint;
  readonly selfSize?: bigint;
  readonly typeId: bigint;
  readonly heapType?: string;
  readonly rootType?: string;
}

export interface JavaHeapReferenceRow {
  readonly ownerId: bigint;
  readonly ownedId: bigint;
  readonly fieldName?: string;
}

function bigIntegerOf(value: string | undefined): bigint | undefined {
  if (value === undefined) return undefined;
  const trimmed = value.trim();
  if (!/^-?[0-9]+$/.test(trimmed)) return undefined;
  return BigInt(trimmed);
}

export const JAVA_HEAP_CLASS_QUERY = new TraceQuery<JavaHeapClassRow>(
  'SELECT id, COALESCE(deobfuscated_name, name) AS class_name, superclass_id, classloader_id, kind FROM heap_graph_class ORDER BY id',
  traceQuerySchemaV57_2(
    TraceColumn.string('id'),
    TraceColumn.string('class_name'),
    TraceColumn.string('superclass_id'),
    TraceColumn.string('classloader_id'),
    TraceColumn.string('kind'),
  ),
  (row) => {
    const name = row.string('class_name');
    const superclassId = bigIntegerOf(row.string('superclass_id'));
    const classLoaderId = bigIntegerOf(row.string('classloader_id'));
    const kind = row.string('kind');
    return {
      id: bigIntegerOf(row.string('id')) ?? 0n,
      ...(name !== undefined ? { name } : {}),
      ...(superclassId !== undefined ? { superclassId } : {}),
      ...(classLoaderId !== undefined ? { classLoaderId } : {}),
      ...(kind !== undefined ? { kind } : {}),
    };
  },
);

export const JAVA_HEAP_OBJECT_QUERY = new TraceQuery<JavaHeapObjectRow>(
  'SELECT id, self_size, type_id, heap_type, root_type FROM heap_graph_object ORDER BY id',
  traceQuerySchemaV57_2(
    TraceColumn.string('id'),
    TraceColumn.string('self_size'),
    TraceColumn.string('type_id'),
    TraceColumn.string('heap_type'),
    TraceColumn.string('root_type'),
  ),
  (row) => {
    const selfSize = bigIntegerOf(row.string('self_size'));
    const heapType = row.string('heap_type');
    const rootType = row.string('root_type');
    return {
      id: bigIntegerOf(row.string('id')) ?? 0n,
      ...(selfSize !== undefined ? { selfSize } : {}),
      typeId: bigIntegerOf(row.string('type_id')) ?? 0n,
      ...(heapType !== undefined ? { heapType } : {}),
      ...(rootType !== undefined ? { rootType } : {}),
    };
  },
);

export const JAVA_HEAP_REFERENCE_QUERY = new TraceQuery<JavaHeapReferenceRow>(
  'SELECT owner_id, owned_id, COALESCE(deobfuscated_field_name, field_name) AS field_name FROM heap_graph_reference ORDER BY owner_id, id',
  traceQuerySchemaV57_2(
    TraceColumn.string('owner_id'),
    TraceColumn.string('owned_id'),
    TraceColumn.string('field_name'),
  ),
  (row) => {
    const fieldName = row.string('field_name');
    return {
      ownerId: bigIntegerOf(row.string('owner_id')) ?? 0n,
      ownedId: bigIntegerOf(row.string('owned_id')) ?? 0n,
      ...(fieldName !== undefined ? { fieldName } : {}),
    };
  },
);

function heapTypeOf(value: string | undefined): number {
  switch (value?.toLowerCase()) {
    case 'app':
      return 1;
    case 'zygote':
      return 2;
    case 'image':
      return 3;
    default:
      return 0;
  }
}

function rootTypeOf(value: string): number {
  switch (value.toLowerCase()) {
    case 'jni_global':
      return 1;
    case 'jni_local':
      return 2;
    case 'java_frame':
      return 3;
    case 'native_stack':
      return 4;
    case 'sticky_class':
      return 5;
    case 'thread_block':
      return 6;
    case 'monitor_used':
      return 7;
    case 'thread_object':
      return 8;
    case 'interned_string':
      return 9;
    case 'finalizing':
      return 10;
    case 'debugger':
      return 11;
    case 'reference_cleanup':
      return 12;
    case 'vm_internal':
      return 13;
    case 'jni_monitor':
      return 14;
    default:
      return 0;
  }
}

/** Reassembles the graph the wire parser would have produced. */
export function mapJavaHeapRows(
  classes: readonly JavaHeapClassRow[],
  objects: readonly JavaHeapObjectRow[],
  references: readonly JavaHeapReferenceRow[],
): HeapGraphData {
  const fieldIds = new Map<string, bigint>();
  const fieldId = (name: string): bigint => {
    const existing = fieldIds.get(name);
    if (existing !== undefined) return existing;
    const id = BigInt(fieldIds.size + 1);
    fieldIds.set(name, id);
    return id;
  };

  const types: HeapGraphType[] = classes.map((row) => ({
    id: row.id,
    className: row.name ?? UNKNOWN_CLASS_NAME,
    objectSize: 0,
    superclassId: row.superclassId ?? 0n,
    kind: row.kind?.toLowerCase() === 'array' ? KIND_ARRAY : 0,
    referenceFieldIds: [],
    locationId: 0n,
    classLoaderId: row.classLoaderId ?? 0n,
  }));

  const referencesByOwner = new Map<string, JavaHeapReferenceRow[]>();
  for (const reference of references) {
    const key = reference.ownerId.toString();
    const bucket = referencesByOwner.get(key);
    if (bucket === undefined) referencesByOwner.set(key, [reference]);
    else bucket.push(reference);
  }

  const graphObjects: HeapGraphObject[] = objects.map((row) => {
    const owned = referencesByOwner.get(row.id.toString()) ?? [];
    return {
      id: row.id,
      typeId: row.typeId,
      selfSize: row.selfSize === undefined ? 0 : Number(row.selfSize),
      referenceFieldIds: owned.map((reference) => fieldId(reference.fieldName ?? '<unknown>')),
      referenceObjectIds: owned.map((reference) => reference.ownedId),
      heapType: heapTypeOf(row.heapType),
    };
  });

  const roots: HeapGraphRoot[] = objects
    .filter((row) => row.rootType !== undefined)
    .map((row) => ({ objectIds: [row.id], rootType: rootTypeOf(row.rootType ?? '') }));

  const fieldNames = new Map<bigint, string>();
  for (const [name, id] of fieldIds) fieldNames.set(id, name);

  return {
    types,
    objects: graphObjects,
    roots,
    fieldNames,
    locationNames: new Map(),
    pid: undefined,
    heapBytesAllocated: undefined,
    sequenceId: 0n,
  };
}

export interface JavaHeapAnalysis {
  readonly graph: HeapGraphData;
  readonly availableCapabilities: readonly string[];
}

export const JAVA_HEAP_CAPABILITIES = {
  CLASSES: 'java_heap.classes',
  OBJECTS: 'java_heap.objects',
  REFERENCES: 'java_heap.references',
  ROOTS: 'java_heap.roots',
  SHALLOW_SIZES: 'java_heap.shallow_sizes',
} as const;

/** Runs the three heap-graph queries and assembles the result. */
export async function analyzeJavaHeapTrace(
  runner: TraceQueryRunner,
): Promise<StudioResult<JavaHeapAnalysis>> {
  const classes = await runner.query(JAVA_HEAP_CLASS_QUERY);
  if (!classes.ok) return classes;
  const objects = await runner.query(JAVA_HEAP_OBJECT_QUERY);
  if (!objects.ok) return objects;
  const references = await runner.query(JAVA_HEAP_REFERENCE_QUERY);
  if (!references.ok) return references;

  const capabilities: string[] = [
    JAVA_HEAP_CAPABILITIES.CLASSES,
    JAVA_HEAP_CAPABILITIES.OBJECTS,
    JAVA_HEAP_CAPABILITIES.REFERENCES,
    JAVA_HEAP_CAPABILITIES.ROOTS,
  ];
  if (objects.value.every((row) => row.selfSize !== undefined)) {
    capabilities.push(JAVA_HEAP_CAPABILITIES.SHALLOW_SIZES);
  }
  return {
    ok: true,
    value: {
      graph: mapJavaHeapRows(classes.value, objects.value, references.value),
      availableCapabilities: capabilities,
    },
  };
}
