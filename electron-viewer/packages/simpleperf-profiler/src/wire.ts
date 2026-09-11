/**
 * Minimal protobuf (proto2) wire decoder.
 *
 * simpleperf emits cmd_report_sample.proto in LITE_RUNTIME form, so the only
 * things needed are tag decoding, varints, fixed-width fields, and length
 * delimited fields. Hand-rolling this keeps the app free of a protobuf runtime
 * and lets malformed input fail with a precise offset.
 */
export const WIRE_TYPES = {
  VARINT: 0,
  FIXED64: 1,
  LENGTH_DELIMITED: 2,
  FIXED32: 5,
} as const;

export type WireValue =
  | { readonly kind: 'varint'; readonly value: bigint }
  | { readonly kind: 'fixed32'; readonly value: number }
  | { readonly kind: 'fixed64'; readonly value: bigint }
  | { readonly kind: 'bytes'; readonly value: Uint8Array };

export interface ProtoField {
  readonly fieldNumber: number;
  readonly value: WireValue;
}

export class ProtoDecodeError extends Error {
  readonly offset: number;

  constructor(message: string, offset: number) {
    super(message + ' at byte offset ' + String(offset));
    this.name = 'ProtoDecodeError';
    this.offset = offset;
  }
}

export class ProtoCursor {
  private readonly bytes: Uint8Array;
  private offset: number;
  /**
   * Exclusive end of the message being read. A length delimited submessage
   * lowers the limit instead of handing out a subarray, because a sample has a
   * dozen frames and the subarray plus cursor pair cost more than the fields.
   */
  private limit: number;

  constructor(bytes: Uint8Array, offset = 0, limit = bytes.length) {
    this.bytes = bytes;
    this.offset = offset;
    this.limit = limit;
  }

  get position(): number {
    return this.offset;
  }

  get remaining(): number {
    return this.limit - this.offset;
  }

  atEnd(): boolean {
    return this.offset >= this.limit;
  }

  /**
   * Enters a length delimited submessage in place and returns the enclosing
   * limit, which the caller must hand back to leaveDelimited.
   */
  enterDelimited(): number {
    const length = this.uint32();
    const end = this.offset + length;
    if (end > this.limit) {
      throw new ProtoDecodeError('Truncated message: needed ' + String(length) + ' bytes', this.offset);
    }
    const previous = this.limit;
    this.limit = end;
    return previous;
  }

  /** Restores the limit saved by enterDelimited. Decoders consume to the end. */
  leaveDelimited(previous: number): void {
    this.limit = previous;
  }

  /**
   * Fast, allocation-free tag read: returns (fieldNumber << 3) | wireType.
   *
   * The generic next() path allocates a BigInt per key and an object per field,
   * which dominated the reader's cost: a 12 frame sample has roughly fifty
   * fields, so that is thousands of allocations per sample. Decoders that switch
   * on the tag use this instead.
   */
  tag(): number {
    let result = 0;
    let shift = 0;
    for (;;) {
      if (this.offset >= this.limit) {
        throw new ProtoDecodeError('Truncated tag', this.offset);
      }
      const byte = this.bytes[this.offset] as number;
      this.offset += 1;
      result |= (byte & 0x7f) << shift;
      if ((byte & 0x80) === 0) return result;
      shift += 7;
      if (shift > 28) throw new ProtoDecodeError('Tag exceeds five bytes', this.offset);
    }
  }

  /** Varint that must fit an unsigned 32 bit value; avoids a BigInt. */
  uint32(): number {
    let result = 0;
    let shift = 0;
    for (;;) {
      if (this.offset >= this.limit) {
        throw new ProtoDecodeError('Truncated varint', this.offset);
      }
      const byte = this.bytes[this.offset] as number;
      this.offset += 1;
      if (shift === 28) {
        // The fifth byte may only contribute its low four bits.
        result |= (byte & 0x0f) << shift;
        if ((byte & 0x80) !== 0) {
          // Consume the remaining sign extension bytes of a 64 bit value.
          while ((this.bytes[this.offset - 1] as number) & 0x80) {
            if (this.offset >= this.limit) {
              throw new ProtoDecodeError('Truncated varint', this.offset);
            }
            this.offset += 1;
          }
        }
        return result >>> 0;
      }
      result |= (byte & 0x7f) << shift;
      if ((byte & 0x80) === 0) return result >>> 0;
      shift += 7;
    }
  }

  /** Signed 32 bit view of a varint, for proto2 int32 fields. */
  int32(): number {
    const value = this.uint32();
    return value > 0x7fffffff ? value - 0x1_0000_0000 : value;
  }

  /** Skips a field of the given wire type. */
  skip(wireType: number): void {
    switch (wireType) {
      case WIRE_TYPES.VARINT:
        this.varint();
        return;
      case WIRE_TYPES.FIXED64:
        this.require(8);
        this.offset += 8;
        return;
      case WIRE_TYPES.FIXED32:
        this.require(4);
        this.offset += 4;
        return;
      case WIRE_TYPES.LENGTH_DELIMITED: {
        const length = this.uint32();
        this.require(length);
        this.offset += length;
        return;
      }
      default:
        throw new ProtoDecodeError('Unsupported protobuf wire type ' + String(wireType), this.offset);
    }
  }

