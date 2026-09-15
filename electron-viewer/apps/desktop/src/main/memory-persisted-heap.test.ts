import { describe, expect, it } from 'vitest';
import type { HprofParseResult, MemorySession } from '@aps/memory-profiler';
import type { CachedHeap } from './memory-heap-cache.js';
import { loadPersistedMemoryHeap } from './memory-persisted-heap.js';

const session: MemorySession = {
  id: 'saved',
  capturedAtEpochMillis: 123,
  deviceSerial: 'emulator-5554',
  packageName: 'com.example.app',
  summary: { version: '1.0.3', identifierSize: 4, classCount: 0, instanceCount: 0, arrayCount: 0, shallowBytes: 0 },
  histogram: [],
  suspects: [],
  warnings: [],
};

function dependencies(overrides: Partial<Parameters<typeof loadPersistedMemoryHeap>[1]> = {}) {
  const restored = { result: {} as HprofParseResult, graph: {} as CachedHeap['graph'], analysis: {} as CachedHeap['analysis'] } as CachedHeap;
  return {
    store: {
      load: async () => session,
      readRawHeap: async () => new Uint8Array([1, 2, 3]),
    },
    cached: () => undefined,
    cache: () => restored,
    parse: async () => ({ session, parsed: {} as HprofParseResult }),
    ...overrides,
  };
}

describe('loadPersistedMemoryHeap', () => {
  it('uses an in-memory cache without touching retained raw evidence', async () => {
    const cached = {} as CachedHeap;
    const load = await loadPersistedMemoryHeap('saved', dependencies({
      cached: () => cached,
      store: {
        load: async () => { throw new Error('should not load'); },
        readRawHeap: async () => { throw new Error('should not read'); },
      },
    }));

    expect(load).toBe(cached);
  });

  it('reparses a retained HPROF with persisted session provenance after cache loss', async () => {
    const parsed = {} as HprofParseResult;
    const cacheCalls: Array<{ id: string; parsed: HprofParseResult }> = [];
    const parseCalls: Array<{ bytes: Uint8Array; metadata: object }> = [];
    const restored = {} as CachedHeap;
    const load = await loadPersistedMemoryHeap('saved', dependencies({
      parse: async (bytes, metadata) => {
        parseCalls.push({ bytes, metadata });
        return { session, parsed };
      },
      cache: (id, value) => {
        cacheCalls.push({ id, parsed: value });
        return restored;
      },
    }));

    expect(load).toBe(restored);
    expect(parseCalls).toEqual([{ bytes: new Uint8Array([1, 2, 3]), metadata: {
      id: 'saved', capturedAtEpochMillis: 123, deviceSerial: 'emulator-5554', packageName: 'com.example.app',
    } }]);
    expect(cacheCalls).toEqual([{ id: 'saved', parsed }]);
  });

  it('keeps legacy, missing, and malformed raw evidence unavailable', async () => {
    const legacy = await loadPersistedMemoryHeap('legacy', dependencies({
      store: { load: async () => session, readRawHeap: async () => undefined },
    }));
    const malformed = await loadPersistedMemoryHeap('bad', dependencies({
      parse: async () => { throw new Error('bad dump'); },
    }));

    expect(legacy).toBeUndefined();
    expect(malformed).toBeUndefined();
  });
});
