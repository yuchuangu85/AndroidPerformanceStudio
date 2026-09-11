/**
 * Session stores for the two memory artifacts that are not heap-dump sessions:
 * bitmap dumps and heapprofd traces. Same JSON-per-session layout as the other
 * feature stores, so a corrupt file only loses its own session.
 */
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { BitmapDumpSession, NativeHeapCaptureRecord } from '../shared/ipc.js';
import { summarizeBitmapSession, summarizeNativeHeapSession } from './memory-summaries.js';
import type { BitmapSessionSummary, NativeHeapSessionSummary } from '../shared/ipc.js';

const INDEX_FILE = 'index.json';

export class BitmapDumpStore {
  private readonly directory: string;

  constructor(directory: string) {
    this.directory = directory;
  }

  pathFor(id: string): string {
    return join(this.directory, id + '.json');
  }

  async add(session: BitmapDumpSession): Promise<BitmapSessionSummary> {
    await mkdir(this.directory, { recursive: true });
    const summary = summarizeBitmapSession(session);
    await writeFile(this.pathFor(session.id), JSON.stringify(session));
    const records = (await this.list()).filter((entry) => entry.id !== session.id);
    await writeFile(join(this.directory, INDEX_FILE), JSON.stringify([summary, ...records], null, 2));
    return summary;
  }

  async list(): Promise<BitmapSessionSummary[]> {
    try {
      const parsed: unknown = JSON.parse(await readFile(join(this.directory, INDEX_FILE), 'utf8'));
      if (!Array.isArray(parsed)) return [];
      return parsed as BitmapSessionSummary[];
    } catch {
      return [];
    }
  }

  async load(id: string): Promise<BitmapDumpSession | undefined> {
    try {
      const parsed = JSON.parse(await readFile(this.pathFor(id), 'utf8')) as BitmapDumpSession;
      return typeof parsed.id === 'string' && parsed.summary !== undefined ? parsed : undefined;
    } catch {
      return undefined;
    }
  }

  async remove(id: string): Promise<boolean> {
    const existing = await this.load(id);
    if (existing === undefined) return false;
    await rm(this.pathFor(id), { force: true });
    const records = (await this.list()).filter((entry) => entry.id !== id);
    await writeFile(join(this.directory, INDEX_FILE), JSON.stringify(records, null, 2));
    return true;
  }
}

export class NativeHeapStore {
  private readonly directory: string;

  constructor(directory: string) {
    this.directory = directory;
  }

  pathFor(id: string): string {
    return join(this.directory, id + '.json');
  }

  async add(record: NativeHeapCaptureRecord): Promise<NativeHeapSessionSummary> {
    await mkdir(this.directory, { recursive: true });
    const summary = summarizeNativeHeapSession(record);
    await writeFile(this.pathFor(record.id), JSON.stringify(record));
    const records = (await this.list()).filter((entry) => entry.id !== record.id);
    await writeFile(join(this.directory, INDEX_FILE), JSON.stringify([summary, ...records], null, 2));
    return summary;
  }

  async list(): Promise<NativeHeapSessionSummary[]> {
    try {
      const parsed: unknown = JSON.parse(await readFile(join(this.directory, INDEX_FILE), 'utf8'));
      if (!Array.isArray(parsed)) return [];
      return parsed as NativeHeapSessionSummary[];
    } catch {
      return [];
    }
  }

  async load(id: string): Promise<NativeHeapCaptureRecord | undefined> {
    try {
      const parsed = JSON.parse(await readFile(this.pathFor(id), 'utf8')) as NativeHeapCaptureRecord;
      return typeof parsed.id === 'string' && parsed.analysis !== undefined ? parsed : undefined;
    } catch {
      return undefined;
    }
  }

  async remove(id: string): Promise<boolean> {
    const existing = await this.load(id);
    if (existing === undefined) return false;
    await rm(this.pathFor(id), { force: true });
    const records = (await this.list()).filter((entry) => entry.id !== id);
    await writeFile(join(this.directory, INDEX_FILE), JSON.stringify(records, null, 2));
    return true;
  }
}
