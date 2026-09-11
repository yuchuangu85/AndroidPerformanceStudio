import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { buildObjectGraph } from './graph.js';
import { classHistogram, groupInstancesByClass, summarizeMemory } from './histogram.js';
import { parseHprof } from './hprof.js';
import { computeDominators, reachableFromRoots } from './dominators.js';
import { computeDominatorsReference } from './dominators-reference.js';
import { findLeakSuspects } from './leaks.js';
import { createMemorySession } from './session.js';

/**
 * Performance baseline for the HPROF pipeline. Disabled by default so the unit
 * suite stays fast; run it with APS_PERF=1:
 *
 *   APS_PERF=1 pnpm --filter @aps/memory-profiler test src/perf.test.ts
 *
 * Set APS_PERF_OUT to also write the numbers as JSON, which is what a future
 * comparison against the JVM implementation consumes.
 */
const ENABLED = process.env['APS_PERF'] === '1';
const OUTPUT = process.env['APS_PERF_OUT'];
const GOLDEN_DIRECTORY = process.env['APS_PERF_OUT'] === undefined ? undefined : process.env['APS_GOLDEN_DIR'];

/** The JVM baseline the CI job writes next to the HPROF corpus. */
function jvmBaselinePath(): string | undefined {
  const explicit = process.env['APS_JVM_BASELINE'];
  if (explicit !== undefined && explicit.length > 0) return explicit;
  if (GOLDEN_DIRECTORY === undefined || GOLDEN_DIRECTORY.length === 0) return undefined;
  return join(GOLDEN_DIRECTORY, 'hprof', 'benchmark.json');
}

/**
 * D2 gate: the TypeScript parse and class aggregation must stay within 1.5x of
 * the JVM implementation on this machine, measured back to back in one CI job.
 *
 * Both stages are shape-independent: the two synthetic shapes share an identical
 * instance graph and differ only in where the reference chains end, which neither
 * parsing nor grouping looks at. Comparing them per shape adds noise rather than
 * signal — the JVM baseline for identical parse work has been seen to differ by
 * 44% between the two shapes on one runner. These stages are compared on the total
 * across shapes, and the per-shape split is printed so the numbers stay readable.
 */
const GATE_RATIO = 1.5;
const GATED_STAGES: readonly string[] = ['parseHprof', 'classSumAggregation'];

interface JvmBenchmark {
  readonly cases: readonly {
    readonly shape: string;
    readonly measurements: readonly { readonly stage: string; readonly milliseconds: number }[];
  }[];
}

function compareWithJvm(report: { readonly cases: readonly ShapeCase[] }, baselinePath: string): void {
  let jvm: JvmBenchmark;
  try {
    jvm = JSON.parse(readFileSync(baselinePath, 'utf8')) as JvmBenchmark;
  } catch (error) {
    // A baseline path only exists when the caller asked for a comparison, so an
    // unreadable file is a broken gate rather than a reason to skip the check.
    throw new Error('JVM baseline unreadable at ' + baselinePath, { cause: error });
  }
  const failures: string[] = [];
  console.log('stage comparison (TypeScript / JVM, total across shapes):');
  const missingShape = report.cases.some(
    (shapeCase) => jvm.cases.find((entry) => entry.shape === shapeCase.shape) === undefined,
  );
  if (missingShape) failures.push('JVM baseline is missing one of the shapes');

  for (const stage of GATED_STAGES) {
    let tsTotal = 0;
    let jvmTotal = 0;
    let measured = true;
    const split: string[] = [];
    for (const shapeCase of report.cases) {
      const jvmCase = jvm.cases.find((entry) => entry.shape === shapeCase.shape);
      const ts = shapeCase.measurements.find((entry) => entry.stage === stage)?.milliseconds;
      const jvmValue = jvmCase?.measurements.find((entry) => entry.stage === stage)?.milliseconds;
      if (ts === undefined || jvmValue === undefined) {
        failures.push(stage + ': measurement missing for ' + shapeCase.shape);
        measured = false;
        continue;
      }
      tsTotal += ts;
      jvmTotal += jvmValue;
      split.push(shapeCase.shape + ' ' + ts.toFixed(1) + ' ms vs JVM ' + jvmValue.toFixed(1) + ' ms');
    }
    if (!measured) continue;
    const ratio = tsTotal / jvmTotal;
    console.log(
      '  ' +
        stage +
        ': ' +
        tsTotal.toFixed(1) +
        ' ms vs JVM ' +
        jvmTotal.toFixed(1) +
        ' ms = ' +
        ratio.toFixed(2) +
        'x  (' +
        split.join('; ') +
        ')',
    );
    if (ratio > GATE_RATIO) {
      failures.push(
        stage + ' is ' + ratio.toFixed(2) + 'x the JVM time (gate ' + GATE_RATIO + 'x)',
      );
    }
  }
  expect(failures).toEqual([]);
}

