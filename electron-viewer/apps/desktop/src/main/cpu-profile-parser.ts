import { fail, ok, type StudioResult } from '@aps/contracts';
import type { CallStackTable } from '@aps/profile-analysis';
import { normalizeSimpleperfReport, samplesToCallStackTable } from '@aps/simpleperf-profiler';

export interface ParsedCpuProfile {
  readonly table: CallStackTable;
  readonly sampleCount: number;
}

/**
 * Turns a retained protobuf report back into a call-stack table. The table is
 * cheap to rebuild and expensive to store, so it is recomputed on demand.
 */
export function parseCpuProfileReport(bytes: Uint8Array): StudioResult<ParsedCpuProfile> {
  const parsed = normalizeSimpleperfReport(bytes);
  if (!parsed.ok) return parsed;
  if (parsed.value.samples.length === 0) {
    return fail('DATA_VALIDATION', 'SIMPLEPERF_NO_SAMPLES', 'The stored report contains no samples');
  }
  return ok({
    table: samplesToCallStackTable(parsed.value.samples),
    sampleCount: parsed.value.samples.length,
  });
}
