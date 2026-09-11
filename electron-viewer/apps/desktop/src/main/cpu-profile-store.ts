import { mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { CallStackTable } from '@aps/profile-analysis';
import type { CpuProfileSessionRecord } from '@aps/simpleperf-profiler';

const RECORD_FILE = 'session.json';

/** Captured sessions keep the converted report; imports keep their source. */
export function reportFileOf(record: CpuProfileSessionRecord): string {
  const separator = record.reportFile.lastIndexOf('/');
  return separator === -1 ? record.reportFile : record.reportFile.slice(separator + 1);
}

export function defaultReportFile(sourceFormat: string | undefined): string {
  return sourceFormat === 'GECKO_PROFILE_JSON_GZIP' ? 'gecko-profile.json.gz' : 'report.pb';
}

export interface StoredCpuProfile {
  readonly record: CpuProfileSessionRecord;
  readonly table: CallStackTable;
}

/**
 * Stores CPU profiles as the retained protobuf report plus a JSON record. The
 * report is what the parser reads, so the call-stack table can always be
 * rebuilt; keeping the parsed table on disk instead would duplicate a large
 * structure that is cheap to recompute.
 */
export class CpuProfileStore {
  private readonly directory: string;
  private readonly tables = new Map<string, CallStackTable>();

  constructor(directory: string) {
    this.directory = directory;
  }

  directoryFor(id: string): string {
    return join(this.directory, id);
  }

  reportPath(id: string, sourceFormat?: string): string {
    return join(this.directoryFor(id), defaultReportFile(sourceFormat));
  }

  /** Remembers a parsed table so snapshots do not reparse the report. */
  cacheTable(id: string, table: CallStackTable): void {
    this.tables.set(id, table);
  }

  cachedTable(id: string): CallStackTable | undefined {
    return this.tables.get(id);
  }

  async save(record: CpuProfileSessionRecord, report: Uint8Array, table: CallStackTable): Promise<void> {
    const directory = this.directoryFor(record.id);
    await mkdir(directory, { recursive: true });
    await writeFile(join(directory, reportFileOf(record)), report);
    await writeFile(join(directory, RECORD_FILE), JSON.stringify(record, null, 2));
    this.tables.set(record.id, table);
    await writeFile(join(this.directory, 'index.json'), JSON.stringify(await this.list(), null, 2));
  }

  async list(): Promise<CpuProfileSessionRecord[]> {
    let entries: string[];
    try {
      entries = await readdir(this.directory);
    } catch {
      return [];
    }
    const records: CpuProfileSessionRecord[] = [];
    for (const entry of entries) {
      const record = await this.readRecord(entry);
      if (record !== undefined) records.push(record);
    }
    return records.sort((left, right) => right.capturedAtEpochMillis - left.capturedAtEpochMillis);
  }

  async readRecord(id: string): Promise<CpuProfileSessionRecord | undefined> {
    if (id.includes('/') || id.includes('\\') || id === 'index.json') return undefined;
    try {
      const parsed: unknown = JSON.parse(await readFile(join(this.directoryFor(id), RECORD_FILE), 'utf8'));
      return isRecord(parsed) ? parsed : undefined;
    } catch {
      return undefined;
    }
  }

  async readReport(record: CpuProfileSessionRecord): Promise<Uint8Array | undefined> {
    try {
      return new Uint8Array(await readFile(join(this.directoryFor(record.id), reportFileOf(record))));
    } catch {
      return undefined;
    }
  }

  async remove(id: string): Promise<boolean> {
    const record = await this.readRecord(id);
    if (record === undefined) return false;
    this.tables.delete(id);
    await rm(this.directoryFor(id), { recursive: true, force: true });
    await writeFile(join(this.directory, 'index.json'), JSON.stringify(await this.list(), null, 2));
    return true;
  }
}

function isRecord(value: unknown): value is CpuProfileSessionRecord {
  if (value === null || typeof value !== 'object') return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record['id'] === 'string' &&
    typeof record['capturedAtEpochMillis'] === 'number' &&
    typeof record['reportFile'] === 'string' &&
    typeof record['sampleCount'] === 'number'
  );
}
