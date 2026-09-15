import type { HeapDiffEntry } from '@aps/memory-profiler';
import type { MemorySessionSummary } from '../../shared/ipc';

/** Keep the compact Kotlin-equivalent heap-diff section scannable. */
export const VISIBLE_HEAP_DIFF_ENTRY_LIMIT = 10;

/**
 * The selected session is the "after" side. Choose the capture immediately
 * before it when chronological history exists; an old first capture has no
 * truthful baseline, rather than silently reversing the comparison.
 */
export function defaultHeapDiffBaselineId(
  sessions: readonly MemorySessionSummary[],
  afterSessionId: string,
): string | undefined {
  const chronological = [...sessions].sort(
    (left, right) =>
      right.capturedAtEpochMillis - left.capturedAtEpochMillis || right.id.localeCompare(left.id),
  );
  const afterIndex = chronological.findIndex((session) => session.id === afterSessionId);
  return afterIndex >= 0 ? chronological[afterIndex + 1]?.id : undefined;
}

export function visibleHeapDiffEntries(entries: readonly HeapDiffEntry[]): readonly HeapDiffEntry[] {
  return entries.slice(0, VISIBLE_HEAP_DIFF_ENTRY_LIMIT);
}
