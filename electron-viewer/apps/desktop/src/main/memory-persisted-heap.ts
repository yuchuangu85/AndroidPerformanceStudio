import type { HprofParseResult, MemorySession } from '@aps/memory-profiler';
import type { HprofSessionMetadata, HprofWorkerValue } from './parser-worker-protocol.js';
import type { CachedHeap } from './memory-heap-cache.js';

/** Main-process storage boundary for retained raw HPROF evidence. */
export interface PersistedMemoryHeapStore {
  load(id: string): Promise<MemorySession | undefined>;
  readRawHeap(id: string): Promise<Uint8Array | undefined>;
}

/** Keeps parsing/cache policy testable without importing Electron's handler module. */
export interface PersistedMemoryHeapDependencies {
  readonly store: PersistedMemoryHeapStore;
  readonly cached: (sessionId: string) => CachedHeap | undefined;
  readonly cache: (sessionId: string, parsed: HprofParseResult) => CachedHeap;
  readonly parse: (bytes: Uint8Array, metadata: HprofSessionMetadata) => Promise<HprofWorkerValue>;
}

/**
 * Rehydrates a retained HPROF only after an in-memory cache miss. Legacy or
 * incomplete sessions have no raw artifact and deliberately remain unavailable.
 */
export async function loadPersistedMemoryHeap(
  sessionId: string,
  dependencies: PersistedMemoryHeapDependencies,
): Promise<CachedHeap | undefined> {
  const existing = dependencies.cached(sessionId);
  if (existing !== undefined) return existing;

  const [session, rawHeap] = await Promise.all([
    dependencies.store.load(sessionId),
    dependencies.store.readRawHeap(sessionId),
  ]);
  if (session === undefined || rawHeap === undefined) return undefined;

  try {
    const reparsed = await dependencies.parse(rawHeap, sessionMetadata(session));
    return dependencies.cache(session.id, reparsed.parsed);
  } catch {
    return undefined;
  }
}

function sessionMetadata(session: MemorySession): HprofSessionMetadata {
  return {
    id: session.id,
    capturedAtEpochMillis: session.capturedAtEpochMillis,
    ...(session.deviceSerial === undefined ? {} : { deviceSerial: session.deviceSerial }),
    ...(session.packageName === undefined ? {} : { packageName: session.packageName }),
  };
}
