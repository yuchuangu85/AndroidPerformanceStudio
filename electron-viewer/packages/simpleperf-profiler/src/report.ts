/**
 * Convenience pipeline: a SIMPLEPERF protobuf stream in, a normalized profile
 * ready for analysis out. Sample frames are kept because the call tree and the
 * flame graph both need the full stacks.
 */
import { fail, ok, type StudioResult } from '@aps/contracts';
import type { NormalizedProfileSummary, NormalizedSample, ProfileMetadata } from './model.js';
import { SimpleperfProfileNormalizer } from './normalizer.js';
import { readSimpleperfReport, type SimpleperfReaderOptions } from './reader.js';

export interface NormalizedProfile {
  readonly summary: NormalizedProfileSummary;
  readonly metadata?: ProfileMetadata;
  readonly samples: readonly NormalizedSample[];
}

export function normalizeSimpleperfReport(
  bytes: Uint8Array,
  options: SimpleperfReaderOptions = {},
): StudioResult<NormalizedProfile> {
  const normalizer = new SimpleperfProfileNormalizer();
  const samples: NormalizedSample[] = [];
  let lostCount = 0n;
  const read = readSimpleperfReport(bytes, {
    ...options,
    onRecord: (envelope) => {
      options.onRecord?.(envelope);
      const record = normalizer.normalize(envelope.record);
      if (record.kind === 'SAMPLE') samples.push(record.value);
      // Lost records already carry the running totals simpleperf reported.
      if (record.kind === 'LOST') lostCount = record.lostCount;
    },
  });
  if (!read.ok) return read;
  const metadata = normalizer.latestMetadata;
  return ok({
    summary: {
      version: read.value.version,
      recordCount: read.value.recordCount,
      bytesRead: read.value.bytesRead,
      sampleCount: BigInt(samples.length),
      lostCount,
      ...(metadata !== undefined ? { metadata } : {}),
    },
    ...(metadata !== undefined ? { metadata } : {}),
    samples,
  });
}

/** Fails with the reader's own error when the stream is not a valid report. */
export function requireNormalizedProfile(bytes: Uint8Array): StudioResult<NormalizedProfile> {
  const result = normalizeSimpleperfReport(bytes);
  if (!result.ok) return result;
  if (result.value.samples.length === 0) {
    return fail('DATA_VALIDATION', 'SIMPLEPERF_NO_SAMPLES', 'The report contains no samples');
  }
  return result;
}