  /** Length delimited payload as a view, without copying. */
  view(): Uint8Array {
    const length = this.uint32();
    this.require(length);
    const slice = this.bytes.subarray(this.offset, this.offset + length);
    this.offset += length;
    return slice;
  }

  /** Reads the next field, or undefined at the end of the message. */
  next(): ProtoField | undefined {
    if (this.atEnd()) return undefined;
    const key = this.varint();
    const fieldNumber = Number(key >> 3n);
    const wireType = Number(key & 7n);
    if (fieldNumber === 0) throw new ProtoDecodeError('Protobuf field number 0 is invalid', this.offset);
    switch (wireType) {
      case WIRE_TYPES.VARINT:
        return { fieldNumber, value: { kind: 'varint', value: this.varint() } };
      case WIRE_TYPES.FIXED64:
        return { fieldNumber, value: { kind: 'fixed64', value: this.fixed64() } };
      case WIRE_TYPES.FIXED32:
        return { fieldNumber, value: { kind: 'fixed32', value: this.fixed32() } };
      case WIRE_TYPES.LENGTH_DELIMITED:
        return { fieldNumber, value: { kind: 'bytes', value: this.lengthDelimited() } };
      default:
        throw new ProtoDecodeError('Unsupported protobuf wire type ' + String(wireType), this.offset);
    }
  }

  varint(): bigint {
    // Fast path: the four bytes that fit a 32 bit accumulator are read as
    // numbers, so a small field costs one BigInt instead of one per byte.
    const start = this.offset;
    let result = 0;
    for (let shift = 0; shift < 28; shift += 7) {
      if (this.offset >= this.limit) {
        throw new ProtoDecodeError('Truncated varint', this.offset);
      }
      const byte = this.bytes[this.offset] as number;
      this.offset += 1;
      result |= (byte & 0x7f) << shift;
      if ((byte & 0x80) === 0) return BigInt(result >>> 0);
    }
    // A fifth byte holds bits 28 to 34, still exact as a double.
    if (this.offset >= this.limit) {
      throw new ProtoDecodeError('Truncated varint', this.offset);
    }
    const byte = this.bytes[this.offset] as number;
    if ((byte & 0x80) === 0) {
      this.offset += 1;
      return BigInt(result >>> 0) + (BigInt(byte) << 28n);
    }
    return this.wideVarint(start);
  }

  /** BigInt path for the 64 bit values that need more than five bytes. */
  private wideVarint(start: number): bigint {
    this.offset = start;
    let result = 0n;
    let shift = 0n;
    for (let index = 0; index < 10; index += 1) {
      if (this.offset >= this.limit) {
        throw new ProtoDecodeError('Truncated varint', this.offset);
      }
      const byte = this.bytes[this.offset] as number;
      this.offset += 1;
      result |= BigInt(byte & 0x7f) << shift;
      if ((byte & 0x80) === 0) return result;
      shift += 7n;
    }
    throw new ProtoDecodeError('Varint exceeds 10 bytes', this.offset);
  }

  fixed32(): number {
    this.require(4);
    const value =
      ((this.bytes[this.offset] as number) |
        ((this.bytes[this.offset + 1] as number) << 8) |
        ((this.bytes[this.offset + 2] as number) << 16) |
        ((this.bytes[this.offset + 3] as number) << 24)) >>>
      0;
    this.offset += 4;
    return value;
  }

  fixed64(): bigint {
    const low = BigInt(this.fixed32());
    const high = BigInt(this.fixed32());
    return low | (high << 32n);
  }

  lengthDelimited(): Uint8Array {
    const length = this.varint();
    if (length > BigInt(Number.MAX_SAFE_INTEGER)) {
      throw new ProtoDecodeError('Length delimited field is too large', this.offset);
    }
    const size = Number(length);
    this.require(size);
    const slice = this.bytes.subarray(this.offset, this.offset + size);
    this.offset += size;
    return slice;
  }

  private require(count: number): void {
    if (this.offset + count > this.limit) {
      throw new ProtoDecodeError('Truncated message: needed ' + String(count) + ' bytes', this.offset);
    }
  }
}

/** Reads every field of a message once, preserving order and repeated values. */
export function readFields(bytes: Uint8Array): ProtoField[] {
  const cursor = new ProtoCursor(bytes);
  const fields: ProtoField[] = [];
  for (let field = cursor.next(); field !== undefined; field = cursor.next()) {
    fields.push(field);
  }
  return fields;
}

export function varintOf(field: ProtoField): bigint {
  if (field.value.kind !== 'varint') {
    throw new ProtoDecodeError('Field ' + String(field.fieldNumber) + ' is not a varint', 0);
  }
  return field.value.value;
}

export function bytesOf(field: ProtoField): Uint8Array {
  if (field.value.kind !== 'bytes') {
    throw new ProtoDecodeError('Field ' + String(field.fieldNumber) + ' is not length delimited', 0);
  }
  return field.value.value;
}

const UTF8_DECODER = new TextDecoder('utf-8');

export function utf8Of(bytes: Uint8Array): string {
  return UTF8_DECODER.decode(bytes);
}

/** proto2 int32 fields arrive as varints that may be sign extended to 64 bits. */
export function int32Of(value: bigint): number {
  const truncated = Number(BigInt.asUintN(32, value));
  return truncated > 0x7fffffff ? truncated - 0x1_0000_0000 : truncated;
}

export function uint32Of(value: bigint): number {
  return Number(BigInt.asUintN(32, value));
}
