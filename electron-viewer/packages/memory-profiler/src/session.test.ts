import { describe, expect, it } from 'vitest';
import { createMemorySession } from './session.js';
import type { HprofParseResult } from './hprof.js';

const header = { version: '1.0.3', identifierSize: 4 as const, timestampMillis: 0, headerBytes: 24 };

function result(): HprofParseResult {
  return {
    header,
    strings: new Map([
      [1n, 'com.example.Node'],
      [2n, 'next'],
    ]),
    classes: new Map([
      [
        0x100n,
        {
          objectId: 0x100n,
          nameId: 1n,
          superClassId: 0n,
          instanceFieldBytes: 4,
          instanceFields: [{ nameId: 2n, type: 2 }],
          staticFieldCount: 0,
          staticReferences: [],
        },
      ],
    ]),
    instances: [
      {
        objectId: 0x200n,
        classObjectId: 0x100n,
        fieldBytes: 4,
        shallowBytes: 4,
        references: [0x300n],
        fieldReferences: [{ nameId: 2n, targetObjectId: 0x300n }],
        primitiveValues: [],
      },
      {
        objectId: 0x300n,
        classObjectId: 0x100n,
        fieldBytes: 4,
        shallowBytes: 4,
        references: [],
        fieldReferences: [],
        primitiveValues: [],
      },
    ],
    arrays: [],
    roots: [0x200n],
    heapByObjectId: new Map([[0x200n, 'App']]),
    warnings: ['a parser warning'],
  };
}

describe('createMemorySession', () => {
  it('persists a histogram and hex-encoded suspects', () => {
    const session = createMemorySession(result(), {
      id: 's1',
      capturedAtEpochMillis: 42,
      deviceSerial: 'SER',
      packageName: 'com.example.app',
    });
    expect(session.id).toBe('s1');
    expect(session.capturedAtEpochMillis).toBe(42);
    expect(session.summary).toMatchObject({ classCount: 1, instanceCount: 2, version: '1.0.3' });
    expect(session.histogram[0]).toEqual({
      className: 'com.example.Node',
      instanceCount: 2,
      // Two instances at the class-declared four-byte instance size.
      shallowBytes: 2 * 4,
    });
    // 0x300 is reachable and not a root, so it is the only suspect.
    expect(session.suspects).toHaveLength(1);
    expect(session.suspects[0]).toMatchObject({
      className: 'com.example.Node',
      objectId: '0x300',
      // Retained size is the shallow size the dump reports, without estimates.
      retainedBytes: 4,
    });
    expect(session.suspects[0]?.referenceChain).toEqual(['0x200', '0x300']);
  });

  it('stays JSON serializable despite 64-bit identifiers', () => {
    const session = createMemorySession(result(), { id: 's2', capturedAtEpochMillis: 1 });
    expect(() => JSON.stringify(session)).not.toThrow();
    expect(JSON.parse(JSON.stringify(session)).suspects[0].objectId).toBe('0x300');
  });

  it('honours the histogram and suspect limits and keeps warnings', () => {
    const session = createMemorySession(result(), {
      id: 's3',
      capturedAtEpochMillis: 0,
      histogramLimit: 0,
      suspectLimit: 0,
    });
    expect(session.histogram).toEqual([]);
    expect(session.suspects).toEqual([]);
    expect(session.warnings).toContain('a parser warning');
  });
});
