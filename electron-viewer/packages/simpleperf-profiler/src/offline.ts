/**
 * Offline profile import: the same sessions the device path produces, but from
 * files the user already has.
 *
 * Port of the parts of OfflineProfileImporter.kt that do not touch storage:
 * format detection, the two-pass protobuf indexing, and the Gecko path.
 *
 * The two passes matter. simpleperf may emit samples before the File, Thread,
 * and MetaInfo records they reference, so the first pass feeds every record
 * through the normalizer to populate its lookup tables and the second pass
 * collects the samples, now fully resolved. A single pass would resolve early
 * samples to <unknown-file> and <unknown-thread>.
 */
import { fail, ok, type StudioResult } from '@aps/contracts';
import { readGeckoProfileText, type GeckoReadResult } from './gecko.js';
import type { NormalizedSample, ProfileMetadata } from './model.js';
import { SimpleperfProfileNormalizer } from './normalizer.js';
import { readSimpleperfReport } from './reader.js';
import type { NormalizedProfile } from './report.js';

export const OFFLINE_PROFILE_FORMATS = ['PERF_DATA', 'SIMPLEPERF_PROTOBUF', 'GECKO_PROFILE_JSON_GZIP'] as const;
export type OfflineProfileFormat = (typeof OFFLINE_PROFILE_FORMATS)[number];

/** Detects the format from the file name, like the reference importer does. */
export function offlineFormatOf(fileName: string): OfflineProfileFormat | undefined {
  const lower = fileName.toLowerCase();
  if (lower.endsWith('.json.gz') || lower.endsWith('.gecko.json.gz')) return 'GECKO_PROFILE_JSON_GZIP';
  if (lower.endsWith('.pb') || lower.endsWith('.protobuf') || lower.endsWith('.simpleperf')) {
    return 'SIMPLEPERF_PROTOBUF';
  }
  if (lower.endsWith('.data')) return 'PERF_DATA';
  return undefined;
}

/** Two-pass normalization: lookups first, then the samples that need them. */
export function normalizeSimpleperfReportWithLookups(bytes: Uint8Array): StudioResult<NormalizedProfile> {
  const normalizer = new SimpleperfProfileNormalizer();
  const first = readSimpleperfReport(bytes, {
    onRecord: (envelope) => {
      normalizer.normalize(envelope.record);
    },
  });
  if (!first.ok) return first;

  const samples: NormalizedSample[] = [];
  let lostCount = 0n;
  const second = readSimpleperfReport(bytes, {
    onRecord: (envelope) => {
      const record = normalizer.normalize(envelope.record);
      if (record.kind === 'SAMPLE') samples.push(record.value);
      if (record.kind === 'LOST') lostCount = record.lostCount;
    },
  });
  if (!second.ok) return second;

  const metadata: ProfileMetadata | undefined = normalizer.latestMetadata;
  return ok({
    summary: {
      version: second.value.version,
      recordCount: second.value.recordCount,
      bytesRead: second.value.bytesRead,
      sampleCount: BigInt(samples.length),
      lostCount,
      ...(metadata !== undefined ? { metadata } : {}),
    },
    ...(metadata !== undefined ? { metadata } : {}),
    samples,
  });
}

export interface OfflineImportResult {
  readonly format: OfflineProfileFormat;
  readonly samples: readonly NormalizedSample[];
  readonly metadata?: ProfileMetadata;
  readonly threadCount: number;
  readonly lostCount: bigint;
}

/**
 * Turns already-read bytes into normalized samples. PERF_DATA is not accepted
 * here: it has to be converted by the host simpleperf first, which is the job of
 * the node-side importer.
 */
export function importOfflineProfile(input: {
  readonly format: OfflineProfileFormat;
  readonly bytes?: Uint8Array;
  readonly text?: string;
}): StudioResult<OfflineImportResult> {
  if (input.format === 'PERF_DATA') {
    return fail(
      'CONFIGURATION',
      'OFFLINE_PERF_DATA_NEEDS_CONVERSION',
      'perf.data has to be converted with the host simpleperf before import',
    );
  }
  if (input.format === 'GECKO_PROFILE_JSON_GZIP') {
    if (input.text === undefined) {
      return fail('DATA_VALIDATION', 'OFFLINE_INPUT_EMPTY', 'The Gecko profile has no content');
    }
    const read: StudioResult<GeckoReadResult> = readGeckoProfileText(input.text);
    if (!read.ok) return read;
    return ok({
      format: input.format,
      samples: read.value.samples,
      threadCount: read.value.threadCount,
      lostCount: 0n,
    });
  }
  if (input.bytes === undefined) {
    return fail('DATA_VALIDATION', 'OFFLINE_INPUT_EMPTY', 'The profile has no content');
  }
  const normalized = normalizeSimpleperfReportWithLookups(input.bytes);
  if (!normalized.ok) return normalized;
  return ok({
    format: input.format,
    samples: normalized.value.samples,
    ...(normalized.value.metadata !== undefined ? { metadata: normalized.value.metadata } : {}),
    threadCount: new Set(normalized.value.samples.map((sample) => sample.threadId)).size,
    lostCount: normalized.value.summary.lostCount,
  });
}
