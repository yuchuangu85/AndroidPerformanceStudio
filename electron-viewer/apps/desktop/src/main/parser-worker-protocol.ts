import type { StudioResult } from '@aps/contracts';
import type { ArtTraceAnalysis } from '@aps/art-trace';
import type { HprofParseResult, MemorySession } from '@aps/memory-profiler';
import type { CallStackFrame, CallStackTable, WeightedCallStack } from '@aps/profile-analysis';
import type {
  NormalizedProfile,
  OfflineImportResult,
  OfflineProfileFormat,
} from '@aps/simpleperf-profiler';

export const PARSER_WORKER_PROTOCOL_VERSION = 1 as const;

export interface HprofSessionMetadata {
  readonly id: string;
  readonly capturedAtEpochMillis: number;
  readonly deviceSerial?: string;
  readonly packageName?: string;
  readonly histogramLimit?: number;
  readonly suspectLimit?: number;
  readonly deep?: boolean;
}

export interface CallStackTableSnapshot {
  readonly framesById: ReadonlyMap<bigint, CallStackFrame>;
  readonly stacks: readonly WeightedCallStack[];
}

export interface HprofWorkerValue {
  readonly session: MemorySession;
  readonly parsed: HprofParseResult;
}

export interface ArtTraceWorkerValue {
  readonly analysis: ArtTraceAnalysis;
  readonly table: CallStackTable;
}

export interface SimpleperfWorkerValue {
  readonly profile: NormalizedProfile;
  readonly table: CallStackTable;
}

export interface OfflineCpuWorkerValue {
  readonly imported: OfflineImportResult;
  readonly table: CallStackTable;
}

interface ArtTraceWorkerTransportValue {
  readonly analysis: ArtTraceAnalysis;
  readonly table: CallStackTableSnapshot;
}

interface SimpleperfWorkerTransportValue {
  readonly profile: NormalizedProfile;
  readonly table: CallStackTableSnapshot;
}

interface OfflineCpuWorkerTransportValue {
  readonly imported: OfflineImportResult;
  readonly table: CallStackTableSnapshot;
}

export type ArtTraceWorkerResult = StudioResult<ArtTraceWorkerValue>;
export type SimpleperfWorkerResult = StudioResult<SimpleperfWorkerValue>;
export type OfflineCpuWorkerResult = StudioResult<OfflineCpuWorkerValue>;

export interface OfflineCpuImportWorkerInput {
  readonly format: OfflineProfileFormat;
  /** Protobuf bytes, perf.data bytes, or compressed Gecko JSON bytes. */
  readonly bytes?: Uint8Array;
  /** Optional uncompressed Gecko JSON, primarily useful to already-decoded callers and tests. */
  readonly text?: string;
}

interface ParserWorkerRequestBase {
  readonly protocolVersion: typeof PARSER_WORKER_PROTOCOL_VERSION;
}

export interface HprofParserWorkerRequest extends ParserWorkerRequestBase {
  readonly operation: 'HPROF';
  readonly bytes: Uint8Array;
  readonly session: HprofSessionMetadata;
}

export interface ArtTraceParserWorkerRequest extends ParserWorkerRequestBase {
  readonly operation: 'ART_TRACE';
  readonly bytes: Uint8Array;
}

export interface SimpleperfParserWorkerRequest extends ParserWorkerRequestBase {
  readonly operation: 'SIMPLEPERF';
  readonly bytes: Uint8Array;
}

export interface OfflineCpuParserWorkerRequest extends ParserWorkerRequestBase, OfflineCpuImportWorkerInput {
  readonly operation: 'OFFLINE_CPU';
}

export type ParserWorkerRequest =
  | HprofParserWorkerRequest
  | ArtTraceParserWorkerRequest
  | SimpleperfParserWorkerRequest
  | OfflineCpuParserWorkerRequest;

export interface ParserWorkerResultByOperation {
  readonly HPROF: HprofWorkerValue;
  readonly ART_TRACE: StudioResult<ArtTraceWorkerTransportValue>;
  readonly SIMPLEPERF: StudioResult<SimpleperfWorkerTransportValue>;
  readonly OFFLINE_CPU: StudioResult<OfflineCpuWorkerTransportValue>;
}

export type ParserWorkerOperation = keyof ParserWorkerResultByOperation;

export interface SerializedWorkerError {
  readonly name: string;
  readonly message: string;
  readonly stack?: string;
}

export interface ParserWorkerSuccess<Operation extends ParserWorkerOperation = ParserWorkerOperation> {
  readonly protocolVersion: typeof PARSER_WORKER_PROTOCOL_VERSION;
  readonly ok: true;
  readonly operation: Operation;
  readonly value: ParserWorkerResultByOperation[Operation];
}

export interface ParserWorkerFailure {
  readonly protocolVersion: typeof PARSER_WORKER_PROTOCOL_VERSION;
  readonly ok: false;
  readonly error: SerializedWorkerError;
}

export type ParserWorkerResponse = ParserWorkerSuccess | ParserWorkerFailure;

export function isParserWorkerResponse(value: unknown): value is ParserWorkerResponse {
  if (value === null || typeof value !== 'object') return false;
  const record = value as Record<string, unknown>;
  if (record['protocolVersion'] !== PARSER_WORKER_PROTOCOL_VERSION || typeof record['ok'] !== 'boolean') return false;
  if (record['ok']) return isParserWorkerOperation(record['operation']) && Object.hasOwn(record, 'value');
  return isSerializedWorkerError(record['error']);
}

export function isParserWorkerOperation(value: unknown): value is ParserWorkerOperation {
  return value === 'HPROF' || value === 'ART_TRACE' || value === 'SIMPLEPERF' || value === 'OFFLINE_CPU';
}

function isSerializedWorkerError(value: unknown): value is SerializedWorkerError {
  if (value === null || typeof value !== 'object') return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record['name'] === 'string' &&
    typeof record['message'] === 'string' &&
    (record['stack'] === undefined || typeof record['stack'] === 'string')
  );
}
