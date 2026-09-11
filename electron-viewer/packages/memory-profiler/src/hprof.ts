/**
 * HPROF (JAVA PROFILE) binary heap-dump reader.
 *
 * The format is a null-terminated text header followed by records:
 *   tag u1, time u4, length u4, body
 * Identifiers are 4 or 8 bytes wide and are read as bigint because a 64-bit id
 * does not fit JavaScript's safe integer range.
 */

export type Identifier = bigint;

export interface HprofHeader {
  readonly version: string;
  readonly identifierSize: 4 | 8;
  readonly timestampMillis: number;
  readonly headerBytes: number;
}

export const HPROF_TAGS = {
  string: 0x01,
  loadClass: 0x02,
  stackFrame: 0x04,
  stackTrace: 0x05,
  heapDump: 0x0c,
  heapDumpSegment: 0x1c,
  heapDumpEnd: 0x2c,
} as const;

export const HEAP_SUBTAGS = {
  rootUnknown: 0xff,
  rootJniGlobal: 0x01,
  rootJniLocal: 0x02,
  rootJavaFrame: 0x03,
  rootNativeStack: 0x04,
  rootStickyClass: 0x05,
  rootThreadBlock: 0x06,
  rootMonitorUsed: 0x07,
  rootThreadObject: 0x08,
  classDump: 0x20,
  instanceDump: 0x21,
  objectArrayDump: 0x22,
  primitiveArrayDump: 0x23,
  rootInternedString: 0x89,
  rootFinalizing: 0x8a,
  rootDebugger: 0x8b,
  rootReferenceCleanup: 0x8c,
  rootVmInternal: 0x8d,
  rootJniMonitor: 0x8e,
  rootUnreachable: 0x90,
  primitiveArrayNoData: 0xc3,
  heapDumpInfo: 0xfe,
} as const;

export const PRIMITIVE_SIZES: Readonly<Record<number, number>> = {
  2: 4, // object
  4: 1, // boolean
  5: 2, // char
  6: 4, // float
  7: 8, // double
  8: 1, // byte
  9: 2, // short
  10: 4, // int
  11: 8, // long
};

export const OBJECT_TYPE = 2;

export function primitiveSize(elementType: number): number {
  return PRIMITIVE_SIZES[elementType] ?? 0;
}

/**
 * Array header size reported by ART for array objects. Instances do not need an
 * estimate: their shallow size is the class's declared instance size, which is
 * what the runtime already accounted for.
 */
export const ARRAY_HEADER_BYTES = 16;

/** Kept for callers that need a nominal object header; not added to sizes. */
export function objectHeaderBytes(identifierSize: 4 | 8): number {
  return identifierSize * 2;
}

export interface HprofFieldDescriptor {
  readonly nameId: Identifier;
  readonly type: number;
}

/**
 * A static object field that holds something. Statics are a handful per class,
 * so this keeps the readable object form the instance fields cannot afford.
 */
export interface HprofStaticReference {
  readonly nameId: Identifier;
  readonly targetObjectId: Identifier;
}

export interface HprofClassRecord {
  readonly objectId: Identifier;
  readonly nameId: Identifier;
  readonly superClassId: Identifier;
  readonly instanceFieldBytes: number;
  readonly instanceFields: readonly HprofFieldDescriptor[];
  readonly staticFieldCount: number;
  /** Static object fields that hold something; the Context heuristic reads these. */
  readonly staticReferences: readonly HprofStaticReference[];
}

/**
 * Shared empty arrays. A dump has millions of fields, so the empty case must
 * not allocate: the aggregation benchmark notices the difference.
 */
const NO_IDENTIFIERS: readonly Identifier[] = Object.freeze([]);
const NO_VALUES: readonly bigint[] = Object.freeze([]);

export { NO_IDENTIFIERS, NO_VALUES };

