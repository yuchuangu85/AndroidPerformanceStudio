/**
 * Differential test for the tag-driven decoder in proto.ts.
 *
 * The reader's hot path decodes records in place and reads tags as numbers. The
 * generic field reader in wire.ts (readFields/next) is the original, slower
 * implementation and is kept as the reference: every record shape below is
 * decoded twice, once by each path, and the results must agree. That covers the
 * field combinations the golden corpus is the only integration test for, without
 * needing the Kotlin fixtures.
 */
import { describe, expect, it } from 'vitest';
import { decodeRecord, encodeBytesField, encodeVarintField, concat } from './proto.js';
import { bytesOf, int32Of, readFields, uint32Of, utf8Of, varintOf } from './wire.js';

/** The pre-rewrite decoder: readFields plus a switch on the field number. */
function reference(bytes: Uint8Array): unknown {
  for (const field of readFields(bytes)) {
    switch (field.fieldNumber) {
      case 1:
        return { kind: 'SAMPLE', sample: referenceSample(bytesOf(field)) };
      case 2:
        return { kind: 'LOST', lost: referenceLost(bytesOf(field)) };
      case 3:
        return { kind: 'FILE', file: referenceFile(bytesOf(field)) };
      case 4:
        return { kind: 'THREAD', thread: referenceThread(bytesOf(field)) };
      case 5:
        return { kind: 'META_INFO', metaInfo: referenceMetaInfo(bytesOf(field)) };
      case 6:
        return { kind: 'CONTEXT_SWITCH', contextSwitch: referenceContextSwitch(bytesOf(field)) };
      default:
        break;
    }
  }
  return { kind: 'NOT_SET' };
}

function referenceSample(bytes: Uint8Array): unknown {
  let time = 0n;
  let threadId = 0;
  let eventCount = 0n;
  let eventTypeId = 0;
  const callchain: unknown[] = [];
  let unwindingResult: unknown;
  for (const field of readFields(bytes)) {
    switch (field.fieldNumber) {
      case 1:
        time = varintOf(field);
        break;
      case 2:
        threadId = int32Of(varintOf(field));
        break;
      case 3:
        callchain.push(referenceCallChainEntry(bytesOf(field)));
        break;
      case 4:
        eventCount = varintOf(field);
        break;
      case 5:
        eventTypeId = uint32Of(varintOf(field));
        break;
      case 6:
        unwindingResult = referenceUnwindingResult(bytesOf(field));
        break;
      default:
        break;
    }
  }
  return {
    time,
    threadId,
    callchain,
    eventCount,
    eventTypeId,
    ...(unwindingResult !== undefined ? { unwindingResult } : {}),
  };
}

function referenceCallChainEntry(bytes: Uint8Array): unknown {
  let vaddrInFile = 0n;
  let fileId = 0;
  let symbolId = -1;
  let executionType = 0;
  for (const field of readFields(bytes)) {
    switch (field.fieldNumber) {
      case 1:
        vaddrInFile = varintOf(field);
        break;
      case 2:
        fileId = uint32Of(varintOf(field));
        break;
      case 3:
        symbolId = int32Of(varintOf(field));
        break;
      case 4:
        executionType = int32Of(varintOf(field));
        break;
      default:
        break;
    }
  }
  return { vaddrInFile, fileId, symbolId, executionType };
}

function referenceUnwindingResult(bytes: Uint8Array): unknown {
  let rawErrorCode = 0;
  let errorAddr = 0n;
  let errorCode = 0;
  for (const field of readFields(bytes)) {
    switch (field.fieldNumber) {
      case 1:
        rawErrorCode = uint32Of(varintOf(field));
        break;
      case 2:
        errorAddr = varintOf(field);
        break;
      case 3:
        errorCode = int32Of(varintOf(field));
        break;
      default:
        break;
    }
  }
  return { rawErrorCode, errorAddr, errorCode };
}

function referenceLost(bytes: Uint8Array): unknown {
  let sampleCount = 0n;
  let lostCount = 0n;
  for (const field of readFields(bytes)) {
    if (field.fieldNumber === 1) sampleCount = varintOf(field);
    else if (field.fieldNumber === 2) lostCount = varintOf(field);
  }
  return { sampleCount, lostCount };
}

