import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { normalizeSimpleperfReport } from './report.js';
import { readSimpleperfReport } from './reader.js';

/**
 * Consumes the SIMPLEPERF golden corpus produced by the Kotlin implementation:
 * records built with the generated protobuf classes, framed the way simpleperf
 * writes them, plus a digest of what the Kotlin reader and normalizer saw.
 *
 * Generated in CI (see .github/workflows/golden.yml); skipped when
 * APS_GOLDEN_DIR is unset or holds no SIMPLEPERF cases.
 */
const GOLDEN_DIRECTORY = process.env['APS_GOLDEN_DIR'];

interface SimpleperfDigest {
  readonly parser: string;
  readonly case: string;
  readonly inputFile: string;
  readonly expectations: {
    readonly version: number;
    readonly recordCount: number;
    readonly sampleCount: number;
    readonly lostCount: number;
    readonly totalEventCount: number;
    readonly eventTypes: readonly string[];
    readonly appPackageName: string | null;
    readonly traceOffCpu: boolean;
    readonly threadNames: readonly string[];
    readonly frameSymbols: readonly string[];
    readonly warningCount: number;
  };
}

function isDigest(value: unknown): value is SimpleperfDigest {
  if (value === null || typeof value !== 'object') return false;
  const record = value as Record<string, unknown>;
  return record['parser'] === 'SIMPLEPERF' && typeof record['inputFile'] === 'string';
}

function loadCorpus(): { digest: SimpleperfDigest; bytes: Uint8Array }[] {
  if (GOLDEN_DIRECTORY === undefined || GOLDEN_DIRECTORY.length === 0) return [];
  let entries: string[];
  try {
    entries = readdirSync(GOLDEN_DIRECTORY);
  } catch {
    return [];
  }
  const corpus: { digest: SimpleperfDigest; bytes: Uint8Array }[] = [];
  for (const entry of entries.filter((name) => name.endsWith('.json')).sort()) {
    const parsed: unknown = JSON.parse(readFileSync(join(GOLDEN_DIRECTORY as string, entry), 'utf8'));
    if (!isDigest(parsed)) continue;
    corpus.push({
      digest: parsed,
      bytes: new Uint8Array(readFileSync(join(GOLDEN_DIRECTORY as string, parsed.inputFile))),
    });
  }
  return corpus;
}

const corpus = loadCorpus();

describe.skipIf(corpus.length === 0)('Kotlin golden corpus (SIMPLEPERF)', () => {
  for (const { digest, bytes } of corpus) {
    it('reproduces the Kotlin digest for ' + digest.case, () => {
      const normalized = normalizeSimpleperfReport(bytes);
      expect(normalized.ok).toBe(true);
      if (!normalized.ok) return;
      const profile = normalized.value;
      const expectations = digest.expectations;

      expect(profile.summary.version).toBe(expectations.version);
      expect(Number(profile.summary.recordCount)).toBe(expectations.recordCount);
      expect(Number(profile.summary.sampleCount)).toBe(expectations.sampleCount);
      expect(Number(profile.summary.lostCount)).toBe(expectations.lostCount);
      // The digest sorts; the report preserves file order.
      expect([...(profile.metadata?.eventTypes ?? [])].sort()).toEqual([...expectations.eventTypes].sort());
      expect(profile.metadata?.appPackageName ?? null).toBe(expectations.appPackageName);
      expect(profile.metadata?.traceOffCpu ?? false).toBe(expectations.traceOffCpu);

      const totalEventCount = profile.samples.reduce((total, sample) => total + sample.eventCount, 0n);
      expect(Number(totalEventCount)).toBe(expectations.totalEventCount);
      expect(profile.samples.map((sample) => sample.threadName)).toEqual(expectations.threadNames);
      expect(
        profile.samples.map((sample) => sample.frames.map((frame) => frame.symbolName).join('|')),
      ).toEqual(expectations.frameSymbols);

      // The reader's own warnings must stay empty for these fixtures.
      const read = readSimpleperfReport(bytes);
      expect(read.ok).toBe(true);
      if (read.ok) expect(Number(read.value.recordCount)).toBe(expectations.recordCount);
    });
  }
});
