import { mkdir, readFile, unlink, writeFile } from 'node:fs/promises';
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

/** Exact raw Visible Window Views evidence, always persisted as a pair. */
export interface LayoutCaptureRawArtifacts {
  readonly zip: Buffer;
  readonly text: string;
}

/** Optional Kotlin-compatible archive entries retained verbatim for re-export. */
export interface LayoutCaptureArchivePayloads {
  readonly analysisReportJson?: string;
  readonly aiAnalysisReportJson?: string;
  readonly timelineHistoryJson?: string;
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

  composeInspectionPath(id: string): string {
    return join(this.directory, id + '.compose.json');
  }

  rawZipPath(id: string): string {
    return join(this.directory, id + '.visible-window-views.zip');
  }

  rawTextPath(id: string): string {
    return join(this.directory, id + '.visible-window-views.txt');
  }

  analysisReportPath(id: string): string {
    return join(this.directory, id + '.analysis-report.json');
  }

  aiAnalysisReportPath(id: string): string {
    return join(this.directory, id + '.ai-analysis-report.json');
  }

  timelineHistoryPath(id: string): string {
    return join(this.directory, id + '.timeline-history.json');
  }

  async add(
    snapshot: LayoutSnapshot,
    screenshotPng: Buffer,
    composeInspectionJson?: string,
    rawArtifacts?: LayoutCaptureRawArtifacts,
    archivePayloads?: LayoutCaptureArchivePayloads,
  ): Promise<LayoutCaptureRecord> {
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
    if (composeInspectionJson === undefined) {
      await unlink(this.composeInspectionPath(id)).catch(() => undefined);
    } else {
      await writeFile(this.composeInspectionPath(id), composeInspectionJson, 'utf8');
    }
    await this.writeRawArtifacts(id, rawArtifacts);
    await this.writeArchivePayloads(id, archivePayloads);
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

  async loadComposeInspection(id: string): Promise<string | undefined> {
    try {
      return await readFile(this.composeInspectionPath(id), 'utf8');
    } catch {
      return undefined;
    }
  }

  async loadArchivePayloads(id: string): Promise<LayoutCaptureArchivePayloads> {
    const [analysisReportJson, aiAnalysisReportJson, timelineHistoryJson] = await Promise.all([
      readOptionalText(this.analysisReportPath(id)),
      readOptionalText(this.aiAnalysisReportPath(id)),
      readOptionalText(this.timelineHistoryPath(id)),
    ]);
    return {
      ...(analysisReportJson !== undefined ? { analysisReportJson } : {}),
      ...(aiAnalysisReportJson !== undefined ? { aiAnalysisReportJson } : {}),
      ...(timelineHistoryJson !== undefined ? { timelineHistoryJson } : {}),
    };
  }

  async loadRawArtifacts(id: string): Promise<LayoutCaptureRawArtifacts | undefined> {
    try {
      const [zip, text] = await Promise.all([
        readFile(this.rawZipPath(id)),
        readFile(this.rawTextPath(id), 'utf8'),
      ]);
      return { zip, text };
    } catch {
      return undefined;
    }
  }

  private async writeArchivePayloads(id: string, payloads: LayoutCaptureArchivePayloads | undefined): Promise<void> {
    await Promise.all([
      writeOptionalText(this.analysisReportPath(id), payloads?.analysisReportJson),
      writeOptionalText(this.aiAnalysisReportPath(id), payloads?.aiAnalysisReportJson),
      writeOptionalText(this.timelineHistoryPath(id), payloads?.timelineHistoryJson),
    ]);
  }

  private async writeRawArtifacts(id: string, rawArtifacts: LayoutCaptureRawArtifacts | undefined): Promise<void> {
    if (rawArtifacts === undefined) {
      await Promise.all([
        unlink(this.rawZipPath(id)).catch(() => undefined),
        unlink(this.rawTextPath(id)).catch(() => undefined),
      ]);
      return;
    }
    try {
      await writeFile(this.rawZipPath(id), rawArtifacts.zip);
      await writeFile(this.rawTextPath(id), rawArtifacts.text, 'utf8');
    } catch (error) {
      await Promise.all([
        unlink(this.rawZipPath(id)).catch(() => undefined),
        unlink(this.rawTextPath(id)).catch(() => undefined),
      ]);
      throw error;
    }
  }
}

async function readOptionalText(path: string): Promise<string | undefined> {
  try {
    return await readFile(path, 'utf8');
  } catch {
    return undefined;
  }
}

async function writeOptionalText(path: string, content: string | undefined): Promise<void> {
  if (content === undefined) {
    await unlink(path).catch(() => undefined);
  } else {
    await writeFile(path, content, 'utf8');
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
