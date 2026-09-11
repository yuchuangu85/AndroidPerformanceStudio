/**
 * Port of BitmapDumpParser.kt, BitmapDumpModel.kt and BitmapDumpAnalyzer.kt.
 *
 * The API 35 `am dumpheap -b png` extension writes a normal HPROF with one
 * addition: the byte arrays that back every Bitmap hold the PNG the runtime
 * encoded, and android.graphics.Bitmap.dumpData names them. This parser walks
 * the file and copies each PNG out.
 *
 * It reads through explicit offsets rather than a stream because the HPROF
 * record order is not the order the payloads are needed in, and it never holds
 * a PNG in memory: each payload is copied to a temporary file in chunks and
 * renamed once complete, so a failure cannot leave a half-written image behind.
 */
import { createHash } from 'node:crypto';
import { closeSync, mkdirSync, openSync, readSync, renameSync, rmSync, statSync, writeSync } from 'node:fs';
import { join } from 'node:path';
import { crc32 } from 'node:zlib';
import { HEAP_SUBTAGS, type Identifier } from './hprof.js';
import type { BitmapDumpImage, BitmapDumpParseResult } from './bitmap-model.js';

const BITMAP_CLASS = 'android.graphics.Bitmap';
const BITMAP_DUMP_DATA_CLASS = 'android.graphics.Bitmap$DumpData';
const BITMAP_DUMP_FIELD = 'dumpData';

const STRING_IN_UTF8 = 0x01;
const LOAD_CLASS = 0x02;
const HEAP_DUMP = 0x0c;
const HEAP_DUMP_SEGMENT = 0x1c;
const CLASS_DUMP = HEAP_SUBTAGS.classDump;
const INSTANCE_DUMP = HEAP_SUBTAGS.instanceDump;
const OBJECT_ARRAY_DUMP = HEAP_SUBTAGS.objectArrayDump;
const PRIMITIVE_ARRAY_DUMP = HEAP_SUBTAGS.primitiveArrayDump;
const PRIMITIVE_ARRAY_NODATA_DUMP = HEAP_SUBTAGS.primitiveArrayNoData;

const OBJECT_TYPE = 2;
const BYTE_TYPE = 8;

// The subtag values come from the parser that already reads this format, so the
// two cannot drift apart.
const ROOT_UNKNOWN = HEAP_SUBTAGS.rootUnknown;
const ROOT_JNI_GLOBAL = HEAP_SUBTAGS.rootJniGlobal;
const ROOT_JNI_LOCAL = HEAP_SUBTAGS.rootJniLocal;
const ROOT_JAVA_FRAME = HEAP_SUBTAGS.rootJavaFrame;
const ROOT_NATIVE_STACK = HEAP_SUBTAGS.rootNativeStack;
const ROOT_STICKY_CLASS = HEAP_SUBTAGS.rootStickyClass;
const ROOT_THREAD_BLOCK = HEAP_SUBTAGS.rootThreadBlock;
const ROOT_MONITOR_USED = HEAP_SUBTAGS.rootMonitorUsed;
const ROOT_THREAD_OBJECT = HEAP_SUBTAGS.rootThreadObject;
const ROOT_INTERNED_STRING = HEAP_SUBTAGS.rootInternedString;
const ROOT_FINALIZING = HEAP_SUBTAGS.rootFinalizing;
const ROOT_DEBUGGER = HEAP_SUBTAGS.rootDebugger;
const ROOT_REFERENCE_CLEANUP = HEAP_SUBTAGS.rootReferenceCleanup;
const ROOT_VM_INTERNAL = HEAP_SUBTAGS.rootVmInternal;
const ROOT_JNI_MONITOR = HEAP_SUBTAGS.rootJniMonitor;
const ROOT_UNREACHABLE = HEAP_SUBTAGS.rootUnreachable;
const HEAP_DUMP_INFO = HEAP_SUBTAGS.heapDumpInfo;

const TOP_RECORD_HEADER_BYTES = 9;
const RESERVED_CLASS_IDS = 6;
const MAX_HEADER_BYTES = 256;
const MAX_METADATA_STRING_BYTES = 4 * 1024 * 1024;
const MAX_DUMP_DATA_BYTES = 1024 * 1024;
const MAX_BITMAP_RECORDS = 1_000_000;
const ARGB_8888_BYTES_PER_PIXEL = 4;
const CHUNK_BYTES = 1024 * 1024;

