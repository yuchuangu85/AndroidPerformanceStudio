/**
 * Bounds-checked little-endian reader over the raw trace bytes. Every read
 * either succeeds or throws ArtTraceFormatError, which the parser turns into a
 * structured failure so a truncated file never escapes as a crash.
 */
export class ArtTraceFormatError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ArtTraceFormatError';
  }
}

export class ArtTraceBinaryReader {
  private readonly bytes: Uint8Array;
  private offset: number;

  constructor(bytes: Uint8Array, offset = 0) {
    this.bytes = bytes;
    this.offset = offset;
  }

  get position(): number {
    return this.offset;
  }

  set position(value: number) {
    this.offset = value;
  }

  get isAtEnd(): boolean {
    return this.offset >= this.bytes.length;
  }

  remaining(): number {
    return this.bytes.length - this.offset;
  }

  readU8(): number {
    this.require(1);
    const value = this.bytes[this.offset] as number;
    this.offset += 1;
    return value;
  }

  readU16(): number {
    this.require(2);
    const value = (this.bytes[this.offset] as number) | ((this.bytes[this.offset + 1] as number) << 8);
    this.offset += 2;
    return value;
  }

  readU24(): number {
    this.require(3);
    const value =
      (this.bytes[this.offset] as number) |
      ((this.bytes[this.offset + 1] as number) << 8) |
      ((this.bytes[this.offset + 2] as number) << 16);
    this.offset += 3;
    return value;
  }

  readU32(): number {
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

  readU64(): bigint {
    const low = BigInt(this.readU32());
    const high = BigInt(this.readU32());
    return low | (high << 32n);
  }

  /**
   * Fast path: four bytes fit a 32 bit accumulator and a fifth still fits a
   * double exactly, so the values an entry block actually contains materialise
   * one BigInt instead of one per byte. A 1600 record block reads three LEB128
   * values per record, and the BigInt churn was most of the parse.
   */
  readUleb128(): bigint {
    const start = this.offset;
    let result = 0;
    for (let shift = 0; shift < 28; shift += 7) {
      this.require(1);
      const byte = this.bytes[this.offset] as number;
      this.offset += 1;
      result |= (byte & 0x7f) << shift;
      if ((byte & 0x80) === 0) return BigInt(result >>> 0);
    }
    this.require(1);
    const byte = this.bytes[this.offset] as number;
    if ((byte & 0x80) === 0) {
      this.offset += 1;
      return BigInt(result >>> 0) + (BigInt(byte & 0x7f) << 28n);
    }
    this.offset = start;
    return this.wideUleb128();
  }

  private wideUleb128(): bigint {
    let result = 0n;
    let shift = 0n;
    for (;;) {
      this.require(1);
      const byte = this.bytes[this.offset] as number;
      this.offset += 1;
      result |= BigInt(byte & 0x7f) << shift;
      if ((byte & 0x80) === 0) return result;
      shift += 7n;
      if (shift >= 70n) throw new ArtTraceFormatError('uleb128 overflow');
    }
  }

  readSleb128(): bigint {
    const start = this.offset;
    let result = 0;
    for (let shift = 0; shift < 28; shift += 7) {
      this.require(1);
      const byte = this.bytes[this.offset] as number;
      this.offset += 1;
      result |= (byte & 0x7f) << shift;
      if ((byte & 0x80) === 0) {
        if ((byte & 0x40) === 0) return BigInt(result >>> 0);
        // A set sign bit means the value continues negative to the left, and
        // the sign extension still fits an int32 at every step of this loop.
        return BigInt((result | (-1 << (shift + 7))) | 0);
      }
    }
    this.offset = start;
    return this.wideSleb128();
  }

  private wideSleb128(): bigint {
    let result = 0n;
    let shift = 0n;
    for (;;) {
      this.require(1);
      const byte = this.bytes[this.offset] as number;
      this.offset += 1;
      result |= BigInt(byte & 0x7f) << shift;
      shift += 7n;
      if ((byte & 0x80) === 0) {
        // A set sign bit means the value is negative and must be extended.
        return (byte & 0x40) === 0 ? result : BigInt.asIntN(64, result | (-1n << shift));
      }
      if (shift >= 70n) throw new ArtTraceFormatError('sleb128 overflow');
    }
  }

  readBytes(count: number): Uint8Array {
    this.require(count);
    const slice = this.bytes.subarray(this.offset, this.offset + count);
    this.offset += count;
    return slice;
  }

  skip(count: number): void {
    this.require(count);
    this.offset += count;
  }

  /** Consumes bytes up to and including the first occurrence of the needle. */
  readUntilInclusive(needle: Uint8Array): Uint8Array {
    const out: number[] = [];
    while (this.offset < this.bytes.length) {
      const byte = this.bytes[this.offset] as number;
      this.offset += 1;
      out.push(byte);
      if (out.length >= needle.length) {
        let match = true;
        for (let index = 0; index < needle.length; index += 1) {
          if (out[out.length - needle.length + index] !== needle[index]) {
            match = false;
            break;
          }
        }
        if (match) break;
      }
    }
    return Uint8Array.from(out);
  }

  private require(count: number): void {
    if (this.offset + count > this.bytes.length) {
      throw new ArtTraceFormatError(
        'truncated trace at offset ' + String(this.offset) + ' (need ' + String(count) + ' bytes)',
      );
    }
  }
}

const UTF8_DECODER = new TextDecoder('utf-8');

export function utf8Of(bytes: Uint8Array): string {
  return UTF8_DECODER.decode(bytes);
}
