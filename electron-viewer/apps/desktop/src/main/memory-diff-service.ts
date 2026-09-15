import { diffHistograms, type MemorySession } from '@aps/memory-profiler';
import type { MemoryDiffOutcome, MemoryDiffRequest } from '../shared/ipc.js';

/** The small store seam keeps comparison independent of the in-memory heap cache. */
export interface MemoryDiffSessionStore {
  load(id: string): Promise<MemorySession | undefined>;
}

/**
 * Compares two persisted heap-session histograms. New sessions retain a full
 * comparison histogram; legacy records transparently use the displayed top-N
 * histogram and report that their outcome is incomplete.
 */
export async function compareMemorySessions(
  store: MemoryDiffSessionStore,
  request: MemoryDiffRequest,
): Promise<MemoryDiffOutcome> {
  if (request.beforeSessionId === request.afterSessionId) {
    return { ok: false, error: 'Choose two different memory sessions to compare.' };
  }
  const [before, after] = await Promise.all([
    store.load(request.beforeSessionId),
    store.load(request.afterSessionId),
  ]);
  if (before === undefined) return { ok: false, error: 'The baseline memory session is no longer available.' };
  if (after === undefined) return { ok: false, error: 'The current memory session is no longer available.' };

  const comparisonComplete = before.comparisonHistogram !== undefined && after.comparisonHistogram !== undefined;
  return {
    ok: true,
    diff: diffHistograms(
      before.comparisonHistogram ?? before.histogram,
      after.comparisonHistogram ?? after.histogram,
      request.matchMode,
    ),
    comparisonComplete,
  };
}
