/**
 * Parser for ART method traces, following AOSP art/runtime/trace.cc.
 *
 * The low two bits of every method word hold the action (0 enter, 1 exit,
 * 2 unroll); the rest is the method index. Records in both layouts are
 * normalized to ArtTraceEvent with a monotonic nanosecond time.
 */
import { fail, ok, type StudioResult } from '@aps/contracts';
import { ArtTraceBinaryReader, ArtTraceFormatError, utf8Of } from './binary-reader.js';
import type {
  ArtClockSource,
  ArtMethod,
  ArtThread,
  ArtTraceAction,
  ArtTraceAnalysis,
  ArtTraceEvent,
  ArtTraceHeader,
} from './model.js';

const TRACE_MAGIC = 0x574f4c53; // 'SLOW'
const HEADER_LENGTH = 32;
const PACKET_THREAD_INFO = 0;
const PACKET_METHOD_INFO = 1;
const PACKET_ENTRY_BLOCK = 2;
const PACKET_SUMMARY = 3;
const RECORD_SIZE_SINGLE = 10;
const RECORD_SIZE_DUAL = 14;
const NANOS_PER_MICRO = 1000n;
const ACTION_MASK = 0x03n;
const END_MARKER = new TextEncoder().encode('*end\n');

const MAX_UNSUPPORTED_VERSION = 5;

export function parseArtTrace(bytes: Uint8Array): StudioResult<ArtTraceAnalysis> {
  try {
    const reader = new ArtTraceBinaryReader(bytes);
    if (reader.readU32() !== TRACE_MAGIC) {
      return fail('DATA_VALIDATION', 'ART_TRACE_MAGIC_INVALID', 'Not an ART method trace (bad magic)');
    }
    const version = reader.readU16();
    if (version === 4 || version === 5) return parseStreaming(reader, version);
    if (version === 2 || version === 3) return parseClassic(reader, version);
    if (version === 1) {
      return fail(
        'UNSUPPORTED_PLATFORM',
        'ART_TRACE_VERSION_UNSUPPORTED',
        'Trace version 1 is not supported (legacy Dalvik format)',
      );
    }
    return fail(
      'DATA_VALIDATION',
      'ART_TRACE_VERSION_UNSUPPORTED',
      'Unsupported trace version ' + String(version) + ' (supported: 2 to ' + String(MAX_UNSUPPORTED_VERSION) + ')',
    );
  } catch (error) {
    if (error instanceof ArtTraceFormatError) {
      return fail('DATA_VALIDATION', 'ART_TRACE_MALFORMED', error.message);
    }
    return fail(
      'DATA_VALIDATION',
      'ART_TRACE_MALFORMED',
      error instanceof Error ? error.message : 'Malformed trace',
    );
  }
}

function parseStreaming(
  reader: ArtTraceBinaryReader,
  version: number,
): StudioResult<ArtTraceAnalysis> {
  const startTimeNanos = reader.readU64();
  reader.skip(HEADER_LENGTH - 14);
  const dualClock = version === 5;
  const methods = new Map<bigint, ArtMethod>();
  const threads = new Map<number, ArtThread>();
  const events: ArtTraceEvent[] = [];
  const warnings: string[] = [];

  while (!reader.isAtEnd) {
    const packetType = reader.readU8();
    if (packetType === PACKET_THREAD_INFO) {
      const threadId = reader.readU32();
      const name = utf8Of(reader.readBytes(reader.readU16()));
      threads.set(threadId, { threadId, name });
      continue;
    }
    if (packetType === PACKET_METHOD_INFO) {
      const methodId = reader.readU64();
      const info = utf8Of(reader.readBytes(reader.readU16()));
      methods.set(methodId, parseMethodInfo(methodId, info));
      continue;
    }
    if (packetType === PACKET_ENTRY_BLOCK) {
      const threadId = reader.readU32();
      const numRecords = reader.readU24();
      const block = new ArtTraceBinaryReader(reader.readBytes(reader.readU32()));
      parseEntryBlock(block, numRecords, threadId, dualClock, events, warnings);
      continue;
    }
    if (packetType === PACKET_SUMMARY) {
      reader.skip(reader.readU16());
      continue;
    }
    warnings.push('Unknown streaming packet type ' + String(packetType) + '; stopped at the end of the trace.');
    break;
  }
  return ok(buildAnalysis(header(version, startTimeNanos, dualClock), methods, threads, events, warnings));
}

function parseEntryBlock(
  block: ArtTraceBinaryReader,
  numRecords: number,
  threadId: number,
  dualClock: boolean,
  events: ArtTraceEvent[],
  warnings: string[],
): void {
  // The first record of a block carries absolute values; the rest are deltas.
  let methodValue = 0n;
  let timeNanos = 0n;
  let cpuNanos = 0n;
  for (let index = 0; index < numRecords; index += 1) {
    methodValue += block.readSleb128();
    timeNanos += block.readUleb128();
    if (dualClock) cpuNanos += block.readUleb128();
    const action = actionOf(methodValue);
    if (action === undefined) {
      warnings.push('Ignored method record with unused action bits 0x03.');
      continue;
    }
    events.push({
      threadId,
      methodId: methodValue >> 2n,
      action,
      timeNanos,
      ...(dualClock ? { cpuNanos } : {}),
    });
  }
}

