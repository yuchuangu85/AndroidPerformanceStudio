import { describe, expect, it } from 'vitest';
import { heapGraphToHprofResult } from './heap-graph-bridge.js';
import { parseJavaHeapTrace } from './java-heap-trace.js';

function concat(parts: readonly Uint8Array[]): Uint8Array {
  const length = parts.reduce((total, part) => total + part.length, 0);
  const result = new Uint8Array(length);
  let offset = 0;
  for (const part of parts) {
    result.set(part, offset);
    offset += part.length;
  }
  return result;
}

function varint(value: bigint): Uint8Array {
  if (value < 0n) throw new Error('Test fixtures use unsigned protobuf values only');
  const bytes: number[] = [];
  let remaining = value;
  do {
    const byte = Number(remaining & 0x7fn);
    remaining >>= 7n;
    bytes.push(remaining === 0n ? byte : byte | 0x80);
  } while (remaining !== 0n);
  return Uint8Array.from(bytes);
}

function fieldVarint(field: number, value: bigint): Uint8Array {
  return concat([varint(BigInt(field << 3)), varint(value)]);
}

function fieldBytes(field: number, value: Uint8Array): Uint8Array {
  return concat([varint(BigInt((field << 3) | 2)), varint(BigInt(value.length)), value]);
}

function string(field: number, value: string): Uint8Array {
  return fieldBytes(field, new TextEncoder().encode(value));
}

function packed(field: number, values: readonly bigint[]): Uint8Array {
  return fieldBytes(field, concat(values.map(varint)));
}

function message(field: number, fields: readonly Uint8Array[]): Uint8Array {
  return fieldBytes(field, concat(fields));
}

function tracePacket(heapGraph: Uint8Array, sequenceId = 0n): Uint8Array {
  return message(1, [fieldVarint(10, sequenceId), fieldBytes(56, heapGraph)]);
}

function object(fields: readonly Uint8Array[]): Uint8Array {
  return message(2, fields);
}

function type(fields: readonly Uint8Array[]): Uint8Array {
  return message(9, fields);
}

function root(fields: readonly Uint8Array[]): Uint8Array {
  return message(7, fields);
}

