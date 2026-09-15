import { describe, expect, it } from 'vitest';
import { MAX_METHOD_TRACE_BYTES } from '@aps/art-trace';
import { classicTrace, streamingTrace } from '@aps/art-trace/testing';
import { importMethodTraceFile, type MethodTraceFileImportDependencies } from './method-import-service.js';
import { parseMethodTraceDirect } from './method-capture-service.js';

function dependencies(overrides: Partial<MethodTraceFileImportDependencies> = {}): MethodTraceFileImportDependencies {
  const trace = streamingTrace();
  return {
    isRegularFile: () => true,
    fileSize: async () => trace.length,
    readFile: async () => trace,
    now: () => 123,
    newId: () => 'imported-1',
    ...overrides,
  };
}

describe('importMethodTraceFile', () => {
  it('loads, parses, and labels a complete imported session', async () => {
    const bytes = classicTrace();
    const result = await importMethodTraceFile(
      dependencies({ fileSize: async () => bytes.length, readFile: async () => bytes }),
      { path: '/tmp/cpu.trace', fileName: 'CPU.TRACE' },
    );

    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.record).toMatchObject({
      id: 'imported-1',
      capturedAtEpochMillis: 123,
      origin: 'IMPORTED',
      sourceFileName: 'CPU.TRACE',
      traceVersion: 2,
      eventCount: 2,
      methodCount: 1,
      threadCount: 1,
    });
    expect(result.value.record).not.toHaveProperty('serial');
    expect(result.value.trace).toEqual(bytes);
    expect(result.value.table.stacks).not.toHaveLength(0);
  });

  it('uses an injected asynchronous parser for offline traces', async () => {
    let parseCalls = 0;
    const result = await importMethodTraceFile(
      dependencies({
        parseTrace: async (trace) => {
          parseCalls += 1;
          return parseMethodTraceDirect(trace);
        },
      }),
      { path: '/tmp/cpu.trace', fileName: 'CPU.TRACE' },
    );
    expect(result.ok).toBe(true);
    expect(parseCalls).toBe(1);
  });

  it('rejects missing, unreadable, oversized, and malformed files before persistence', async () => {
    const missing = await importMethodTraceFile(
      dependencies({ isRegularFile: () => false }),
      { path: '/tmp/missing.trace', fileName: 'missing.trace' },
    );
    expect(missing.ok).toBe(false);
    if (!missing.ok) expect(missing.error.code).toBe('METHOD_TRACE_IMPORT_NOT_FOUND');

    const unreadable = await importMethodTraceFile(
      dependencies({
        fileSize: async () => {
          throw new Error('EACCES');
        },
      }),
      { path: '/tmp/locked.trace', fileName: 'locked.trace' },
    );
    expect(unreadable.ok).toBe(false);
    if (!unreadable.ok) expect(unreadable.error.code).toBe('METHOD_TRACE_IMPORT_UNREADABLE');

    const oversized = await importMethodTraceFile(
      dependencies({ fileSize: async () => MAX_METHOD_TRACE_BYTES + 1 }),
      { path: '/tmp/large.trace', fileName: 'large.trace' },
    );
    expect(oversized.ok).toBe(false);
    if (!oversized.ok) expect(oversized.error.code).toBe('METHOD_TRACE_IMPORT_TOO_LARGE');

    const malformed = await importMethodTraceFile(
      dependencies({ readFile: async () => new Uint8Array([1, 2, 3]) }),
      { path: '/tmp/broken.trace', fileName: 'broken.trace' },
    );
    expect(malformed.ok).toBe(false);
    if (!malformed.ok) expect(malformed.error.code).toBe('ART_TRACE_MALFORMED');
  });
});
