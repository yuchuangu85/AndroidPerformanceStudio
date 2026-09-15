import { describe, expect, it } from 'vitest';
import type { HeapDiffEntry } from '@aps/memory-profiler';
import type { MemorySessionSummary } from '../../shared/ipc';
import { defaultHeapDiffBaselineId, VISIBLE_HEAP_DIFF_ENTRY_LIMIT, visibleHeapDiffEntries } from './memory-heap-diff';

function summary(id: string, capturedAtEpochMillis: number): MemorySessionSummary {
  return {
    id, capturedAtEpochMillis, instanceCount: 0, classCount: 0, shallowBytes: 0, suspectCount: 0, warningCount: 0,
  };
}

function entry(index: number): HeapDiffEntry {
  return {
    className: 'Class' + String(index), beforeCount: 0, afterCount: index, countDelta: index,
    beforeShallowBytes: 0, afterShallowBytes: index, shallowBytesDelta: index, matchedBy: 'CLASS_NAME',
  };
}

describe('memory heap-diff presentation helpers', () => {
  it('selects the previous capture chronologically as the baseline', () => {
    const sessions = [summary('oldest', 100), summary('newest', 300), summary('middle', 200)];
    expect(defaultHeapDiffBaselineId(sessions, 'newest')).toBe('middle');
    expect(defaultHeapDiffBaselineId(sessions, 'middle')).toBe('oldest');
    expect(defaultHeapDiffBaselineId(sessions, 'oldest')).toBeUndefined();
    expect(defaultHeapDiffBaselineId(sessions, 'missing')).toBeUndefined();
  });

  it('keeps the visible output bounded like the Kotlin heap-diff section', () => {
    const entries = Array.from({ length: VISIBLE_HEAP_DIFF_ENTRY_LIMIT + 2 }, (_unused, index) => entry(index));
    expect(visibleHeapDiffEntries(entries)).toEqual(entries.slice(0, VISIBLE_HEAP_DIFF_ENTRY_LIMIT));
  });
});
