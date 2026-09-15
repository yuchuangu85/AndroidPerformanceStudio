/**
 * Minimal host implementation of AOSP UI Inspector's transport protocol.
 *
 * The Android agent speaks a deliberately small framing protocol: eight ASCII
 * bytes (`UIINSPCT`), a big-endian uint32 payload size, and a protobuf message.
 * Keeping the outer envelope hand-written avoids adding a protobuf runtime to
 * the desktop bundle while preserving command ids, agent crashes, and unknown
 * newer fields.
 */

export const UI_INSPECTOR_MAGIC = Buffer.from('UIINSPCT', 'ascii');
export const UI_INSPECTOR_FRAME_HEADER_BYTES = 12;
export const MAX_UI_INSPECTOR_MESSAGE_BYTES = 16 * 1024 * 1024;

const encoder = new TextEncoder();
const decoder = new TextDecoder('utf-8', { fatal: true });

export class UiInspectorProtocolError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'UiInspectorProtocolError';
  }
}

export interface UiInspectorInspectorResponse {
  readonly inspectorId: string;
  readonly payload: Uint8Array;
}

export interface UiInspectorResponse {
  readonly commandId: number;
  readonly ok: boolean;
  readonly errorMessage?: string;
  readonly inspectorMessage?: UiInspectorInspectorResponse;
  readonly versions?: ReadonlyMap<string, string>;
}

export type UiInspectorEvent =
  | { readonly kind: 'inspectorMessage'; readonly inspectorId: string; readonly payload: Uint8Array }
  | { readonly kind: 'crash'; readonly errorMessage: string; readonly stackTrace: string };

export type UiInspectorAgentMessage =
  | { readonly kind: 'response'; readonly response: UiInspectorResponse }
  | { readonly kind: 'event'; readonly event: UiInspectorEvent };

/** Incrementally splits an arbitrary TCP chunk stream into validated payloads. */
export class UiInspectorFrameDecoder {
  private buffered = Buffer.alloc(0);

  push(chunk: Uint8Array): Buffer[] {
    if (chunk.length === 0) return [];
    this.buffered = Buffer.concat([this.buffered, chunk]);
    const messages: Buffer[] = [];
    while (this.buffered.length >= UI_INSPECTOR_FRAME_HEADER_BYTES) {
      if (!this.buffered.subarray(0, UI_INSPECTOR_MAGIC.length).equals(UI_INSPECTOR_MAGIC)) {
        throw new UiInspectorProtocolError('Invalid UI Inspector frame magic');
      }
      const length = this.buffered.readUInt32BE(UI_INSPECTOR_MAGIC.length);
      if (length > MAX_UI_INSPECTOR_MESSAGE_BYTES) {
        throw new UiInspectorProtocolError('UI Inspector frame exceeds message limit');
      }
      const total = UI_INSPECTOR_FRAME_HEADER_BYTES + length;
      if (this.buffered.length < total) break;
      messages.push(this.buffered.subarray(UI_INSPECTOR_FRAME_HEADER_BYTES, total));
      this.buffered = this.buffered.subarray(total);
    }
    return messages;
  }
}

export function frameUiInspectorMessage(payload: Uint8Array): Buffer {
  if (payload.length > MAX_UI_INSPECTOR_MESSAGE_BYTES) {
    throw new UiInspectorProtocolError('UI Inspector message exceeds message limit');
  }
  const frame = Buffer.allocUnsafe(UI_INSPECTOR_FRAME_HEADER_BYTES + payload.length);
  UI_INSPECTOR_MAGIC.copy(frame, 0);
  frame.writeUInt32BE(payload.length, UI_INSPECTOR_MAGIC.length);
  Buffer.from(payload).copy(frame, UI_INSPECTOR_FRAME_HEADER_BYTES);
  return frame;
}

/** Command { command_id = 1, get_version = 5 }. */
export function encodeGetVersionCommand(commandId: number, libraryIds: readonly string[]): Uint8Array {
  return message([varintField(1, commandId), bytesField(5, concat(libraryIds.map((id) => stringField(1, id))))]);
}

/** Command { command_id = 1, create_inspector = 4 }. */
export function encodeCreateInspectorCommand(commandId: number, inspectorId: string, dexPath: string): Uint8Array {
  return message([
    varintField(1, commandId),
    bytesField(4, concat([stringField(1, inspectorId), stringField(2, dexPath)])),
  ]);
}

