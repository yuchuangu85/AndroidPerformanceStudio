/**
 * Port of HeapGraphToHeapDump.kt: Perfetto's java_hprof graph into the parser's
 * canonical result, so one analysis pipeline serves both sources.
 *
 * Two differences from an HPROF dump are structural and stay visible:
 * Perfetto omits general primitive field values (so the lifecycle filters can
 * only see what it does send: the Bitmap and application-version fields), and
 * it reports class sizes and relationships separately from the objects. Both
 * are recorded as warnings rather than silently smoothed over.
 */
import type {
  HprofArrayRecord,
  HprofClassRecord,
  HprofInstanceRecord,
  HprofParseResult,
  Identifier,
} from './hprof.js';
import { OBJECT_TYPE } from './hprof.js';
import { MEMORY_HEAP_NAMES } from './hprof.js';
import type { HeapGraphData, HeapGraphObject, HeapGraphType } from './java-heap-trace.js';

const FORMAT = 'perfetto-java-hprof';
const KIND_ARRAY = 4;
const UNKNOWN_CLASS_NAME = '<unknown>';

/**
 * Perfetto has no string table, so field names are interned here. The high base
 * keeps synthetic ids clear of the trace's own ids.
 */
const SYNTHETIC_ID_BASE = 0xf000000000000000n;

function descriptorElementType(descriptor: string): number | undefined {
  switch (descriptor) {
    case 'Z':
      return 4;
    case 'C':
      return 5;
    case 'F':
      return 6;
    case 'D':
      return 7;
    case 'B':
      return 8;
    case 'S':
      return 9;
    case 'I':
      return 10;
    case 'J':
      return 11;
    default:
      return undefined;
  }
}

