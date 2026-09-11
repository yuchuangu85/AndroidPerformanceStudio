import { fail, ok, type StudioResult } from '@aps/contracts';
import {
  importOfflineProfile,
  offlineFormatOf,
  samplesToCallStackTable,
  type NormalizedSample,
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

/**
 * Imports an offline profile into the same shape a capture produces. perf.data
 * is converted by the caller (it needs the host simpleperf binary); by the time
 * it reaches here it is a protobuf report.
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
  if (imported.value.samples.length === 0) {
    return fail('DATA_VALIDATION', 'CPU_IMPORT_NO_SAMPLES', 'The imported profile contains no samples');
  }
  return ok({
    format: imported.value.format,
    samples: imported.value.samples,
    table: samplesToCallStackTable(imported.value.samples),
    ...(imported.value.metadata !== undefined ? { metadata: imported.value.metadata } : {}),
    threadKeys: threadKeysOf(imported.value.samples),
    lostCount: imported.value.lostCount,
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