/** Command { command_id = 1, inspector_message = 2 }. */
export function encodeInspectorMessageCommand(commandId: number, inspectorId: string, payload: Uint8Array): Uint8Array {
  return message([
    varintField(1, commandId),
    bytesField(2, concat([stringField(1, inspectorId), bytesField(2, payload)])),
  ]);
}

/** Decodes AgentMessage { response = 1 | event = 2 }. */
export function decodeUiInspectorAgentMessage(bytes: Uint8Array): UiInspectorAgentMessage {
  const fields = readFields(bytes);
  const responses = fieldsOfBytes(fields, 1);
  const events = fieldsOfBytes(fields, 2);
  if (responses.length === 1 && events.length === 0) return { kind: 'response', response: decodeResponse(responses[0] as Uint8Array) };
  if (events.length === 1 && responses.length === 0) return { kind: 'event', event: decodeEvent(events[0] as Uint8Array) };
  throw new UiInspectorProtocolError('Agent message must contain exactly one response or event');
}

function decodeResponse(bytes: Uint8Array): UiInspectorResponse {
  const fields = readFields(bytes);
  const commandId = requiredUint32(fields, 1, 'response command id');
  const status = firstUint32(fields, 2) ?? 0;
  if (status !== 0 && status !== 1) throw new UiInspectorProtocolError('Unknown response status');
  const errorMessage = firstString(fields, 3);
  const inspector = firstBytes(fields, 4);
  const versions = firstBytes(fields, 7);
  if (inspector !== undefined && versions !== undefined) {
    throw new UiInspectorProtocolError('Response contains multiple specialized payloads');
  }
  return {
    commandId,
    ok: status === 0,
    ...(errorMessage !== undefined ? { errorMessage } : {}),
    ...(inspector !== undefined ? { inspectorMessage: decodeInspectorResponse(inspector) } : {}),
    ...(versions !== undefined ? { versions: decodeVersions(versions) } : {}),
  };
}

function decodeInspectorResponse(bytes: Uint8Array): UiInspectorInspectorResponse {
  const fields = readFields(bytes);
  return {
    inspectorId: requiredString(fields, 1, 'inspector response id'),
    payload: requiredBytes(fields, 2, 'inspector response payload'),
  };
}

function decodeVersions(bytes: Uint8Array): ReadonlyMap<string, string> {
  const versions = new Map<string, string>();
  for (const entryBytes of fieldsOfBytes(readFields(bytes), 1)) {
    const entry = readFields(entryBytes);
    const key = requiredString(entry, 1, 'version map key');
    const value = requiredString(entry, 2, 'version map value');
    if (versions.has(key)) throw new UiInspectorProtocolError('Duplicate version map key: ' + key);
    versions.set(key, value);
  }
  return versions;
}

function decodeEvent(bytes: Uint8Array): UiInspectorEvent {
  const fields = readFields(bytes);
  const inspector = firstBytes(fields, 1);
  const crash = firstBytes(fields, 2);
  if (inspector !== undefined && crash === undefined) {
    return { kind: 'inspectorMessage', ...decodeInspectorResponse(inspector) };
  }
  if (crash !== undefined && inspector === undefined) {
    const details = readFields(crash);
    return {
      kind: 'crash',
      errorMessage: requiredString(details, 1, 'agent crash error message'),
      stackTrace: firstString(details, 2) ?? '',
    };
  }
  throw new UiInspectorProtocolError('Event must contain exactly one specialized payload');
}

type WireField = { readonly number: number; readonly wire: number; readonly value: bigint | Uint8Array };

function readFields(bytes: Uint8Array): WireField[] {
  const reader = new Reader(bytes);
  const fields: WireField[] = [];
  while (!reader.atEnd) {
    const tag = reader.varint();
    const number = Number(tag >> 3n);
    const wire = Number(tag & 7n);
    if (number === 0) throw new UiInspectorProtocolError('Protobuf field number 0 is invalid');
    switch (wire) {
      case 0: fields.push({ number, wire, value: reader.varint() }); break;
      case 1: reader.skip(8); fields.push({ number, wire, value: 0n }); break;
      case 2: fields.push({ number, wire, value: reader.bytes() }); break;
      case 5: reader.skip(4); fields.push({ number, wire, value: 0n }); break;
      default: throw new UiInspectorProtocolError('Unsupported protobuf wire type ' + String(wire));
    }
  }
  return fields;
}

