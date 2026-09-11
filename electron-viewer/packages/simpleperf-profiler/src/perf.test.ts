import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { readSimpleperfReport } from './reader.js';
import { normalizeSimpleperfReport } from './report.js';
import { samplesToCallStackTable } from './analysis/table.js';

/**
 * Performance baseline for the SIMPLEPERF pipeline, mirroring the HPROF one.
 * Opt-in: run with APS_PERF=1. When APS_GOLDEN_DIR holds the JVM baseline
 * (simpleperf/benchmark.json, written by SimpleperfJvmBenchmarkTest in CI), the
 * same two stages are compared and gated at 1.5x.
 */
const ENABLED = process.env['APS_PERF'] === '1';
const GOLDEN_DIRECTORY = process.env['APS_GOLDEN_DIR'];
const GATE_RATIO = 1.5;
const GATED_STAGES: readonly (readonly [string, string])[] = [
  ['read', 'read'],
  ['readAndNormalize', 'readAndNormalize'],
];

const FILE_COUNT = 40;
const SYMBOLS_PER_FILE = 120;
const THREAD_COUNT = 64;
const SAMPLE_COUNT = 100_000;
const FRAMES_PER_SAMPLE = 12;

class Writer {
  private bytes = new Uint8Array(1 << 20);
  private length = 0;

  private ensure(count: number): void {
    if (this.length + count <= this.bytes.length) return;
    let size = this.bytes.length * 2;
    while (size < this.length + count) size *= 2;
    const grown = new Uint8Array(size);
    grown.set(this.bytes.subarray(0, this.length));
    this.bytes = grown;
  }

  u8(value: number): void {
    this.ensure(1);
    this.bytes[this.length] = value & 0xff;
    this.length += 1;
  }

  u32(value: number): void {
    this.ensure(4);
    this.bytes[this.length] = value & 0xff;
    this.bytes[this.length + 1] = (value >>> 8) & 0xff;
    this.bytes[this.length + 2] = (value >>> 16) & 0xff;
    this.bytes[this.length + 3] = (value >>> 24) & 0xff;
    this.length += 4;
  }

  bytesOf(value: Uint8Array): void {
    this.ensure(value.length);
    this.bytes.set(value, this.length);
    this.length += value.length;
  }

  utf8(value: string): void {
    this.bytesOf(new TextEncoder().encode(value));
  }

  varint(value: number | bigint): void {
    let remaining = BigInt(value);
    do {
      const byte = Number(remaining & 0x7fn);
      remaining >>= 7n;
      this.u8(remaining === 0n ? byte : byte | 0x80);
    } while (remaining !== 0n);
  }

  result(): Uint8Array {
    return this.bytes.subarray(0, this.length);
  }

  get size(): number {
    return this.length;
  }
}

function varintField(field: number, value: number | bigint): Uint8Array {
  const writer = new Writer();
  writer.varint((BigInt(field) << 3n) | 0n);
  writer.varint(value);
  return writer.result().slice();
}

function bytesField(field: number, value: Uint8Array): Uint8Array {
  const writer = new Writer();
  writer.varint((BigInt(field) << 3n) | 2n);
  writer.varint(value.length);
  writer.bytesOf(value);
  return writer.result().slice();
}

function record(field: number, payload: Uint8Array): Uint8Array {
  return bytesField(field, payload);
}

function joinBytes(chunks: readonly Uint8Array[]): Uint8Array {
  const writer = new Writer();
  chunks.forEach((chunk) => writer.bytesOf(chunk));
  return writer.result().slice();
}

/** A report shaped like a real capture: many files, symbols, threads, samples. */
function syntheticReport(): Uint8Array {
  const stream = new Writer();
  stream.utf8('SIMPLEPERF');
  stream.u8(1);
  stream.u8(0);
  const emit = (recordBytes: Uint8Array): void => {
    stream.u32(recordBytes.length);
    stream.bytesOf(recordBytes);
  };

  const metaInfo = joinBytes([
    bytesField(1, new TextEncoder().encode('cpu-cycles')),
    bytesField(2, new TextEncoder().encode('com.example.app')),
    varintField(6, 0),
  ]);
  emit(record(5, metaInfo));
  for (let file = 0; file < FILE_COUNT; file += 1) {
    const symbols: Uint8Array[] = [];
    for (let symbol = 0; symbol < SYMBOLS_PER_FILE; symbol += 1) {
      symbols.push(bytesField(3, new TextEncoder().encode('symbol_' + String(file) + '_' + String(symbol))));
    }
    emit(
      record(
        3,
        joinBytes([
          varintField(1, file),
          bytesField(2, new TextEncoder().encode('/system/lib64/lib' + String(file) + '.so')),
          ...symbols,
        ]),
      ),
    );
  }
  for (let thread = 0; thread < THREAD_COUNT; thread += 1) {
    emit(
      record(
        4,
        joinBytes([
          varintField(1, thread),
          varintField(2, 1000 + thread),
          bytesField(3, new TextEncoder().encode('Thread-' + String(thread))),
        ]),
      ),
    );
  }

  // Frames alternate between two files so the normalizer resolves real names.
  for (let index = 0; index < SAMPLE_COUNT; index += 1) {
    const frames: Uint8Array[] = [];
    for (let frame = 0; frame < FRAMES_PER_SAMPLE; frame += 1) {
      frames.push(
        record(
          3,
          joinBytes([
            varintField(1, 0x1000 + frame * 8),
            varintField(2, (index + frame) % FILE_COUNT),
            varintField(3, (index + frame) % SYMBOLS_PER_FILE),
            varintField(4, 0),
          ]),
        ),
      );
    }
    emit(
      record(
        1,
        joinBytes([
          varintField(1, index),
          varintField(2, index % THREAD_COUNT),
          ...frames,
          varintField(4, 1000),
          varintField(5, 0),
        ]),
      ),
    );
  }
  stream.u32(0);
  return stream.result().slice();
}