interface SyntheticHeap {
  readonly bytes: Uint8Array;
  readonly classCount: number;
  readonly instanceCount: number;
  readonly objectArrayCount: number;
  readonly primitiveArrayCount: number;
  readonly rootCount: number;
  readonly garbageCount: number;
}

const ID_SIZE = 4;
const HEAP_NAME_ID = 1n;
const FIRST_CLASS_NAME_ID = 2n;

class Writer {
  private readonly chunks: Buffer[] = [];

  u1(value: number): this {
    this.chunks.push(Buffer.from([value & 0xff]));
    return this;
  }

  u2(value: number): this {
    const buffer = Buffer.alloc(2);
    buffer.writeUInt16BE(value, 0);
    this.chunks.push(buffer);
    return this;
  }

  u4(value: number): this {
    const buffer = Buffer.alloc(4);
    buffer.writeUInt32BE(value >>> 0, 0);
    this.chunks.push(buffer);
    return this;
  }

  u8(value: bigint): this {
    const buffer = Buffer.alloc(8);
    buffer.writeBigUInt64BE(BigInt.asUintN(64, value), 0);
    this.chunks.push(buffer);
    return this;
  }

  id(value: bigint): this {
    return this.u4(Number(BigInt.asUintN(32, value)));
  }

  raw(value: Buffer): this {
    this.chunks.push(value);
    return this;
  }

  bytes(): Buffer {
    return Buffer.concat(this.chunks);
  }

  get length(): number {
    return this.chunks.reduce((total, chunk) => total + chunk.length, 0);
  }
}

function record(tag: number, body: Buffer): Buffer {
  const head = Buffer.alloc(9);
  head.writeUInt8(tag, 0);
  head.writeUInt32BE(0, 1);
  head.writeUInt32BE(body.length, 5);
  return Buffer.concat([head, body]);
}

/**
 * Builds a heap dump with the shape of a real Android app: many classes, far
 * more instances, object references linking them into deep chains, object and
 * primitive arrays, one root per class, and an unreachable tail as garbage.
 */
