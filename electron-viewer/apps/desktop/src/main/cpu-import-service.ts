import { fail, ok, type StudioResult } from '@aps/contracts';
import {
  importOfflineProfile,
  offlineFormatOf,
  samplesToCallStackTable,
  type NormalizedSample,
  type OfflineImportResult,
  type OfflineProfileFormat,
  type ProfileMetadata,
} from '@aps/simpleperf-profiler';
import type { CallStackTable } from '@aps/profile-analysis';

export interface CpuImportInput {
  readonly fileName: string;
  readonly bytes?: Uint8Array;
  /** Already decompressed text, for the Gecko path. */
  readonly text?: string;
}

export interface CpuImportResult {
  readonly format: OfflineProfileFormat;
  readonly samples: readonly NormalizedSample[];
  readonly table: CallStackTable;
  readonly metadata?: ProfileMetadata;
  readonly threadKeys: readonly string[];
  readonly lostCount: bigint;
}

export interface ParsedOfflineCpuProfile {
  readonly imported: OfflineImportResult;
  readonly table: CallStackTable;
}

/** Runs both protobuf and Gecko JSON decoding away from Electron's main thread. */
export type OfflineCpuProfileParser = (
  input: {
    readonly format: OfflineProfileFormat;
    readonly bytes?: Uint8Array;
    readonly text?: string;
  },
) => Promise<StudioResult<ParsedOfflineCpuProfile>>;

export function formatOfFile(fileName: string): StudioResult<OfflineProfileFormat> {
  const format = offlineFormatOf(fileName);
  if (format === undefined) {
    return fail(
      'CONFIGURATION',
      'CPU_IMPORT_FORMAT_UNKNOWN',
      'Unsupported profile file: ' + fileName + ' (expected perf.data, a simpleperf protobuf, or a gzipped Gecko profile)',
    );
  }
  return ok(format);
}

/** Direct fallback used by service unit tests and non-Electron callers. */
export async function importOfflineCpuProfileDirect(input: {
  readonly format: OfflineProfileFormat;
  readonly bytes?: Uint8Array;
  readonly text?: string;
}): Promise<StudioResult<ParsedOfflineCpuProfile>> {
  const imported = importOfflineProfile(input);
  if (!imported.ok) return imported;
  return ok({ imported: imported.value, table: samplesToCallStackTable(imported.value.samples) });
}

/**
 * Imports an offline profile into the same shape a capture produces. perf.data
 * is converted by the caller (it needs the host simpleperf binary); by the time
 * it reaches here it is a protobuf report. Electron injects a worker parser.
 */
export async function importCpuProfileAsync(
  input: CpuImportInput,
  parser: OfflineCpuProfileParser = importOfflineCpuProfileDirect,
): Promise<StudioResult<CpuImportResult>> {
  const format = formatOfFile(input.fileName);
  if (!format.ok) return format;

  let parsed: StudioResult<ParsedOfflineCpuProfile>;
  try {
    parsed = await parser({
      format: format.value,
      ...(input.bytes !== undefined ? { bytes: input.bytes } : {}),
      ...(input.text !== undefined ? { text: input.text } : {}),
    });
  } catch (error) {
    return fail(
      'UNKNOWN',
      'CPU_IMPORT_PARSE_FAILED',
      error instanceof Error ? error.message : 'Failed to parse the imported CPU profile',
    );
  }
  if (!parsed.ok) return parsed;
  return toCpuImportResult(parsed.value);
}

/**
 * Synchronous direct compatibility entry point for pure package-level callers.
 * Production IPC must use importCpuProfileAsync with a worker-backed parser.
 */
export function importCpuProfile(input: CpuImportInput): StudioResult<CpuImportResult> {
  const format = formatOfFile(input.fileName);
  if (!format.ok) return format;
  const imported = importOfflineProfile({
    format: format.value,
    ...(input.bytes !== undefined ? { bytes: input.bytes } : {}),
    ...(input.text !== undefined ? { text: input.text } : {}),
  });
  if (!imported.ok) return imported;
  return toCpuImportResult({
    imported: imported.value,
    table: samplesToCallStackTable(imported.value.samples),
  });
}

function toCpuImportResult(parsed: ParsedOfflineCpuProfile): StudioResult<CpuImportResult> {
  if (parsed.imported.samples.length === 0) {
    return fail('DATA_VALIDATION', 'CPU_IMPORT_NO_SAMPLES', 'The imported profile contains no samples');
  }
  return ok({
    format: parsed.imported.format,
    samples: parsed.imported.samples,
    table: parsed.table,
    ...(parsed.imported.metadata !== undefined ? { metadata: parsed.imported.metadata } : {}),
    threadKeys: threadKeysOf(parsed.imported.samples),
    lostCount: parsed.imported.lostCount,
  });
}

function threadKeysOf(samples: readonly NormalizedSample[]): string[] {
  const keys: string[] = [];
  for (const sample of samples) {
    const key = sample.threadName + ' (tid ' + String(sample.threadId) + ')';
    if (!keys.includes(key)) keys.push(key);
  }
  return keys;
}
