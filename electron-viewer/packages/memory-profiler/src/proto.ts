/**
 * Minimal protobuf wire decoder shared by the memory parsers.
 *
 * Same shape as the simpleperf reader: tag decoding, varints, and length
 * delimited fields, with malformed input failing loudly. Kept local so the
 * memory package does not depend on another feature package for sixty lines.
 */
export const WIRE_VARINT = 0;
export const WIRE_FIXED64 = 1;
export const WIRE_LENGTH_DELIMITED = 2;
export const WIRE_FIXED32 = 5;

export class ProtoReader {
  private readonly bytes: Uint8Array;
  private offset = 0;

  constructor(bytes: Uint8Array) {
    this.bytes = bytes;
  }

  get atEnd(): boolean {
    return this.offset >= this.bytes.length;
  }

  get position(): number {
    return this.offset;
  }

  readTag(): number {
    return Number(this.readVarint() & 0xffffffffn);
  }

  readVarint(): bigint {
    let result = 0n;
    let shift = 0n;
    while (this.offset < this.bytes.length) {
      const byte = this.bytes[this.offset] ?? 0;
      this.offset += 1;
      result |= BigInt(byte & 0x7f) << shift;
      if ((byte & 0x80) === 0) return result;
      shift += 7n;
      if (shift > 70n) throw new Error('Malformed varint');
    }
    throw new Error('Truncated varint');
  }

  readLengthDelimited(): Uint8Array {
    const length = Number(this.readVarint());
    if (!Number.isSafeInteger(length) || length < 0 || this.offset + length > this.bytes.length) {
      throw new Error('Truncated message');
    }
    const slice = this.bytes.subarray(this.offset, this.offset + length);
    this.offset += length;
    return slice;
  }

  skip(wireType: number): void {
    switch (wireType) {
      case WIRE_VARINT:
        this.readVarint();
        return;
      case WIRE_FIXED64:
        this.offset += 8;
        return;
      case WIRE_LENGTH_DELIMITED: {
        const length = Number(this.readVarint());
        if (!Number.isSafeInteger(length) || length < 0) throw new Error('Truncated message');
        this.offset += length;
        return;
      }
      case WIRE_FIXED32:
        this.offset += 4;
        return;
      default:
        throw new Error('Unknown wire type ' + String(wireType));
    }
  }

  readBytes(length: number): Uint8Array {
    if (this.offset + length > this.bytes.length) throw new Error('Truncated message');
    const slice = this.bytes.subarray(this.offset, this.offset + length);
    this.offset += length;
    return slice;
  }

  readU8(): number {
    return this.bytes[this.offset++] ?? 0;
  }
}

/** Varints that may repeat packed or unpacked; both spellings are accepted. */
export function readPackedVarints(reader: ProtoReader, wireType: number, target: bigint[]): void {
  if (wireType === WIRE_LENGTH_DELIMITED) {
    const packed = new ProtoReader(reader.readLengthDelimited());
    while (!packed.atEnd) target.push(packed.readVarint());
    return;
  }
  if (wireType === WIRE_VARINT) {
    target.push(reader.readVarint());
    return;
  }
  reader.skip(wireType);
}

/** Unsigned varints that have to fit a JavaScript number. */
export function toByteCount(value: bigint, what: string): number {
  if (value < 0n || value > BigInt(Number.MAX_SAFE_INTEGER)) {
    throw new Error(what + ' does not fit a safe integer: ' + value.toString());
  }
  return Number(value);
}