function syntheticHeap(options: {
  readonly classCount: number;
  readonly instancesPerClass: number;
  readonly objectArrays: number;
  readonly primitiveArrays: number;
  /** When true each class's reference chain wraps back to its first instance. */
  readonly wrapChains: boolean;
}): SyntheticHeap {
  const { classCount, instancesPerClass, objectArrays, primitiveArrays, wrapChains } = options;
  const garbagePerClass = Math.max(Math.floor(instancesPerClass / 100), 1);
  const instanceSize = 2 * ID_SIZE + 4;
  const records: Buffer[] = [];

  records.push(
    Buffer.concat([
      Buffer.from('JAVA PROFILE 1.0.3', 'utf8'),
      Buffer.from([0]),
      new Writer().u4(ID_SIZE).u8(0n).bytes(),
    ]),
  );

  const stringRecord = (id: bigint, value: string): Buffer =>
    record(0x01, Buffer.concat([new Writer().id(id).bytes(), Buffer.from(value, 'utf8')]));
  const loadClassRecord = (serial: number, classId: bigint, nameId: bigint): Buffer =>
    record(0x02, new Writer().u4(serial).id(classId).u4(0).id(nameId).bytes());

  records.push(stringRecord(HEAP_NAME_ID, 'app heap'));
  for (let index = 0; index < classCount; index += 1) {
    records.push(stringRecord(FIRST_CLASS_NAME_ID + BigInt(index), 'com.example.Class' + String(index)));
  }
  // Field-name strings are shared by every class layout.
  const fieldNameIds = [0x500n, 0x501n, 0x502n];
  records.push(stringRecord(fieldNameIds[0] as bigint, 'next'));
  records.push(stringRecord(fieldNameIds[1] as bigint, 'skip'));
  records.push(stringRecord(fieldNameIds[2] as bigint, 'count'));

  const classIdOf = (index: number): bigint => 0x1000n + BigInt(index);
  const instanceIdOf = (classIndex: number, instanceIndex: number): bigint =>
    0x100000n + BigInt(classIndex) * BigInt(instancesPerClass + garbagePerClass) + BigInt(instanceIndex);

  for (let index = 0; index < classCount; index += 1) {
    records.push(loadClassRecord(index, classIdOf(index), FIRST_CLASS_NAME_ID + BigInt(index)));
  }

  const segment = new Writer();
  for (let index = 0; index < classCount; index += 1) {
    segment.u1(0x20).id(classIdOf(index)).u4(0);
    segment.id(0n).id(0n).id(0n).id(0n).id(0n).id(0n); // super, loader, signers, domain, reserved
    segment.u4(instanceSize);
    segment.u2(0); // constant pool entries
    segment.u2(0); // static fields
    segment.u2(3); // instance fields
    segment.id(fieldNameIds[0] as bigint).u1(2);
    segment.id(fieldNameIds[1] as bigint).u1(2);
    segment.id(fieldNameIds[2] as bigint).u1(10);
  }

  // One GC root per class; the reference chain makes the rest of the class live.
  for (let index = 0; index < classCount; index += 1) {
    segment.u1(0x01).id(instanceIdOf(index, 0)).id(0x700000n + BigInt(index));
  }

  for (let classIndex = 0; classIndex < classCount; classIndex += 1) {
    for (let instanceIndex = 0; instanceIndex < instancesPerClass; instanceIndex += 1) {
      segment.u1(0x21).id(instanceIdOf(classIndex, instanceIndex)).u4(0).id(classIdOf(classIndex));
      segment.u4(instanceSize);
      // A deep chain, but it ends: wrapping the last instance back to the first
      // would create a 2000-long cycle and inflate the dominator fixpoint far
      // beyond any real object graph. The cycle variant is measured separately.
      const next = instanceIndex + 1 < instancesPerClass || wrapChains
        ? instanceIdOf(classIndex, (instanceIndex + 1) % instancesPerClass)
        : 0n;
      const skip = instanceIndex + 7 < instancesPerClass || wrapChains
        ? instanceIdOf(classIndex, (instanceIndex + 7) % instancesPerClass)
        : 0n;
      segment.id(next).id(skip);
      segment.u4(instanceIndex);
    }
    // Allocated but referenced by nobody: the unreachable tail.
    for (let garbage = 0; garbage < garbagePerClass; garbage += 1) {
      segment.u1(0x21).id(instanceIdOf(classIndex, instancesPerClass + garbage)).u4(0).id(classIdOf(classIndex));
      segment.u4(instanceSize).id(0n).id(0n).u4(0);
    }
  }

  let arrayId = 0x900000n;
  for (let index = 0; index < objectArrays; index += 1) {
    const elements = 16;
    segment.u1(0x22).id(arrayId).u4(0).u4(elements).id(classIdOf(index % classCount));
    for (let element = 0; element < elements; element += 1) {
      segment.id(instanceIdOf(index % classCount, element % instancesPerClass));
    }
    arrayId += 1n;
  }
  for (let index = 0; index < primitiveArrays; index += 1) {
    const elements = 256;
    segment.u1(0x23).id(arrayId).u4(0).u4(elements).u1(10);
    for (let element = 0; element < elements; element += 1) segment.u4(element);
    arrayId += 1n;
  }
  segment.u1(0x2c);

  const heapBody = segment.bytes();
  const segmentHead = Buffer.alloc(9);
  segmentHead.writeUInt8(0x1c, 0);
  segmentHead.writeUInt32BE(0, 1);
  segmentHead.writeUInt32BE(heapBody.length, 5);

  const total = records.reduce((sum, part) => sum + part.length, 0) + 9 + heapBody.length;
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const part of records) {
    bytes.set(part, offset);
    offset += part.length;
  }
  bytes.set(segmentHead, offset);
  offset += 9;
  bytes.set(heapBody, offset);

  return {
    bytes,
    classCount,
    instanceCount: classCount * instancesPerClass,
    objectArrayCount: objectArrays,
    primitiveArrayCount: primitiveArrays,
    rootCount: classCount,
    garbageCount: classCount * garbagePerClass,
  };
}

interface Measurement {
  readonly stage: string;
  readonly milliseconds: number;
}

/** Warmup rounds before a measured stage, matching the JVM benchmark. */
/**
 * Three warm-ups then five measured rounds, matching HprofJvmBenchmarkTest. A
 * median of three is a single sample, which makes the ratio move with the runner
 * instead of with the code.
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
function measure(stage: string, run: () => void, results: Measurement[]): number {
  const start = performance.now();
  run();
  const elapsed = performance.now() - start;
  results.push({ stage, milliseconds: Math.round(elapsed * 10) / 10 });
  return elapsed;
}

interface ShapeCase {
  readonly shape: string;
  readonly heap: {
    readonly dumpBytes: number;
    readonly classes: number;
    readonly instances: number;
    readonly objectArrays: number;
    readonly primitiveArrays: number;
    readonly roots: number;
    readonly unreachableObjects: number;
    readonly graphNodes: number;
  };
  readonly measurements: readonly Measurement[];
}

// Kept so the aggregation work cannot be optimized away.
let sink = 0;
void sink;

/** Both benchmarks repeat the aggregation this many times per measured round. */
const AGGREGATION_ITERATIONS = 20;

