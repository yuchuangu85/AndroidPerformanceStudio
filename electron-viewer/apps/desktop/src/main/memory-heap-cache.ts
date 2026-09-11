/**
 * Parsed heap dumps kept in memory, because a session file holds only the
 * reports. Browsing instances needs the heap itself, so the parse is retained
 * for the sessions the user just created or opened.
 *
 * The raw dump is still not persisted: it is reproducible evidence and can be
 * gigabytes. That means instance browsing is a property of this run, which the
 * panel states rather than pretending otherwise.
 */
import { analyzeGraph, buildObjectGraph, type GraphAnalysis, type HprofParseResult, type ObjectGraph } from '@aps/memory-profiler';

export interface CachedHeap {
  readonly result: HprofParseResult;
  readonly graph: ObjectGraph;
  readonly analysis: GraphAnalysis;
}

/** Three heaps is more than a reviewer compares at once and bounds the memory. */
export const HEAP_CACHE_LIMIT = 3;

const heaps = new Map<string, CachedHeap>();

export function cacheHeap(sessionId: string, result: HprofParseResult): CachedHeap {
  // One graph build and one dominator pass serve every later query.
  const graph = buildObjectGraph(result);
  const entry: CachedHeap = { result, graph, analysis: analyzeGraph(graph) };
  heaps.delete(sessionId);
  heaps.set(sessionId, entry);
  while (heaps.size > HEAP_CACHE_LIMIT) {
    const oldest = heaps.keys().next().value;
    if (oldest === undefined) break;
    heaps.delete(oldest);
  }
  return entry;
}

export function cachedHeap(sessionId: string): CachedHeap | undefined {
  return heaps.get(sessionId);
}

export function forgetHeap(sessionId: string): void {
  heaps.delete(sessionId);
}

export function clearHeapCache(): void {
  heaps.clear();
}