describe('parseJavaHeapTrace', () => {
  it('matches Kotlin’s basic graph fixture: interned fields, types, objects, and roots', () => {
    // Direct TypeScript port of JavaHeapTraceParserTest.traceBytes().
    const heapGraph = concat([
      message(4, [fieldVarint(1, 1n), string(2, 'mContext')]),
      message(4, [fieldVarint(1, 2n), string(2, 'value')]),
      type([
        fieldVarint(1, 1n),
        string(3, 'android/app/Activity'),
        fieldVarint(4, 16n),
        fieldVarint(7, 1n),
        packed(6, [1n]),
      ]),
      type([fieldVarint(1, 2n), string(3, 'java/lang/String'), fieldVarint(4, 24n)]),
      object([fieldVarint(1, 100n), fieldVarint(2, 1n), fieldVarint(3, 16n), packed(4, [1n]), packed(5, [200n])]),
      object([fieldVarint(1, 200n), fieldVarint(2, 2n), fieldVarint(3, 24n)]),
      root([packed(1, [100n]), fieldVarint(2, 1n)]),
    ]);

    const result = parseJavaHeapTrace(tracePacket(heapGraph));
    expect(result.ok).toBe(true);
    if (!result.ok) return;

    expect(result.graph.fieldNames).toEqual(new Map([[1n, 'mContext'], [2n, 'value']]));
    expect(result.graph.types).toEqual([
      {
        id: 1n,
        className: 'android/app/Activity',
        objectSize: 16,
        superclassId: 0n,
        kind: 1,
        referenceFieldIds: [1n],
        locationId: 0n,
        classLoaderId: 0n,
      },
      {
        id: 2n,
        className: 'java/lang/String',
        objectSize: 24,
        superclassId: 0n,
        kind: 0,
        referenceFieldIds: [],
        locationId: 0n,
        classLoaderId: 0n,
      },
    ]);
    expect(result.graph.objects.map(({ id, typeId, selfSize, referenceFieldIds, referenceObjectIds }) => ({
      id,
      typeId,
      selfSize,
      referenceFieldIds,
      referenceObjectIds,
    }))).toEqual([
      { id: 100n, typeId: 1n, selfSize: 16, referenceFieldIds: [1n], referenceObjectIds: [200n] },
      { id: 200n, typeId: 2n, selfSize: 24, referenceFieldIds: [], referenceObjectIds: [] },
    ]);
    expect(result.graph.roots).toEqual([{ objectIds: [100n], rootType: 1 }]);
  });

  it('assembles continued packets with object ID deltas, reference bases, and inherited heap types', () => {
    const firstChunk = concat([
      fieldVarint(1, 4242n),
      fieldVarint(5, 1n),
      fieldVarint(6, 0n),
      message(4, [fieldVarint(1, 3n), string(2, 'child')]),
      object([fieldVarint(1, 100n), fieldVarint(2, 1n), fieldVarint(9, 7n)]),
    ]);
    const secondChunk = concat([
      fieldVarint(5, 0n),
      fieldVarint(6, 1n),
      object([fieldVarint(2, 1n), fieldVarint(6, 1_000n), packed(5, [2n, 0n]), fieldVarint(7, 1n)]),
    ]);

    const result = parseJavaHeapTrace(concat([tracePacket(firstChunk, 77n), tracePacket(secondChunk, 77n)]));
    expect(result.ok).toBe(true);
    if (!result.ok) return;

    expect(result.graph.sequenceId).toBe(77n);
    expect(result.graph.pid).toBe(4242);
    expect(result.graph.fieldNames.get(3n)).toBe('child');
    expect(result.graph.objects.map((value) => ({
      id: value.id,
      referenceObjectIds: value.referenceObjectIds,
      heapType: value.heapType,
    }))).toEqual([
      { id: 100n, referenceObjectIds: [], heapType: 7 },
      { id: 101n, referenceObjectIds: [1002n, 0n], heapType: 7 },
    ]);
  });

  it('preserves superclass fields, null references, runtime references, and Android metadata through the HPROF bridge', () => {
    // Direct TypeScript port of Kotlin's `uses type and superclass field ids and captures Android metadata` fixture.
    const heapGraph = concat([
      message(4, [fieldVarint(1, 1n), string(2, 'child')]),
      message(4, [fieldVarint(1, 2n), string(2, 'parent')]),
      type([fieldVarint(1, 1n), string(3, 'Parent'), packed(6, [2n])]),
      type([fieldVarint(1, 2n), string(3, 'Child'), fieldVarint(5, 1n), fieldVarint(8, 99n), packed(6, [1n])]),
      object([
        fieldVarint(1, 100n),
        fieldVarint(2, 2n),
        packed(5, [200n, 0n]),
        packed(10, [300n]),
        fieldVarint(8, 4096n),
        fieldVarint(9, 3n),
        fieldVarint(13, 20n),
        fieldVarint(14, 10n),
      ]),
    ]);
    const parsed = parseJavaHeapTrace(tracePacket(heapGraph));
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;

    const dump = heapGraphToHprofResult(parsed.graph);
    const instance = dump.instances[0];
    expect(instance).toBeDefined();
    if (instance === undefined) return;
    expect(instance.referenceNameIds.map((nameId) => dump.strings.get(nameId))).toEqual([
      'child',
      'parent',
      '<runtime-internal-0>',
    ]);
    expect(instance.references).toEqual([200n, 0n, 300n]);
    expect(instance.primitiveNameIds.map((nameId) => dump.strings.get(nameId))).toContain('mWidth');
    expect(instance.primitiveValues[instance.primitiveNameIds.findIndex((nameId) => dump.strings.get(nameId) === 'mWidth')]).toBe(20n);
    expect(dump.heapByObjectId.get(100n)).toBe('Image');
  });

  it('rejects missing continuation chunks, truncated packets, and traces with no heap graph', () => {
    const missingChunk = parseJavaHeapTrace(tracePacket(fieldVarint(6, 1n), 2n));
    expect(missingChunk).toEqual({
      ok: false,
      error: 'Missing java_hprof packet for sequence 2: expected index 0, got 1.',
    });

    const truncated = parseJavaHeapTrace(Uint8Array.from([0x0a, 0x05, 0x01]));
    expect(truncated.ok).toBe(false);

    const noHeapGraph = parseJavaHeapTrace(message(1, [string(1, 'not a heap graph')]));
    expect(noHeapGraph.ok).toBe(false);
    if (!noHeapGraph.ok) expect(noHeapGraph.error).toContain('heap graph');
  });
});
