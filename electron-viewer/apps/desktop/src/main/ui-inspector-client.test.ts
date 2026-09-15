import { describe, expect, it } from 'vitest';
import { UiInspectorAgentCrashError, UiInspectorClient, type UiInspectorTransport } from './ui-inspector-client.js';
import { frameUiInspectorMessage } from './ui-inspector-protocol.js';

class FakeTransport implements UiInspectorTransport {
  readonly writes: Uint8Array[] = [];
  private data?: (chunk: Uint8Array) => void;
  private closeListener?: (error?: Error) => void;
  write(frame: Uint8Array): void { this.writes.push(frame); }
  close(): void { this.closeListener?.(); }
  onData(listener: (chunk: Uint8Array) => void): void { this.data = listener; }
  onClose(listener: (error?: Error) => void): void { this.closeListener = listener; }
  receive(payload: Uint8Array): void { this.data?.(frameUiInspectorMessage(payload)); }
}

function varint(value: number): number[] { const bytes: number[] = []; do { const b = value & 0x7f; value >>>= 7; bytes.push(value === 0 ? b : b | 0x80); } while (value !== 0); return bytes; }
function field(number: number, value: Uint8Array): Uint8Array { return Uint8Array.from([...varint((number << 3) | 2), ...varint(value.length), ...value]); }
function numberField(number: number, value: number): Uint8Array { return Uint8Array.from([...varint(number << 3), ...varint(value)]); }
function text(value: string): Uint8Array { return new TextEncoder().encode(value); }
function concat(parts: readonly Uint8Array[]): Uint8Array { return Uint8Array.from(parts.flatMap((part) => [...part])); }
function response(commandId: number, specialized: Uint8Array): Uint8Array { return field(1, concat([numberField(1, commandId), numberField(2, 0), specialized])); }

describe('UiInspectorClient', () => {
  it('sends a session token before all outer-protocol commands', async () => {
    const transport = new FakeTransport();
    new UiInspectorClient(transport, { sessionToken: 'a'.repeat(64) });
    expect(transport.writes).toEqual([frameUiInspectorMessage(new TextEncoder().encode('a'.repeat(64)))]);
  });

  it('creates an inspector with a correlated command', async () => {
    const transport = new FakeTransport();
    const client = new UiInspectorClient(transport);
    const pending = client.createInspector('view', '/data/user/0/pkg/view.jar');
    transport.receive(response(1, Uint8Array.from([])));
    await expect(pending).resolves.toBeUndefined();
  });

  it('correlates simultaneous commands despite out-of-order agent replies', async () => {
    const transport = new FakeTransport();
    const client = new UiInspectorClient(transport);
    const first = client.getVersion(['one']);
    const second = client.getVersion(['two']);
    const versions = (key: string, value: string) => field(7, field(1, concat([field(1, text(key)), field(2, text(value))])));
    transport.receive(response(2, versions('two', '2')));
    transport.receive(response(1, versions('one', '1')));
    await expect(first).resolves.toEqual(new Map([['one', '1']]));
    await expect(second).resolves.toEqual(new Map([['two', '2']]));
  });

  it('rejects all pending commands when the agent emits a crash event', async () => {
    const transport = new FakeTransport();
    const client = new UiInspectorClient(transport);
    const pending = client.getVersion(['androidx.compose.ui:ui']);
    transport.receive(field(2, field(2, concat([field(1, text('agent died')), field(2, text('stack'))]))));
    await expect(pending).rejects.toBeInstanceOf(UiInspectorAgentCrashError);
  });

  it('rejects a mismatched inspector response instead of leaking it across commands', async () => {
    const transport = new FakeTransport();
    const client = new UiInspectorClient(transport);
    const pending = client.sendInspectorCommand('expected', Uint8Array.from([7]));
    transport.receive(response(1, field(4, concat([field(1, text('other')), field(2, Uint8Array.from([1]))]))));
    await expect(pending).rejects.toThrow(/mismatch/);
  });
});
