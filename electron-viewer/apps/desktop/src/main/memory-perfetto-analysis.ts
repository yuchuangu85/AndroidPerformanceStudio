/**
 * Port of MemoryPerfettoAnalysis.kt: the summary is read through the pinned
 * Trace Processor, which resolves callsites to symbols and knows the heap graph
 * tables, and only falls back to reading the trace bytes when that is
 * unavailable. The fallback is never silent: the result carries which path
 * produced it and why the other one did not.
 */
import { fail, ok, type StudioResult } from '@aps/contracts';
import {
  analyzeJavaHeapTrace,
  analyzeNativeHeapTrace,
  heapGraphToHprofResult,
  parseJavaHeapTrace,
  parseNativeHeapTraceStrict,
  type HeapGraphData,
  type NativeHeapAnalysis,
} from '@aps/memory-profiler/node';
import { buildObjectGraph, analyzeGraph, analyzeHeapDeeply, type MemoryDeepReports } from '@aps/memory-profiler';
import type { TraceQueryRunner } from '@aps/memory-profiler/node';

export type MemoryEvidenceSource = 'TRACE_PROCESSOR' | 'WIRE_FALLBACK';

export interface NativeHeapArtifactAnalysis {
  readonly analysis: NativeHeapAnalysis;
  readonly evidenceSource: MemoryEvidenceSource;
  /** Set only when the wire parse produced the result. */
  readonly fallbackReason?: string;
  readonly availableCapabilities: readonly string[];
}

export interface JavaHeapArtifactAnalysis {
  readonly graph: HeapGraphData;
  readonly deep: MemoryDeepReports;
  readonly evidenceSource: MemoryEvidenceSource;
  readonly fallbackReason?: string;
  readonly availableCapabilities: readonly string[];
}

/**
 * Trace Processor first, wire second. A processor failure is reported in
 * fallbackReason rather than raised, because the raw trace is still readable
 * and a partial summary beats none — but the caller always learns which one it
 * got.
 */
export async function analyzeNativeHeapArtifact(
  runner: TraceQueryRunner | undefined,
  bytes: Uint8Array,
): Promise<StudioResult<NativeHeapArtifactAnalysis>> {
  if (runner !== undefined) {
    const processed = await analyzeNativeHeapTrace(runner);
    if (processed.ok) {
      return ok({
        analysis: processed.value.analysis,
        evidenceSource: 'TRACE_PROCESSOR',
        availableCapabilities: processed.value.availableCapabilities,
      });
    }
    const processedFailure = processed.error.code + ': ' + processed.error.message;
    return wireNativeHeap(bytes, processedFailure);
  }
  return wireNativeHeap(bytes, 'Trace Processor is unavailable');
}

function wireNativeHeap(
  bytes: Uint8Array,
  fallbackReason: string,
): StudioResult<NativeHeapArtifactAnalysis> {
  try {
    return ok({
      analysis: parseNativeHeapTraceStrict(bytes),
      evidenceSource: 'WIRE_FALLBACK',
      fallbackReason,
      // The wire path has no symbolization and no per-callsite frame names.
      availableCapabilities: [],
    });
  } catch (error) {
    return fail(
      'DATA_VALIDATION',
      'NATIVE_HEAP_UNREADABLE',
      fallbackReason + '; the wire parse also failed: ' + (error instanceof Error ? error.message : ''),
    );
  }
}

/** Same shape for the Java heap graph, with the deep reports built from it. */
export async function analyzeJavaHeapArtifact(
  runner: TraceQueryRunner | undefined,
  bytes: Uint8Array,
): Promise<StudioResult<JavaHeapArtifactAnalysis>> {
  let graph: HeapGraphData | undefined;
  let source: MemoryEvidenceSource = 'TRACE_PROCESSOR';
  let fallbackReason: string | undefined;
  let capabilities: readonly string[] = [];
  if (runner !== undefined) {
    const processed = await analyzeJavaHeapTrace(runner);
    if (processed.ok) {
      graph = processed.value.graph;
      capabilities = processed.value.availableCapabilities;
    } else {
      fallbackReason = processed.error.code + ': ' + processed.error.message;
    }
  } else {
    fallbackReason = 'Trace Processor is unavailable';
  }
  if (graph === undefined) {
    const parsed = parseJavaHeapTrace(bytes);
    if (!parsed.ok) {
      return fail(
        'DATA_VALIDATION',
        'JAVA_HEAP_UNREADABLE',
        (fallbackReason ?? '') + '; the wire parse also failed: ' + parsed.error,
      );
    }
    graph = parsed.graph;
    source = 'WIRE_FALLBACK';
  }
  const result = heapGraphToHprofResult(graph);
  const objectGraph = buildObjectGraph(result);
  const deep = analyzeHeapDeeply(result, objectGraph, { analysis: analyzeGraph(objectGraph) });
  return ok({
    graph,
    deep,
    evidenceSource: source,
    ...(fallbackReason !== undefined ? { fallbackReason } : {}),
    availableCapabilities: capabilities,
  });
}

/** The ArtifactKind values the memory captures produce. */
export const MEMORY_ARTIFACT_KINDS = {
  BITMAP_HPROF: 'memory.bitmap-hprof',
  NATIVE_HEAP_TRACE: 'memory.native-heap-trace',
  JAVA_HEAP_TRACE: 'memory.java-heap-trace',
} as const;