export interface HprofInstanceRecord {
  readonly objectId: Identifier;
  readonly classObjectId: Identifier;
  /** Bytes of field data present in the dump. */
  readonly fieldBytes: number;
  /**
   * Shallow size as the runtime reported it: the class's declared instance size,
   * or the dumped field bytes when the class layout never arrived.
   */
  readonly shallowBytes: number;
  readonly references: readonly Identifier[];
  /**
   * Parallel to references: the field each one was read from. Deep analysis
   * reasons about fields ("mDestroyed", "mFragmentManager", "mWidth"), which a
   * flat id list cannot answer. Names stay as ids because a class layout can
   * arrive before or after the strings it refers to.
   *
   * Parallel arrays rather than one object per field: a heap dump with 200,000
   * instances otherwise allocates close to a million tiny objects, and the
   * aggregation stage of the HPROF gate pays for them.
   */
  readonly referenceNameIds: readonly Identifier[];
  /** Parallel arrays for the primitive fields, in class layout order. */
  readonly primitiveNameIds: readonly Identifier[];
  readonly primitiveValues: readonly bigint[];
}

export interface HprofArrayRecord {
  readonly objectId: Identifier;
  readonly kind: 'object' | 'primitive';
  readonly elementType?: number;
  readonly length: number;
  readonly shallowBytes: number;
  readonly references: readonly Identifier[];
  /** Real array class name when the source has one (Perfetto does). */
  readonly className?: string;
}

export interface HprofParseResult {
  readonly header: HprofHeader;
  readonly strings: ReadonlyMap<Identifier, string>;
  readonly classes: ReadonlyMap<Identifier, HprofClassRecord>;
  readonly instances: readonly HprofInstanceRecord[];
  readonly arrays: readonly HprofArrayRecord[];
  /** GC roots seen in the heap dump. */
  readonly roots: readonly Identifier[];
  /** Normalized heap name per object, from HEAP_DUMP_INFO records. */
  readonly heapByObjectId: ReadonlyMap<Identifier, string>;
  readonly warnings: readonly string[];
}

/** Heap labels matching MemoryHeapNames in the Kotlin model. */
export const MEMORY_HEAP_NAMES = {
  APP: 'App',
  IMAGE: 'Image',
  ZYGOTE: 'Zygote',
  DEFAULT: 'Default',
} as const;

/** Port of normalizeHeapName: the raw name only has to identify the heap. */
export function normalizeHeapName(raw: string | undefined): string {
  const lower = (raw ?? '').toLowerCase();
  if (lower.includes('image')) return MEMORY_HEAP_NAMES.IMAGE;
  if (lower.includes('zygote')) return MEMORY_HEAP_NAMES.ZYGOTE;
  if (lower.includes('app')) return MEMORY_HEAP_NAMES.APP;
  return MEMORY_HEAP_NAMES.DEFAULT;
}

function readIdentifierAt(bytes: Uint8Array, offset: number, identifierSize: 4 | 8): Identifier {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  return identifierSize === 8 ? view.getBigUint64(offset, false) : BigInt(view.getUint32(offset, false));
}

/**
 * Resolves an instance's object-reference fields from its raw payload. Field
 * order in INSTANCE_DUMP follows the CLASS_DUMP order.
 */
export interface CollectedInstanceFields {
  readonly references: readonly Identifier[];
  readonly referenceNameIds: readonly Identifier[];
  readonly primitiveNameIds: readonly Identifier[];
  readonly primitiveValues: readonly bigint[];
}

/** Walks an instance payload once and keeps references, names, and primitives. */
export function collectInstanceFields(
  payload: Uint8Array,
  fields: readonly HprofFieldDescriptor[],
  identifierSize: 4 | 8,
): CollectedInstanceFields {
  const references: Identifier[] = [];
  const referenceNameIds: Identifier[] = [];
  const primitiveNameIds: Identifier[] = [];
  const primitiveValues: bigint[] = [];
  let offset = 0;
  for (const field of fields) {
    const size = field.type === OBJECT_TYPE ? identifierSize : primitiveSize(field.type);
    if (offset + size > payload.length) break;
    if (field.type === OBJECT_TYPE) {
      const id = readIdentifierAt(payload, offset, identifierSize);
      if (id !== 0n) {
        references.push(id);
        referenceNameIds.push(field.nameId);
      }
    } else if (size > 0) {
      primitiveNameIds.push(field.nameId);
      primitiveValues.push(readPrimitiveAt(payload, offset, field.type));
    }
    offset += size;
  }
  return {
    references: references.length === 0 ? NO_IDENTIFIERS : references,
    referenceNameIds: referenceNameIds.length === 0 ? NO_IDENTIFIERS : referenceNameIds,
    primitiveNameIds: primitiveNameIds.length === 0 ? NO_IDENTIFIERS : primitiveNameIds,
    primitiveValues: primitiveValues.length === 0 ? NO_VALUES : primitiveValues,
  };
}

