import { mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { SourceIndex } from './source-workspace-service.js';
import type { SourceWorkspaceRecord } from '../shared/ipc.js';

const RECORD_FILE = 'workspace.json';
const INDEX_FILE = 'index.json';

/** A workspace plus its index summary, as shown in the panel. */
export interface StoredSourceWorkspace {
  readonly record: SourceWorkspaceRecord;
  readonly index?: SourceIndex;
}

export class SourceWorkspaceStore {
  private readonly directory: string;
  private readonly indexes = new Map<string, SourceIndex>();

  constructor(directory: string) {
    this.directory = directory;
  }

  directoryFor(id: string): string {
    return join(this.directory, id);
  }

  async save(record: SourceWorkspaceRecord, index: SourceIndex | undefined): Promise<void> {
    const directory = this.directoryFor(record.id);
    await mkdir(directory, { recursive: true });
    await writeFile(join(directory, RECORD_FILE), JSON.stringify(record, null, 2));
    if (index === undefined) {
      this.indexes.delete(record.id);
    } else {
      this.indexes.set(record.id, index);
      // The symbol table is large but cheap to rebuild; only the summary is kept
      // on disk so a restart does not have to parse a multi-megabyte file.
      await writeFile(
        join(directory, INDEX_FILE),
        JSON.stringify(
          {
            snapshot: index.snapshot,
            fileCount: index.files.length,
            symbolCount: index.symbols.length,
          },
          null,
          2,
        ),
      );
    }
  }

  cachedIndex(id: string): SourceIndex | undefined {
    return this.indexes.get(id);
  }

  async list(): Promise<SourceWorkspaceRecord[]> {
    let entries: string[];
    try {
      entries = await readdir(this.directory);
    } catch {
      return [];
    }
    const records: SourceWorkspaceRecord[] = [];
    for (const entry of entries) {
      const record = await this.readRecord(entry);
      if (record !== undefined) records.push(record);
    }
    return records.sort((left, right) => left.displayName.localeCompare(right.displayName));
  }

  async readRecord(id: string): Promise<SourceWorkspaceRecord | undefined> {
    if (id.includes('/') || id.includes('\\')) return undefined;
    try {
      const parsed: unknown = JSON.parse(await readFile(join(this.directoryFor(id), RECORD_FILE), 'utf8'));
      return isRecord(parsed) ? parsed : undefined;
    } catch {
      return undefined;
    }
  }

  async remove(id: string): Promise<boolean> {
    const record = await this.readRecord(id);
    if (record === undefined) return false;
    this.indexes.delete(id);
    await rm(this.directoryFor(id), { recursive: true, force: true });
    return true;
  }
}

function isRecord(value: unknown): value is SourceWorkspaceRecord {
  if (value === null || typeof value !== 'object') return false;
  const record = value as Record<string, unknown>;
  return typeof record['id'] === 'string' && typeof record['root'] === 'string';
}
