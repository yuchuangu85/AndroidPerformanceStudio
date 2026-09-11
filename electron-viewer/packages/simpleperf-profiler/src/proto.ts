/**
 * Decoder for simpleperf's cmd_report_sample.proto message tree.
 *
 * Field numbers and defaults mirror the AOSP proto shipped with the Kotlin
 * app; unknown fields are skipped so a newer simpleperf still parses.
 */
import { ProtoCursor, utf8Of, type ProtoField } from './wire.js';
import type { ProfileUnwindErrorCode } from './model.js';

export interface ProtoCallChainEntry {
  readonly vaddrInFile: bigint;
  readonly fileId: number;
  readonly symbolId: number;
  /** 0 NATIVE_METHOD, 1 INTERPRETED_JVM_METHOD, 2 JIT_JVM_METHOD, 3 ART_METHOD. */
  readonly executionType: number;
}

export interface ProtoSample {
  readonly time: bigint;
  readonly threadId: number;
  readonly callchain: readonly ProtoCallChainEntry[];
  readonly eventCount: bigint;
  readonly eventTypeId: number;
  readonly unwindingResult?: { readonly rawErrorCode: number; readonly errorAddr: bigint; readonly errorCode: number };
}

export interface ProtoLostSituation {
  readonly sampleCount: bigint;
  readonly lostCount: bigint;
}

export interface ProtoFile {
  readonly id: number;
  readonly path: string;
  readonly symbols: readonly string[];
  readonly mangledSymbols: readonly string[];
}

export interface ProtoThread {
  readonly threadId: number;
  readonly processId: number;
  readonly threadName: string;
}

export interface ProtoMetaInfo {
  readonly eventTypes: readonly string[];
  readonly appPackageName?: string;
  readonly appType?: string;
  readonly androidSdkVersion?: string;
  readonly androidBuildType?: string;
  readonly traceOffCpu: boolean;
}

export interface ProtoContextSwitch {
  readonly switchOn: boolean;
  readonly time: bigint;
  readonly threadId: number;
}

export type ProtoRecord =
  | { readonly kind: 'SAMPLE'; readonly sample: ProtoSample }
  | { readonly kind: 'LOST'; readonly lost: ProtoLostSituation }
  | { readonly kind: 'FILE'; readonly file: ProtoFile }
  | { readonly kind: 'THREAD'; readonly thread: ProtoThread }
  | { readonly kind: 'META_INFO'; readonly metaInfo: ProtoMetaInfo }
  | { readonly kind: 'CONTEXT_SWITCH'; readonly contextSwitch: ProtoContextSwitch }
  | { readonly kind: 'NOT_SET' };

const EXECUTION_TYPE_NAMES = ['NATIVE_METHOD', 'INTERPRETED_JVM_METHOD', 'JIT_JVM_METHOD', 'ART_METHOD'] as const;

const UNWIND_ERROR_CODES: readonly ProfileUnwindErrorCode[] = [
  'ERROR_NONE',
  'ERROR_UNKNOWN',
  'ERROR_NOT_ENOUGH_STACK',
  'ERROR_MEMORY_INVALID',
  'ERROR_UNWIND_INFO',
  'ERROR_INVALID_MAP',
  'ERROR_MAX_FRAME_EXCEEDED',
  'ERROR_REPEATED_FRAME',
  'ERROR_INVALID_ELF',
];

export function unwindErrorCodeName(value: number): ProfileUnwindErrorCode {
  return UNWIND_ERROR_CODES[value] ?? 'ERROR_NONE';
}

export function executionTypeName(value: number): (typeof EXECUTION_TYPE_NAMES)[number] {
  return EXECUTION_TYPE_NAMES[value] ?? 'NATIVE_METHOD';
}

/**
 * Decodes one record from `bytes[offset, end)`. Decoding in place avoids the
 * per-record subarray the reader would otherwise hand out; nothing in the
 * result aliases the buffer, so the caller can keep reusing it.
 */