export function collectInstanceReferences(
  payload: Uint8Array,
  fields: readonly HprofFieldDescriptor[],
  identifierSize: 4 | 8,
): readonly Identifier[] {
  return collectInstanceFields(payload, fields, identifierSize).references;
}

/** Big-endian primitive field read, matching the dump's field order. */
export function readPrimitiveAt(payload: Uint8Array, offset: number, type: number): bigint {
  const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
  switch (type) {
    case 4:
      return view.getUint8(offset) === 0 ? 0n : 1n;
    case 5:
      return BigInt(view.getUint16(offset, false));
    case 6:
      return BigInt(Math.trunc(view.getFloat32(offset, false)));
    case 7:
      return BigInt(Math.trunc(view.getFloat64(offset, false)));
    case 8:
      return BigInt(view.getInt8(offset));
    case 9:
      return BigInt(view.getInt16(offset, false));
    case 10:
      return BigInt(view.getInt32(offset, false));
    case 11:
      return view.getBigInt64(offset, false);
    default:
      return 0n;
  }
}

class Cursor {
  private offset: number;
  constructor(
    private readonly bytes: Uint8Array,
    private readonly identifierSize: 4 | 8,
    start = 0,
  ) {
    this.offset = start;
  }

  get position(): number {
    return this.offset;
  }

  get remaining(): number {
    return this.bytes.length - this.offset;
  }

  u1(): number {
    if (this.offset >= this.bytes.length) throw new Error('HPROF ended mid-field');
    return this.bytes[this.offset++] as number;
  }

  u2(): number {
    const value = this.view().getUint16(this.offset, false);
    this.offset += 2;
    return value;
  }

  u4(): number {
    const value = this.view().getUint32(this.offset, false);
    this.offset += 4;
    return value;
  }

  u8(): number {
    const value = this.view().getBigUint64(this.offset, false);
    this.offset += 8;
    return Number(value);
  }

  id(): Identifier {
    const value =
      this.identifierSize === 8
        ? this.view().getBigUint64(this.offset, false)
        : BigInt(this.view().getUint32(this.offset, false));
    this.offset += this.identifierSize;
    return value;
  }

  skip(count: number): void {
    this.offset += count;
  }

  bytesOf(count: number): Uint8Array {
    const slice = this.bytes.subarray(this.offset, this.offset + count);
    this.offset += count;
    return slice;
  }

  utf8UntilNull(): string {
    let end = this.offset;
    while (end < this.bytes.length && this.bytes[end] !== 0) end += 1;
    const value = utf8Of(this.bytes.subarray(this.offset, end));
    this.offset = end + 1;
    return value;
  }

  private view(): DataView {
    return new DataView(this.bytes.buffer, this.bytes.byteOffset, this.bytes.byteLength);
  }
}

const MAGIC = 'JAVA PROFILE 1.0.';
const UTF8_DECODER = new TextDecoder('utf-8');

function utf8Of(bytes: Uint8Array): string {
  return UTF8_DECODER.decode(bytes);
}

