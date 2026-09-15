/**
 * Serializes normalized Simpleperf samples into the Gecko Profile v24 format
 * consumed by Firefox Profiler. This is the inverse of gecko.ts and mirrors
 * the Kotlin GeckoProfileWriter: samples retain their original leaf-first
 * representation, while Gecko stack prefixes are written root-to-leaf.
 */
import type { NormalizedSample, ProfileFrame } from './model.js';

const NANOS_PER_MILLISECOND = 1_000_000;
const USER_CATEGORY = 0;
const KERNEL_CATEGORY = 1;
const NATIVE_CATEGORY = 2;
const DEX_CATEGORY = 3;
const OAT_CATEGORY = 4;
const OFF_CPU_CATEGORY = 5;
const JIT_CATEGORY = 7;

export interface GeckoProfileExportResult {
  readonly json: string;
  readonly threadCount: number;
  readonly sampleCount: number;
}

interface GeckoFrame {
  readonly location: number;
  readonly category: number;
}

interface GeckoStack {
  readonly prefix: number | null;
  readonly frame: number;
}

interface GeckoSample {
  readonly stack: number | null;
  readonly time: number;
}

interface GeckoThread {
  readonly tid: number;
  readonly pid: number;
  readonly name: string;
  readonly markers: {
    readonly schema: Readonly<Record<string, number>>;
    readonly data: readonly unknown[];
  };
  readonly samples: {
    readonly schema: Readonly<Record<string, number>>;
    readonly data: readonly (readonly [number | null, number, number])[];
  };
  readonly frameTable: {
    readonly schema: Readonly<Record<string, number>>;
    readonly data: readonly (readonly [number, boolean, number, null, null, null, null, number, number])[];
  };
  readonly stackTable: {
    readonly schema: Readonly<Record<string, number>>;
    readonly data: readonly (readonly [number | null, number, number])[];
  };
  readonly stringTable: readonly string[];
  readonly registerTime: number;
  readonly unregisterTime: null;
  readonly processType: 'default';
}

interface MutableThread {
  readonly processId: number;
  readonly threadId: number;
  readonly threadName: string;
  readonly stringIds: Map<string, number>;
  readonly frameIds: Map<string, number>;
  readonly stackIds: Map<string, number>;
  readonly strings: string[];
  readonly frames: GeckoFrame[];
  readonly stacks: GeckoStack[];
  readonly samples: GeckoSample[];
}

/**
 * Builds a complete, plain JSON Gecko v24 profile. Gzip and file ownership are
 * intentionally main-process concerns; this package stays browser-safe.
 */
export function exportGeckoProfile(samples: readonly NormalizedSample[]): GeckoProfileExportResult {
  const threads = new Map<string, MutableThread>();
  for (const sample of samples) {
    const key = [sample.processId, sample.threadId, sample.threadName].join('\u0000');
    let thread = threads.get(key);
    if (thread === undefined) {
      thread = {
        processId: sample.processId,
        threadId: sample.threadId,
        threadName: sample.threadName,
        stringIds: new Map(),
        frameIds: new Map(),
        stackIds: new Map(),
        strings: [],
        frames: [],
        stacks: [],
        samples: [],
      };
      threads.set(key, thread);
    }
    thread.samples.push({
      stack: stackFor(thread, sample.frames),
      time: Number(sample.timestampNanos) / NANOS_PER_MILLISECOND,
    });
  }

  const profile = {
    meta: {
      interval: 1,
      processType: 0,
      product: 'Android Performance Studio',
      device: null,
      platform: null,
      stackwalk: 1,
      debug: 0,
      gcpoison: 0,
      asyncstack: 1,
      startTime: 0,
      shutdownTime: null,
      version: 24,
      presymbolicated: true,
      categories: [
        category('User', 'yellow'),
        category('Kernel', 'orange'),
        category('Native', 'yellow'),
        category('DEX', 'green'),
        category('OAT', 'green'),
        category('Off-CPU', 'blue'),
        category('Other', 'grey'),
        category('JIT', 'green'),
      ],
      markerSchema: [],
      abi: null,
      oscpu: null,
      appBuildID: null,
    },
    libs: [],
    threads: [...threads.values()].map(toGeckoThread),
    processes: [],
    pausedRanges: [],
  };

  return { json: JSON.stringify(profile), threadCount: threads.size, sampleCount: samples.length };
}

function category(name: string, color: string): { readonly name: string; readonly color: string; readonly subcategories: readonly string[] } {
  return { name, color, subcategories: ['Other'] };
}

function stackFor(thread: MutableThread, leafFirstFrames: readonly ProfileFrame[]): number | null {
  let prefix: number | null = null;
  for (const frame of [...leafFirstFrames].reverse()) {
    const frameId = frameFor(thread, frame);
    const key = String(prefix) + '\u0000' + String(frameId);
    let stackId = thread.stackIds.get(key);
    if (stackId === undefined) {
      stackId = thread.stacks.length;
      thread.stackIds.set(key, stackId);
      thread.stacks.push({ prefix, frame: frameId });
    }
    prefix = stackId;
  }
  return prefix;
}

function frameFor(thread: MutableThread, frame: ProfileFrame): number {
  const location = frame.symbolName + ' (in ' + frame.filePath + ')';
  let frameId = thread.frameIds.get(location);
  if (frameId !== undefined) return frameId;
  let locationId = thread.stringIds.get(location);
  if (locationId === undefined) {
    locationId = thread.strings.length;
    thread.stringIds.set(location, locationId);
    thread.strings.push(location);
  }
  frameId = thread.frames.length;
  thread.frameIds.set(location, frameId);
  thread.frames.push({ location: locationId, category: geckoCategory(frame) });
  return frameId;
}

function geckoCategory(frame: ProfileFrame): number {
  const location = frame.symbolName + ' (in ' + frame.filePath + ')';
  if (location.includes('kallsyms') || location.includes('.ko')) {
    return location.startsWith('__schedule ') ? OFF_CPU_CATEGORY : KERNEL_CATEGORY;
  }
  if (location.includes('.so')) return NATIVE_CATEGORY;
  if (location.includes('.vdex')) return DEX_CATEGORY;
  if (location.includes('.oat')) return OAT_CATEGORY;
  if (location.includes('[JIT app cache]')) return JIT_CATEGORY;
  return USER_CATEGORY;
}

function toGeckoThread(thread: MutableThread): GeckoThread {
  return {
    tid: thread.threadId,
    pid: thread.processId,
    name: thread.threadName,
    markers: {
      schema: { name: 0, startTime: 1, endTime: 2, phase: 3, category: 4, data: 5 },
      data: [],
    },
    samples: {
      schema: { stack: 0, time: 1, responsiveness: 2 },
      data: thread.samples.map((sample) => [sample.stack, sample.time, 0]),
    },
    frameTable: {
      schema: {
        location: 0,
        relevantForJS: 1,
        innerWindowID: 2,
        implementation: 3,
        optimizations: 4,
        line: 5,
        column: 6,
        category: 7,
        subcategory: 8,
      },
      data: thread.frames.map((frame) => [frame.location, false, 0, null, null, null, null, frame.category, 0]),
    },
    stackTable: {
      schema: { prefix: 0, frame: 1, category: 2 },
      data: thread.stacks.map((stack) => [stack.prefix, stack.frame, 0]),
    },
    stringTable: thread.strings,
    registerTime: 0,
    unregisterTime: null,
    processType: 'default',
  };
}
