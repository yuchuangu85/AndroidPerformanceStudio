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

export const STRING_TAG = 0x01;
export const OBJECT_TAG = 0x02;

export function primitiveSize(elementType: number): number {
  return PRIMITIVE_SIZES[elementType] ?? 0;
}

/** Object header estimate: two identifiers, as used by the Android runtime. */
export function objectHeaderBytes(identifierSize: 4 | 8): number {
  return identifierSize * 2;
}

export interface HprofClassRecord {
  readonly objectId: Identifier;
  readonly nameId: Identifier;
  readonly superClassId: Identifier;
  readonly instanceFieldBytes: number;
  readonly instanceFieldCount: number;
  readonly staticFieldCount: number;
}

export interface HprofInstanceRecord {
  readonly objectId: Identifier;
  readonly classObjectId: Identifier;
  readonly fieldBytes: number;
}

export interface HprofArrayRecord {
  readonly objectId: Identifier;
  readonly kind: 'object' | 'primitive';
  readonly elementType?: number;
  readonly length: number;
  readonly shallowBytes: number;
}

export interface HprofParseResult {
  readonly header: HprofHeader;
  readonly strings: ReadonlyMap<Identifier, string>;
  readonly classes: ReadonlyMap<Identifier, HprofClassRecord>;
  readonly instances: readonly HprofInstanceRecord[];
  readonly arrays: readonly HprofArrayRecord[];
  readonly warnings: readonly string[];
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
      this.identifierSize === 8 ? this.view().getBigUint64(this.offset, false) : BigInt(this.view().getUint32(this.offset, false));
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
    const value = Buffer.from(this.bytes.subarray(this.offset, end)).toString('utf8');
    this.offset = end + 1;
    return value;
  }

  private view(): DataView {
    return new DataView(this.bytes.buffer, this.bytes.byteOffset, this.bytes.byteLength);
  }
}

const MAGIC = 'JAVA PROFILE 1.0.';

export function parseHprofHeader(bytes: Uint8Array): HprofHeader {
  const magicEnd = bytes.indexOf(0);
  if (magicEnd < 0) throw new Error('HPROF header is not null terminated');
  const magic = Buffer.from(bytes.subarray(0, magicEnd)).toString('utf8');
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

function skipRoot(cursor: Cursor, subTag: number, identifierSize: 4 | 8): void {
  const id = (): void => cursor.skip(identifierSize);
  const u4 = (): void => cursor.skip(4);
  switch (subTag) {
    case HEAP_SUBTAGS.rootJniGlobal:
      id();
      id();
      break;
    case HEAP_SUBTAGS.rootJniLocal:
    case HEAP_SUBTAGS.rootJavaFrame:
    case HEAP_SUBTAGS.rootThreadObject:
      id();
      u4();
      u4();
      break;
    case HEAP_SUBTAGS.rootNativeStack:
    case HEAP_SUBTAGS.rootThreadBlock:
      id();
      u4();
      break;
    case HEAP_SUBTAGS.rootJniMonitor:
      id();
      u4();
      u4();
      break;
    case HEAP_SUBTAGS.heapDumpInfo:
      u4();
      id();
      break;
    default:
      // ROOT_UNKNOWN, STICKY_CLASS, MONITOR_USED, INTERNED_STRING, FINALIZING,
      // DEBUGGER, REFERENCE_CLEANUP, VM_INTERNAL, UNREACHABLE all carry one id.
      id();
      break;
  }
}

function readClassDump(cursor: Cursor, identifierSize: 4 | 8, warnings: string[]): HprofClassRecord {
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
    cursor.skip(type === 2 ? identifierSize : primitiveSize(type));
  }
  const staticFieldCount = cursor.u2();
  for (let index = 0; index < staticFieldCount; index += 1) {
    cursor.skip(identifierSize);
    const type = cursor.u1();
    cursor.skip(type === 2 ? identifierSize : primitiveSize(type));
  }
  const instanceFieldCount = cursor.u2();
  for (let index = 0; index < instanceFieldCount; index += 1) {
    cursor.skip(identifierSize);
    cursor.u1();
  }
  if (constantPoolCount === 0 && staticFieldCount === 0 && instanceFieldCount === 0) {
    warnings.push('Class dump without fields was read; sizes remain valid.');
  }
  return { objectId, nameId: 0n, superClassId, instanceFieldBytes, instanceFieldCount, staticFieldCount };
}