export function parseHprofHeader(bytes: Uint8Array): HprofHeader {
  const magicEnd = bytes.indexOf(0);
  if (magicEnd < 0) throw new Error('HPROF header is not null terminated');
  const magic = utf8Of(bytes.subarray(0, magicEnd));
  if (!magic.startsWith(MAGIC)) throw new Error('Not an HPROF Java profile: ' + magic);
  const version = magic.slice('JAVA PROFILE '.length);
  const cursor = new Cursor(bytes, 4, magicEnd + 1);
  const identifierSize = cursor.u4();
  if (identifierSize !== 4 && identifierSize !== 8) {
    throw new Error('Unsupported HPROF identifier size: ' + String(identifierSize));
  }
  const timestampMillis = cursor.u8();
  return { version, identifierSize, timestampMillis, headerBytes: cursor.position };
}

/** Reads one root record and returns the object id it roots. */
function readRoot(cursor: Cursor, subTag: number, identifierSize: 4 | 8): Identifier {
  const first = cursor.id();
  switch (subTag) {
    case HEAP_SUBTAGS.rootJniGlobal:
      cursor.skip(identifierSize);
      break;
    case HEAP_SUBTAGS.rootJniLocal:
    case HEAP_SUBTAGS.rootJavaFrame:
    case HEAP_SUBTAGS.rootThreadObject:
    case HEAP_SUBTAGS.rootJniMonitor:
      cursor.skip(4);
      cursor.skip(4);
      break;
    case HEAP_SUBTAGS.rootNativeStack:
    case HEAP_SUBTAGS.rootThreadBlock:
      cursor.skip(4);
      break;
    default:
      break;
  }
  return first;
}

const ROOT_SUBTAGS: Readonly<Record<number, true>> = {
  [HEAP_SUBTAGS.rootUnknown]: true,
  [HEAP_SUBTAGS.rootJniGlobal]: true,
  [HEAP_SUBTAGS.rootJniLocal]: true,
  [HEAP_SUBTAGS.rootJavaFrame]: true,
  [HEAP_SUBTAGS.rootNativeStack]: true,
  [HEAP_SUBTAGS.rootStickyClass]: true,
  [HEAP_SUBTAGS.rootThreadBlock]: true,
  [HEAP_SUBTAGS.rootMonitorUsed]: true,
  [HEAP_SUBTAGS.rootThreadObject]: true,
  [HEAP_SUBTAGS.rootInternedString]: true,
  [HEAP_SUBTAGS.rootFinalizing]: true,
  [HEAP_SUBTAGS.rootDebugger]: true,
  [HEAP_SUBTAGS.rootReferenceCleanup]: true,
  [HEAP_SUBTAGS.rootVmInternal]: true,
  [HEAP_SUBTAGS.rootJniMonitor]: true,
  [HEAP_SUBTAGS.rootUnreachable]: true,
};

interface DeferredInstance {
  readonly index: number;
  readonly classObjectId: Identifier;
  readonly payload: Uint8Array;
}

function readClassDump(cursor: Cursor, identifierSize: 4 | 8): Omit<HprofClassRecord, 'nameId'> {
  const objectId = cursor.id();
  cursor.u4(); // stack trace serial
  const superClassId = cursor.id();
  cursor.id(); // class loader
  cursor.id(); // signers
  cursor.id(); // protection domain
  cursor.id(); // reserved 1
  cursor.id(); // reserved 2
  const instanceFieldBytes = cursor.u4();
  const constantPoolCount = cursor.u2();
  for (let index = 0; index < constantPoolCount; index += 1) {
    cursor.skip(2);
    const type = cursor.u1();
    cursor.skip(type === OBJECT_TYPE ? identifierSize : primitiveSize(type));
  }
  const staticFieldCount = cursor.u2();
  const staticReferences: HprofStaticReference[] = [];
  for (let index = 0; index < staticFieldCount; index += 1) {
    const nameId = cursor.id();
    const type = cursor.u1();
    if (type === OBJECT_TYPE) {
      const targetObjectId = cursor.id();
      if (targetObjectId !== 0n) staticReferences.push({ nameId, targetObjectId });
    } else {
      cursor.skip(primitiveSize(type));
    }
  }
  const instanceFieldCount = cursor.u2();
  const instanceFields: HprofFieldDescriptor[] = [];
  for (let index = 0; index < instanceFieldCount; index += 1) {
    const nameId = cursor.id();
    const type = cursor.u1();
    instanceFields.push({ nameId, type });
  }
  // A class dump may legitimately declare no instance fields (all state in
  // superclasses or statics); the reference implementation stays silent, and the
  // instance size below is still authoritative.
  return {
    objectId,
    superClassId,
    instanceFieldBytes,
    instanceFields,
    staticFieldCount,
    staticReferences,
  };
}

