import type { ClassHistogramEntry, MemorySummary } from './histogram.js';
import { classHistogram, summarizeMemory } from './histogram.js';
import { analyzeGraph } from './dominators.js';
import {
  analyzeHeapDeeply,
  type ActivityLeakEntry,
  type BitmapInstanceStats,
  type DeepLeakSuspect,
} from './deep-analysis.js';
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

/** The reports that need field names, which only the deep pass pays for. */
export interface MemoryDeepReports {
  readonly suspects: readonly DeepLeakSuspect[];
  readonly activityLeaks: readonly ActivityLeakEntry[];
  readonly bitmaps: readonly BitmapInstanceStats[];
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
  readonly deep?: MemoryDeepReports;
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
    /**
     * Adds the field-aware reports. On by default because the panel shows them;
     * a caller that only needs sizes can turn it off and skip the extra walk.
     */
    readonly deep?: boolean;
  },
): MemorySession {
  const graph = buildObjectGraph(result);
  // One analysis for the whole session: the leak ranking, the deep reports, and
  // the bitmap list all reuse it instead of recomputing dominators.
  const analysis = analyzeGraph(graph);
  const report = findLeakSuspects(graph, { top: options.suspectLimit ?? 20, analysis });
  const deep = options.deep === false ? undefined : analyzeHeapDeeply(result, graph, { analysis });
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
    ...(deep !== undefined ? { deep } : {}),
  };
}
