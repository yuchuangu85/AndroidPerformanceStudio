/**
 * Reader for the SIMPLEPERF protobuf stream that simpleperf writes with
 * "report-sample --protobuf":
 *
 *   char magic[10] = "SIMPLEPERF";
 *   LittleEndian16(version) = 1;
 *   LittleEndian32(record_size) + Record ... repeated
 *   LittleEndian32(0)
 *
 * The reader is streaming-friendly: records are handed to a callback and the
 * payload is never retained, because a full report can be hundreds of MB.
 */
import { fail, ok, type StudioResult } from '@aps/contracts';
import { decodeRecord, type ProtoRecord } from './proto.js';

export const SIMPLEPERF_MAGIC = 'SIMPLEPERF';
export const SUPPORTED_SIMPLEPERF_VERSION = 1;
export const DEFAULT_MAX_RECORD_BYTES = 64 * 1024 * 1024;

const MAGIC_BYTES = new TextEncoder().encode(SIMPLEPERF_MAGIC);

export interface SimpleperfRecordEnvelope {
  readonly index: bigint;
  readonly byteOffset: bigint;
  readonly encodedSize: number;
  readonly record: ProtoRecord;
}

export interface SimpleperfReadSummary {
  readonly version: number;
  readonly recordCount: bigint;
  readonly bytesRead: bigint;
}

export interface SimpleperfReaderOptions {
  readonly maxRecordBytes?: number;
  /** Called after each decoded record; used to build the normalized model. */
  readonly onRecord?: (envelope: SimpleperfRecordEnvelope) => void;
}

class SimpleperfFormatError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = 'SimpleperfFormatError';
    this.code = code;
  }
}

function formatFailure(code: string, message: string): never {
  throw new SimpleperfFormatError(code, message);
}

export function readSimpleperfReport(
  bytes: Uint8Array,
  options: SimpleperfReaderOptions = {},
): StudioResult<SimpleperfReadSummary> {
  const maxRecordBytes = options.maxRecordBytes ?? DEFAULT_MAX_RECORD_BYTES;
  if (!Number.isInteger(maxRecordBytes) || maxRecordBytes <= 0) {
    return fail('CONFIGURATION', 'SIMPLEPERF_LIMIT_INVALID', 'maxRecordBytes must be a positive integer');
  }
  try {
    return ok(parse(bytes, maxRecordBytes, options.onRecord));
  } catch (error) {
    if (error instanceof SimpleperfFormatError) {
      return fail('DATA_VALIDATION', error.code, error.message);
    }
    return fail(
      'IO',
      'SIMPLEPERF_STREAM_READ_FAILED',
      error instanceof Error ? error.message : 'Failed to read Simpleperf protobuf stream',
    );
  }
}

function parse(
  bytes: Uint8Array,
  maxRecordBytes: number,
  onRecord: ((envelope: SimpleperfRecordEnvelope) => void) | undefined,
): SimpleperfReadSummary {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (bytes.length < MAGIC_BYTES.length + 2) {
    formatFailure('SIMPLEPERF_MAGIC_INVALID', 'Invalid SIMPLEPERF magic at offset 0');
  }
  for (let index = 0; index < MAGIC_BYTES.length; index += 1) {
    if (bytes[index] !== MAGIC_BYTES[index]) {
      formatFailure('SIMPLEPERF_MAGIC_INVALID', 'Invalid SIMPLEPERF magic at offset 0');
    }
  }
  let offset = MAGIC_BYTES.length;
  const version = (bytes[offset] as number) | ((bytes[offset + 1] as number) << 8);
  offset += 2;
  if (version !== SUPPORTED_SIMPLEPERF_VERSION) {
    formatFailure(
      'SIMPLEPERF_VERSION_UNSUPPORTED',
      'Unsupported SIMPLEPERF version ' + String(version) + ' at offset ' + String(MAGIC_BYTES.length),
    );
  }

  let recordIndex = 0n;
  while (true) {
    const lengthOffset = offset;
    if (offset + 4 > bytes.length) {
      formatFailure(
        'SIMPLEPERF_LENGTH_TRUNCATED',
        'Truncated length for record ' + String(recordIndex) + ' at offset ' + String(lengthOffset),
      );
    }
    const encodedSize = view.getUint32(offset, true);
    offset += 4;
    if (encodedSize === 0) break;
    if (encodedSize > maxRecordBytes) {
      formatFailure(
        'SIMPLEPERF_RECORD_TOO_LARGE',
        'Record ' +
          String(recordIndex) +
          ' has ' +
          String(encodedSize) +
          ' bytes at offset ' +
          String(lengthOffset) +
          '; limit is ' +
          String(maxRecordBytes),
      );
    }
    if (offset + encodedSize > bytes.length) {
      formatFailure(
        'SIMPLEPERF_RECORD_TRUNCATED',
        'Truncated payload for record ' +
          String(recordIndex) +
          ' at offset ' +
          String(offset) +
          ': expected ' +
          String(encodedSize) +
          ', got ' +
          String(bytes.length - offset),
      );
    }
    const byteOffset = offset;
    offset += encodedSize;
    onRecord?.({
      index: recordIndex,
      byteOffset: BigInt(byteOffset),
      encodedSize,
      // Decoded straight out of the stream: the record holds no view of it,
      // so the payload never needs its own subarray.
      record: decodeRecord(bytes, byteOffset, offset),
    });
    recordIndex += 1n;
  }
  return { version, recordCount: recordIndex, bytesRead: BigInt(offset) };
}
