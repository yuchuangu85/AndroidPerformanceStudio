import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { parseArtTrace } from './parser.js';
import { toCallStackTable } from './projector.js';
import { TRACE_MAGIC, TraceWriter } from './trace-builder.js';

/**
 * Performance baseline for the ART method-trace pipeline, mirroring the HPROF
 * and SIMPLEPERF ones. Opt-in: run with APS_PERF=1. When APS_GOLDEN_DIR holds
 * the JVM baseline (art-trace/benchmark.json, written by ArtTraceJvmBenchmarkTest
 * in CI) the same two stages are compared and gated at 1.5x.
 */
const ENABLED = process.env['APS_PERF'] === '1';
const GOLDEN_DIRECTORY = process.env['APS_GOLDEN_DIR'];
const GATE_RATIO = 1.5;
const GATED_STAGES: readonly (readonly [string, string])[] = [
  ['parse', 'parse'],
  ['parseAndProject', 'parseAndProject'],
];

const METHOD_COUNT = 2000;
const THREAD_COUNT = 64;
const EVENTS_PER_THREAD = 1600;
const EVENT_COUNT = THREAD_COUNT * EVENTS_PER_THREAD;

const WARMUP_ROUNDS = 2;
const MEASURED_ROUNDS = 3;

const HEADER_PADDING = new Uint8Array(18);

function threadInfoPacket(threadId: number, name: string): Uint8Array {
  const encoded = new TextEncoder().encode(name);
  return new TraceWriter().u8(0).u32(threadId).u16(encoded.length).raw(encoded).bytes();
}

function methodInfoPacket(methodId: bigint, info: string): Uint8Array {
  const encoded = new TextEncoder().encode(info);
  return new TraceWriter().u8(1).u64(methodId).u16(encoded.length).raw(encoded).bytes();
}

/**
 * One entry block per thread. Method words are (index << 2) | action and are
 * delta encoded inside the block, exactly like ART writes them.
 *
 * The block header declares EVENTS_PER_THREAD records, so the body has to hold
 * that many: a version 5 record is a delta, a time, and a CPU time, and the
 * parser reads exactly as many records as the header promises.
 */
function entryBlock(threadId: number, methodIds: readonly number[]): Uint8Array {
  const payload = new TraceWriter();
  let previous = 0n;
  for (let index = 0; index < EVENTS_PER_THREAD; index += 1) {
    // Two enters followed by two exits keeps a small live stack per thread.
    const step = index % 4;
    const action = step < 2 ? 0 : 1;
    const methodIndex = step < 2 ? step : 3 - step;
    const word = BigInt((methodIds[methodIndex] as number) * 4 + action);
    payload.sleb(word - previous);
    previous = word;
    payload.uleb(1000n);
    payload.uleb(500n);
  }
  const body = payload.bytes();
  // Packet type, thread id, u24 record count, u32 payload length.
  return new TraceWriter()
    .u8(2)
    .u32(threadId)
    .u8(EVENTS_PER_THREAD & 0xff)
    .u8((EVENTS_PER_THREAD >>> 8) & 0xff)
    .u8((EVENTS_PER_THREAD >>> 16) & 0xff)
    .u32(body.length)
    .raw(body)
    .bytes();
}

/** A trace shaped like a real recording: many methods, threads, and events. */
function syntheticTrace(): Uint8Array {
  const chunks: Uint8Array[] = [
    new TraceWriter().u32(TRACE_MAGIC).u16(5).u64(1_000_000n).raw(HEADER_PADDING).bytes(),
  ];
  for (let thread = 0; thread < THREAD_COUNT; thread += 1) {
    chunks.push(threadInfoPacket(thread, 'Thread-' + String(thread)));
  }
  for (let method = 0; method < METHOD_COUNT; method += 1) {
    chunks.push(methodInfoPacket(BigInt(method), 'Lcom/example/Class' + String(method) + '\tmethod' + String(method) + '\t()V\tClass' + String(method) + '.java'));
  }
  for (let thread = 0; thread < THREAD_COUNT; thread += 1) {
    const methodIds: number[] = [];
    for (let index = 0; index < 4; index += 1) {
      methodIds.push((thread * 7 + index * 13) % METHOD_COUNT);
    }
    chunks.push(entryBlock(thread, methodIds));
  }
  chunks.push(new TraceWriter().u8(3).u16(0).bytes());
  const total = chunks.reduce((sum, chunk) => sum + chunk.length, 0);
  const bytes = new Uint8Array(total);
  let offset = 0;
  chunks.forEach((chunk) => {
    bytes.set(chunk, offset);
    offset += chunk.length;
  });
  return bytes;
}

interface Measurement {
  readonly stage: string;
  readonly milliseconds: number;
}

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

describe.runIf(ENABLED)('ART trace performance baseline', () => {
  it('parses and projects a large synthetic trace', () => {
    const bytes = syntheticTrace();
    const results: Measurement[] = [];

    let events = 0;
    measureMedian('parse', () => {
      const parsed = parseArtTrace(bytes);
      if (!parsed.ok) throw new Error(parsed.error.message);
      events = parsed.value.events.length;
    }, results);
    expect(events).toBe(EVENT_COUNT);

    let stacks = 0;
    measureMedian('parseAndProject', () => {
      const parsed = parseArtTrace(bytes);
      if (!parsed.ok) throw new Error(parsed.error.message);
      stacks = toCallStackTable(parsed.value).stacks.length;
    }, results);
    expect(stacks).toBeGreaterThan(0);

    const report = {
      generatedAt: new Date().toISOString(),
      runtime: { node: process.version, platform: process.platform, arch: process.arch },
      input: {
        bytes: bytes.length,
        methods: METHOD_COUNT,
        threads: THREAD_COUNT,
        events: EVENT_COUNT,
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
  return join(GOLDEN_DIRECTORY, 'art-trace', 'benchmark.json');
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
