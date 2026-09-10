import { describe, expect, it } from 'vitest';
import { parseHprof } from './hprof.js';
import { buildObjectGraph } from './graph.js';
import { computeDominators, reachableFromRoots } from './dominators.js';
import { findLeakSuspects, referenceChainTo } from './leaks.js';

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
    return this.u4(value);
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

function classDump(classId: number): Buffer {
  return new Writer()
    .u1(0x20)
    .id(classId)
    .u4(0)
    .id(0)
    .id(0)
    .id(0)
    .id(0)
    .id(0)
    .id(0)
    .u4(ID_SIZE) // one object-reference field
    .u2(0)
    .u2(0)
    .u2(1)
    .id(2)
    .u1(2)
    .bytes();
}

function instance(objectId: number, classId: number, target: number): Buffer {
  return new Writer()
    .u1(0x21)
    .id(objectId)
    .u4(0)
    .id(classId)
    .u4(ID_SIZE)
    .id(target)
    .bytes();
}

function sampleHprof(): Buffer {
  const header = Buffer.concat([
    Buffer.from('JAVA PROFILE 1.0.3', 'utf8'),
    Buffer.from([0]),
    new Writer().u4(ID_SIZE).u8(0).bytes(),
  ]);
  const heap = new Writer()
    .raw(classDump(0x100)) // class A, field -> next
    .u1(0xff)
    .id(0x200) // root -> A#1
    .raw(instance(0x200, 0x100, 0x300)) // A#1 -> B#1
    .raw(instance(0x300, 0x100, 0x400)) // B#1 -> B#2
    .raw(instance(0x400, 0x100, 0)) // B#2 -> null
    .raw(instance(0x500, 0x100, 0x600)) // detached cycle
    .raw(instance(0x600, 0x100, 0x500))
    .bytes();
  return Buffer.concat([
    header,
    stringRecord(1, 'com.example.Node'),
    stringRecord(2, 'next'),
    record(0x02, new Writer().u4(1).id(0x100).u4(0).id(1).bytes()),
    record(0x1c, heap),
    record(0x2c, Buffer.alloc(0)),
  ]);
}

describe('object graph', () => {
  it('resolves references and roots from the dump', () => {
    const result = parseHprof(sampleHprof());
    expect(result.roots).toEqual([0x200n]);
    expect(result.instances.find((instance) => instance.objectId === 0x200n)?.references).toEqual([0x300n]);
    expect(result.instances.find((instance) => instance.objectId === 0x400n)?.references).toEqual([]);

    const graph = buildObjectGraph(result);
    expect(graph.nodes.get(0x200n)).toMatchObject({
      className: 'com.example.Node',
      kind: 'instance',
      isRoot: true,
      references: [0x300n],
    });
    expect(graph.danglingRoots).toEqual([]);
    expect(graph.warnings).toEqual([]);
  });

  it('separates reachable objects from an unreachable cycle', () => {
    const graph = buildObjectGraph(parseHprof(sampleHprof()));
    const reachability = reachableFromRoots(graph);
    const sorted = [...reachability.reachable].sort((left, right) => (left < right ? -1 : left > right ? 1 : 0));
    expect(sorted).toEqual([0x200n, 0x300n, 0x400n]);
    expect(reachability.unreachable.length).toBe(2);
  });

  it('computes retained sizes along the dominance chain', () => {
    const graph = buildObjectGraph(parseHprof(sampleHprof()));
    const { retainedBytes } = computeDominators(graph);
    const shallow = graph.nodes.get(0x200n)?.shallowBytes ?? 0;
    expect(shallow).toBe(ID_SIZE + 8);
    expect(retainedBytes.get(0x400n)).toBe(shallow);
    expect(retainedBytes.get(0x300n)).toBe(shallow * 2);
    expect(retainedBytes.get(0x200n)).toBe(shallow * 3);
  });
});

describe('leak suspects', () => {
  it('ranks non-root objects by retained size with an evidence chain', () => {
    const graph = buildObjectGraph(parseHprof(sampleHprof()));
    const report = findLeakSuspects(graph, { top: 3 });
    // Only reachable, non-root objects can be suspects: 0x200 is a root and the
    // 0x500/0x600 cycle is unreachable, so it is garbage rather than a leak.
    expect(report.suspects).toHaveLength(2);
    expect(report.suspects[0]?.objectId).toBe(0x300n);
    expect(report.suspects[0]?.referenceChain).toEqual([0x200n, 0x300n]);
    expect(report.suspects[1]?.objectId).toBe(0x400n);
    expect(report.suspects[1]?.referenceChain).toEqual([0x200n, 0x300n, 0x400n]);
    expect(report.suspects.some((suspect) => suspect.objectId === 0x200n)).toBe(false);
  });

  it('returns a direct chain for a root and nothing for an unreachable object', () => {
    const graph = buildObjectGraph(parseHprof(sampleHprof()));
    expect(referenceChainTo(graph, 0x200n)).toEqual([0x200n]);
    expect(referenceChainTo(graph, 0x500n)).toBeUndefined();
  });
});
