import { once } from 'node:events';
import { connect, type Socket } from 'node:net';
import {
  UiInspectorFrameDecoder,
  UiInspectorProtocolError,
  decodeUiInspectorAgentMessage,
  encodeCreateInspectorCommand,
  encodeGetVersionCommand,
  encodeInspectorMessageCommand,
  frameUiInspectorMessage,
  type UiInspectorEvent,
  type UiInspectorResponse,
} from './ui-inspector-protocol.js';

export const COMPOSE_INSPECTOR_ID = 'layoutinspector.compose.inspection';
export const COMPOSE_UI_LIBRARY_ID = 'androidx.compose.ui:ui';

export class UiInspectorConnectionError extends Error {
  constructor(message: string, cause?: unknown) {
    super(message, cause === undefined ? undefined : { cause });
    this.name = 'UiInspectorConnectionError';
  }
}

export class UiInspectorAgentCrashError extends Error {
  readonly stackTrace: string;
  constructor(message: string, stackTrace: string) {
    super(message);
    this.name = 'UiInspectorAgentCrashError';
    this.stackTrace = stackTrace;
  }
}

export interface UiInspectorTransport {
  write(frame: Uint8Array): void;
  close(): void;
  onData(listener: (chunk: Uint8Array) => void): void;
  onClose(listener: (error?: Error) => void): void;
}

export interface UiInspectorClientOptions {
  readonly commandTimeoutMs?: number;
  /** 256-bit lowercase hex token sent before every outer-protocol command. */
  readonly sessionToken?: string;
  readonly onEvent?: (event: UiInspectorEvent) => void;
}

interface Pending {
  readonly resolve: (response: UiInspectorResponse) => void;
  readonly reject: (reason: Error) => void;
  readonly timer: ReturnType<typeof setTimeout>;
}

/** Correlates AOSP outer-protocol responses and fails every pending call on disconnect/crash. */
export class UiInspectorClient {
  private readonly decoder = new UiInspectorFrameDecoder();
  private readonly pending = new Map<number, Pending>();
  private readonly timeoutMs: number;
  private readonly onEvent?: (event: UiInspectorEvent) => void;
  private nextCommandId = 1;
  private closed = false;

  constructor(private readonly transport: UiInspectorTransport, options: UiInspectorClientOptions = {}) {
    this.timeoutMs = options.commandTimeoutMs ?? 15_000;
    this.onEvent = options.onEvent;
    if (options.sessionToken !== undefined) {
      if (!/^[a-f0-9]{64}$/.test(options.sessionToken)) throw new UiInspectorConnectionError('Invalid UI Inspector session token');
      transport.write(frameUiInspectorMessage(new TextEncoder().encode(options.sessionToken)));
    }
    transport.onData((chunk) => this.handleData(chunk));
    transport.onClose((error) => this.failAll(error ?? new UiInspectorConnectionError('UI Inspector connection closed')));
  }

  async getVersion(libraryIds: readonly string[]): Promise<ReadonlyMap<string, string>> {
    const response = await this.send((commandId) => encodeGetVersionCommand(commandId, libraryIds));
    if (!response.ok) throw new UiInspectorConnectionError(response.errorMessage ?? 'UI Inspector rejected version request');
    return response.versions ?? new Map();
  }

  async createInspector(inspectorId: string, dexPath: string): Promise<void> {
    if (inspectorId.trim().length === 0 || !dexPath.startsWith('/')) throw new UiInspectorConnectionError('Invalid UI Inspector dex path');
    const response = await this.send((commandId) => encodeCreateInspectorCommand(commandId, inspectorId, dexPath));
    if (!response.ok) throw new UiInspectorConnectionError(response.errorMessage ?? 'UI Inspector could not create inspector');
  }

  async sendInspectorCommand(inspectorId: string, payload: Uint8Array): Promise<Uint8Array> {
    const response = await this.send((commandId) => encodeInspectorMessageCommand(commandId, inspectorId, payload));
    if (!response.ok) throw new UiInspectorConnectionError(response.errorMessage ?? 'UI Inspector rejected command');
    const inspector = response.inspectorMessage;
    if (inspector === undefined) throw new UiInspectorProtocolError('Inspector command response has no inspector payload');
    if (inspector.inspectorId !== inspectorId) {
      throw new UiInspectorProtocolError('Inspector response id mismatch: expected ' + inspectorId + ', got ' + inspector.inspectorId);
    }
    return inspector.payload;
  }

  close(): void {
    this.transport.close();
    this.failAll(new UiInspectorConnectionError('UI Inspector client closed'));
  }

  private send(encode: (commandId: number) => Uint8Array): Promise<UiInspectorResponse> {
    if (this.closed) return Promise.reject(new UiInspectorConnectionError('UI Inspector client is closed'));
    const commandId = this.nextCommandId++;
    return new Promise<UiInspectorResponse>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(commandId);
        reject(new UiInspectorConnectionError('UI Inspector command timed out: ' + String(commandId)));
      }, this.timeoutMs);
      this.pending.set(commandId, { resolve, reject, timer });
      try {
        this.transport.write(frameUiInspectorMessage(encode(commandId)));
      } catch (error) {
        clearTimeout(timer);
        this.pending.delete(commandId);
        reject(error instanceof Error ? error : new UiInspectorConnectionError('Unable to write UI Inspector command'));
      }
    });
  }

  private handleData(chunk: Uint8Array): void {
    try {
      for (const payload of this.decoder.push(chunk)) {
        const message = decodeUiInspectorAgentMessage(payload);
        if (message.kind === 'event') {
          if (message.event.kind === 'crash') {
            this.failAll(new UiInspectorAgentCrashError(message.event.errorMessage, message.event.stackTrace));
            return;
          }
          this.onEvent?.(message.event);
          continue;
        }
        const pending = this.pending.get(message.response.commandId);
        if (pending === undefined) continue; // Late reply after timeout; it cannot affect another command id.
        this.pending.delete(message.response.commandId);
        clearTimeout(pending.timer);
        pending.resolve(message.response);
      }
    } catch (error) {
      this.failAll(error instanceof Error ? error : new UiInspectorProtocolError('Invalid UI Inspector agent message'));
    }
  }

  private failAll(error: Error): void {
    if (this.closed && this.pending.size === 0) return;
    this.closed = true;
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }
    this.pending.clear();
  }
}

/** Opens a loopback connection created by `adb forward tcp:PORT localabstract:ui_inspector_PID`. */
export async function connectUiInspector(port: number, options: UiInspectorClientOptions = {}): Promise<UiInspectorClient> {
  if (!Number.isInteger(port) || port < 1 || port > 65_535) throw new UiInspectorConnectionError('Invalid UI Inspector loopback port');
  const socket = connect({ host: '127.0.0.1', port });
  const timeout = options.commandTimeoutMs ?? 15_000;
  const timer = setTimeout(() => socket.destroy(new UiInspectorConnectionError('UI Inspector connection timed out')), timeout);
  try {
    await once(socket, 'connect');
    return new UiInspectorClient(socketTransport(socket), options);
  } catch (error) {
    throw new UiInspectorConnectionError('Unable to connect to UI Inspector', error);
  } finally {
    clearTimeout(timer);
  }
}

function socketTransport(socket: Socket): UiInspectorTransport {
  return {
    write: (frame) => socket.write(frame),
    close: () => socket.destroy(),
    onData: (listener) => socket.on('data', listener),
    onClose: (listener) => {
      socket.once('error', (error) => listener(error));
      socket.once('close', () => listener());
    },
  };
}
