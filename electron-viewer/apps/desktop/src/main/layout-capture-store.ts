import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { decodeLayoutSnapshot, type LayoutSnapshot } from '@aps/layout-inspector';

const INDEX_FILE = 'index.json';

export interface LayoutCaptureRecord {
  readonly id: string;
  readonly packageName: string;
  readonly capturedAtEpochMillis: number;
  readonly nodeCount: number;
}

export interface LayoutCaptureStoreDependencies {
  readonly countNodes: (snapshot: LayoutSnapshot) => number;
}

/** Stores layout captures as <id>.json plus <id>.png with a JSON index. */
export class LayoutCaptureStore {
  private readonly directory: string;
  private readonly dependencies: LayoutCaptureStoreDependencies;

  constructor(directory: string, dependencies: LayoutCaptureStoreDependencies) {
    this.directory = directory;
    this.dependencies = dependencies;
  }

  snapshotPath(id: string): string {
    return join(this.directory, id + '.json');
  }

  screenshotPath(id: string): string {
    return join(this.directory, id + '.png');
  }

  async add(snapshot: LayoutSnapshot, screenshotPng: Buffer): Promise<LayoutCaptureRecord> {
    await mkdir(this.directory, { recursive: true });
    const id = String(snapshot.capturedAtEpochMillis);
    const record: LayoutCaptureRecord = {
      id,
      packageName: snapshot.packageName,
      capturedAtEpochMillis: snapshot.capturedAtEpochMillis,
      nodeCount: this.dependencies.countNodes(snapshot),
    };
    await writeFile(this.snapshotPath(id), JSON.stringify(snapshot));
    await writeFile(this.screenshotPath(id), screenshotPng);
    const records = (await this.list()).filter((existing) => existing.id !== id);
    await writeFile(join(this.directory, INDEX_FILE), JSON.stringify([record, ...records], null, 2));
    return record;
  }

  async list(): Promise<LayoutCaptureRecord[]> {
    try {
      const parsed: unknown = JSON.parse(await readFile(join(this.directory, INDEX_FILE), 'utf8'));
      if (!Array.isArray(parsed)) return [];
      return parsed.filter(isRecord);
    } catch {
      return [];
    }
  }

  async loadSnapshot(id: string): Promise<LayoutSnapshot | undefined> {
    try {
      return decodeLayoutSnapshot(await readFile(this.snapshotPath(id), 'utf8'));
    } catch {
      return undefined;
    }
  }

  async loadScreenshot(id: string): Promise<Buffer | undefined> {
    try {
      return await readFile(this.screenshotPath(id));
    } catch {
      return undefined;
    }
  }
}

function isRecord(value: unknown): value is LayoutCaptureRecord {
  if (value === null || typeof value !== 'object') return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record['id'] === 'string' &&
    typeof record['packageName'] === 'string' &&
    typeof record['capturedAtEpochMillis'] === 'number' &&
    typeof record['nodeCount'] === 'number'
  );
}
