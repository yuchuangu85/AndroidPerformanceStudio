import { describe, expect, it } from 'vitest';
import { ProtoCursor, ProtoDecodeError, readFields, int32Of, uint32Of } from './wire.js';
import { concat, encodeBytesField, encodeVarint, encodeVarintField } from './proto.js';

describe('protobuf wire decoding', () => {
  it('round-trips varints at the boundaries of the encodable range', () => {
    for (const value of [0n, 1n, 127n, 128n, 300n, 9007199254740993n, 18446744073709551615n]) {
      const decoded = new ProtoCursor(encodeVarint(value)).varint();
      expect(decoded).toBe(value);
    }
  });

  it('reads mixed wire types and skips nothing it understands', () => {
    const bytes = concat([
      encodeVarintField(1, 5n),
      encodeBytesField(2, new TextEncoder().encode('hello')),
      // field 3, fixed32
      Uint8Array.from([(3 << 3) | 5, 0x78, 0x56, 0x34, 0x12]),
      // field 4, fixed64
      Uint8Array.from([(4 << 3) | 1, 1, 0, 0, 0, 0, 0, 0, 0]),
    ]);
    const fields = readFields(bytes);
    expect(fields.map((field) => field.fieldNumber)).toEqual([1, 2, 3, 4]);
    expect(fields[0]?.value).toEqual({ kind: 'varint', value: 5n });
    expect(fields[1]?.value.kind).toBe('bytes');
    expect(fields[2]?.value).toEqual({ kind: 'fixed32', value: 0x12345678 });
    expect(fields[3]?.value).toEqual({ kind: 'fixed64', value: 1n });
  });

  it('rejects truncation, field number zero, and unknown wire types', () => {
    expect(() => new ProtoCursor(Uint8Array.from([0x80])).varint()).toThrow(ProtoDecodeError);
    expect(() => new ProtoCursor(Uint8Array.from([0x00, 0x01])).next()).toThrow(/field number 0/);
    expect(() => new ProtoCursor(Uint8Array.from([(1 << 3) | 3, 0x00])).next()).toThrow(/wire type 3/);
    // Field 1, length delimited, claiming four bytes while only one is present.
    expect(() => new ProtoCursor(Uint8Array.from([0x0a, 0x04, 0x00])).next()).toThrow(/Truncated/);
  });

  it('decodes proto2 int32 values that were sign extended to 64 bits', () => {
    expect(int32Of(0n)).toBe(0);
    expect(int32Of(7n)).toBe(7);
    expect(int32Of(0xffffffffffffffffn)).toBe(-1);
    expect(int32Of(0xffffffffn)).toBe(-1);
    expect(uint32Of(0xffffffffffffffffn)).toBe(0xffffffff);
  });
});
