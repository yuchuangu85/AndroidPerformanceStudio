import { describe, expect, it } from 'vitest';
import {
  UI_INSPECTOR_MAGIC,
  UiInspectorFrameDecoder,
  UiInspectorProtocolError,
  decodeUiInspectorAgentMessage,
  encodeCreateInspectorCommand,
  encodeGetVersionCommand,
  encodeInspectorMessageCommand,
  frameUiInspectorMessage,
} from './ui-inspector-protocol.js';

function varint(value: number): number[] {
  const bytes: number[] = [];
  do { const byte = value & 0x7f; value >>>= 7; bytes.push(value === 0 ? byte : byte | 0x80); } while (value !== 0);
  return bytes;
}
function field(number: number, value: Uint8Array): Uint8Array {
  return Uint8Array.from([...(varint((number << 3) | 2)), ...varint(value.length), ...value]);
}
function string(value: string): Uint8Array { return new TextEncoder().encode(value); }
function concat(parts: readonly Uint8Array[]): Uint8Array { return Uint8Array.from(parts.flatMap((part) => [...part])); }
function numberField(number: number, value: number): Uint8Array { return Uint8Array.from([...varint(number << 3), ...varint(value)]); }

function response(commandId: number, status: number, specialized: Uint8Array): Uint8Array {
  return concat([numberField(1, commandId), numberField(2, status), specialized]);
}
function agentResponse(value: Uint8Array): Uint8Array { return field(1, value); }

describe('UI Inspector protocol', () => {
  it('frames and incrementally recovers multiple TCP messages', () => {
    const first = frameUiInspectorMessage(Uint8Array.from([1, 2]));
    const second = frameUiInspectorMessage(Uint8Array.from([3]));
    const decoder = new UiInspectorFrameDecoder();
    expect(decoder.push(first.subarray(0, 5))).toEqual([]);
    expect(decoder.push(Buffer.concat([first.subarray(5), second]))).toEqual([Buffer.from([1, 2]), Buffer.from([3])]);
  });

  it('rejects malformed framing and unreasonable message lengths', () => {
    expect(() => new UiInspectorFrameDecoder().push(Buffer.from('notagentxxxx'))).toThrow(UiInspectorProtocolError);
    const frame = Buffer.alloc(12);
    UI_INSPECTOR_MAGIC.copy(frame);
    frame.writeUInt32BE(16 * 1024 * 1024 + 1, 8);
    expect(() => new UiInspectorFrameDecoder().push(frame)).toThrow(/exceeds/);
  });

  it('encodes the three host command envelopes with their AOSP field ids', () => {
    expect([...encodeGetVersionCommand(7, ['androidx.compose.ui:ui'])]).toEqual([
      0x08, 0x07, 0x2a, 0x18, 0x0a, 0x16, ...string('androidx.compose.ui:ui'),
    ]);
    expect([...encodeCreateInspectorCommand(1, 'inspect', '/data/local/tmp/inspect.jar')].slice(0, 4)).toEqual([0x08, 0x01, 0x22, 0x26]);
    expect([...encodeInspectorMessageCommand(3, 'compose', Uint8Array.from([9]))]).toEqual([0x08, 0x03, 0x12, 0x0c, 0x0a, 0x07, ...string('compose'), 0x12, 0x01, 0x09]);
  });

  it('decodes correlated get-version responses', () => {
    const entry = concat([field(1, string('androidx.compose.ui:ui')), field(2, string('1.10.4'))]);
    const versions = field(7, field(1, entry));
    const decoded = decodeUiInspectorAgentMessage(agentResponse(response(42, 0, versions)));
    expect(decoded.kind).toBe('response');
    if (decoded.kind !== 'response') throw new Error('expected response');
    expect(decoded.response.commandId).toBe(42);
    expect(decoded.response.versions?.get('androidx.compose.ui:ui')).toBe('1.10.4');
  });

  it('decodes inspector payloads, error responses, and agent crashes', () => {
    const inspector = field(4, concat([field(1, string('layoutinspector.compose.inspection')), field(2, Uint8Array.from([1, 2, 3]))]));
    const payload = decodeUiInspectorAgentMessage(agentResponse(response(8, 0, inspector)));
    expect(payload).toMatchObject({ kind: 'response', response: { commandId: 8, inspectorMessage: { inspectorId: 'layoutinspector.compose.inspection' } } });

    const failed = decodeUiInspectorAgentMessage(agentResponse(concat([numberField(1, 9), numberField(2, 1), field(3, string('bad command'))])));
    expect(failed).toMatchObject({ kind: 'response', response: { ok: false, errorMessage: 'bad command' } });

    const crash = decodeUiInspectorAgentMessage(field(2, field(2, concat([field(1, string('boom')), field(2, string('stack'))]))));
    expect(crash).toEqual({ kind: 'event', event: { kind: 'crash', errorMessage: 'boom', stackTrace: 'stack' } });
  });

  it('fails closed for malformed envelopes', () => {
    expect(() => decodeUiInspectorAgentMessage(Uint8Array.from([]))).toThrow(/exactly one/);
    expect(() => decodeUiInspectorAgentMessage(field(1, numberField(2, 0)))).toThrow(/command id/);
  });
});