export class BitmapDumpParseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'BitmapDumpParseError';
  }
}

interface Header {
  readonly recordStart: number;
  readonly idSize: number;
}

interface DumpField {
  readonly name: string;
  readonly type: number;
}

interface ParsedClassDump {
  readonly classObjectId: Identifier;
  readonly staticObjectReferences: ReadonlyMap<string, Identifier>;
  readonly instanceFields: readonly DumpField[];
}

/** Offset-addressed reader; the file is never mapped or slurped. */
export class HprofFileReader {
  private readonly descriptor: number;
  readonly size: number;

  constructor(path: string) {
    this.descriptor = openSync(path, 'r');
    this.size = statSync(path).size;
  }

  close(): void {
    closeSync(this.descriptor);
  }

  readBytes(position: number, count: number): Uint8Array {
    if (position < 0 || count < 0 || position + count > this.size) {
      throw new BitmapDumpParseError('Truncated HPROF at ' + String(position));
    }
    const buffer = Buffer.allocUnsafe(count);
    let read = 0;
    while (read < count) {
      const bytes = readSync(this.descriptor, buffer, read, count - read, position + read);
      if (bytes <= 0) throw new BitmapDumpParseError('Truncated HPROF at ' + String(position));
      read += bytes;
    }
    return new Uint8Array(buffer);
  }

  readU1(position: number): number {
    return this.readBytes(position, 1)[0] ?? 0;
  }

  readU2(position: number): number {
    const bytes = this.readBytes(position, 2);
    return ((bytes[0] ?? 0) << 8) | (bytes[1] ?? 0);
  }

  readU4(position: number): number {
    const bytes = this.readBytes(position, 4);
    return (
      (((bytes[0] ?? 0) << 24) | ((bytes[1] ?? 0) << 16) | ((bytes[2] ?? 0) << 8) | (bytes[3] ?? 0)) >>> 0
    );
  }

  readId(position: number, idSize: number): Identifier {
    const bytes = this.readBytes(position, idSize);
    let result = 0n;
    for (const byte of bytes) result = (result << 8n) | BigInt(byte);
    return result;
  }

  forEachTopRecord(header: Header, block: (tag: number, body: number, end: number) => void): void {
    let offset = header.recordStart;
    while (offset < this.size) {
      if (offset + TOP_RECORD_HEADER_BYTES > this.size) {
        throw new BitmapDumpParseError('Truncated HPROF record at ' + String(offset));
      }
      const tag = this.readU1(offset);
      const body = offset + TOP_RECORD_HEADER_BYTES;
      const end = body + this.readU4(offset + 5);
      if (end < body || end > this.size) {
        throw new BitmapDumpParseError('Truncated HPROF record at ' + String(offset));
      }
      block(tag, body, end);
      offset = end;
    }
  }

  forEachHeapRecord(header: Header, block: (tag: number, body: number, end: number) => void): void {
    this.forEachTopRecord(header, (tag, body, end) => {
      if (tag !== HEAP_DUMP && tag !== HEAP_DUMP_SEGMENT) return;
      let offset = body;
      while (offset < end) {
        const subTag = this.readU1(offset);
        const recordBody = offset + 1;
        const next = this.nextHeapRecordOffset(subTag, recordBody, header.idSize);
        if (next <= offset || next > end) {
          throw new BitmapDumpParseError(
            'Truncated heap record 0x' + subTag.toString(16) + ' at ' + String(offset),
          );
        }
        block(subTag, recordBody, next);
        offset = next;
      }
    });
  }