function readHeapSegment(
  cursor: Cursor,
  identifierSize: 4 | 8,
  strings: Map<Identifier, string>,
  classes: Map<Identifier, HprofClassRecord>,
  instances: HprofInstanceRecord[],
  arrays: HprofArrayRecord[],
  warnings: string[],
  end: number,
): void {
  while (cursor.position < end) {
    const subTag = cursor.u1();
    switch (subTag) {
      case HEAP_SUBTAGS.classDump: {
        const record = readClassDump(cursor, identifierSize, warnings);
        // LOAD_CLASS carries the name; CLASS_DUMP carries the field layout.
        // Either record may arrive first, so merge instead of overwriting.
        const existing = classes.get(record.objectId);
        classes.set(record.objectId, {
          ...record,
          nameId: existing !== undefined && existing.nameId !== 0n ? existing.nameId : record.nameId,
          superClassId: record.superClassId !== 0n ? record.superClassId : (existing?.superClassId ?? 0n),
        });
        break;
      }
      case HEAP_SUBTAGS.instanceDump: {
        const objectId = cursor.id();
        cursor.u4();
        const classObjectId = cursor.id();
        const fieldBytes = cursor.u4();
        cursor.skip(fieldBytes);
        instances.push({ objectId, classObjectId, fieldBytes });
        break;
      }
      case HEAP_SUBTAGS.objectArrayDump: {
        const objectId = cursor.id();
        cursor.u4();
        const length = cursor.u4();
        cursor.id();
        cursor.skip(length * identifierSize);
        arrays.push({
          objectId,
          kind: 'object',
          length,
          shallowBytes: length * identifierSize + objectHeaderBytes(identifierSize),
        });
        break;
      }
      case HEAP_SUBTAGS.primitiveArrayDump:
      case HEAP_SUBTAGS.primitiveArrayNoData: {
        const objectId = cursor.id();
        cursor.u4();
        const length = cursor.u4();
        const elementType = cursor.u1();
        const elementSize = primitiveSize(elementType);
        if (subTag === HEAP_SUBTAGS.primitiveArrayDump) cursor.skip(length * elementSize);
        arrays.push({
          objectId,
          kind: 'primitive',
          elementType,
          length,
          shallowBytes: length * elementSize + objectHeaderBytes(identifierSize),
        });
        break;
      }
      default:
        if (subTag in ROOT_SUBTAGS) {
          skipRoot(cursor, subTag, identifierSize);
        } else {
          warnings.push('Unknown heap sub-tag 0x' + subTag.toString(16) + '; parsing stopped for this segment.');
          return;
        }
        break;
    }
  }
  void strings;
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
  [HEAP_SUBTAGS.heapDumpInfo]: true,
};

/** Parses a heap dump into classes, instances, arrays, and strings. */
export function parseHprof(bytes: Uint8Array): HprofParseResult {
  const header = parseHprofHeader(bytes);
  const strings = new Map<Identifier, string>();
  const classes = new Map<Identifier, HprofClassRecord>();
  const instances: HprofInstanceRecord[] = [];
  const arrays: HprofArrayRecord[] = [];
  const warnings: string[] = [];
  const cursor = new Cursor(bytes, header.identifierSize, header.headerBytes);

  while (cursor.remaining >= 9) {
    const tag = cursor.u1();
    cursor.u4();
    const length = cursor.u4();
    const bodyEnd = cursor.position + length;
    if (tag === HPROF_TAGS.string) {
      const id = cursor.id();
      strings.set(id, cursor.utf8UntilNull());
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
        instanceFieldCount: existing?.instanceFieldCount ?? 0,
        staticFieldCount: existing?.staticFieldCount ?? 0,
      });
    } else if (tag === HPROF_TAGS.heapDump || tag === HPROF_TAGS.heapDumpSegment) {
      readHeapSegment(cursor, header.identifierSize, strings, classes, instances, arrays, warnings, bodyEnd);
    }
    cursor.skip(Math.max(0, bodyEnd - cursor.position));
  }
  if (strings.size === 0) warnings.push('No class or field names were found in the dump.');
  return { header, strings, classes, instances, arrays, warnings };
}
