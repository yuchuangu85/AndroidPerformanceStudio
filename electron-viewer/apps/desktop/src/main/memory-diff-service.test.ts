import { describe, expect, it } from 'vitest';
import type { MemorySession } from '@aps/memory-profiler';
import { compareMemorySessions } from './memory-diff-service.js';

function session(
  id: string,
  histogram: MemorySession['histogram'],
  includeComparisonHistogram: boolean = true,
): MemorySession {
  return {
    id,
    capturedAtEpochMillis: 1,
    summary: {
      version: '1.0.3', identifierSize: 4, classCount: histogram.length, instanceCount: 0, arrayCount: 0, shallowBytes: 0,
    },
    histogram,
    ...(includeComparisonHistogram ? { comparisonHistogram: histogram } : {}),
    suspects: [],
    warnings: [],
  };
}

function store(...sessions: readonly MemorySession[]) {
  const byId = new Map(sessions.map((entry) => [entry.id, entry]));
  return { load: async (id: string) => byId.get(id) };
}

describe('compareMemorySessions', () => {
  it('uses the domain histogram diff for added, removed, and changed classes', async () => {
    const outcome = await compareMemorySessions(
      store(
        session('before', [
          { className: 'Removed', instanceCount: 5, shallowBytes: 50 },
          { className: 'Changed', instanceCount: 2, shallowBytes: 20 },
        ]),
        session('after', [
          { className: 'Added', instanceCount: 7, shallowBytes: 70 },
          { className: 'Changed', instanceCount: 4, shallowBytes: 40 },
        ]),
      ),
      { beforeSessionId: 'before', afterSessionId: 'after' },
    );

    expect(outcome).toMatchObject({ ok: true, comparisonComplete: true });
    if (!outcome.ok || outcome.diff === undefined) return;
    expect(outcome.diff.added).toEqual([
      expect.objectContaining({ className: 'Added', beforeCount: 0, afterCount: 7, countDelta: 7, shallowBytesDelta: 70 }),
    ]);
    expect(outcome.diff.removed).toEqual([
      expect.objectContaining({ className: 'Removed', beforeCount: 5, afterCount: 0, countDelta: -5, shallowBytesDelta: -50 }),
    ]);
    expect(outcome.diff.changed).toEqual([
      expect.objectContaining({ className: 'Changed', beforeCount: 2, afterCount: 4, countDelta: 2, shallowBytesDelta: 20 }),
    ]);
  });

  it('falls back to an old display histogram but labels the result as partial', async () => {
    const old = session('old', [{ className: 'Old', instanceCount: 2, shallowBytes: 20 }], false);
    const current = session('current', [{ className: 'Current', instanceCount: 3, shallowBytes: 30 }]);
    const outcome = await compareMemorySessions(store(old, current), {
      beforeSessionId: 'old', afterSessionId: 'current',
    });

    expect(outcome).toMatchObject({ ok: true, comparisonComplete: false });
    if (!outcome.ok || outcome.diff === undefined) return;
    expect(outcome.diff.entries.map((entry) => entry.className)).toEqual(['Current', 'Old']);
  });

  it('rejects equal and missing session ids without returning a diff', async () => {
    const records = store(session('present', []));
    await expect(compareMemorySessions(records, { beforeSessionId: 'present', afterSessionId: 'present' })).resolves.toEqual({
      ok: false,
      error: 'Choose two different memory sessions to compare.',
    });
    await expect(compareMemorySessions(records, { beforeSessionId: 'missing', afterSessionId: 'present' })).resolves.toEqual({
      ok: false,
      error: 'The baseline memory session is no longer available.',
    });
  });
});