  private nextHeapRecordOffset(tag: number, body: number, idSize: number): number {
    switch (tag) {
      case ROOT_UNKNOWN:
      case ROOT_STICKY_CLASS:
      case ROOT_MONITOR_USED:
      case ROOT_INTERNED_STRING:
      case ROOT_FINALIZING:
      case ROOT_DEBUGGER:
      case ROOT_REFERENCE_CLEANUP:
      case ROOT_VM_INTERNAL:
      case ROOT_UNREACHABLE:
        return body + idSize;
      case ROOT_JNI_GLOBAL:
        return body + idSize * 2;
      case ROOT_JNI_LOCAL:
      case ROOT_JAVA_FRAME:
      case ROOT_THREAD_OBJECT:
      case ROOT_JNI_MONITOR:
        return body + idSize + 8;
      case ROOT_NATIVE_STACK:
      case ROOT_THREAD_BLOCK:
        return body + idSize + 4;
      case HEAP_DUMP_INFO:
        return body + 4 + idSize;
      case CLASS_DUMP:
        return this.classDumpEnd(body, idSize);
      case INSTANCE_DUMP: {
        const lengthOffset = body + idSize + 4 + idSize;
        return lengthOffset + 4 + this.readU4(lengthOffset);
      }
      case OBJECT_ARRAY_DUMP: {
        const countOffset = body + idSize + 4;
        return countOffset + 4 + idSize + this.readU4(countOffset) * idSize;
      }
      case PRIMITIVE_ARRAY_DUMP:
      case PRIMITIVE_ARRAY_NODATA_DUMP: {
        const countOffset = body + idSize + 4;
        const count = this.readU4(countOffset);
        const type = this.readU1(countOffset + 4);
        return (
          countOffset + 5 + (tag === PRIMITIVE_ARRAY_DUMP ? count * primitiveWidth(type, idSize) : 0)
        );
      }
      default:
        throw new BitmapDumpParseError('Unknown heap record 0x' + tag.toString(16));
    }
  }

  private classDumpEnd(body: number, idSize: number): number {
    let cursor = body + idSize + 4 + RESERVED_CLASS_IDS * idSize + 4;
    const constants = this.readU2(cursor);
    cursor += 2;
    for (let index = 0; index < constants; index += 1) {
      const type = this.readU1(cursor + 2);
      cursor += 3 + primitiveWidth(type, idSize);
    }
    const statics = this.readU2(cursor);
    cursor += 2;
    for (let index = 0; index < statics; index += 1) {
      const type = this.readU1(cursor + idSize);
      cursor += idSize + 1 + primitiveWidth(type, idSize);
    }
    const fields = this.readU2(cursor);
    return cursor + 2 + fields * (idSize + 1);
  }

  /** Feeds a byte range into a CRC in bounded chunks. */
  updateCrc(seed: number, position: number, count: number): number {
    let crc = seed;
    this.forEachChunk(position, count, (chunk) => {
      crc = crc32(chunk, crc);
    });
    return crc;
  }

  /** Copies a range to a file and returns its SHA-256, one chunk at a time. */
  copyAndDigest(position: number, count: number, output: string): string {
    const digest = createHash('sha256');
    const descriptor = openSync(output, 'w');
    try {
      this.forEachChunk(position, count, (chunk) => {
        digest.update(chunk);
        writeSync(descriptor, chunk);
      });
    } finally {
      closeSync(descriptor);
    }
    return digest.digest('hex');
  }

  private forEachChunk(
    position: number,
    count: number,
    block: (chunk: Uint8Array) => void,
  ): void {
    let offset = position;
    let remaining = count;
    while (remaining > 0) {
      const size = Math.min(CHUNK_BYTES, remaining);
      block(this.readBytes(offset, size));
      offset += size;
      remaining -= size;
    }
  }
}

function primitiveWidth(type: number, idSize: number): number {
  switch (type) {
    case OBJECT_TYPE:
      return idSize;
    case 4:
    case 8:
      return 1;
    case 5:
    case 9:
      return 2;
    case 6:
    case 10:
      return 4;
    case 7:
    case 11:
      return 8;
    default:
      throw new BitmapDumpParseError('Unknown HPROF value type ' + String(type));
  }
}

interface PngInfo {
  readonly width: number;
  readonly height: number;
}

const PNG_SIGNATURE = Uint8Array.from([137, 80, 78, 71, 13, 10, 26, 10]);
const PNG_CHUNK_OVERHEAD = 12;
const MINIMUM_PNG_BYTES = 33;
const MAX_PNG_CHUNK_BYTES = 512 * 1024 * 1024;

function bytesEqual(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  for (let index = 0; index < left.length; index += 1) {
    if (left[index] !== right[index]) return false;
  }
  return true;
}

