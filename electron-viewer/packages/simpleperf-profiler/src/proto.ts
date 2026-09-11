/**
 * Decoder for simpleperf's cmd_report_sample.proto message tree.
 *
 * Field numbers and defaults mirror the AOSP proto shipped with the Kotlin
 * app; unknown fields are skipped so a newer simpleperf still parses.
 */
import {
  bytesOf,
  int32Of,
  ProtoCursor,
  readFields,
  uint32Of,
  utf8Of,
  varintOf,
  type ProtoField,
} from './wire.js';
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

export function decodeRecord(bytes: Uint8Array): ProtoRecord {
  // Hot path: a report has one record per sample, so this loop avoids the
  // allocation-heavy generic field reader.
  const cursor = new ProtoCursor(bytes);
  while (!cursor.atEnd()) {
    const tag = cursor.tag();
    const field = tag >> 3;
    switch (field) {
      case 1:
        return { kind: 'SAMPLE', sample: decodeSample(cursor.view()) };
      case 2:
        return { kind: 'LOST', lost: decodeLost(cursor.view()) };
      case 3:
        return { kind: 'FILE', file: decodeFile(cursor.view()) };
      case 4:
        return { kind: 'THREAD', thread: decodeThread(cursor.view()) };
      case 5:
        return { kind: 'META_INFO', metaInfo: decodeMetaInfo(cursor.view()) };
      case 6:
        return { kind: 'CONTEXT_SWITCH', contextSwitch: decodeContextSwitch(cursor.view()) };
      default:
        cursor.skip(tag & 7);
        break;
    }
  }
  return { kind: 'NOT_SET' };
}

function decodeSample(bytes: Uint8Array): ProtoSample {
  const cursor = new ProtoCursor(bytes);
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
        (callchain ??= []).push(decodeCallChainEntry(cursor.view()));
        break;
      case 4:
        eventCount = cursor.varint();
        break;
      case 5:
        eventTypeId = cursor.uint32();
        break;
      case 6:
        unwindingResult = decodeUnwindingResult(cursor.view());
        break;
      default:
        cursor.skip(tag & 7);
        break;
    }
  }
  return {
    time,
    threadId,
    callchain: callchain ?? [],
    eventCount,
    eventTypeId,
    ...(unwindingResult !== undefined ? { unwindingResult } : {}),
  };
}

function decodeCallChainEntry(bytes: Uint8Array): ProtoCallChainEntry {
  const cursor = new ProtoCursor(bytes);
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
  return { vaddrInFile, fileId, symbolId, executionType };
}

function decodeUnwindingResult(bytes: Uint8Array): NonNullable<ProtoSample['unwindingResult']> {
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

function decodeLost(bytes: Uint8Array): ProtoLostSituation {
  let sampleCount = 0n;
  let lostCount = 0n;
  for (const field of readFields(bytes)) {
    if (field.fieldNumber === 1) sampleCount = varintOf(field);
    else if (field.fieldNumber === 2) lostCount = varintOf(field);
  }
  return { sampleCount, lostCount };
}

function decodeFile(bytes: Uint8Array): ProtoFile {
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

function decodeThread(bytes: Uint8Array): ProtoThread {
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

function decodeMetaInfo(bytes: Uint8Array): ProtoMetaInfo {
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

function decodeContextSwitch(bytes: Uint8Array): ProtoContextSwitch {
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