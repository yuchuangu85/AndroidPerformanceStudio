import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { SimpleperfProfileNormalizer } from './normalizer.js';
import { readSimpleperfReport } from './reader.js';
import { normalizeSimpleperfReport, type NormalizedProfile } from './report.js';
import { samplesToCallStackTable } from './analysis/table.js';

/**
 * Performance baseline for the SIMPLEPERF pipeline, mirroring the HPROF one.
 * Opt-in: run with APS_PERF=1. When APS_GOLDEN_DIR holds the JVM baseline
 * (simpleperf/benchmark.json, written by SimpleperfJvmBenchmarkTest in CI), the
 * same two stages are compared and gated at 1.5x.
 *
 * The gated stages are the JVM benchmark's stages, not the app's pipeline:
 * SimpleperfJvmBenchmarkTest decodes every record and then feeds every record
 * through SimpleperfProfileNormalizer without keeping the result. Measuring
 * normalizeSimpleperfReport here would materialise a 100000 sample profile on
 * the TypeScript side only and compare two different amounts of work. The full
 * pipeline is measured separately, without a gate.
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
const EXPECTED_RECORDS = 1 + FILE_COUNT + THREAD_COUNT + SAMPLE_COUNT;
/** Size of the report the generator below builds; the JVM builds the same one. */
const EXPECTED_BYTES = 15_052_649;

const ENCODER = new TextEncoder();

/**
 * Growable byte sink. The generator used to build every field as its own
 * Uint8Array and copy it into its parent: 1.2 million call chain frames meant
 * tens of millions of short-lived arrays, and generation alone took 150 s of
 * the 152 s this test ran. Writing into one reused buffer takes well under a
 * second for the same bytes.
 */
class ByteSink {
  private bytes = new Uint8Array(1 << 20);
  private length = 0;

  get size(): number {
    return this.length;
  }

  reset(): void {
    this.length = 0;
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

  /** Unsigned LEB128; the generator only writes values far below 2^53. */
  varint(value: number): void {
    let remaining = value;
    do {
      const byte = remaining & 0x7f;
      remaining = Math.floor(remaining / 128);
      this.u8(remaining === 0 ? byte : byte | 0x80);
    } while (remaining !== 0);
  }

  raw(source: Uint8Array): void {
    this.ensure(source.length);
    this.bytes.set(source, this.length);
    this.length += source.length;
  }

  utf8(value: string): void {
    this.raw(ENCODER.encode(value));
  }

  result(): Uint8Array {
    return this.bytes.subarray(0, this.length);
  }

  private ensure(count: number): void {
    if (this.length + count <= this.bytes.length) return;
    let size = this.bytes.length * 2;
    while (size < this.length + count) size *= 2;
    const grown = new Uint8Array(size);
    grown.set(this.bytes.subarray(0, this.length));
    this.bytes = grown;
  }
}

/** One protobuf message, reused across records so nesting allocates nothing. */
class Message {
  private readonly body = new ByteSink();

  get length(): number {
    return this.body.size;
  }

  reset(): void {
    this.body.reset();
  }

  varintField(field: number, value: number): void {
    this.body.varint(field << 3);
    this.body.varint(value);
  }

  stringField(field: number, value: string): void {
    this.body.varint((field << 3) | 2);
    this.body.varint(ENCODER.encode(value).length);
    this.body.utf8(value);
  }

  /** Appends this message to `parent` as a length delimited field. */
  appendTo(parent: Message, field: number): void {
    parent.body.varint((field << 3) | 2);
    parent.body.varint(this.body.size);
    parent.body.raw(this.body.result());
  }