/** A payload only counts as an image when every chunk checks out. */
function validatePng(reader: HprofFileReader, offset: number, byteCount: number): PngInfo | undefined {
  if (byteCount < MINIMUM_PNG_BYTES) return undefined;
  if (!bytesEqual(reader.readBytes(offset, PNG_SIGNATURE.length), PNG_SIGNATURE)) return undefined;
  let cursor = offset + PNG_SIGNATURE.length;
  const end = offset + byteCount;
  let width = 0;
  let height = 0;
  let first = true;
  while (cursor + PNG_CHUNK_OVERHEAD <= end) {
    const length = reader.readU4(cursor);
    if (length > MAX_PNG_CHUNK_BYTES) return undefined;
    const chunkEnd = cursor + PNG_CHUNK_OVERHEAD + length;
    if (chunkEnd > end) return undefined;
    const type = reader.readBytes(cursor + 4, 4);
    const storedCrc = reader.readU4(cursor + 8 + length);
    const computed = reader.updateCrc(0, cursor + 4, 4 + length);
    if (computed !== storedCrc) return undefined;
    if (first) {
      if (!bytesEqual(type, Uint8Array.from([0x49, 0x48, 0x44, 0x52])) || length !== 13) return undefined;
      const dimensions = reader.readBytes(cursor + 8, 8);
      width = readInt32(dimensions, 0);
      height = readInt32(dimensions, 4);
      if (width <= 0 || height <= 0) return undefined;
      first = false;
    }
    cursor = chunkEnd;
    if (bytesEqual(type, Uint8Array.from([0x49, 0x45, 0x4e, 0x44]))) {
      return cursor === end ? { width, height } : undefined;
    }
  }
  return undefined;
}

function readInt32(bytes: Uint8Array, offset: number): number {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  return view.getInt32(offset, false);
}

function readHeader(reader: HprofFileReader): Header {
  let end = 0;
  while (end < MAX_HEADER_BYTES && end < reader.size && reader.readU1(end) !== 0) end += 1;
  if (end === reader.size || end === MAX_HEADER_BYTES) {
    throw new BitmapDumpParseError('Truncated HPROF header');
  }
  const text = new TextDecoder().decode(reader.readBytes(0, end));
  if (!text.startsWith('JAVA PROFILE')) throw new BitmapDumpParseError('File is not a valid HPROF');
  const idSize = reader.readU4(end + 1);
  if (idSize !== 4 && idSize !== 8) throw new BitmapDumpParseError('Unsupported HPROF id size ' + String(idSize));
  return { recordStart: end + 1 + 4 + 8, idSize };
}

function readBigEndian(bytes: Uint8Array, offset: number, count: number): bigint {
  let value = 0n;
  for (let index = 0; index < count; index += 1) {
    value = (value << 8n) | BigInt(bytes[offset + index] ?? 0);
  }
  return value;
}

function parseClassDump(
  reader: HprofFileReader,
  body: number,
  idSize: number,
  strings: ReadonlyMap<Identifier, string>,
): ParsedClassDump {
  const classObjectId = reader.readId(body, idSize);
  let cursor = body + idSize + 4 + RESERVED_CLASS_IDS * idSize + 4;
  const constantCount = reader.readU2(cursor);
  cursor += 2;
  for (let index = 0; index < constantCount; index += 1) {
    const type = reader.readU1(cursor + 2);
    cursor += 2 + 1 + primitiveWidth(type, idSize);
  }
  const staticReferences = new Map<string, Identifier>();
  const staticCount = reader.readU2(cursor);
  cursor += 2;
  for (let index = 0; index < staticCount; index += 1) {
    const nameId = reader.readId(cursor, idSize);
    const type = reader.readU1(cursor + idSize);
    const valueOffset = cursor + idSize + 1;
    if (type === OBJECT_TYPE) {
      staticReferences.set(strings.get(nameId) ?? '', reader.readId(valueOffset, idSize));
    }
    cursor = valueOffset + primitiveWidth(type, idSize);
  }
  const fieldCount = reader.readU2(cursor);
  cursor += 2;
  const instanceFields: DumpField[] = [];
  for (let index = 0; index < fieldCount; index += 1) {
    const nameId = reader.readId(cursor, idSize);
    const type = reader.readU1(cursor + idSize);
    cursor += idSize + 1;
    instanceFields.push({ name: strings.get(nameId) ?? '', type });
  }
  return { classObjectId, staticObjectReferences: staticReferences, instanceFields };
}

function moveCompletedImage(temporary: string, output: string): void {
  try {
    renameSync(temporary, output);
  } catch {
    rmSync(output, { force: true });
    renameSync(temporary, output);
  }
}

