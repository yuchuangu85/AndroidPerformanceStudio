import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { parseArtTrace } from './parser.js';

/**
 * Consumes the ART method-trace golden corpus produced by the Kotlin
 * implementation, which covers the streaming (versions 4 and 5) and classic
 * (version 2) layouts. Generated in CI; skipped without APS_GOLDEN_DIR.
 */
const GOLDEN_DIRECTORY = process.env['APS_GOLDEN_DIR'];
/** Each exporter writes its own subdirectory. */
const CORPUS_DIRECTORY = 'art-trace';

interface ArtTraceDigest {
  readonly parser: string;
  readonly case: string;
  readonly inputFile: string;
  readonly expectations: {
    readonly version: number;
    readonly clockSource: string;
    readonly startTimeNanos: string;
    readonly endTimeNanos: string;
    readonly eventCount: number;
    readonly enterCount: number;
    readonly exitCount: number;
    readonly unrollCount: number;
    readonly methodNames: readonly string[];
    readonly threadNames: readonly string[];
    readonly warningCount: number;
  };
}

function isDigest(value: unknown): value is ArtTraceDigest {
  if (value === null || typeof value !== 'object') return false;
  const record = value as Record<string, unknown>;
  return record['parser'] === 'ART_TRACE' && typeof record['inputFile'] === 'string';
}

function loadCorpus(): { digest: ArtTraceDigest; bytes: Uint8Array }[] {
  if (GOLDEN_DIRECTORY === undefined || GOLDEN_DIRECTORY.length === 0) return [];
  let entries: string[];
  try {
    entries = readdirSync(join(GOLDEN_DIRECTORY, CORPUS_DIRECTORY));
  } catch {
    return [];
  }
  const corpus: { digest: ArtTraceDigest; bytes: Uint8Array }[] = [];
  for (const entry of entries.filter((name) => name.endsWith('.json')).sort()) {
    const parsed: unknown = JSON.parse(readFileSync(join(GOLDEN_DIRECTORY as string, CORPUS_DIRECTORY, entry), 'utf8'));
    if (!isDigest(parsed)) continue;
    corpus.push({
      digest: parsed,
      bytes: new Uint8Array(readFileSync(join(GOLDEN_DIRECTORY as string, CORPUS_DIRECTORY, parsed.inputFile))),
    });
  }
  return corpus;
}

const corpus = loadCorpus();

// A corpus path that is set but empty is a failure, not a skip: silently
// skipping would make a missing exporter look green.
const corpusSuite = GOLDEN_DIRECTORY === undefined ? describe.skip : describe;

corpusSuite('Kotlin golden corpus (ART trace)', () => {
  for (const { digest, bytes } of corpus) {
    it('reproduces the Kotlin digest for ' + digest.case, () => {
      const parsed = parseArtTrace(bytes);
      expect(parsed.ok).toBe(true);
      if (!parsed.ok) return;
      const analysis = parsed.value;
      const expectations = digest.expectations;

      expect(analysis.header.version).toBe(expectations.version);
      expect(analysis.header.clockSource).toBe(expectations.clockSource);
      expect(analysis.startTimeNanos.toString()).toBe(expectations.startTimeNanos);
      expect(analysis.endTimeNanos.toString()).toBe(expectations.endTimeNanos);
      expect(analysis.events.length).toBe(expectations.eventCount);
      expect(analysis.events.filter((event) => event.action === 'ENTER').length).toBe(expectations.enterCount);
      expect(analysis.events.filter((event) => event.action === 'EXIT').length).toBe(expectations.exitCount);
      expect(analysis.events.filter((event) => event.action === 'UNROLL').length).toBe(expectations.unrollCount);
      expect(analysis.warnings.length).toBe(expectations.warningCount);

      // Same display-name rule as the Kotlin exporter.
      const methodNames = [...analysis.methods.values()]
        .map((method) => {
          const dotted = method.className.split('/').join('.').replace(/;$/, '');
          const qualified = method.methodName.length === 0 ? dotted : dotted + '.' + method.methodName;
          return qualified;
        })
        .filter((name) => name.trim().length > 0)
        .sort();
      expect(methodNames).toEqual(expectations.methodNames);

      const threadNames = [...analysis.threads.values()]
        .map((thread) => thread.name + ' (tid ' + String(thread.threadId) + ')')
        .sort();
      expect(threadNames).toEqual(expectations.threadNames);
    });
  }
});