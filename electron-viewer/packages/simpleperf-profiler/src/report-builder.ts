/**
 * Builds SIMPLEPERF protobuf streams for tests. Kept out of the package entry
 * point: production code only ever reads these streams.
 */
import { concat, encodeBytesField, encodeVarint, encodeVarintField } from './proto.js';
import { SIMPLEPERF_MAGIC, SUPPORTED_SIMPLEPERF_VERSION } from './reader.js';

export function record(...fields: readonly Uint8Array[]): Uint8Array {
  return concat(fields);
}

export function sample(options: {
  readonly time?: bigint;
  readonly threadId?: number;
  readonly eventCount?: bigint;
  readonly eventTypeId?: number;
  readonly callchain?: readonly {
    readonly vaddrInFile?: bigint;
    readonly fileId?: number;
    readonly symbolId?: number;
    readonly executionType?: number;
  }[];
  readonly unwindingResult?: { readonly rawErrorCode?: number; readonly errorAddr?: bigint; readonly errorCode?: number };
}): Uint8Array {
  const fields: Uint8Array[] = [];
  if (options.time !== undefined) fields.push(encodeVarintField(1, options.time));
  if (options.threadId !== undefined) fields.push(encodeVarintField(2, BigInt(options.threadId)));
  for (const entry of options.callchain ?? []) {
    const entryFields: Uint8Array[] = [];
    if (entry.vaddrInFile !== undefined) entryFields.push(encodeVarintField(1, entry.vaddrInFile));
    if (entry.fileId !== undefined) entryFields.push(encodeVarintField(2, BigInt(entry.fileId)));
    if (entry.symbolId !== undefined) {
      // proto2 int32: negative values are sign extended to 64 bits.
      entryFields.push(encodeVarintField(3, BigInt.asUintN(64, BigInt(entry.symbolId))));
    }
    if (entry.executionType !== undefined) entryFields.push(encodeVarintField(4, BigInt(entry.executionType)));
    // Sample.callchain = 3
    fields.push(encodeBytesField(3, concat(entryFields)));
  }
  if (options.eventCount !== undefined) fields.push(encodeVarintField(4, options.eventCount));
  if (options.eventTypeId !== undefined) fields.push(encodeVarintField(5, BigInt(options.eventTypeId)));
  if (options.unwindingResult !== undefined) {
    const unwindFields: Uint8Array[] = [];
    if (options.unwindingResult.rawErrorCode !== undefined) {
      unwindFields.push(encodeVarintField(1, BigInt(options.unwindingResult.rawErrorCode)));
    }
    if (options.unwindingResult.errorAddr !== undefined) {
      unwindFields.push(encodeVarintField(2, options.unwindingResult.errorAddr));
    }
    if (options.unwindingResult.errorCode !== undefined) {
      unwindFields.push(encodeVarintField(3, BigInt(options.unwindingResult.errorCode)));
    }
    fields.push(encodeBytesField(6, concat(unwindFields)));
  }
  return concat(fields);
}

export function fileRecord(options: {
  readonly id: number;
  readonly path: string;
  readonly symbols?: readonly string[];
  readonly mangledSymbols?: readonly string[];
}): Uint8Array {
  const fields: Uint8Array[] = [encodeVarintField(1, BigInt(options.id))];
  fields.push(encodeBytesField(2, new TextEncoder().encode(options.path)));
  for (const symbol of options.symbols ?? []) fields.push(encodeBytesField(3, new TextEncoder().encode(symbol)));
  for (const symbol of options.mangledSymbols ?? []) fields.push(encodeBytesField(4, new TextEncoder().encode(symbol)));
  return concat(fields);
}

export function threadRecord(options: {
  readonly threadId: number;
  readonly processId: number;
  readonly threadName: string;
}): Uint8Array {
  return concat([
    encodeVarintField(1, BigInt(options.threadId)),
    encodeVarintField(2, BigInt(options.processId)),
    encodeBytesField(3, new TextEncoder().encode(options.threadName)),
  ]);
}

export function metaInfoRecord(options: {
  readonly eventTypes: readonly string[];
  readonly appPackageName?: string;
  readonly appType?: string;
  readonly androidSdkVersion?: string;
  readonly androidBuildType?: string;
  readonly traceOffCpu?: boolean;
}): Uint8Array {
  const fields: Uint8Array[] = options.eventTypes.map((eventType) =>
    encodeBytesField(1, new TextEncoder().encode(eventType)),
  );
  if (options.appPackageName !== undefined) fields.push(encodeBytesField(2, new TextEncoder().encode(options.appPackageName)));
  if (options.appType !== undefined) fields.push(encodeBytesField(3, new TextEncoder().encode(options.appType)));
  if (options.androidSdkVersion !== undefined) {
    fields.push(encodeBytesField(4, new TextEncoder().encode(options.androidSdkVersion)));
  }
  if (options.androidBuildType !== undefined) {
    fields.push(encodeBytesField(5, new TextEncoder().encode(options.androidBuildType)));
  }
  if (options.traceOffCpu !== undefined) fields.push(encodeVarintField(6, options.traceOffCpu ? 1n : 0n));
  return concat(fields);
}

export function lostRecord(sampleCount: bigint, lostCount: bigint): Uint8Array {
  return concat([encodeVarintField(1, sampleCount), encodeVarintField(2, lostCount)]);
}

export function contextSwitchRecord(switchOn: boolean, time: bigint, threadId: number): Uint8Array {
  return concat([
    encodeVarintField(1, switchOn ? 1n : 0n),
    encodeVarintField(2, time),
    encodeVarintField(3, BigInt(threadId)),
  ]);
}

export interface StreamRecord {
  readonly field: number;
  readonly payload: Uint8Array;
}

/** Record.sample = 1 */
export function sampleRecord(payload: Uint8Array): StreamRecord {
  return { field: 1, payload };
}

/** Record.lost = 2 */
export function lostEntry(payload: Uint8Array): StreamRecord {
  return { field: 2, payload };
}

/** Record.file = 3 */
export function fileEntry(payload: Uint8Array): StreamRecord {
  return { field: 3, payload };
}

/** Record.thread = 4 */
export function threadEntry(payload: Uint8Array): StreamRecord {
  return { field: 4, payload };
}

/** Record.meta_info = 5 */
export function metaInfoEntry(payload: Uint8Array): StreamRecord {
  return { field: 5, payload };
}

/** Record.context_switch = 6 */
export function contextSwitchEntry(payload: Uint8Array): StreamRecord {
  return { field: 6, payload };
}

export function stream(
  records: readonly StreamRecord[],
  options: { readonly magic?: string; readonly version?: number; readonly terminate?: boolean } = {},
): Uint8Array {
  const chunks: Uint8Array[] = [new TextEncoder().encode(options.magic ?? SIMPLEPERF_MAGIC)];
  const version = options.version ?? SUPPORTED_SIMPLEPERF_VERSION;
  chunks.push(Uint8Array.from([version & 0xff, (version >> 8) & 0xff]));
  for (const entry of records) {
    const body = encodeBytesField(entry.field, entry.payload);
    const length = new Uint8Array(4);
    new DataView(length.buffer).setUint32(0, body.length, true);
    chunks.push(length, body);
  }
  if (options.terminate !== false) chunks.push(new Uint8Array(4));
  return concat(chunks);
}

export { encodeVarint };
