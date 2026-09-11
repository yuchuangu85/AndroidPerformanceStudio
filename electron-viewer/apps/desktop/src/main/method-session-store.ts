import { mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { ArtTraceAnalysis } from '@aps/art-trace';
import type { CallStackTable } from '@aps/profile-analysis';
import type { MethodSessionRecord } from '../shared/ipc.js';

const RECORD_FILE = 'session.json';
const TRACE_FILE = 'method.trace';

export type { MethodSessionRecord };

export interface StoredMethodSession {
  readonly record: MethodSessionRecord;
  readonly table: CallStackTable;
  readonly analysis: ArtTraceAnalysis;
}

/**
 * Stores method traces as the raw .trace file plus a JSON record. The trace is
 * the evidence; the call stack table and the method aggregates are cheap to
 * rebuild from it.
 */
export class MethodSessionStore {
  private readonly directory: string;
  private readonly sessions = new Map<string, StoredMethodSession>();

  constructor(directory: string) {
    this.directory = directory;
  }

  directoryFor(id: string): string {
    return join(this.directory, id);
  }

  tracePath(id: string): string {
    return join(this.directoryFor(id), TRACE_FILE);
  }

  cache(session: StoredMethodSession): void {
    this.sessions.set(session.record.id, session);
  }

  cached(id: string): StoredMethodSession | undefined {
    return this.sessions.get(id);
  }

  async save(record: MethodSessionRecord, trace: Uint8Array, session: StoredMethodSession): Promise<void> {
    const directory = this.directoryFor(record.id);
    await mkdir(directory, { recursive: true });
    await writeFile(join(directory, TRACE_FILE), trace);
    await writeFile(join(directory, RECORD_FILE), JSON.stringify(record, null, 2));
    this.sessions.set(record.id, session);
  }

  async list(): Promise<MethodSessionRecord[]> {
    let entries: string[];
    try {
      entries = await readdir(this.directory);
    } catch {
      return [];
    }
    const records: MethodSessionRecord[] = [];
    for (const entry of entries) {
      const record = await this.readRecord(entry);
      if (record !== undefined) records.push(record);
    }
    return records.sort((left, right) => right.capturedAtEpochMillis - left.capturedAtEpochMillis);
  }

  async readRecord(id: string): Promise<MethodSessionRecord | undefined> {
    if (id.includes('/') || id.includes('\\')) return undefined;
    try {
      const parsed: unknown = JSON.parse(await readFile(join(this.directoryFor(id), RECORD_FILE), 'utf8'));
      return isRecord(parsed) ? parsed : undefined;
    } catch {
      return undefined;
    }
  }

  async readTrace(id: string): Promise<Uint8Array | undefined> {
    try {
      return new Uint8Array(await readFile(this.tracePath(id)));
    } catch {
      return undefined;
    }
  }

  async remove(id: string): Promise<boolean> {
    const record = await this.readRecord(id);
    if (record === undefined) return false;
    this.sessions.delete(id);
    await rm(this.directoryFor(id), { recursive: true, force: true });
    return true;
  }
}

function isRecord(value: unknown): value is MethodSessionRecord {
  if (value === null || typeof value !== 'object') return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record['id'] === 'string' &&
    typeof record['capturedAtEpochMillis'] === 'number' &&
    typeof record['packageName'] === 'string' &&
    typeof record['eventCount'] === 'number'
  );
}