export function decodeRecord(bytes: Uint8Array, offset = 0, end = bytes.length): ProtoRecord {
  // Hot path: a report has one record per sample, so this loop avoids the
  // allocation-heavy generic field reader.
  const cursor = new ProtoCursor(bytes, offset, end);
  while (!cursor.atEnd()) {
    const tag = cursor.tag();
    switch (tag >> 3) {
      case 1:
        return { kind: 'SAMPLE', sample: decodeSample(cursor) };
      case 2:
        return { kind: 'LOST', lost: decodeLost(cursor) };
      case 3:
        return { kind: 'FILE', file: decodeFile(cursor) };
      case 4:
        return { kind: 'THREAD', thread: decodeThread(cursor) };
      case 5:
        return { kind: 'META_INFO', metaInfo: decodeMetaInfo(cursor) };
      case 6:
        return { kind: 'CONTEXT_SWITCH', contextSwitch: decodeContextSwitch(cursor) };
      default:
        cursor.skip(tag & 7);
        break;
    }
  }
  return { kind: 'NOT_SET' };
}

function decodeSample(cursor: ProtoCursor): ProtoSample {
  const enclosing = cursor.enterDelimited();
  let time = 0n;
  let threadId = 0;
  let eventCount = 0n;
  let eventTypeId = 0;
  let callchain: ProtoCallChainEntry[] | undefined;
  let unwindingResult: ProtoSample['unwindingResult'];
  while (!cursor.atEnd()) {
    const tag = cursor.tag();
    switch (tag >> 3) {
      case 1:
        time = cursor.varint();
        break;
      case 2:
        threadId = cursor.int32();
        break;
      case 3:
        (callchain ??= []).push(decodeCallChainEntry(cursor));
        break;
      case 4:
        eventCount = cursor.varint();
        break;
      case 5:
        eventTypeId = cursor.uint32();
        break;
      case 6:
        unwindingResult = decodeUnwindingResult(cursor);
        break;
      default:
        cursor.skip(tag & 7);
        break;
    }
  }
  cursor.leaveDelimited(enclosing);
  // Every sample gets the same shape: a conditional property here would make
  // the frames polymorphic for the normalizer that walks them right after.
  return { time, threadId, callchain: callchain ?? [], eventCount, eventTypeId, unwindingResult };
}

function decodeCallChainEntry(cursor: ProtoCursor): ProtoCallChainEntry {
  const enclosing = cursor.enterDelimited();
  let vaddrInFile = 0n;
  let fileId = 0;
  let symbolId = -1;
  let executionType = 0;
  while (!cursor.atEnd()) {
    const tag = cursor.tag();
    switch (tag >> 3) {
      case 1:
        vaddrInFile = cursor.varint();
        break;
      case 2:
        fileId = cursor.uint32();
        break;
      case 3:
        symbolId = cursor.int32();
        break;
      case 4:
        executionType = cursor.int32();
        break;
      default:
        cursor.skip(tag & 7);
        break;
    }
  }
  cursor.leaveDelimited(enclosing);
  return { vaddrInFile, fileId, symbolId, executionType };
}

function decodeUnwindingResult(cursor: ProtoCursor): NonNullable<ProtoSample['unwindingResult']> {
  const enclosing = cursor.enterDelimited();
  let rawErrorCode = 0;
  let errorAddr = 0n;
  let errorCode = 0;
  while (!cursor.atEnd()) {
    const tag = cursor.tag();
    switch (tag >> 3) {
      case 1:
        rawErrorCode = cursor.uint32();
        break;
      case 2:
        errorAddr = cursor.varint();
        break;
      case 3:
        errorCode = cursor.int32();
        break;
      default:
        cursor.skip(tag & 7);
        break;
    }
  }
  cursor.leaveDelimited(enclosing);
  return { rawErrorCode, errorAddr, errorCode };
}

function decodeLost(cursor: ProtoCursor): ProtoLostSituation {
  const enclosing = cursor.enterDelimited();
  let sampleCount = 0n;
  let lostCount = 0n;
  while (!cursor.atEnd()) {
    const tag = cursor.tag();
    switch (tag >> 3) {
      case 1:
        sampleCount = cursor.varint();
        break;
      case 2:
        lostCount = cursor.varint();
        break;
      default:
        cursor.skip(tag & 7);
        break;
    }
  }
  cursor.leaveDelimited(enclosing);
  return { sampleCount, lostCount };
}

