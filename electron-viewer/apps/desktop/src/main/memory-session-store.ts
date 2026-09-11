import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { MemorySession } from '@aps/memory-profiler';
import type { MemorySessionSummary } from '../shared/ipc.js';

const INDEX_FILE = 'index.json';

/** Stores heap-dump analysis sessions; the raw dump itself is never persisted. */
export class MemorySessionStore {
  private readonly directory: string;

  constructor(directory: string) {
    this.directory = directory;
  }

  pathFor(id: string): string {
    return join(this.directory, id + '.json');
  }

  async add(session: MemorySession): Promise<MemorySessionSummary> {
    await mkdir(this.directory, { recursive: true });
    const summary = summarizeMemorySession(session);
    await writeFile(this.pathFor(session.id), JSON.stringify(session));
    const records = (await this.list()).filter((existing) => existing.id !== session.id);
    await writeFile(join(this.directory, INDEX_FILE), JSON.stringify([summary, ...records], null, 2));
    return summary;
  }

  async list(): Promise<MemorySessionSummary[]> {
    try {
      const parsed: unknown = JSON.parse(await readFile(join(this.directory, INDEX_FILE), 'utf8'));
      if (!Array.isArray(parsed)) return [];
      return parsed.filter(isSummary);
    } catch {
      return [];
    }
  }

  async load(id: string): Promise<MemorySession | undefined> {
    try {
      const parsed = JSON.parse(await readFile(this.pathFor(id), 'utf8')) as MemorySession;
      if (typeof parsed.id !== 'string' || parsed.summary === undefined) return undefined;
      return parsed;
    } catch {
      return undefined;
    }
  }
}

export function summarizeMemorySession(session: MemorySession): MemorySessionSummary {
  return {
    id: session.id,
    capturedAtEpochMillis: session.capturedAtEpochMillis,
    instanceCount: session.summary.instanceCount,
    classCount: session.summary.classCount,
    shallowBytes: session.summary.shallowBytes,
    suspectCount: session.suspects.length,
    warningCount: session.warnings.length,
    ...(session.packageName !== undefined ? { packageName: session.packageName } : {}),
    ...(session.deviceSerial !== undefined ? { deviceSerial: session.deviceSerial } : {}),
    ...(session.deep !== undefined
      ? {
          bitmapCount: session.deep.bitmaps.length,
          activityLeakCount: session.deep.activityLeaks.length,
          deepSuspectCount: session.deep.suspects.length,
        }
      : {}),
  };
}

function isSummary(value: unknown): value is MemorySessionSummary {
  if (value === null || typeof value !== 'object') return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record['id'] === 'string' &&
    typeof record['capturedAtEpochMillis'] === 'number' &&
    typeof record['instanceCount'] === 'number' &&
    typeof record['shallowBytes'] === 'number'
  );
}
