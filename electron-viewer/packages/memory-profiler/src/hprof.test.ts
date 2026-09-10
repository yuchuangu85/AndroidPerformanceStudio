import { describe, expect, it } from 'vitest';
import { parseHprof, parseHprofHeader } from './hprof.js';
import { arrayShallowBytes, classHistogram, summarizeMemory } from './histogram.js';

const ID_SIZE = 4;

class Writer {
  private readonly chunks: Buffer[] = [];

  u1(value: number): this {
    this.chunks.push(Buffer.from([value & 0xff]));
    return this;
  }

  u2(value: number): this {
    const buffer = Buffer.alloc(2);
    buffer.writeUInt16BE(value, 0);
    this.chunks.push(buffer);
    return this;
  }

  u4(value: number): this {
    const buffer = Buffer.alloc(4);
    buffer.writeUInt32BE(value, 0);
    this.chunks.push(buffer);
    return this;
  }

  u8(value: number): this {
    const buffer = Buffer.alloc(8);
    buffer.writeBigUInt64BE(BigInt(value), 0);
    this.chunks.push(buffer);
    return this;
  }

  id(value: number): this {
    return ID_SIZE === 4 ? this.u4(value) : this.u8(value);
  }

  utf8z(value: string): this {
    this.chunks.push(Buffer.from(value, 'utf8'), Buffer.from([0]));
    return this;
  }

  raw(value: Buffer): this {
    this.chunks.push(value);
    return this;
  }

  bytes(): Buffer {
    return Buffer.concat(this.chunks);
  }
}

function record(tag: number, body: Buffer): Buffer {
  const head = Buffer.alloc(9);
  head.writeUInt8(tag, 0);
  head.writeUInt32BE(0, 1);
  head.writeUInt32BE(body.length, 5);
  return Buffer.concat([head, body]);
}

function stringRecord(id: number, value: string): Buffer {
  return record(0x01, new Writer().id(id).utf8z(value).bytes());
}

function classDumpBody(): Buffer {
  const writer = new Writer();
  writer.u1(0x20); // CLASS_DUMP
  writer.id(0x100); // class object id
  writer.u4(0); // stack trace serial
  writer.id(0); // super class
  writer.id(0); // class loader
  writer.id(0); // signers
  writer.id(0); // protection domain
  writer.id(0); // reserved 1
  writer.id(0); // reserved 2
  writer.u4(8); // instance field bytes
  writer.u2(0); // constant pool
  writer.u2(0); // static fields
  writer.u2(2); // instance fields
  writer.id(2).u1(2); // name id 2, object type
  writer.id(3).u1(10); // name id 3, int type
  return writer.bytes();
}

function instanceDumpBody(objectId: number): Buffer {
  const writer = new Writer();
  writer.u1(0x21);
  writer.id(objectId);
  writer.u4(0);
  writer.id(0x100);
  writer.u4(8);
  writer.raw(Buffer.alloc(8));
  return writer.bytes();
}

function sampleHprof(): Buffer {
  const header = Buffer.concat([
    Buffer.from('JAVA PROFILE 1.0.3', 'utf8'),
    Buffer.from([0]),
    new Writer().u4(ID_SIZE).u8(1_700_000_000_000).bytes(),
  ]);
  const heapBody = new Writer()
    .raw(classDumpBody())
    .raw(instanceDumpBody(0x200))
    .raw(instanceDumpBody(0x201))
    .u1(0x23) // PRIMITIVE_ARRAY_DUMP
    .id(0x300)
    .u4(0)
    .u4(4)
    .u1(10) // int
    .raw(Buffer.alloc(16))
    .u1(0x22) // OBJECT_ARRAY_DUMP
    .id(0x400)
    .u4(0)
    .u4(2)
    .id(0)
    .id(0x200)
    .id(0x201)
    .bytes();
  return Buffer.concat([
    header,
    stringRecord(1, 'com.example.Foo'),
    stringRecord(2, 'next'),
    stringRecord(3, 'count'),
    record(0x02, new Writer().u4(1).id(0x100).u4(0).id(1).bytes()),
    record(0x1c, heapBody),
    record(0x2c, Buffer.alloc(0)),
  ]);
}

