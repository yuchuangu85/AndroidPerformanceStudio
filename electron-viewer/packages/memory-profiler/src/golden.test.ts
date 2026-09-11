import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { classHistogram, summarizeMemory } from './histogram.js';
import { parseHprof } from './hprof.js';

/**
 * Cross-language golden fixture. The file is the Kotlin parser's own committed
 * test resource; the expectations below are the assertions of
 * desktop-viewer/memory-profiler/parser-hprof/src/test/.../HprofParserTest.kt
 * ("parses converted Android sample hprof resource without warnings") plus the
 * provenance digest from the fixture README. If either language changes its
 * reading of this file, this test fails.
 */
const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
const FIXTURE = join(
  REPO_ROOT,
  'desktop-viewer',
  'memory-profiler',
  'parser-hprof',
  'src',
  'test',
  'resources',
  'hprof',
  'android-converted-sample.hprof',
);

const EXPECTED_SHA256 = '7d6727771e253c5f9445fff0f2497074fd1de91ee4977beb06608165c156ed66';

function fixtureBytes(): Uint8Array {
  return new Uint8Array(readFileSync(FIXTURE));
}

describe('HPROF golden fixture (Kotlin-owned)', () => {
  it('matches the provenance digest recorded by the Kotlin fixture README', () => {
    const digest = createHash('sha256').update(fixtureBytes()).digest('hex');
    expect(digest).toBe(EXPECTED_SHA256);
  });

  it('reproduces every assertion of HprofParserTest on the same bytes', () => {
    const result = parseHprof(fixtureBytes());

    // Kotlin reports the whole magic as format; the TS header keeps the version.
    expect(result.header.version).toBe('1.0.2');
    expect(result.header.identifierSize).toBe(4);
    expect(result.warnings).toEqual([]);

    const classes = [...result.classes.values()];
    expect(classes).toHaveLength(1);
    const classRecord = classes[0]!;
    expect(result.strings.get(classRecord.nameId)).toBe('com.example.ConvertedSample');

    expect(result.instances).toHaveLength(1);
    const instance = result.instances[0]!;
    expect(instance.classObjectId).toBe(classRecord.objectId);
    // Kotlin reports shallowSize from the class dump's instance size, which is
    // the same 24 bytes the fixture README documents.
    expect(classRecord.instanceFieldBytes).toBe(24);
    // The dump carries no field values for this synthetic class, so the shallow
    // size comes from the class metadata, exactly as the Kotlin parser reports it.
    expect(instance.fieldBytes).toBe(0);
    expect(instance.shallowBytes).toBe(24);
  });

  it('produces a histogram and summary consistent with the fixture documentation', () => {
    const result = parseHprof(fixtureBytes());
    const histogram = classHistogram(result);
    expect(histogram).toHaveLength(1);
    expect(histogram[0]?.className).toBe('com.example.ConvertedSample');
    expect(histogram[0]?.instanceCount).toBe(1);

    const summary = summarizeMemory(result);
    expect(summary.version).toBe('1.0.2');
    expect(summary.classCount).toBe(1);
    expect(summary.instanceCount).toBe(1);
    expect(summary.arrayCount).toBe(0);
    // The class declares a 24-byte instance size and no header is added, matching
    // HeapInstance.shallowSize in the Kotlin model.
    expect(summary.shallowBytes).toBe(24);
  });
});