function measureShape(shape: string, wrapChains: boolean): ShapeCase {
  const heap = syntheticHeap({
    classCount: 100,
    instancesPerClass: 2000,
    objectArrays: 1000,
    primitiveArrays: 2000,
    wrapChains,
  });
  const results: Measurement[] = [];
  // Warm up once so the numbers reflect steady state, not first-call work.
  parseHprof(heap.bytes);
  let parsed = parseHprof(heap.bytes);
  measureMedian('parseHprof', () => {
    parsed = parseHprof(heap.bytes);
  }, results);

  expect(parsed.instances).toHaveLength(heap.instanceCount + heap.garbageCount);
  expect(parsed.classes.size).toBe(heap.classCount);
  expect(parsed.arrays).toHaveLength(heap.objectArrayCount + heap.primitiveArrayCount);
  expect(parsed.roots.length).toBe(heap.rootCount);

  measure('classHistogram', () => void classHistogram(parsed), results);
  // The same grouping work without name resolution, so it can be compared with
  // the JVM benchmark's classSumAggregation stage.
  // Repeated so the stage is long enough to compare: a single pass is a few
  // milliseconds and would be decided by JIT and GC noise. The JVM benchmark
  // repeats it the same number of times.
  measureMedian('classSumAggregation', () => {
    for (let round = 0; round < AGGREGATION_ITERATIONS; round += 1) {
      sink = groupInstancesByClass(parsed.instances).length;
    }
  }, results);
  measure('summarizeMemory', () => void summarizeMemory(parsed), results);

  let graph = buildObjectGraph(parsed);
  measure('buildObjectGraph', () => {
    graph = buildObjectGraph(parsed);
  }, results);

  measure('reachableFromRoots', () => void reachableFromRoots(graph), results);
  measure('computeDominators', () => void computeDominators(graph), results);
  // The iterative fixpoint this replaced, measured in the same run: cross-run
  // numbers on shared machines are not comparable.
  measure('computeDominatorsReference', () => void computeDominatorsReference(graph), results);
  measure('findLeakSuspects', () => void findLeakSuspects(graph, { top: 20 }), results);

  // A session is what the UI actually builds: graph, dominators, leaks, history.
  let session = createMemorySession(parsed, { id: 'perf', capturedAtEpochMillis: 0, histogramLimit: 50 });
  measure('createMemorySession', () => {
    session = createMemorySession(parsed, { id: 'perf', capturedAtEpochMillis: 0, histogramLimit: 50 });
  }, results);

  // The pre-optimization path, as an A/B control in the same run: the leak
  // ranking walked reachability a second time because the caller had no way to
  // pass its analysis in. The saving is one traversal, not a whole dominator
  // pass, so expect a few percent rather than a multiple.
  measure('sessionBeforeReachabilityReuse', () => {
    const graphBeforeReuse = buildObjectGraph(parsed);
    findLeakSuspects(graphBeforeReuse, { top: 20 });
    sink = classHistogram(parsed).length;
  }, results);
  expect(session.histogram.length).toBeGreaterThan(0);
  expect(session.summary.instanceCount).toBe(heap.instanceCount + heap.garbageCount);

  return {
    shape,
    heap: {
      dumpBytes: heap.bytes.length,
      classes: heap.classCount,
      instances: heap.instanceCount,
      objectArrays: heap.objectArrayCount,
      primitiveArrays: heap.primitiveArrayCount,
      roots: heap.rootCount,
      unreachableObjects: heap.garbageCount,
      graphNodes: graph.nodes.size,
    },
    measurements: results,
  };
}

describe.runIf(ENABLED)('HPROF performance baseline', () => {
  it('parses and analyses a large synthetic dump', () => {
    // Two shapes over the same node count: a heap whose reference chains end,
    // and one whose chains wrap into long cycles. The second is the worst case
    // for the dominator fixpoint and is kept as an explicit data point.
    const cases = [measureShape('chain', false), measureShape('cyclic', true)];
    const report = {
      generatedAt: new Date().toISOString(),
      runtime: { node: process.version, platform: process.platform, arch: process.arch },
      parseThroughputInstancesPerSecond: Math.round(
        (cases[0]?.heap.instances ?? 0) / ((cases[0]?.measurements[0]?.milliseconds ?? 1) / 1000),
      ),
      cases,
    };
    console.log(JSON.stringify(report, null, 2));
    if (OUTPUT !== undefined && OUTPUT.length > 0) {
      writeFileSync(OUTPUT, JSON.stringify(report, null, 2));
    }
    const baseline = jvmBaselinePath();
    if (baseline !== undefined) compareWithJvm(report, baseline);
  }, 900_000);
});