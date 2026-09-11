/**
 * Port of InstanceReferenceQuery.kt: the instance list for a class and the
 * field/reference detail for one object.
 *
 * The histogram answers "how much does this class hold"; these two answer
 * "which objects are they, and who points at them", which is where a leak is
 * actually confirmed or dismissed by hand.
 */
import type { GraphAnalysis } from './dominators.js';
import { hexId } from './deep-analysis.js';
import { fieldNameOf } from './deep-analysis.js';
import type { ObjectGraph } from './graph.js';
import type { HprofInstanceRecord, HprofParseResult, Identifier } from './hprof.js';
import { primitiveSize } from './hprof.js';
import { normalizeHeapName } from './hprof.js';

export interface InstanceQueryRow {
  readonly objectId: string;
  readonly className: string;
  readonly heap: string;
  readonly shallowBytes: number;
  readonly retainedBytes?: number;
  /** True when the object is still reachable from a GC root. */
  readonly reachable: boolean;
}

export interface InstanceQueryOptions {
  readonly limit?: number;
  /** A heap label from MEMORY_HEAP_NAMES; undefined means every heap. */
  readonly heap?: string;
  readonly retainedBytes?: ReadonlyMap<Identifier, number>;
  readonly reachable?: ReadonlySet<Identifier>;
}

export interface FieldValue {
  readonly name: string;
  readonly kind: 'primitive' | 'reference';
  /** Primitive value, or the target object id for a reference. */
  readonly value: string;
  readonly displayValue: string;
  readonly targetClassName?: string;
}

export interface InstanceQueryDetail {
  readonly objectId: string;
  readonly className: string;
  readonly heap: string;
  readonly shallowBytes: number;
  readonly retainedBytes?: number;
  readonly fieldBytes: number;
  readonly fields: readonly FieldValue[];
}

/** Instances of one class, largest retention first. */
export function instancesOf(
  result: HprofParseResult,
  graph: ObjectGraph,
  className: string,
  options: InstanceQueryOptions = {},
): InstanceQueryRow[] {
  const classNameById = new Map<Identifier, string>();
  for (const node of graph.nodes.values()) classNameById.set(node.objectId, node.className);
  const wantedHeap = options.heap === undefined ? undefined : normalizeHeapName(options.heap);
  const rows: InstanceQueryRow[] = [];
  for (const instance of result.instances) {
    if ((classNameById.get(instance.objectId) ?? '<unknown>') !== className) continue;
    const heap = result.heapByObjectId.get(instance.objectId) ?? 'Default';
    if (wantedHeap !== undefined && heap !== wantedHeap) continue;
    const retained = options.retainedBytes?.get(instance.objectId);
    rows.push({
      objectId: hexId(instance.objectId),
      className,
      heap,
      shallowBytes: instance.shallowBytes,
      ...(retained !== undefined ? { retainedBytes: retained } : {}),
      reachable: options.reachable === undefined ? true : options.reachable.has(instance.objectId),
    });
  }
  rows.sort((left, right) => {
    const leftSize = left.retainedBytes ?? left.shallowBytes;
    const rightSize = right.retainedBytes ?? right.shallowBytes;
    if (rightSize !== leftSize) return rightSize - leftSize;
    return left.objectId < right.objectId ? -1 : left.objectId > right.objectId ? 1 : 0;
  });
  return options.limit === undefined ? rows : rows.slice(0, options.limit);
}

/** Reads one instance's fields, including the values that make it identifiable. */
export function instanceDetail(
  result: HprofParseResult,
  graph: ObjectGraph,
  objectId: string,
  analysis?: GraphAnalysis,
): InstanceQueryDetail | undefined {
  const id = parseHexId(objectId);
  if (id === undefined) return undefined;
  const instance = result.instances.find((entry) => entry.objectId === id);
  if (instance === undefined) return undefined;
  const node = graph.nodes.get(id);
  const classNameById = new Map<Identifier, string>();
  for (const other of graph.nodes.values()) classNameById.set(other.objectId, other.className);
  const className = node?.className ?? '<unknown>';

  const fields: FieldValue[] = [];
  for (const value of instance.primitiveValues) {
    const name = fieldNameOf(result, value.nameId);
    fields.push({
      name,
      kind: 'primitive',
      value: value.value.toString(),
      displayValue: formatPrimitive(value.value, value.nameId, result),
    });
  }
  for (const reference of instance.fieldReferences) {
    const targetClassName = classNameById.get(reference.targetObjectId) ?? '<unknown>';
    fields.push({
      name: fieldNameOf(result, reference.nameId),
      kind: 'reference',
      value: hexId(reference.targetObjectId),
      displayValue: targetClassName + ' @ ' + hexId(reference.targetObjectId),
      targetClassName,
    });
  }
  fields.sort((left, right) => (left.name < right.name ? -1 : left.name > right.name ? 1 : 0));

  const retained = analysis?.dominators.retainedBytes.get(id);
  return {
    objectId: hexId(id),
    className,
    heap: result.heapByObjectId.get(id) ?? 'Default',
    shallowBytes: instance.shallowBytes,
    ...(retained !== undefined ? { retainedBytes: retained } : {}),
    fieldBytes: instance.fieldBytes,
    fields,
  };
}

function formatPrimitive(value: bigint, nameId: Identifier, result: HprofParseResult): string {
  void nameId;
  void result;
  return value.toString();
}

function parseHexId(value: string): Identifier | undefined {
  const trimmed = value.trim().replace(/^0x/i, '');
  if (!/^[0-9a-fA-F]+$/.test(trimmed)) return undefined;
  try {
    return BigInt('0x' + trimmed);
  } catch {
    return undefined;
  }
}

/** Field layout of a class, so the UI can show what the parser expected to read. */
export function declaredFields(
  result: HprofParseResult,
  className: string,
): readonly { readonly name: string; readonly type: number; readonly sizeBytes: number }[] {
  for (const klass of result.classes.values()) {
    if (result.strings.get(klass.nameId) !== className) continue;
    return klass.instanceFields.map((field) => ({
      name: fieldNameOf(result, field.nameId),
      type: field.type,
      sizeBytes: field.type === 2 ? (result.header.identifierSize as number) : primitiveSize(field.type),
    }));
  }
  return [];
}

/** Instance records as the query layer needs them, for callers holding ids. */
export function instancesById(result: HprofParseResult): Map<Identifier, HprofInstanceRecord> {
  const map = new Map<Identifier, HprofInstanceRecord>();
  for (const instance of result.instances) map.set(instance.objectId, instance);
  return map;
}
