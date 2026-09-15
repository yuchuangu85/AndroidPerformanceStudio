import { parentPort } from 'node:worker_threads';
import { ok } from '@aps/contracts';
import { parseArtTrace, toCallStackTable } from '@aps/art-trace';
import { createMemorySession, parseHprof } from '@aps/memory-profiler';
import {
  importOfflineProfile,
  normalizeSimpleperfReport,
  samplesToCallStackTable,
} from '@aps/simpleperf-profiler';
import { gunzipProfileText } from '@aps/simpleperf-profiler/node';
import type {
  CallStackTableSnapshot,
  ParserWorkerFailure,
  ParserWorkerRequest,
  ParserWorkerResponse,
  ParserWorkerSuccess,
  SerializedWorkerError,
} from './parser-worker-protocol.js';

const PROTOCOL_VERSION = 1 as const;
const port = parentPort;
if (port === null) throw new Error('Parser worker must run inside a Node Worker');

port.once('message', (message: unknown) => {
  try {
    const request = requireRequest(message);
    port.postMessage(executeParserWorkerRequest(request));
  } catch (error) {
    const response: ParserWorkerFailure = {
      protocolVersion: PROTOCOL_VERSION,
      ok: false,
      error: serializeError(error),
    };
    port.postMessage(response);
  }
});

export function executeParserWorkerRequest(request: ParserWorkerRequest): ParserWorkerResponse {
  switch (request.operation) {
    case 'HPROF': {
      const parsed = parseHprof(request.bytes);
      return success(request.operation, { parsed, session: createMemorySession(parsed, request.session) });
    }
    case 'ART_TRACE': {
      const parsed = parseArtTrace(request.bytes);
      return success(
        request.operation,
        parsed.ok ? ok({ analysis: parsed.value, table: snapshot(toCallStackTable(parsed.value)) }) : parsed,
      );
    }
    case 'SIMPLEPERF': {
      const parsed = normalizeSimpleperfReport(request.bytes);
      return success(
        request.operation,
        parsed.ok
          ? ok({ profile: parsed.value, table: snapshot(samplesToCallStackTable(parsed.value.samples)) })
          : parsed,
      );
    }
    case 'OFFLINE_CPU': {
      const textResult =
        request.format === 'GECKO_PROFILE_JSON_GZIP' && request.text === undefined && request.bytes !== undefined
          ? gunzipProfileText(request.bytes)
          : undefined;
      const imported =
        textResult !== undefined && !textResult.ok
          ? textResult
          : importOfflineProfile({
              format: request.format,
              ...(request.bytes === undefined ? {} : { bytes: request.bytes }),
              ...(request.text !== undefined
                ? { text: request.text }
                : textResult?.ok === true
                  ? { text: textResult.value }
                  : {}),
            });
      return success(
        request.operation,
        imported.ok
          ? ok({ imported: imported.value, table: snapshot(samplesToCallStackTable(imported.value.samples)) })
          : imported,
      );
    }
  }
}

function success<Operation extends ParserWorkerRequest['operation']>(
  operation: Operation,
  value: ParserWorkerSuccess<Operation>['value'],
): ParserWorkerSuccess<Operation> {
  return { protocolVersion: PROTOCOL_VERSION, ok: true, operation, value };
}

function snapshot(table: CallStackTableSnapshot): CallStackTableSnapshot {
  return { framesById: table.framesById, stacks: table.stacks };
}

function requireRequest(value: unknown): ParserWorkerRequest {
  if (value === null || typeof value !== 'object') throw new TypeError('Parser worker request must be an object');
  const record = value as Record<string, unknown>;
  if (record['protocolVersion'] !== PROTOCOL_VERSION) throw new TypeError('Unsupported parser worker protocol version');
  if (!isOperation(record['operation'])) throw new TypeError('Unknown parser worker operation');
  if (record['operation'] !== 'OFFLINE_CPU' && !(record['bytes'] instanceof Uint8Array)) {
    throw new TypeError('Parser worker bytes must be a Uint8Array');
  }
  if (record['operation'] === 'OFFLINE_CPU' && !isOfflineRequest(record)) {
    throw new TypeError('Offline CPU parser worker request is malformed');
  }
  if (record['operation'] === 'HPROF' && !isHprofSessionMetadata(record['session'])) {
    throw new TypeError('HPROF parser worker request requires valid session metadata');
  }
  return value as ParserWorkerRequest;
}

function isOperation(value: unknown): boolean {
  return value === 'HPROF' || value === 'ART_TRACE' || value === 'SIMPLEPERF' || value === 'OFFLINE_CPU';
}

function isOfflineRequest(record: Record<string, unknown>): boolean {
  return (
    (record['format'] === 'PERF_DATA' ||
      record['format'] === 'SIMPLEPERF_PROTOBUF' ||
      record['format'] === 'GECKO_PROFILE_JSON_GZIP') &&
    (record['bytes'] === undefined || record['bytes'] instanceof Uint8Array) &&
    (record['text'] === undefined || typeof record['text'] === 'string')
  );
}

function isHprofSessionMetadata(value: unknown): boolean {
  if (value === null || typeof value !== 'object') return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record['id'] === 'string' &&
    record['id'].length > 0 &&
    typeof record['capturedAtEpochMillis'] === 'number' &&
    Number.isFinite(record['capturedAtEpochMillis']) &&
    optionalString(record['deviceSerial']) &&
    optionalString(record['packageName']) &&
    optionalNonNegativeInteger(record['histogramLimit']) &&
    optionalNonNegativeInteger(record['suspectLimit']) &&
    (record['deep'] === undefined || typeof record['deep'] === 'boolean')
  );
}

function optionalString(value: unknown): boolean {
  return value === undefined || typeof value === 'string';
}

function optionalNonNegativeInteger(value: unknown): boolean {
  return value === undefined || (typeof value === 'number' && Number.isInteger(value) && value >= 0);
}

function serializeError(error: unknown): SerializedWorkerError {
  if (error instanceof Error) {
    return {
      name: error.name,
      message: error.message,
      ...(error.stack === undefined ? {} : { stack: error.stack }),
    };
  }
  return { name: 'Error', message: String(error) };
}
