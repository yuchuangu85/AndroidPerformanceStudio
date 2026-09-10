import { describe, expect, it } from 'vitest';
import { parseBenchmarkJson } from './parser.js';

const JSON_TEXT = JSON.stringify({
  context: {
    deviceModel: 'Pixel 8',
    apiLevel: 34,
    abi: 'arm64-v8a',
    fingerprint: 'fp-1',
    build: { variant: 'benchmark' },
  },
  benchmarks: [
    {
      name: 'com.example.Benchmark.someTest',
      className: 'com.example.Benchmark',
      testName: 'someTest',
      metrics: {
        timeNs: { minimum: 100, median: 200, maximum: 300, runs: [100, 200, 300] },
        allocationCount: 5,
      },
      tracePaths: ['traces/a.pftrace'],
      params: { compilationMode: 'SpeedProfile' },
    },
  ],
  unknownField: 1,
});

describe('parseBenchmarkJson', () => {
  it('parses device, build, cases, and metrics', () => {
    const { run, warnings } = parseBenchmarkJson(JSON_TEXT, '/base/report.json', 'run-1', 1000);
    expect(run.id).toBe('run-1');
    expect(run.device.model).toBe('Pixel 8');
    expect(run.device.apiLevel).toBe(34);
    expect(run.device.abi).toBe('arm64-v8a');
    expect(run.build.variant).toBe('benchmark');
    expect(run.cases).toHaveLength(1);

    const benchmarkCase = run.cases[0];
    expect(benchmarkCase?.className).toBe('com.example.Benchmark');
    expect(benchmarkCase?.testName).toBe('someTest');
    expect(benchmarkCase?.compilationMode).toBe('SpeedProfile');
    expect(benchmarkCase?.traceArtifacts).toEqual(['/base/traces/a.pftrace']);

    const time = benchmarkCase?.metrics.find((metric) => metric.name === 'timeNs');
    expect(time?.unit).toBe('ns');
    expect(time?.direction).toBe('LOWER_IS_BETTER');
    expect(time?.confidence).toBe('EXACT');
    expect(time?.samples).toEqual([100, 200, 300]);
    expect(time?.median).toBe(200);

    const allocation = benchmarkCase?.metrics.find((metric) => metric.name === 'allocationCount');
    expect(allocation?.confidence).toBe('PARTIAL');
    expect(allocation?.median).toBe(5);
    expect(allocation?.direction).toBe('UNKNOWN');

    expect(warnings.some((warning) => warning.includes('unknownField'))).toBe(true);
  });

  it('derives identity from the dotted name when className is absent', () => {
    const text = JSON.stringify({
      benchmarks: [{ name: 'com.example.Other.doThing', metrics: { timeNs: { median: 1 } } }],
    });
    const { run } = parseBenchmarkJson(text, '/tmp/a.json', 'run-2', 1);
    expect(run.cases[0]?.className).toBe('com.example.Other');
    expect(run.cases[0]?.testName).toBe('doThing');
    expect(run.cases[0]?.metrics[0]?.confidence).toBe('PARTIAL');
  });

  it('reports unusable input', () => {
    expect(() => parseBenchmarkJson('{}', '/tmp/a.json', 'r', 1)).toThrow(/No benchmarks/);
    expect(() => parseBenchmarkJson(JSON.stringify({ benchmarks: [] }), '/tmp/a.json', 'r', 1)).toThrow(/No valid benchmark cases/);
    // A case without metrics is skipped, but a valid sibling keeps the run usable.
    const mixed = parseBenchmarkJson(
      JSON.stringify({ benchmarks: [{ name: 'x' }, { name: 'a.b.c', metrics: { timeNs: { median: 1 } } }] }),
      '/tmp/a.json',
      'r',
      1,
    ).run;
    expect(mixed.cases).toHaveLength(1);
    expect(mixed.warnings.some((warning) => warning.includes('No metrics found'))).toBe(true);
  });
});
