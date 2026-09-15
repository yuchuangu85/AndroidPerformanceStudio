import { describe, expect, it } from 'vitest';
import {
  EMPTY_NATIVE_HEAP_ANALYSIS,
  parseNativeHeapTrace,
  parseNativeHeapTraceStrict,
} from './native-heap-trace.js';

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

function sample(
  callstackId: bigint,
  { allocated = 0n, freed = 0n, allocCount = 0n, freeCount = 0n }: {
    readonly allocated?: bigint;
    readonly freed?: bigint;
    readonly allocCount?: bigint;
    readonly freeCount?: bigint;
  } = {},
): Uint8Array {
  return concat([
    fieldVarint(1, callstackId),
    fieldVarint(2, allocated),
    fieldVarint(3, freed),
    fieldVarint(5, allocCount),
    fieldVarint(6, freeCount),
  ]);
}

function profilePacket(fields: readonly Uint8Array[]): Uint8Array {
  return fieldBytes(37, concat(fields));
}

function tracePacket(fields: readonly Uint8Array[]): Uint8Array {
  return fieldBytes(1, concat(fields));
}

function standardProfile(
  functionName: string,
  samples: readonly Uint8Array[],
): Uint8Array {
  const internedString = concat([fieldVarint(1, 1n), string(2, functionName)]);
  const frame = concat([fieldVarint(1, 1n), fieldVarint(2, 1n)]);
  const callstack = concat([fieldVarint(1, 1n), fieldVarint(2, 1n)]);
  const processDump = concat([fieldVarint(1, 42n), ...samples.map((value) => fieldBytes(2, value))]);
  return profilePacket([
    fieldBytes(1, internedString),
    fieldBytes(2, frame),
    fieldBytes(3, callstack),
    fieldBytes(5, processDump),
  ]);
}

function internedData(functionName: string): Uint8Array {
  const internedString = concat([fieldVarint(1, 1n), string(2, functionName)]);
  const frame = concat([fieldVarint(1, 1n), fieldVarint(2, 1n)]);
  const callstack = concat([fieldVarint(1, 1n), fieldVarint(2, 1n)]);
  return concat([fieldBytes(5, internedString), fieldBytes(6, frame), fieldBytes(7, callstack)]);
}

function profileWithSample(allocated: bigint): Uint8Array {
  const processDump = concat([fieldVarint(1, 42n), fieldBytes(2, sample(1n, { allocated }))]);
  return profilePacket([fieldBytes(5, processDump)]);
}

describe('parseNativeHeapTrace', () => {
  it('matches Kotlin’s shared-leaf aggregation fixture', () => {
    const trace = tracePacket([
      standardProfile('malloc', [
        sample(1n, { allocated: 100n, allocCount: 1n }),
        sample(1n, { allocated: 200n, allocCount: 2n }),
      ]),
    ]);

    expect(parseNativeHeapTraceStrict(trace)).toEqual({
      totalAllocatedBytes: 300,
      totalFreedBytes: 0,
      sampleCount: 2,
      topAllocations: [
        {
          functionName: 'malloc',
          allocatedBytes: 300,
          freedBytes: 0,
          allocCount: 3,
          freeCount: 0,
          callStack: ['malloc'],
        },
      ],
    });
  });

  it('keeps interned data isolated by trusted packet sequence', () => {
    const first = tracePacket([
      fieldVarint(10, 1n),
      fieldBytes(12, internedData('first')),
      profileWithSample(100n),
    ]);
    const second = tracePacket([
      fieldVarint(10, 2n),
      fieldBytes(12, internedData('second')),
      profileWithSample(200n),
    ]);

    expect(parseNativeHeapTraceStrict(concat([first, second])).topAllocations.map((item) => item.functionName)).toEqual([
      'second',
      'first',
    ]);
  });

  it('uses the documented empty fallback only for malformed best-effort traces', () => {
    const malformed = Uint8Array.from([0x0a, 0x05, 0x01]);
    expect(() => parseNativeHeapTraceStrict(malformed)).toThrow();
    expect(parseNativeHeapTrace(malformed)).toEqual(EMPTY_NATIVE_HEAP_ANALYSIS);
  });
});