function referenceFile(bytes: Uint8Array): unknown {
  let id = 0;
  let path = '';
  const symbols: string[] = [];
  const mangledSymbols: string[] = [];
  for (const field of readFields(bytes)) {
    switch (field.fieldNumber) {
      case 1:
        id = uint32Of(varintOf(field));
        break;
      case 2:
        path = utf8Of(bytesOf(field));
        break;
      case 3:
        symbols.push(utf8Of(bytesOf(field)));
        break;
      case 4:
        mangledSymbols.push(utf8Of(bytesOf(field)));
        break;
      default:
        break;
    }
  }
  return { id, path, symbols, mangledSymbols };
}

function referenceThread(bytes: Uint8Array): unknown {
  let threadId = 0;
  let processId = 0;
  let threadName = '';
  for (const field of readFields(bytes)) {
    switch (field.fieldNumber) {
      case 1:
        threadId = uint32Of(varintOf(field));
        break;
      case 2:
        processId = uint32Of(varintOf(field));
        break;
      case 3:
        threadName = utf8Of(bytesOf(field));
        break;
      default:
        break;
    }
  }
  return { threadId, processId, threadName };
}

function referenceMetaInfo(bytes: Uint8Array): unknown {
  const eventTypes: string[] = [];
  let appPackageName: string | undefined;
  let appType: string | undefined;
  let androidSdkVersion: string | undefined;
  let androidBuildType: string | undefined;
  let traceOffCpu = false;
  for (const field of readFields(bytes)) {
    switch (field.fieldNumber) {
      case 1:
        eventTypes.push(utf8Of(bytesOf(field)));
        break;
      case 2:
        appPackageName = utf8Of(bytesOf(field));
        break;
      case 3:
        appType = utf8Of(bytesOf(field));
        break;
      case 4:
        androidSdkVersion = utf8Of(bytesOf(field));
        break;
      case 5:
        androidBuildType = utf8Of(bytesOf(field));
        break;
      case 6:
        traceOffCpu = varintOf(field) !== 0n;
        break;
      default:
        break;
    }
  }
  return {
    eventTypes,
    ...(appPackageName !== undefined ? { appPackageName } : {}),
    ...(appType !== undefined ? { appType } : {}),
    ...(androidSdkVersion !== undefined ? { androidSdkVersion } : {}),
    ...(androidBuildType !== undefined ? { androidBuildType } : {}),
    traceOffCpu,
  };
}

function referenceContextSwitch(bytes: Uint8Array): unknown {
  let switchOn = false;
  let time = 0n;
  let threadId = 0;
  for (const field of readFields(bytes)) {
    switch (field.fieldNumber) {
      case 1:
        switchOn = varintOf(field) !== 0n;
        break;
      case 2:
        time = varintOf(field);
        break;
      case 3:
        threadId = uint32Of(varintOf(field));
        break;
      default:
        break;
    }
  }
  return { switchOn, time, threadId };
}

/** Deterministic pseudo random source, so a failure is reproducible. */
let seed = 0x1234_5678;

function next(): number {
  seed = (seed * 1103515245 + 12345) & 0x7fff_ffff;
  return seed;
}

/** Values chosen to sit on the varint fast path's boundaries. */
const VARINT_EDGES = [
  0n,
  1n,
  127n,
  128n,
  268435455n,
  268435456n,
  4294967295n,
  34359738367n,
  34359738368n,
  9007199254740993n,
  0xffff_ffff_ffff_ffffn,
];

function randomVarint(): bigint {
  if (next() % 3 === 0) return VARINT_EDGES[next() % VARINT_EDGES.length] as bigint;
  let value = BigInt(next());
  for (let index = 0; index < (next() % 4) + 1; index += 1) {
    value = (value << 16n) | BigInt(next() % 0x1_0000);
  }
  return value & 0xffff_ffff_ffff_ffffn;
}

function maybe(fields: Uint8Array[], bytes: Uint8Array): void {
  if (next() % 4 === 0) return;
  fields.push(bytes);
}

