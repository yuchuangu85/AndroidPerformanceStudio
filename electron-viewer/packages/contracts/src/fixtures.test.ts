import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { decodeCaptureArtifact, encodeCaptureArtifact } from '../src/capture-artifact';

/**
 * Golden fixtures. The harness is deliberately fixture-driven so that JSON
 * produced by the Kotlin implementation can be dropped into fixtures/ and is
 * immediately checked for decode + canonical re-encode stability.
 */
const fixturesDirectory = fileURLToPath(new URL('../fixtures', import.meta.url));
const fixtureFiles = readdirSync(fixturesDirectory).filter((name) => name.endsWith('.json')).sort();

describe('capture artifact golden fixtures', () => {
  it('ships at least one fixture', () => {
    expect(fixtureFiles.length).toBeGreaterThan(0);
  });

  for (const file of fixtureFiles) {
    it('canonically round-trips ' + file, () => {
      const raw = readFileSync(fixturesDirectory + '/' + file, 'utf8');
      const decoded = decodeCaptureArtifact(raw);
      const canonical = encodeCaptureArtifact(decoded);
      expect(encodeCaptureArtifact(decodeCaptureArtifact(canonical))).toBe(canonical);
      expect(decoded.contractVersion).toBe(1);
    });
  }
});
