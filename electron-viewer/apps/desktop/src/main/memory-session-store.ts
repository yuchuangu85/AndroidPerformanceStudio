import { mkdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { MemorySession } from '@aps/memory-profiler';
import type { MemorySessionSummary } from '../shared/ipc.js';

const INDEX_FILE = 'index.json';
const MAX_RETAINED_RAW_HEAP_BYTES = 2 * 1024 * 1024 * 1024;

/** Stores derived sessions and raw HPROF evidence needed for historical instance browsing. */
export class MemorySessionStore {
  private readonly directory: string;

  constructor(directory: string) {
    this.directory = directory;
  }

  pathFor(id: string): string {
    return join(this.directory, id + '.json');
  }

  rawPathFor(id: string): string {
    return join(this.directory, id + '.hprof');
  }

  stagedRawPathFor(id: string): string {
    return join(this.directory, '.' + id + '.hprof.pending');
  }

  /** Writes raw evidence before parsing transfers the source buffer to a worker. */
  async stageRawHeap(id: string, bytes: Uint8Array): Promise<void> {
    await mkdir(this.directory, { recursive: true });
    await writeFile(this.stagedRawPathFor(id), bytes, { flag: 'wx' });
  }

  /** Removes a staged capture that failed before producing a valid session. */
  async discardStagedRawHeap(id: string): Promise<void> {
    await rm(this.stagedRawPathFor(id), { force: true });
  }

  /** Commits a session only after its staged raw HPROF is available for future reparse. */
  async addCaptured(session: MemorySession): Promise<MemorySessionSummary> {
    await mkdir(this.directory, { recursive: true });
    const stagedRawPath = this.stagedRawPathFor(session.id);
    const rawPath = this.rawPathFor(session.id);
    await rename(stagedRawPath, rawPath);
    try {
      return await this.add(session);
    } catch (error) {
      await Promise.all([rm(rawPath, { force: true }), rm(this.pathFor(session.id), { force: true })]);
      const records = (await this.list()).filter((existing) => existing.id !== session.id);
      await writeFile(join(this.directory, INDEX_FILE), JSON.stringify(records, null, 2)).catch(() => undefined);
      throw error;
    }
  }

  /** Returns main-process-owned raw HPROF bytes, if the session retained them. */
  async readRawHeap(id: string): Promise<Uint8Array | undefined> {
    try {
      const path = this.rawPathFor(id);
      const details = await stat(path);
      if (!details.isFile() || details.size > MAX_RETAINED_RAW_HEAP_BYTES) return undefined;
      return new Uint8Array(await readFile(path));
    } catch {
      return undefined;
    }
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