function randomCallChainEntry(): Uint8Array {
  const fields: Uint8Array[] = [];
  maybe(fields, encodeVarintField(1, randomVarint()));
  maybe(fields, encodeVarintField(2, BigInt(next() % 64)));
  maybe(fields, encodeVarintField(3, BigInt(next() % 128)));
  maybe(fields, encodeVarintField(4, BigInt(next() % 4)));
  // An unknown field must be skipped by both decoders.
  maybe(fields, encodeVarintField(9, randomVarint()));
  return concat(fields);
}

function randomSample(): Uint8Array {
  const fields: Uint8Array[] = [];
  maybe(fields, encodeVarintField(1, randomVarint()));
  maybe(fields, encodeVarintField(2, BigInt(next() % 512)));
  for (let index = 0; index < next() % 4; index += 1) {
    fields.push(encodeBytesField(3, randomCallChainEntry()));
  }
  maybe(fields, encodeVarintField(4, randomVarint()));
  maybe(fields, encodeVarintField(5, BigInt(next() % 8)));
  if (next() % 3 === 0) {
    fields.push(encodeBytesField(6, concat([encodeVarintField(1, BigInt(next() % 16)), encodeVarintField(2, randomVarint()), encodeVarintField(3, BigInt(next() % 16))])));
  }
  return concat(fields);
}

function randomText(): string {
  return ['', 'a', 'libfoo.so', 'Lcom/example/Foo;', '\u00e9\u4e2d\u6587'][next() % 5] as string;
}

function randomRecord(): Uint8Array {
  switch (next() % 8) {
    case 0:
      return encodeBytesField(1, randomSample());
    case 1:
      return encodeBytesField(2, concat([encodeVarintField(1, randomVarint()), encodeVarintField(2, randomVarint())]));
    case 2:
      return encodeBytesField(3, concat([
        encodeVarintField(1, BigInt(next() % 64)),
        encodeBytesField(2, new TextEncoder().encode(randomText())),
        encodeBytesField(3, new TextEncoder().encode(randomText())),
        encodeBytesField(3, new TextEncoder().encode(randomText())),
        encodeBytesField(4, new TextEncoder().encode(randomText())),
      ]));
    case 3:
      return encodeBytesField(4, concat([
        encodeVarintField(1, BigInt(next() % 4096)),
        encodeVarintField(2, BigInt(next() % 4096)),
        encodeBytesField(3, new TextEncoder().encode(randomText())),
      ]));
    case 4:
      return encodeBytesField(5, concat([
        encodeBytesField(1, new TextEncoder().encode(randomText())),
        encodeBytesField(2, new TextEncoder().encode(randomText())),
        encodeVarintField(6, BigInt(next() % 2)),
      ]));
    case 5:
      return encodeBytesField(6, concat([
        encodeVarintField(1, BigInt(next() % 2)),
        encodeVarintField(2, randomVarint()),
        encodeVarintField(3, BigInt(next() % 4096)),
      ]));
    case 6:
      // Unknown fields only: decoded as NOT_SET by both paths.
      return concat([encodeVarintField(7, randomVarint()), encodeBytesField(11, new TextEncoder().encode(randomText()))]);
    default:
      // An unknown field first, then a thread: the record loop has to skip.
      return concat([encodeVarintField(9, randomVarint()), encodeBytesField(4, concat([encodeVarintField(1, BigInt(next() % 4096))]))]);
  }
}

describe('tag-driven record decoding', () => {
  it('agrees with the generic field reader on every record shape', () => {
    const records = 300;
    for (let index = 0; index < records; index += 1) {
      const bytes = randomRecord();
      expect(decodeRecord(bytes), 'record ' + String(index)).toEqual(reference(bytes));
    }
  });

  it('decodes in place from a record that sits inside a larger buffer', () => {
    const prefix = Uint8Array.from([0xde, 0xad, 0xbe, 0xef]);
    const suffix = Uint8Array.from([0x99]);
    for (let index = 0; index < 100; index += 1) {
      const record = randomRecord();
      const framed = concat([prefix, record, suffix]);
      const inPlace = decodeRecord(framed, prefix.length, prefix.length + record.length);
      expect(inPlace, 'framed record ' + String(index)).toEqual(reference(record));
    }
  });
});