function firstBytes(fields: readonly WireField[], number: number): Uint8Array | undefined {
  const matches = fieldsOfBytes(fields, number);
  if (matches.length > 1) throw new UiInspectorProtocolError('Repeated singular field ' + String(number));
  return matches[0];
}

function fieldsOfBytes(fields: readonly WireField[], number: number): Uint8Array[] {
  return fields.filter((field) => field.number === number && field.wire === 2).map((field) => field.value as Uint8Array);
}

function firstUint32(fields: readonly WireField[], number: number): number | undefined {
  const matches = fields.filter((field) => field.number === number && field.wire === 0);
  if (matches.length > 1) throw new UiInspectorProtocolError('Repeated singular field ' + String(number));
  return matches.length === 0 ? undefined : uint32(matches[0]?.value as bigint);
}

function requiredUint32(fields: readonly WireField[], number: number, label: string): number {
  const value = firstUint32(fields, number);
  if (value === undefined) throw new UiInspectorProtocolError('Missing ' + label);
  return value;
}

function firstString(fields: readonly WireField[], number: number): string | undefined {
  const bytes = firstBytes(fields, number);
  return bytes === undefined ? undefined : decodeUtf8(bytes);
}

function requiredString(fields: readonly WireField[], number: number, label: string): string {
  const value = firstString(fields, number);
  if (value === undefined) throw new UiInspectorProtocolError('Missing ' + label);
  return value;
}

function requiredBytes(fields: readonly WireField[], number: number, label: string): Uint8Array {
  const value = firstBytes(fields, number);
  if (value === undefined) throw new UiInspectorProtocolError('Missing ' + label);
  return value;
}

function decodeUtf8(bytes: Uint8Array): string {
  try {
    return decoder.decode(bytes);
  } catch {
    throw new UiInspectorProtocolError('Invalid UTF-8 protobuf string');
  }
}

function message(parts: readonly Uint8Array[]): Uint8Array { return concat(parts); }
function stringField(number: number, value: string): Uint8Array { return bytesField(number, encoder.encode(value)); }
function bytesField(number: number, value: Uint8Array): Uint8Array { return concat([encodeVarint(BigInt((number << 3) | 2)), encodeVarint(BigInt(value.length)), value]); }
function varintField(number: number, value: number): Uint8Array { return concat([encodeVarint(BigInt(number << 3)), encodeVarint(BigInt(value >>> 0))]); }

function encodeVarint(value: bigint): Uint8Array {
  if (value < 0n) throw new UiInspectorProtocolError('Cannot encode a negative protobuf varint');
  const out: number[] = [];
  do {
    const byte = Number(value & 0x7fn);
    value >>= 7n;
    out.push(value === 0n ? byte : byte | 0x80);
  } while (value !== 0n);
  return Uint8Array.from(out);
}

function concat(parts: readonly Uint8Array[]): Uint8Array {
  const length = parts.reduce((sum, part) => sum + part.length, 0);
  const out = new Uint8Array(length);
  let offset = 0;
  for (const part of parts) { out.set(part, offset); offset += part.length; }
  return out;
}

function uint32(value: bigint): number {
  if (value < 0n || value > 0xffff_ffffn) throw new UiInspectorProtocolError('Protobuf uint32 is out of range');
  return Number(value);
}

class Reader {
  private offset = 0;
  constructor(private readonly data: Uint8Array) {}
  get atEnd(): boolean { return this.offset === this.data.length; }
  varint(): bigint {
    let value = 0n;
    for (let shift = 0n; shift < 70n; shift += 7n) {
      if (this.offset >= this.data.length) throw new UiInspectorProtocolError('Truncated protobuf varint');
      const byte = this.data[this.offset++] as number;
      value |= BigInt(byte & 0x7f) << shift;
      if ((byte & 0x80) === 0) return value;
    }
    throw new UiInspectorProtocolError('Protobuf varint exceeds ten bytes');
  }
  bytes(): Uint8Array {
    const length = this.varint();
    if (length > BigInt(Number.MAX_SAFE_INTEGER)) throw new UiInspectorProtocolError('Protobuf byte field is too large');
    const size = Number(length);
    if (this.offset + size > this.data.length) throw new UiInspectorProtocolError('Truncated protobuf byte field');
    const result = this.data.subarray(this.offset, this.offset + size);
    this.offset += size;
    return result;
  }
  skip(length: number): void {
    if (this.offset + length > this.data.length) throw new UiInspectorProtocolError('Truncated protobuf fixed-width field');
    this.offset += length;
  }
}
