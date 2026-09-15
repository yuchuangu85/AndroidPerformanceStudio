import { fail, ok, type StudioResult } from '@aps/contracts';
import {
  parseSimpleperfReportDirect,
  type SimpleperfReportParser,
} from '@aps/simpleperf-profiler';
import type { CallStackTable } from '@aps/profile-analysis';

export interface ParsedCpuProfile {
  readonly table: CallStackTable;
  readonly sampleCount: number;
}

/**
 * Turns a retained protobuf report back into a call-stack table. The table is
 * cheap to rebuild and expensive to store, so it is recomputed on demand.
 * Electron supplies a worker parser; unit callers keep the direct default.
 */
export async function parseCpuProfileReport(
  bytes: Uint8Array,
  parser: SimpleperfReportParser = parseSimpleperfReportDirect,
): Promise<StudioResult<ParsedCpuProfile>> {
  let parsed: Awaited<ReturnType<SimpleperfReportParser>>;
  try {
    parsed = await parser(bytes);
  } catch (error) {
    return fail(
      'UNKNOWN',
      'SIMPLEPERF_REPORT_PARSE_FAILED',
      error instanceof Error ? error.message : 'Failed to parse the stored simpleperf report',
    );
  }
  if (!parsed.ok) return parsed;
  if (parsed.value.profile.samples.length === 0) {
    return fail('DATA_VALIDATION', 'SIMPLEPERF_NO_SAMPLES', 'The stored report contains no samples');
  }
  return ok({
    table: parsed.value.table,
    sampleCount: parsed.value.profile.samples.length,
  });
}
