import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import type { BenchmarkRun } from '@aps/benchmark-regression';
import { BenchmarkStore, summarizeBenchmarkRun } from './benchmark-store.js';

const directories: string[] = [];

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'aps-bench-'));
  directories.push(directory);
  return directory;
}

afterEach(async () => {
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

function run(id: string): BenchmarkRun {
  return {
    id,
    sourceFile: '/tmp/' + id + '.json',
    device: { model: 'Pixel 8', apiLevel: 34, abi: 'arm64-v8a' },
    build: { variant: 'benchmark' },
    importedAtEpochMillis: 100,
    cases: [
      {
        className: 'com.example.Bench',
        testName: 'someTest',
        metrics: [
          {
            name: 'timeNs',
            unit: 'ns',
            direction: 'LOWER_IS_BETTER',
            samples: [1, 2, 3],
            median: 2,
            confidence: 'EXACT',
            sourceFields: {},
          },
        ],
        traceArtifacts: [],
      },
    ],
    warnings: ['a warning'],
  };
}

describe('BenchmarkStore', () => {
  it('summarizes the comparability fields and stores the run', async () => {
    const directory = await temporaryDirectory();
    const store = new BenchmarkStore(join(directory, 'benchmarks'));
    expect(await store.list()).toEqual([]);

    const summary = await store.add(run('a'));
    expect(summary).toMatchObject({
      id: 'a',
      caseCount: 1,
      deviceModel: 'Pixel 8',
      apiLevel: 34,
      abi: 'arm64-v8a',
      variant: 'benchmark',
      warningCount: 1,
    });
    const loaded = await store.load('a');
    expect(loaded?.cases[0]?.metrics[0]?.name).toBe('timeNs');
    expect(summarizeBenchmarkRun(run('b')).deviceModel).toBe('Pixel 8');
  });

  it('keeps the newest run first and returns undefined for an unknown id', async () => {
    const directory = await temporaryDirectory();
    const store = new BenchmarkStore(join(directory, 'benchmarks'));
    await store.add(run('a'));
    await store.add(run('b'));
    expect((await store.list()).map((entry) => entry.id)).toEqual(['b', 'a']);
    expect(await store.load('missing')).toBeUndefined();
  });
});