describe('parseHprofHeader', () => {
  it('reads the version, identifier size, and timestamp', () => {
    const header = parseHprofHeader(sampleHprof());
    expect(header.version).toBe('1.0.3');
    expect(header.identifierSize).toBe(4);
    expect(header.timestampMillis).toBe(1_700_000_000_000);
  });

  it('rejects non-HPROF input and unsupported identifier sizes', () => {
    expect(() => parseHprofHeader(Buffer.from('not a profile', 'utf8'))).toThrow(/HPROF/);
    expect(() => parseHprofHeader(Buffer.from('JAVA PROFILE 1.0.3\u0000', 'utf8'))).toThrow();
    const badIdSize = Buffer.concat([
      Buffer.from('JAVA PROFILE 1.0.3', 'utf8'),
      Buffer.from([0]),
      new Writer().u4(16).u8(0).bytes(),
    ]);
    expect(() => parseHprofHeader(badIdSize)).toThrow(/identifier size/);
  });
});

describe('parseHprof', () => {
  it('reads classes, instances, arrays, and names', () => {
    const result = parseHprof(sampleHprof());
    expect(result.strings.get(1n)).toBe('com.example.Foo');
    expect(result.classes.get(0x100n)).toMatchObject({
      nameId: 1n,
      instanceFieldBytes: 8,
    });
    expect(result.classes.get(0x100n)?.instanceFields).toEqual([
      { nameId: 2n, type: 2 },
      { nameId: 3n, type: 10 },
    ]);
    expect(result.instances).toHaveLength(2);
    expect(result.instances[0]).toMatchObject({ objectId: 0x200n, classObjectId: 0x100n, fieldBytes: 8 });
    expect(result.arrays).toHaveLength(2);
    expect(result.arrays[0]).toMatchObject({ objectId: 0x300n, kind: 'primitive', elementType: 10, length: 4 });
    expect(result.arrays[0]?.shallowBytes).toBe(4 * 4 + 8);
    expect(result.arrays[1]).toMatchObject({ objectId: 0x400n, kind: 'object', length: 2 });
    expect(result.arrays[1]?.shallowBytes).toBe(2 * 4 + 8);
    expect(result.warnings).toEqual([]);
  });

  it('warns instead of failing on an unknown heap sub-tag', () => {
    const unknown = new Writer().u1(0x7f).bytes();
    const file = Buffer.concat([
      Buffer.concat([
        Buffer.from('JAVA PROFILE 1.0.3', 'utf8'),
        Buffer.from([0]),
        new Writer().u4(ID_SIZE).u8(0).bytes(),
      ]),
      stringRecord(1, 'x'),
      record(0x1c, unknown),
    ]);
    const result = parseHprof(file);
    expect(result.warnings.some((warning) => warning.includes('Unknown heap sub-tag'))).toBe(true);
  });
});

describe('memory summaries', () => {
  it('computes a histogram with estimated object headers', () => {
    const result = parseHprof(sampleHprof());
    const histogram = classHistogram(result);
    expect(histogram).toEqual([
      { className: 'com.example.Foo', instanceCount: 2, shallowBytes: 2 * (8 + 8) },
    ]);
    expect(arrayShallowBytes(result)).toBe(24 + 16);

    const summary = summarizeMemory(result);
    expect(summary).toMatchObject({
      version: '1.0.3',
      identifierSize: 4,
      classCount: 1,
      instanceCount: 2,
      arrayCount: 2,
      shallowBytes: 32 + 24 + 16,
    });
  });

  it('names an unknown class id rather than guessing', () => {
    const file = Buffer.concat([
      Buffer.concat([
        Buffer.from('JAVA PROFILE 1.0.3', 'utf8'),
        Buffer.from([0]),
        new Writer().u4(ID_SIZE).u8(0).bytes(),
      ]),
      record(0x1c, instanceDumpBody(0x999)),
    ]);
    expect(classHistogram(parseHprof(file))[0]?.className).toContain('unknown class');
  });
});
