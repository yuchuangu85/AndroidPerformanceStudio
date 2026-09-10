import type { ClassHistogramEntry, MemorySummary } from './histogram.js';
import { classHistogram, summarizeMemory } from './histogram.js';
import { buildObjectGraph } from './graph.js';
import { findLeakSuspects } from './leaks.js';
import type { HprofParseResult, Identifier } from './hprof.js';

/** Object ids are hex strings because a 64-bit id cannot survive JSON numbers. */
export interface MemoryLeakSuspect {
  readonly className: string;
  readonly objectId: string;
  readonly retainedBytes: number;
  readonly shallowBytes: number;
  readonly referenceChain: readonly string[];
}

export interface MemorySession {
  readonly id: string;
  readonly deviceSerial?: string;
  readonly packageName?: string;
  readonly capturedAtEpochMillis: number;
  readonly summary: MemorySummary;
  readonly histogram: readonly ClassHistogramEntry[];
  readonly suspects: readonly MemoryLeakSuspect[];
  readonly warnings: readonly string[];
}

function toHex(id: Identifier): string {
  return '0x' + id.toString(16);
}

/** Builds the persisted session: a histogram plus ranked leak suspects. */
export function createMemorySession(
  result: HprofParseResult,
  options: {
    readonly id: string;
    readonly capturedAtEpochMillis: number;
    readonly deviceSerial?: string;
    readonly packageName?: string;
    readonly histogramLimit?: number;
    readonly suspectLimit?: number;
  },
): MemorySession {
  const graph = buildObjectGraph(result);
  const report = findLeakSuspects(graph, { top: options.suspectLimit ?? 20 });
  return {
    id: options.id,
    ...(options.deviceSerial !== undefined ? { deviceSerial: options.deviceSerial } : {}),
    ...(options.packageName !== undefined ? { packageName: options.packageName } : {}),
    capturedAtEpochMillis: options.capturedAtEpochMillis,
    summary: summarizeMemory(result),
    histogram: classHistogram(result).slice(0, options.histogramLimit ?? 50),
    suspects: report.suspects.map((suspect) => ({
      className: suspect.className,
      objectId: toHex(suspect.objectId),
      retainedBytes: suspect.retainedBytes,
      shallowBytes: suspect.shallowBytes,
      referenceChain: suspect.referenceChain.map(toHex),
    })),
    warnings: [...result.warnings, ...graph.warnings],
  };
}