function readHeapSegment(
  cursor: Cursor,
  identifierSize: 4 | 8,
  classes: Map<Identifier, HprofClassRecord>,
  instances: HprofInstanceRecord[],
  arrays: HprofArrayRecord[],
  roots: Set<Identifier>,
  deferred: DeferredInstance[],
  warnings: string[],
  end: number,
  heapByObjectId: Map<Identifier, string>,
  strings: ReadonlyMap<Identifier, string>,
): void {
  // HEAP_DUMP_INFO switches the heap every following object belongs to; objects
  // seen before the first one belong to the default heap.
  let currentHeap: string = MEMORY_HEAP_NAMES.DEFAULT;
  while (cursor.position < end) {
    const subTag = cursor.u1();
    switch (subTag) {
      case HEAP_SUBTAGS.heapDumpInfo: {
        // [u4 heap serial, id of the heap-name string].
        cursor.u4();
        currentHeap = normalizeHeapName(strings.get(cursor.id()));
        break;
      }
      case HEAP_SUBTAGS.classDump: {
        const record = readClassDump(cursor, identifierSize);
        // LOAD_CLASS carries the name; CLASS_DUMP carries the field layout.
        // Either record may arrive first, so merge instead of overwriting.
        const existing = classes.get(record.objectId);
        classes.set(record.objectId, {
          ...record,
          nameId: existing !== undefined && existing.nameId !== 0n ? existing.nameId : 0n,
        });
        break;
      }
      case HEAP_SUBTAGS.instanceDump: {
        const objectId = cursor.id();
        cursor.u4();
        const classObjectId = cursor.id();
        const fieldBytes = cursor.u4();
        const payload = cursor.bytesOf(fieldBytes);
        const classRecord = classes.get(classObjectId);
        const layout = classRecord?.instanceFields;
        const shallowBytes = classRecord?.instanceFieldBytes ?? Math.max(fieldBytes, 0);
        heapByObjectId.set(objectId, currentHeap);
        const index = instances.length;
        if (layout === undefined) {
          deferred.push({ index, classObjectId, payload });
          instances.push({
            objectId,
            classObjectId,
            fieldBytes,
            shallowBytes,
            references: NO_IDENTIFIERS,
            referenceNameIds: NO_IDENTIFIERS,
            primitiveNameIds: NO_IDENTIFIERS,
            primitiveValues: NO_VALUES,
          });
        } else {
          const collected = collectInstanceFields(payload, layout, identifierSize);
          instances.push({
            objectId,
            classObjectId,
            fieldBytes,
            shallowBytes,
            references: collected.references,
            referenceNameIds: collected.referenceNameIds,
            primitiveNameIds: collected.primitiveNameIds,
            primitiveValues: collected.primitiveValues,
          });
        }
        break;
      }
      case HEAP_SUBTAGS.objectArrayDump: {
        const objectId = cursor.id();
        cursor.u4();
        const length = cursor.u4();
        cursor.id();
        heapByObjectId.set(objectId, currentHeap);
        const references: Identifier[] = [];
        for (let index = 0; index < length; index += 1) {
          const element = cursor.id();
          if (element !== 0n) references.push(element);
        }
        arrays.push({
          objectId,
          kind: 'object',
          length,
          shallowBytes: ARRAY_HEADER_BYTES + length * identifierSize,
          references,
        });
        break;
      }
      case HEAP_SUBTAGS.primitiveArrayDump:
      case HEAP_SUBTAGS.primitiveArrayNoData: {
        const objectId = cursor.id();
        cursor.u4();
        const length = cursor.u4();
        const elementType = cursor.u1();
        heapByObjectId.set(objectId, currentHeap);
        const elementSize = primitiveSize(elementType);
        if (subTag === HEAP_SUBTAGS.primitiveArrayDump) cursor.skip(length * elementSize);
        arrays.push({
          objectId,
          kind: 'primitive',
          elementType,
          length,
          shallowBytes: ARRAY_HEADER_BYTES + length * elementSize,
          references: [],
        });
        break;
      }
      default:
        if (subTag in ROOT_SUBTAGS) {
          // A zero object id is not a root; the reference implementation drops it.
          const rootId = readRoot(cursor, subTag, identifierSize);
          if (rootId !== 0n) roots.add(rootId);
        } else {
          warnings.push('Unknown heap sub-tag 0x' + subTag.toString(16) + '; parsing stopped for this segment.');
          return;
        }
        break;
    }
  }
}