/**
 * Extracts every PNG the dump carries and writes it next to the HPROF. Progress
 * runs 0..100 across the phases the caller shows.
 */
export function parseBitmapDump(
  hprofFile: string,
  imagesDirectory: string,
  onProgress: (percent: number) => void = () => undefined,
): BitmapDumpParseResult {
  if (!statSync(hprofFile, { throwIfNoEntry: false })?.isFile()) {
    throw new BitmapDumpParseError('Bitmap HPROF does not exist: ' + hprofFile);
  }
  mkdirSync(imagesDirectory, { recursive: true });
  const reader = new HprofFileReader(hprofFile);
  try {
    const header = readHeader(reader);
    onProgress(5);

    const strings = new Map<Identifier, string>();
    const classNameStringIds = new Map<Identifier, Identifier>();
    reader.forEachTopRecord(header, (tag, body, end) => {
      if (tag === STRING_IN_UTF8) {
        const id = reader.readId(body, header.idSize);
        const length = end - body - header.idSize;
        if (length > MAX_METADATA_STRING_BYTES) {
          throw new BitmapDumpParseError('HPROF string is too large at offset ' + String(body));
        }
        strings.set(id, new TextDecoder().decode(reader.readBytes(body + header.idSize, length)));
      } else if (tag === LOAD_CLASS) {
        const classObjectId = reader.readId(body + 4, header.idSize);
        const nameId = reader.readId(body + 4 + header.idSize + 4, header.idSize);
        classNameStringIds.set(classObjectId, nameId);
      }
    });

    const bitmapClassId = firstKeyFor(strings, classNameStringIds, BITMAP_CLASS);
    if (bitmapClassId === undefined) {
      throw new BitmapDumpParseError('HPROF does not contain ' + BITMAP_CLASS);
    }
    const dumpDataClassId = firstKeyFor(strings, classNameStringIds, BITMAP_DUMP_DATA_CLASS);
    if (dumpDataClassId === undefined) {
      throw new BitmapDumpParseError('HPROF does not contain Android Bitmap dump metadata');
    }

    let dumpObjectId: Identifier | undefined;
    let dumpFields: readonly DumpField[] | undefined;
    reader.forEachHeapRecord(header, (tag, body) => {
      if (tag !== CLASS_DUMP) return;
      const parsed = parseClassDump(reader, body, header.idSize, strings);
      if (parsed.classObjectId === bitmapClassId) {
        dumpObjectId = parsed.staticObjectReferences.get(BITMAP_DUMP_FIELD);
      } else if (parsed.classObjectId === dumpDataClassId) {
        dumpFields = parsed.instanceFields;
      }
    });
    if (dumpObjectId === undefined || dumpObjectId === 0n) {
      throw new BitmapDumpParseError('Bitmap.dumpData is empty');
    }
    if (dumpFields === undefined) throw new BitmapDumpParseError('Bitmap DumpData fields are missing');

    let fieldBytes: Uint8Array | undefined;
    reader.forEachHeapRecord(header, (tag, body) => {
      if (tag !== INSTANCE_DUMP) return;
      if (reader.readId(body, header.idSize) !== dumpObjectId) return;
      const classId = reader.readId(body + header.idSize + 4, header.idSize);
      if (classId !== dumpDataClassId) return;
      const lengthOffset = body + header.idSize + 4 + header.idSize;
      const byteCount = reader.readU4(lengthOffset);
      if (byteCount > MAX_DUMP_DATA_BYTES) {
        throw new BitmapDumpParseError('Bitmap DumpData instance is too large: ' + String(byteCount) + ' bytes');
      }
      fieldBytes = reader.readBytes(lengthOffset + 4, byteCount);
    });
    if (fieldBytes === undefined) throw new BitmapDumpParseError('Bitmap DumpData instance was not found');
    onProgress(35);

    const values = new Map<string, bigint>();
    let offset = 0;
    for (const field of dumpFields) {
      const width = primitiveWidth(field.type, header.idSize);
      if (offset + width > fieldBytes.length) {
        throw new BitmapDumpParseError('Bitmap DumpData fields are truncated');
      }
      values.set(field.name, readBigEndian(fieldBytes, offset, width));
      offset += width;
    }
    const recordedCount = positiveCount(values.get('count'), 'Bitmap DumpData count is missing');
    const discoveredCount = positiveCount(values.get('max'), 'Bitmap DumpData max is missing');
    const buffersObjectId = values.get('buffers');
    if (buffersObjectId === undefined || buffersObjectId === 0n) {
      throw new BitmapDumpParseError('Bitmap DumpData buffers are missing');
    }

    let bufferIds: Identifier[] | undefined;
    reader.forEachHeapRecord(header, (tag, body) => {
      if (tag !== OBJECT_ARRAY_DUMP) return;
      if (reader.readId(body, header.idSize) !== buffersObjectId) return;
      const countOffset = body + header.idSize + 4;
      const count = reader.readU4(countOffset);
      if (count > MAX_BITMAP_RECORDS) {
        throw new BitmapDumpParseError('Bitmap buffer array is too large: ' + String(count) + ' entries');
      }
      const elements = countOffset + 4 + header.idSize;
      const ids: Identifier[] = [];
      for (let index = 0; index < count; index += 1) {
        ids.push(reader.readId(elements + index * header.idSize, header.idSize));
      }
      bufferIds = ids;
    });
    if (bufferIds === undefined) throw new BitmapDumpParseError('Bitmap image buffer array was not found');
    onProgress(50);

    // The same array can back several recorded bitmaps, so the map is one-to-many.
    const recordsByArrayId = new Map<string, number[]>();
    bufferIds.slice(0, recordedCount).forEach((objectId, index) => {
      if (objectId === 0n) return;
      const key = objectId.toString();
      const bucket = recordsByArrayId.get(key);
      if (bucket === undefined) recordsByArrayId.set(key, [index + 1]);
      else bucket.push(index + 1);
    });

    const images: BitmapDumpImage[] = [];
    const totalRecords = Math.max(
      [...recordsByArrayId.values()].reduce((total, list) => total + list.length, 0),
      1,
    );
    let visited = 0;
    reader.forEachHeapRecord(header, (tag, body) => {
      if (tag !== PRIMITIVE_ARRAY_DUMP) return;
      const objectId = reader.readId(body, header.idSize);
      const recordIndexes = recordsByArrayId.get(objectId.toString());
      if (recordIndexes === undefined) return;
      const countOffset = body + header.idSize + 4;
      const byteCount = reader.readU4(countOffset);
      const type = reader.readU1(countOffset + 4);
      if (type !== BYTE_TYPE) return;
      const payloadOffset = countOffset + 4 + 1;
      const png = validatePng(reader, payloadOffset, byteCount);
      if (png === undefined) return;
      for (const recordIndex of recordIndexes) {
        const temporary = join(imagesDirectory, '.bitmap-' + String(recordIndex) + '.tmp');
        const sha256 = reader.copyAndDigest(payloadOffset, byteCount, temporary);
        const fileName =
          String(recordIndex).padStart(4, '0') +
          '_' + String(png.width) + 'x' + String(png.height) +
          '_' + String(byteCount) + 'B_' + sha256.slice(0, 12) + '.png';
        const output = join(imagesDirectory, fileName);
        moveCompletedImage(temporary, output);
        images.push({
          recordIndex,
          arrayObjectId: '0x' + objectId.toString(16),
          file: output,
          width: png.width,
          height: png.height,
          pngBytes: byteCount,
          estimatedMemoryBytes: png.width * png.height * ARGB_8888_BYTES_PER_PIXEL,
          sha256,
          duplicateCount: 1,
        });
        visited += 1;
        onProgress(50 + Math.floor((visited * 50) / totalRecords));
      }
    });
    onProgress(100);
    return {
      recordedBitmapCount: recordedCount,
      discoveredBitmapCount: discoveredCount,
      images: images.sort((left, right) => left.recordIndex - right.recordIndex),
    };
  } finally {
    reader.close();
  }
}

function firstKeyFor(
  strings: ReadonlyMap<Identifier, string>,
  classNames: ReadonlyMap<Identifier, Identifier>,
  wanted: string,
): Identifier | undefined {
  for (const [classObjectId, nameId] of classNames) {
    if (strings.get(nameId) === wanted) return classObjectId;
  }
  return undefined;
}

function positiveCount(value: bigint | undefined, message: string): number {
  if (value === undefined || value < 0n) throw new BitmapDumpParseError(message);
  const asNumber = Number(value);
  if (!Number.isSafeInteger(asNumber)) throw new BitmapDumpParseError(message);
  return asNumber;
}