/** JVM descriptor to the display name the rest of the app uses. */
export function normalizeClassName(rawName: string): string {
  if (rawName.trim().length === 0) return UNKNOWN_CLASS_NAME;
  const name = rawName.replace(/\//g, '.');
  if (!name.startsWith('[')) return name.replace(/^L/, '').replace(/;$/, '');
  let dimensions = 0;
  while (dimensions < name.length && name[dimensions] === '[') dimensions += 1;
  const descriptor = name[dimensions];
  let element: string;
  switch (descriptor) {
    case 'Z':
      element = 'boolean';
      break;
    case 'C':
      element = 'char';
      break;
    case 'F':
      element = 'float';
      break;
    case 'D':
      element = 'double';
      break;
    case 'B':
      element = 'byte';
      break;
    case 'S':
      element = 'short';
      break;
    case 'I':
      element = 'int';
      break;
    case 'J':
      element = 'long';
      break;
    case 'L':
      element = name.slice(dimensions + 1).replace(/;$/, '');
      break;
    default:
      element = descriptor ?? UNKNOWN_CLASS_NAME;
      break;
  }
  return element + '[]'.repeat(dimensions);
}

function isPrimitiveArrayName(rawName: string): boolean {
  const tail = rawName.slice(rawName.lastIndexOf('[') + 1);
  return descriptorElementType(tail.charAt(0)) !== undefined;
}

function primitiveArrayElementType(rawName: string): number {
  const tail = rawName.slice(rawName.lastIndexOf('[') + 1);
  return descriptorElementType(tail.charAt(0)) ?? 0;
}

/** 1 App, 2 Zygote, 3 Image, anything else Default. */
function heapName(heapType: number): string {
  switch (heapType) {
    case 1:
      return MEMORY_HEAP_NAMES.APP;
    case 2:
      return MEMORY_HEAP_NAMES.ZYGOTE;
    case 3:
      return MEMORY_HEAP_NAMES.IMAGE;
    default:
      return MEMORY_HEAP_NAMES.DEFAULT;
  }
}

function referenceFields(type: HeapGraphType, typesById: ReadonlyMap<string, HeapGraphType>): Identifier[] {
  const result: Identifier[] = [];
  const visited = new Set<string>();
  let current: HeapGraphType | undefined = type;
  while (current !== undefined && !visited.has(current.id.toString())) {
    visited.add(current.id.toString());
    result.push(...current.referenceFieldIds);
    current = typesById.get(current.superclassId.toString());
  }
  return result;
}

function specialFields(object: HeapGraphObject): readonly { readonly name: string; readonly value: bigint }[] {
  const fields: { name: string; value: bigint }[] = [];
  if (object.bitmapId !== undefined) fields.push({ name: 'mId', value: object.bitmapId });
  if (object.bitmapSourceId !== undefined) fields.push({ name: 'mSourceId', value: object.bitmapSourceId });
  if (object.bitmapWidth !== undefined) fields.push({ name: 'mWidth', value: BigInt(object.bitmapWidth) });
  if (object.bitmapHeight !== undefined) fields.push({ name: 'mHeight', value: BigInt(object.bitmapHeight) });
  if (object.applicationInfoLongVersionCode !== undefined) {
    fields.push({ name: 'longVersionCode', value: object.applicationInfoLongVersionCode });
  }
  return fields;
}

export function heapGraphToHprofResult(graph: HeapGraphData): HprofParseResult {
  const typesById = new Map<string, HeapGraphType>();
  for (const type of graph.types) typesById.set(type.id.toString(), type);
  const classNames = new Map<string, string>();
  for (const type of graph.types) classNames.set(type.id.toString(), normalizeClassName(type.className));
  const objectClassNames = new Map<string, string>();
  for (const object of graph.objects) {
    objectClassNames.set(
      object.id.toString(),
      classNames.get(object.typeId.toString()) ?? UNKNOWN_CLASS_NAME,
    );
  }
  const fieldsByType = new Map<string, Identifier[]>();
  for (const type of graph.types) fieldsByType.set(type.id.toString(), referenceFields(type, typesById));

  const strings = new Map<Identifier, string>();
  const internedNames = new Map<string, Identifier>();
  const internFieldName = (name: string): Identifier => {
    const existing = internedNames.get(name);
    if (existing !== undefined) return existing;
    const id = SYNTHETIC_ID_BASE + BigInt(internedNames.size);
    internedNames.set(name, id);
    strings.set(id, name);
    return id;
  };

  const classes = new Map<Identifier, HprofClassRecord>();
  for (const type of graph.types) {
    strings.set(type.id, classNames.get(type.id.toString()) ?? UNKNOWN_CLASS_NAME);
    const fieldIds = fieldsByType.get(type.id.toString()) ?? [];
    for (const fieldId of fieldIds) {
      strings.set(fieldId, graph.fieldNames.get(fieldId) ?? '<field-' + fieldId.toString() + '>');
    }
    classes.set(type.id, {
      objectId: type.id,
      nameId: type.id,
      superClassId: type.superclassId,
      instanceFieldBytes: type.objectSize,
      instanceFields: fieldIds.map((nameId) => ({ nameId, type: OBJECT_TYPE })),
      staticFieldCount: 0,
      staticReferences: [],
    });
  }

  const instances: HprofInstanceRecord[] = [];
  const arrays: HprofArrayRecord[] = [];
  const heapByObjectId = new Map<Identifier, string>();

  for (const object of graph.objects) {
    heapByObjectId.set(object.id, heapName(object.heapType));
    const type = typesById.get(object.typeId.toString());
    const className = objectClassNames.get(object.id.toString()) ?? UNKNOWN_CLASS_NAME;
    if (type !== undefined && type.kind === KIND_ARRAY) {
      if (isPrimitiveArrayName(type.className)) {
        arrays.push({
          objectId: object.id,
          kind: 'primitive',
          elementType: primitiveArrayElementType(type.className),
          length: 0,
          shallowBytes: object.selfSize,
          references: [],
          className,
        });
      } else {
        arrays.push({
          objectId: object.id,
          kind: 'object',
          length: object.referenceObjectIds.length,
          shallowBytes: object.selfSize,
          references: object.referenceObjectIds.filter((target) => target !== 0n),
          className,
        });
      }
      continue;
    }
    const classRecord = classes.get(object.typeId);
    const fieldIds = object.referenceFieldIds.length > 0
      ? object.referenceFieldIds
      : (fieldsByType.get(object.typeId.toString()) ?? []);
    const references: Identifier[] = [];
    const referenceNameIds: Identifier[] = [];
    object.referenceObjectIds.forEach((target, index) => {
      // A null target is not recorded; the class layout still declares the
      // field, which is how the Fragment heuristic tells "empty" from "absent".
      if (target === 0n) return;
      const nameId = fieldIds[index] ?? internFieldName('[' + String(index) + ']');
      if (!strings.has(nameId)) {
        strings.set(nameId, graph.fieldNames.get(nameId) ?? '[' + String(index) + ']');
      }
      references.push(target);
      referenceNameIds.push(nameId);
    });
    const primitiveNameIds: Identifier[] = [];
    const primitiveValues: bigint[] = [];
    for (const field of specialFields(object)) {
      primitiveNameIds.push(internFieldName(field.name));
      primitiveValues.push(field.value);
    }
    instances.push({
      objectId: object.id,
      classObjectId: object.typeId,
      // Perfetto reports self_size, not a field payload; the reference fields
      // are the only field data the graph carries.
      fieldBytes: classRecord === undefined ? 0 : (fieldsByType.get(object.typeId.toString()) ?? []).length * 8,
      shallowBytes: object.selfSize,
      references,
      referenceNameIds,
      primitiveNameIds,
      primitiveValues,
    });
  }

  const roots: Identifier[] = [];
  for (const root of graph.roots) {
    for (const objectId of root.objectIds) {
      if (objectId !== 0n) roots.push(objectId);
    }
  }

  return {
    header: { version: FORMAT, identifierSize: 8, timestampMillis: 0, headerBytes: 0 },
    strings,
    classes,
    instances,
    arrays,
    roots,
    heapByObjectId,
    warnings: [
      'Perfetto java_hprof omits general primitive field values; lifecycle leak filters may be partial.',
      'Perfetto java_hprof reports reference fields only; primitive fields are absent.',
    ],
  };
}