function decodeFile(cursor: ProtoCursor): ProtoFile {
  const enclosing = cursor.enterDelimited();
  let id = 0;
  let path = '';
  const symbols: string[] = [];
  const mangledSymbols: string[] = [];
  while (!cursor.atEnd()) {
    const tag = cursor.tag();
    switch (tag >> 3) {
      case 1:
        id = cursor.uint32();
        break;
      case 2:
        path = utf8Of(cursor.view());
        break;
      case 3:
        symbols.push(utf8Of(cursor.view()));
        break;
      case 4:
        mangledSymbols.push(utf8Of(cursor.view()));
        break;
      default:
        cursor.skip(tag & 7);
        break;
    }
  }
  cursor.leaveDelimited(enclosing);
  return { id, path, symbols, mangledSymbols };
}

function decodeThread(cursor: ProtoCursor): ProtoThread {
  const enclosing = cursor.enterDelimited();
  let threadId = 0;
  let processId = 0;
  let threadName = '';
  while (!cursor.atEnd()) {
    const tag = cursor.tag();
    switch (tag >> 3) {
      case 1:
        threadId = cursor.uint32();
        break;
      case 2:
        processId = cursor.uint32();
        break;
      case 3:
        threadName = utf8Of(cursor.view());
        break;
      default:
        cursor.skip(tag & 7);
        break;
    }
  }
  cursor.leaveDelimited(enclosing);
  return { threadId, processId, threadName };
}

function decodeMetaInfo(cursor: ProtoCursor): ProtoMetaInfo {
  const enclosing = cursor.enterDelimited();
  const eventTypes: string[] = [];
  let appPackageName: string | undefined;
  let appType: string | undefined;
  let androidSdkVersion: string | undefined;
  let androidBuildType: string | undefined;
  let traceOffCpu = false;
  while (!cursor.atEnd()) {
    const tag = cursor.tag();
    switch (tag >> 3) {
      case 1:
        eventTypes.push(utf8Of(cursor.view()));
        break;
      case 2:
        appPackageName = utf8Of(cursor.view());
        break;
      case 3:
        appType = utf8Of(cursor.view());
        break;
      case 4:
        androidSdkVersion = utf8Of(cursor.view());
        break;
      case 5:
        androidBuildType = utf8Of(cursor.view());
        break;
      case 6:
        traceOffCpu = cursor.varint() !== 0n;
        break;
      default:
        cursor.skip(tag & 7);
        break;
    }
  }
  cursor.leaveDelimited(enclosing);
  return {
    eventTypes,
    ...(appPackageName !== undefined ? { appPackageName } : {}),
    ...(appType !== undefined ? { appType } : {}),
    ...(androidSdkVersion !== undefined ? { androidSdkVersion } : {}),
    ...(androidBuildType !== undefined ? { androidBuildType } : {}),
    traceOffCpu,
  };
}

function decodeContextSwitch(cursor: ProtoCursor): ProtoContextSwitch {
  const enclosing = cursor.enterDelimited();
  let switchOn = false;
  let time = 0n;
  let threadId = 0;
  while (!cursor.atEnd()) {
    const tag = cursor.tag();
    switch (tag >> 3) {
      case 1:
        switchOn = cursor.varint() !== 0n;
        break;
      case 2:
        time = cursor.varint();
        break;
      case 3:
        threadId = cursor.uint32();
        break;
      default:
        cursor.skip(tag & 7);
        break;
    }
  }
  cursor.leaveDelimited(enclosing);
  return { switchOn, time, threadId };
}

/** Test helper: encodes one field of a message in protobuf wire format. */
export function encodeVarintField(fieldNumber: number, value: bigint): Uint8Array {
  const key = encodeVarint(BigInt(fieldNumber) << 3n);
  return concat([key, encodeVarint(value)]);
}

export function encodeBytesField(fieldNumber: number, value: Uint8Array): Uint8Array {
  const key = encodeVarint((BigInt(fieldNumber) << 3n) | 2n);
  return concat([key, encodeVarint(BigInt(value.length)), value]);
}

export function encodeVarint(value: bigint): Uint8Array {
  const bytes: number[] = [];
  let remaining = value;
  do {
    const byte = Number(remaining & 0x7fn);
    remaining >>= 7n;
    bytes.push(remaining === 0n ? byte : byte | 0x80);
  } while (remaining !== 0n);
  return Uint8Array.from(bytes);
}

export function concat(chunks: readonly Uint8Array[]): Uint8Array {
  const total = chunks.reduce((sum, chunk) => sum + chunk.length, 0);
  const result = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.length;
  }
  return result;
}

export { ProtoCursor, type ProtoField };