  /** Appends the raw message bytes, for the stream's own length framing. */
  bodyTo(sink: ByteSink): void {
    sink.raw(this.body.result());
  }
}

/** A report shaped like a real capture: many files, symbols, threads, samples. */
function syntheticReport(): Uint8Array {
  const stream = new ByteSink();
  stream.utf8('SIMPLEPERF');
  stream.u8(1);
  stream.u8(0);

  const record = new Message();
  const meta = new Message();
  const file = new Message();
  const thread = new Message();
  const sample = new Message();
  const entry = new Message();

  // Emits and clears: the record message is reused for the next record, so
  // leaving it populated would copy every earlier record again on each emit.
  const emit = (message: Message): void => {
    stream.u32(message.length);
    message.bodyTo(stream);
    message.reset();
  };

  meta.stringField(1, 'cpu-cycles');
  meta.stringField(2, 'com.example.app');
  meta.varintField(6, 0);
  meta.appendTo(record, 5);
  emit(record);

  for (let index = 0; index < FILE_COUNT; index += 1) {
    file.reset();
    file.varintField(1, index);
    file.stringField(2, '/system/lib64/lib' + String(index) + '.so');
    for (let symbol = 0; symbol < SYMBOLS_PER_FILE; symbol += 1) {
      file.stringField(3, 'symbol_' + String(index) + '_' + String(symbol));
    }
    file.appendTo(record, 3);
    emit(record);
  }

  for (let index = 0; index < THREAD_COUNT; index += 1) {
    thread.reset();
    thread.varintField(1, index);
    thread.varintField(2, 1000 + index);
    thread.stringField(3, 'Thread-' + String(index));
    thread.appendTo(record, 4);
    emit(record);
  }

  // Frames alternate between two files so the normalizer resolves real names.
  for (let index = 0; index < SAMPLE_COUNT; index += 1) {
    sample.reset();
    sample.varintField(1, index);
    sample.varintField(2, index % THREAD_COUNT);
    for (let frame = 0; frame < FRAMES_PER_SAMPLE; frame += 1) {
      entry.reset();
      entry.varintField(1, 0x1000 + frame * 8);
      entry.varintField(2, (index + frame) % FILE_COUNT);
      entry.varintField(3, (index + frame) % SYMBOLS_PER_FILE);
      entry.varintField(4, 0);
      entry.appendTo(sample, 3);
    }
    sample.varintField(4, 1000);
    sample.varintField(5, 0);
    sample.appendTo(record, 1);
    emit(record);
  }

  stream.u32(0);
  return stream.result().slice();
}

interface Measurement {
  readonly stage: string;
  readonly milliseconds: number;
}

/**
 * Warm-ups and measured rounds, matching the JVM benchmark. Three warm-ups and
 * five measured rounds on both sides: a median of three is one sample, so a
 * single scheduling hiccup moves the ratio by tens of percent.
 */
const WARMUP_ROUNDS = 3;
const MEASURED_ROUNDS = 5;

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

/** Informational stage: not compared with the JVM, so a single sample is fine. */
function measure(stage: string, run: () => void, results: Measurement[]): void {
  const start = performance.now();
  run();
  results.push({ stage, milliseconds: Math.round((performance.now() - start) * 10) / 10 });
}

describe.runIf(ENABLED)('SIMPLEPERF performance baseline', () => {
  it('reads and normalizes a large synthetic report', () => {
    const bytes = syntheticReport();
    // The JVM benchmark builds the same report from the same constants; a
    // different size means the two sides stopped measuring the same input.
    expect(bytes.length).toBe(EXPECTED_BYTES);
    const results: Measurement[] = [];

    let records = 0;
    measureMedian('read', () => {
      records = 0;
      const read = readSimpleperfReport(bytes, { onRecord: () => { records += 1; } });
      if (!read.ok) throw new Error(read.error.message);
    }, results);
    expect(records).toBe(EXPECTED_RECORDS);

    let samples = 0;
    measureMedian('readAndNormalize', () => {
      samples = 0;
      const normalizer = new SimpleperfProfileNormalizer();
      const read = readSimpleperfReport(bytes, {
        onRecord: (envelope) => {
          if (normalizer.normalize(envelope.record).kind === 'SAMPLE') samples += 1;
        },
      });
      if (!read.ok) throw new Error(read.error.message);
    }, results);
    expect(samples).toBe(SAMPLE_COUNT);

    // The pipeline the app actually runs, which keeps every sample and frame.
    let materialized: NormalizedProfile | undefined;
    measure('normalizeProfile', () => {
      const normalized = normalizeSimpleperfReport(bytes);
      if (!normalized.ok) throw new Error(normalized.error.message);
      materialized = normalized.value;
    }, results);
    const profile = materialized;
    if (profile === undefined) throw new Error('normalizeProfile did not run');
    expect(profile.samples.length).toBe(SAMPLE_COUNT);

    let stacks = 0;
    measure('buildCallStackTable', () => {
      stacks = samplesToCallStackTable(profile.samples).stacks.length;
    }, results);
    expect(stacks).toBe(SAMPLE_COUNT);

    const report = {
      generatedAt: new Date().toISOString(),
      runtime: { node: process.version, platform: process.platform, arch: process.arch },
      gatedStages: GATED_STAGES.map((pair) => pair[0]),
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
  }, 300_000);
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
  } catch (error) {
    // APS_GOLDEN_DIR is set, so the CI job that writes this file ran: a missing
    // or unreadable baseline is a broken gate, not a reason to skip the check.
    throw new Error('JVM baseline unreadable at ' + baseline, { cause: error });
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
