import type { Worker, WorkerOptions } from 'node:worker_threads';
import { CallStackTable } from '@aps/profile-analysis';
// electron-vite replaces this query with a Node Worker constructor during the main-process build.
// @ts-expect-error tsconfig.node intentionally excludes electron-vite's optional ambient declarations.
import createParserWorkerModule from './parser-worker.ts?nodeWorker';
import {
  PARSER_WORKER_PROTOCOL_VERSION,
  isParserWorkerResponse,
  type ArtTraceWorkerResult,
  type HprofSessionMetadata,
  type HprofWorkerValue,
  type OfflineCpuImportWorkerInput,
  type OfflineCpuWorkerResult,
  type ParserWorkerOperation,
  type ParserWorkerRequest,
  type ParserWorkerResultByOperation,
  type SimpleperfWorkerResult,
} from './parser-worker-protocol.js';

const createParserWorker: (options: WorkerOptions) => Worker = createParserWorkerModule;

export async function parseHeapDumpInWorker(
  bytes: Uint8Array,
  metadata: HprofSessionMetadata,
): Promise<HprofWorkerValue> {
  return runParserWorker({
    protocolVersion: PARSER_WORKER_PROTOCOL_VERSION,
    operation: 'HPROF',
    bytes,
    session: metadata,
  });
}

export async function parseMethodTraceInWorker(trace: Uint8Array): Promise<ArtTraceWorkerResult> {
  return runParserWorker({ protocolVersion: PARSER_WORKER_PROTOCOL_VERSION, operation: 'ART_TRACE', bytes: trace });
}

export async function parseSimpleperfReportInWorker(bytes: Uint8Array): Promise<SimpleperfWorkerResult> {
  return runParserWorker({ protocolVersion: PARSER_WORKER_PROTOCOL_VERSION, operation: 'SIMPLEPERF', bytes });
}

export async function importOfflineCpuProfileInWorker(
  input: OfflineCpuImportWorkerInput,
): Promise<OfflineCpuWorkerResult> {
  return runParserWorker({
    protocolVersion: PARSER_WORKER_PROTOCOL_VERSION,
    operation: 'OFFLINE_CPU',
    format: input.format,
    ...(input.bytes === undefined ? {} : { bytes: input.bytes }),
    ...(input.text === undefined ? {} : { text: input.text }),
  });
}

interface HydratedResultByOperation {
  readonly HPROF: HprofWorkerValue;
  readonly ART_TRACE: ArtTraceWorkerResult;
  readonly SIMPLEPERF: SimpleperfWorkerResult;
  readonly OFFLINE_CPU: OfflineCpuWorkerResult;
}

function runParserWorker<Operation extends ParserWorkerOperation>(
  request: Extract<ParserWorkerRequest, { readonly operation: Operation }>,
): Promise<HydratedResultByOperation[Operation]> {
  return new Promise((resolve, reject) => {
    let settled = false;
    let worker: Worker;
    try {
      worker = createParserWorker({});
    } catch (error) {
      reject(error);
      return;
    }

    const finish = (settle: () => void): void => {
      if (settled) return;
      settled = true;
      worker.removeAllListeners();
      settle();
      void worker.terminate();
    };

    worker.once('message', (message: unknown) => {
      if (!isParserWorkerResponse(message)) {
        finish(() => reject(new Error('Parser worker returned a malformed response')));
      } else if (!message.ok) {
        const error = new Error(message.error.message);
        error.name = message.error.name;
        if (message.error.stack !== undefined) error.stack = message.error.stack;
        finish(() => reject(error));
      } else if (message.operation !== request.operation) {
        finish(() => reject(new Error(`Parser worker returned ${message.operation} for ${request.operation}`)));
      } else {
        try {
          const hydrated = hydrateWorkerValue(
            request.operation,
            message.value as ParserWorkerResultByOperation[Operation],
          ) as HydratedResultByOperation[Operation];
          finish(() => resolve(hydrated));
        } catch (error) {
          finish(() => reject(error));
        }
      }
    });
    worker.once('messageerror', (error) => {
      finish(() => reject(new Error('Parser worker response could not be deserialized', { cause: error })));
    });
    worker.once('error', (error) => finish(() => reject(error)));
    worker.once('exit', (code) => {
      if (settled) return;
      const detail = code === 0 ? 'before returning a response' : `with nonzero exit code ${code}`;
      finish(() => reject(new Error(`Parser worker exited ${detail}`)));
    });

    try {
      const transfer = request.bytes === undefined ? undefined : transferableBytes(request.bytes);
      const outgoing = transfer === undefined ? request : ({ ...request, bytes: transfer.bytes } as ParserWorkerRequest);
      worker.postMessage(outgoing, transfer === undefined ? [] : [transfer.buffer]);
    } catch (error) {
      finish(() => reject(error));
    }
  });
}

function hydrateWorkerValue<Operation extends ParserWorkerOperation>(
  operation: Operation,
  value: ParserWorkerResultByOperation[Operation],
): HydratedResultByOperation[Operation] {
  if (operation === 'HPROF') return value as HydratedResultByOperation[Operation];
  const result = value as ParserWorkerResultByOperation['ART_TRACE'];
  if (!result.ok) return result as HydratedResultByOperation[Operation];
  return {
    ok: true,
    value: {
      ...result.value,
      table: new CallStackTable(result.value.table.framesById, result.value.table.stacks),
    },
  } as HydratedResultByOperation[Operation];
}

function transferableBytes(bytes: Uint8Array): { readonly bytes: Uint8Array; readonly buffer: ArrayBuffer } {
  if (bytes.buffer instanceof ArrayBuffer && bytes.byteOffset === 0 && bytes.byteLength === bytes.buffer.byteLength) {
    return { bytes, buffer: bytes.buffer };
  }
  const copy = Uint8Array.from(bytes);
  return { bytes: copy, buffer: copy.buffer };
}
