import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { parseHprof } from './hprof.js';

/**
 * Consumes the golden corpus produced by the Kotlin implementation.
 *
 * The Kotlin side (desktop-viewer/memory-profiler/parser-hprof) builds fixtures
 * with HprofFixtureBuilder and writes each case as a .hprof file plus a .json
 * digest of what its own parser saw. This test parses the same bytes with the
 * TypeScript implementation and compares the digest field by field, so the two
 * implementations are checked against each other rather than against a
 * hand-written expectation.
 *
 * The corpus is generated in CI, where the JVM toolchain is available:
 *
 *   APS_GOLDEN_DIR=... pnpm --filter @aps/memory-profiler test src/golden-corpus.test.ts
 *
 * Without APS_GOLDEN_DIR (or with an empty directory) the suite is skipped.
 */
const GOLDEN_DIRECTORY = process.env['APS_GOLDEN_DIR'];

interface GoldenDigest {
  readonly parser: string;
  readonly case: string;
  readonly inputFile: string;
  readonly inputSha256: string;
  readonly expectations: {
    readonly format: string;
    readonly idSize: number;
    readonly classNames: readonly string[];
    readonly instanceCount: number;
    readonly instanceShallowSizes: readonly number[];
    readonly arrayShallowSizes: readonly number[];
    readonly rootCount: number;
    readonly warningCount: number;
  };
}

function loadCorpus(): readonly { readonly digest: GoldenDigest; readonly bytes: Uint8Array }[] {
  if (GOLDEN_DIRECTORY === undefined || GOLDEN_DIRECTORY.length === 0) return [];
  let entries: string[];
  try {
    entries = readdirSync(GOLDEN_DIRECTORY);
  } catch {
    return [];
  }
  return entries
    .filter((entry) => entry.endsWith('.json'))
    .sort()
    .map((entry) => {
      const digest = JSON.parse(readFileSync(join(GOLDEN_DIRECTORY as string, entry), 'utf8')) as GoldenDigest;
      const bytes = new Uint8Array(readFileSync(join(GOLDEN_DIRECTORY as string, digest.inputFile)));
      return { digest, bytes };
    });
}

const corpus = loadCorpus();

function sorted(values: readonly number[]): number[] {
  return [...values].sort((left, right) => left - right);
}

describe.skipIf(corpus.length === 0)('Kotlin golden corpus', () => {
  it('found at least one case', () => {
    expect(corpus.length).toBeGreaterThan(0);
  });

  for (const { digest, bytes } of corpus) {
    it('reproduces the Kotlin digest for ' + digest.case, () => {
      expect(digest.parser).toBe('HPROF');
      const result = parseHprof(bytes);

      // Kotlin reports the whole magic as format; the TS header keeps the version.
      expect('JAVA PROFILE ' + result.header.version).toBe(digest.expectations.format);
      expect(result.header.identifierSize).toBe(digest.expectations.idSize);
      expect(result.instances).toHaveLength(digest.expectations.instanceCount);
      expect(sorted(result.instances.map((instance) => instance.shallowBytes))).toEqual(
        sorted(digest.expectations.instanceShallowSizes),
      );
      expect(sorted(result.arrays.map((array) => array.shallowBytes))).toEqual(
        sorted(digest.expectations.arrayShallowSizes),
      );
      expect(result.roots.length).toBe(digest.expectations.rootCount);
      expect(result.warnings.length).toBe(digest.expectations.warningCount);

      const classNames = [...result.classes.values()]
        .map((record) => result.strings.get(record.nameId) ?? '<unnamed>')
        .sort();
      expect(classNames).toEqual([...digest.expectations.classNames].sort());
    });
  }
});