/** Parses a heap dump into classes, instances, arrays, roots, and strings. */
export function parseHprof(bytes: Uint8Array): HprofParseResult {
  const header = parseHprofHeader(bytes);
  const strings = new Map<Identifier, string>();
  const classes = new Map<Identifier, HprofClassRecord>();
  const instances: HprofInstanceRecord[] = [];
  const arrays: HprofArrayRecord[] = [];
  const roots = new Set<Identifier>();
  const deferred: DeferredInstance[] = [];
  const warnings: string[] = [];
  const heapByObjectId = new Map<Identifier, string>();
  const cursor = new Cursor(bytes, header.identifierSize, header.headerBytes);

  while (cursor.remaining >= 9) {
    const tag = cursor.u1();
    cursor.u4();
    const length = cursor.u4();
    const bodyEnd = cursor.position + length;
    if (tag === HPROF_TAGS.string) {
      const id = cursor.id();
      // STRING bodies are id + UTF-8 text delimited by the record length, not by
      // a NUL byte; scanning for a terminator consumes the next record.
      strings.set(id, utf8Of(cursor.bytesOf(Math.max(length - header.identifierSize, 0))));
    } else if (tag === HPROF_TAGS.loadClass) {
      cursor.u4();
      const classObjectId = cursor.id();
      cursor.u4();
      const nameId = cursor.id();
      const existing = classes.get(classObjectId);
      classes.set(classObjectId, {
        objectId: classObjectId,
        nameId,
        superClassId: existing?.superClassId ?? 0n,
        instanceFieldBytes: existing?.instanceFieldBytes ?? 0,
        instanceFields: existing?.instanceFields ?? [],
        staticFieldCount: existing?.staticFieldCount ?? 0,
        staticReferences: existing?.staticReferences ?? [],
      });
    } else if (tag === HPROF_TAGS.heapDump || tag === HPROF_TAGS.heapDumpSegment) {
      readHeapSegment(
        cursor,
        header.identifierSize,
        classes,
        instances,
        arrays,
        roots,
        deferred,
        warnings,
        bodyEnd,
        heapByObjectId,
        strings,
      );
    }
    cursor.skip(Math.max(0, bodyEnd - cursor.position));
  }

  // A class layout may only arrive after its instances, so resolve those here.
  for (const entry of deferred) {
    const layout = classes.get(entry.classObjectId)?.instanceFields;
    const current = instances[entry.index];
    if (layout === undefined || current === undefined) continue;
    const collected = collectInstanceFields(entry.payload, layout, header.identifierSize);
    instances[entry.index] = {
      ...current,
      shallowBytes: classes.get(entry.classObjectId)?.instanceFieldBytes ?? current.shallowBytes,
      references: collected.references,
      referenceNameIds: collected.referenceNameIds,
      primitiveNameIds: collected.primitiveNameIds,
      primitiveValues: collected.primitiveValues,
    };
  }
  if (deferred.length > 0) {
    warnings.push(deferred.length + ' instance(s) were resolved after their class layout.');
  }
  if (strings.size === 0) warnings.push('No class or field names were found in the dump.');
  return { header, strings, classes, instances, arrays, roots: [...roots], heapByObjectId, warnings };
}