interface Measurement {
  readonly stage: string;
  readonly milliseconds: number;
}

/** Warmup rounds before a measured stage, matching the JVM benchmark. */
const WARMUP_ROUNDS = 2;
const MEASURED_ROUNDS = 3;

/**
 * Measures a gated stage as the median of several rounds. A single sample is
 * decided by whatever GC or CPU contention happened during it, which is how a
 * 1.3x stage once reported 2.0x. The JVM benchmark reports a median too, so both
 * sides are summarised the same way.
 */
function measureMedian(stage: string, run: () => void, results: Measurement[]): void {
  for (let round = 0; round < WARMUP_ROUNDS; round += 1) run();
  const runs: number[] = [];
  for (let round = 0; round < MEASURED_ROUNDS; round += 1) {
    const start = performance.now();
    run();
    runs.push(performance.now() - start);
  }
  const sorted = [...runs].sort((left, right) => left - right);
  results.push({
    stage,
    milliseconds: Math.round((sorted[Math.floor(sorted.length / 2)] as number) * 10) / 10,
  });
}
function measure(stage: string, run: () => void, results: Measurement[]): void {
  const start = performance.now();
  run();
  results.push({ stage, milliseconds: Math.round((performance.now() - start) * 10) / 10 });
}

describe.runIf(ENABLED)('SIMPLEPERF performance baseline', () => {
  it('reads and normalizes a large synthetic report', () => {
    const bytes = syntheticReport();
    const results: Measurement[] = [];

    let records = 0;
    measureMedian('read', () => {
      records = 0;
      const read = readSimpleperfReport(bytes, { onRecord: () => { records += 1; } });
      if (!read.ok) throw new Error(read.error.message);
    }, results);
    expect(records).toBe(1 + FILE_COUNT + THREAD_COUNT + SAMPLE_COUNT);

    let samples = 0;
    measureMedian('readAndNormalize', () => {
      const normalized = normalizeSimpleperfReport(bytes);
      if (!normalized.ok) throw new Error(normalized.error.message);
      samples = normalized.value.samples.length;
    }, results);
    expect(samples).toBe(SAMPLE_COUNT);

    let stacks = 0;
    measure('buildCallStackTable', () => {
      const normalized = normalizeSimpleperfReport(bytes);
      if (!normalized.ok) throw new Error(normalized.error.message);
      stacks = samplesToCallStackTable(normalized.value.samples).stacks.length;
    }, results);
    expect(stacks).toBe(SAMPLE_COUNT);

    const report = {
      generatedAt: new Date().toISOString(),
      runtime: { node: process.version, platform: process.platform, arch: process.arch },
      input: {
        bytes: bytes.length,
        files: FILE_COUNT,
        symbolsPerFile: SYMBOLS_PER_FILE,
        threads: THREAD_COUNT,
        samples: SAMPLE_COUNT,
        framesPerSample: FRAMES_PER_SAMPLE,
      },
      measurements: results,
    };
    console.log(JSON.stringify(report, null, 2));

    const baseline = baselinePath();
    if (baseline !== undefined) compareWithJvm(report, baseline);
  }, 900_000);
});

function baselinePath(): string | undefined {
  if (GOLDEN_DIRECTORY === undefined || GOLDEN_DIRECTORY.length === 0) return undefined;
  return join(GOLDEN_DIRECTORY, 'simpleperf', 'benchmark.json');
}

interface JvmBenchmark {
  readonly measurements: readonly { readonly stage: string; readonly milliseconds: number }[];
}

function compareWithJvm(report: { readonly measurements: readonly Measurement[] }, baseline: string): void {
  let parsed: JvmBenchmark;
  try {
    parsed = JSON.parse(readFileSync(baseline, 'utf8')) as JvmBenchmark;
  } catch {
    // Without the JVM baseline there is nothing to gate against.
    return;
  }
  const failures: string[] = [];
  console.log('stage comparison (TypeScript / JVM):');
  for (const pair of GATED_STAGES) {
    const tsStage = pair[0];
    const jvmStage = pair[1];
    const ts = report.measurements.find((entry) => entry.stage === tsStage)?.milliseconds;
    const jvmValue = parsed.measurements.find((entry) => entry.stage === jvmStage)?.milliseconds;
    if (ts === undefined || jvmValue === undefined) {
      failures.push(tsStage + ': measurement missing');
      continue;
    }
    const ratio = ts / jvmValue;
    console.log('  ' + tsStage + ': ' + ts.toFixed(1) + ' ms vs JVM ' + jvmValue.toFixed(1) + ' ms = ' + ratio.toFixed(2) + 'x');
    if (ratio > GATE_RATIO) {
      failures.push(tsStage + ' is ' + ratio.toFixed(2) + 'x the JVM time (gate ' + GATE_RATIO + 'x)');
    }
  }
  expect(failures).toEqual([]);
}