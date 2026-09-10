import { describe, expect, it } from 'vitest';
import { MAX_BENCHMARK_JSON_BYTES } from '@aps/benchmark-regression/node';
import { importBenchmarkJson, type BenchmarkImportDependencies } from './benchmark-import-service.js';

const REPORT = JSON.stringify({
  context: { deviceModel: 'Pixel 8', apiLevel: 34, abi: 'arm64-v8a', build: { variant: 'benchmark' } },
  benchmarks: [
    { name: 'com.example.Bench.someTest', metrics: { timeNs: { median: 100, runs: [100, 100, 100] } } },
  ],
});

function dependencies(overrides: Partial<BenchmarkImportDependencies> = {}): BenchmarkImportDependencies {
  return {
    readFileText: async () => REPORT,
    fileSize: async () => REPORT.length,
    isRegularFile: () => true,
    newId: () => 'run-1',
    now: () => 7,
    ...overrides,
  };
}

describe('importBenchmarkJson', () => {
  it('imports a usable report', async () => {
    const result = await importBenchmarkJson(dependencies(), '/tmp/bench.json');
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.id).toBe('run-1');
    expect(result.value.importedAtEpochMillis).toBe(7);
    expect(result.value.device.model).toBe('Pixel 8');
    expect(result.value.cases[0]?.metrics[0]?.name).toBe('timeNs');
  });

  it('reports missing, unreadable, oversized, and malformed input', async () => {
    const missing = await importBenchmarkJson(dependencies({ isRegularFile: () => false }), '/tmp/none.json');
    expect(missing.ok).toBe(false);
    if (!missing.ok) expect(missing.error.code).toBe('BENCHMARK_JSON_NOT_FOUND');

    const unreadable = await importBenchmarkJson(
      dependencies({
        fileSize: async () => {
          throw new Error('EACCES');
        },
      }),
      '/tmp/locked.json',
    );
    expect(unreadable.ok).toBe(false);
    if (!unreadable.ok) expect(unreadable.error.code).toBe('BENCHMARK_JSON_UNREADABLE');

    const oversized = await importBenchmarkJson(
      dependencies({ fileSize: async () => MAX_BENCHMARK_JSON_BYTES + 1 }),
      '/tmp/big.json',
    );
    expect(oversized.ok).toBe(false);
    if (!oversized.ok) expect(oversized.error.code).toBe('BENCHMARK_JSON_TOO_LARGE');

    const malformed = await importBenchmarkJson(dependencies({ readFileText: async () => '{oops' }), '/tmp/bad.json');
    expect(malformed.ok).toBe(false);
    if (!malformed.ok) expect(malformed.error.code).toBe('BENCHMARK_JSON_MALFORMED');
  });
});
