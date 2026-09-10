import { copyFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { TraceRecordSummary } from '../shared/ipc.js';

const INDEX_FILE = 'index.json';

/** Stores captured traces in a flat directory with a JSON index. */
export class TraceStore {
  private readonly directory: string;

  constructor(directory: string) {
    this.directory = directory;
  }

  directoryPath(): string {
    return this.directory;
  }

  pathFor(id: string): string {
    return join(this.directory, id + '.pftrace');
  }

  async addTrace(
    sourcePath: string,
    meta: {
      readonly sha256: string;
      readonly capturedAtEpochMillis: number;
      readonly durationMillis: number;
      readonly deviceSerial?: string;
    },
  ): Promise<TraceRecordSummary> {
    await mkdir(this.directory, { recursive: true });
    const id = String(meta.capturedAtEpochMillis) + '-' + meta.sha256.slice(0, 8);
    const target = this.pathFor(id);
    await copyFile(sourcePath, target);
    const record: TraceRecordSummary = {
      id,
      path: target,
      sha256: meta.sha256,
      capturedAtEpochMillis: meta.capturedAtEpochMillis,
      durationMillis: meta.durationMillis,
      ...(meta.deviceSerial !== undefined ? { deviceSerial: meta.deviceSerial } : {}),
    };
    const records = await this.list();
    const withoutDuplicate = records.filter((existing) => existing.id !== id);
    await writeFile(join(this.directory, INDEX_FILE), JSON.stringify([record, ...withoutDuplicate], null, 2));
    return record;
  }

  async list(): Promise<TraceRecordSummary[]> {
    try {
      const parsed: unknown = JSON.parse(await readFile(join(this.directory, INDEX_FILE), 'utf8'));
      if (!Array.isArray(parsed)) return [];
      return parsed.filter(isTraceRecord);
    } catch {
      return [];
    }
  }
}

function isTraceRecord(value: unknown): value is TraceRecordSummary {
  if (value === null || typeof value !== 'object') return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record['id'] === 'string' &&
    typeof record['path'] === 'string' &&
    typeof record['sha256'] === 'string' &&
    typeof record['capturedAtEpochMillis'] === 'number' &&
    typeof record['durationMillis'] === 'number'
  );
}