function parseClassic(reader: ArtTraceBinaryReader, version: number): StudioResult<ArtTraceAnalysis> {
  const dataOffset = reader.readU16();
  const startTimeNanos = reader.readU64() * NANOS_PER_MICRO;
  reader.skip(HEADER_LENGTH - 16);
  const dualClock = version === 3;
  const methods = new Map<bigint, ArtMethod>();
  const threads = new Map<number, ArtThread>();
  const events: ArtTraceEvent[] = [];
  const warnings: string[] = [];

  const textBytes = reader.readUntilInclusive(END_MARKER);
  if (endsWith(textBytes, END_MARKER)) {
    parseClassicTables(utf8Of(textBytes), methods, threads);
  } else {
    const fallback = Math.max(dataOffset, HEADER_LENGTH);
    reader.position = Math.min(fallback, textBytes.length + HEADER_LENGTH);
  }

  const recordSize = dualClock ? RECORD_SIZE_DUAL : RECORD_SIZE_SINGLE;
  while (reader.remaining() >= recordSize) {
    const threadId = reader.readU16();
    const methodValue = BigInt(reader.readU32());
    const cpuMicros = BigInt(reader.readU32());
    const wallMicros = dualClock ? BigInt(reader.readU32()) : 0n;
    const action = actionOf(methodValue);
    if (action === undefined) {
      warnings.push('Ignored method record with unused action bits 0x03.');
      continue;
    }
    events.push({
      threadId,
      methodId: methodValue >> 2n,
      action,
      timeNanos: (dualClock ? wallMicros : cpuMicros) * NANOS_PER_MICRO,
      ...(dualClock ? { cpuNanos: cpuMicros * NANOS_PER_MICRO } : {}),
    });
  }
  return ok(buildAnalysis(header(version, startTimeNanos, dualClock), methods, threads, events, warnings));
}

export function parseClassicTables(
  text: string,
  methods: Map<bigint, ArtMethod>,
  threads: Map<number, ArtThread>,
): void {
  let section = '';
  for (const raw of text.split('\n')) {
    const line = raw.trim();
    if (line.startsWith('*threads')) {
      section = 'threads';
      continue;
    }
    if (line.startsWith('*methods')) {
      section = 'methods';
      continue;
    }
    if (line.startsWith('*end')) {
      section = '';
      continue;
    }
    if (line.length === 0) continue;
    if (section === 'threads') {
      const tokens = line.split('\t').filter((token) => token.length > 0);
      const id = parseRadixId(tokens[0]);
      if (id === undefined) continue;
      threads.set(Number(id), { threadId: Number(id), name: tokens.slice(1).join(' ') });
      continue;
    }
    if (section === 'methods') {
      const tokens = line.split('\t').filter((token) => token.length > 0);
      const id = parseRadixId(tokens[0]);
      if (id === undefined) continue;
      methods.set(id >> 2n, {
        methodId: id >> 2n,
        className: tokens[1] ?? '',
        methodName: tokens[2] ?? '',
        signature: tokens[3] ?? '',
        sourceFile: tokens[4] ?? '',
      });
    }
  }
}

function parseRadixId(token: string | undefined): bigint | undefined {
  if (token === undefined) return undefined;
  try {
    return BigInt('0x' + token);
  } catch {
    try {
      return BigInt(token);
    } catch {
      return undefined;
    }
  }
}

export function parseMethodInfo(methodId: bigint, info: string): ArtMethod {
  const fields = info.trim().split('\t');
  return {
    methodId,
    className: fields[0] ?? '',
    methodName: fields[1] ?? '',
    signature: fields[2] ?? '',
    sourceFile: fields[3] ?? '',
  };
}

export function actionOf(methodValue: bigint): ArtTraceAction | undefined {
  switch (methodValue & ACTION_MASK) {
    case 0n:
      return 'ENTER';
    case 1n:
      return 'EXIT';
    case 2n:
      return 'UNROLL';
    default:
      return undefined;
  }
}

function buildAnalysis(
  header: ArtTraceHeader,
  methods: Map<bigint, ArtMethod>,
  threads: Map<number, ArtThread>,
  events: ArtTraceEvent[],
  warnings: string[],
): ArtTraceAnalysis {
  let startNanos: bigint | undefined;
  let endNanos: bigint | undefined;
  events.forEach((event) => {
    if (startNanos === undefined || event.timeNanos < startNanos) startNanos = event.timeNanos;
    if (endNanos === undefined || event.timeNanos > endNanos) endNanos = event.timeNanos;
  });
  return {
    header,
    methods,
    threads,
    events,
    startTimeNanos: startNanos ?? header.startTimeNanos,
    endTimeNanos: endNanos ?? header.startTimeNanos,
    warnings,
  };
}

function header(version: number, startTimeNanos: bigint, dualClock: boolean): ArtTraceHeader {
  const clockSource: ArtClockSource = dualClock ? 'DUAL' : 'SINGLE';
  return { version, startTimeNanos, clockSource };
}

function endsWith(bytes: Uint8Array, needle: Uint8Array): boolean {
  if (bytes.length < needle.length) return false;
  for (let index = 0; index < needle.length; index += 1) {
    if (bytes[bytes.length - needle.length + index] !== needle[index]) return false;
  }
  return true;
}
