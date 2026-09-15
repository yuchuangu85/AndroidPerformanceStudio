import { copyFile, cp, mkdir, readFile, readdir, rename, rm, stat, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { join } from 'node:path';
import type { CallStackTable } from '@aps/profile-analysis';
import type { CpuProfileSessionRecord } from '@aps/simpleperf-profiler';

const RECORD_FILE = 'session.json';
const SOURCE_SESSION_DIRECTORY = 'source-session';
const PERF_DATA_FILE = 'perf.data';
const SIMPLEPERF_PROTOBUF_FILE = 'simpleperf.protobuf';
const CAPTURE_ARTIFACT_FILE = 'capture-artifact.json';
const SESSION_PROPERTIES_FILE = 'session.properties';

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

export interface CpuProfileSaveOptions {
  /** A verified Kotlin session-package directory retained as opaque provenance. */
  readonly sourceSessionDirectory?: string;
  /** Raw native simpleperf evidence retained before temporary capture cleanup. */
  readonly perfDataPath?: string;
  /** Reusable Kotlin captured-session report, distinct from Electron's report.pb. */
  readonly simpleperfProtobuf?: Uint8Array;
  /** Kotlin CaptureArtifactJson v1 envelope for native captures. */
  readonly captureArtifactJson?: string;
  /** Human-readable capture metadata recognized by Kotlin session tooling. */
  readonly sessionProperties?: string;
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

  sourceSessionDirectoryFor(id: string): string {
    return join(this.directoryFor(id), SOURCE_SESSION_DIRECTORY);
  }


  /**
   * Returns an export-ready captured-session directory. Imported packages keep
   * their source opaque so Electron never drops manifest-listed unknown files.
   */
  async sessionPackageDirectoryFor(id: string): Promise<string | undefined> {
    if (await this.readRecord(id) === undefined) return undefined;
    const source = this.sourceSessionDirectoryFor(id);
    if (await isDirectory(source) && await isRegularFile(join(source, PERF_DATA_FILE))) return source;
    const native = this.directoryFor(id);
    return await isRegularFile(join(native, PERF_DATA_FILE)) ? native : undefined;
  }

  /** Remembers a parsed table so snapshots do not reparse the report. */
  cacheTable(id: string, table: CallStackTable): void {
    this.tables.set(id, table);
  }

  cachedTable(id: string): CallStackTable | undefined {
    return this.tables.get(id);
  }

  async save(
    record: CpuProfileSessionRecord,
    report: Uint8Array,
    table: CallStackTable,
    options: CpuProfileSaveOptions = {},
  ): Promise<void> {
    const directory = this.directoryFor(record.id);
    const staged = join(this.directory, '.' + record.id + '.pending-' + randomUUID());
    await mkdir(this.directory, { recursive: true });
    try {
      await mkdir(staged);
      if (options.sourceSessionDirectory !== undefined) {
        await cp(options.sourceSessionDirectory, join(staged, SOURCE_SESSION_DIRECTORY), {
          recursive: true,
          force: false,
          errorOnExist: true,
        });
      }
      if (options.perfDataPath !== undefined) await copyFile(options.perfDataPath, join(staged, PERF_DATA_FILE));
      if (options.simpleperfProtobuf !== undefined) {
        await writeFile(join(staged, SIMPLEPERF_PROTOBUF_FILE), options.simpleperfProtobuf);
      }
      if (options.captureArtifactJson !== undefined) {
        await writeFile(join(staged, CAPTURE_ARTIFACT_FILE), options.captureArtifactJson, 'utf8');
      }
      if (options.sessionProperties !== undefined) {
        await writeFile(join(staged, SESSION_PROPERTIES_FILE), options.sessionProperties, 'utf8');
      }
      await writeFile(join(staged, reportFileOf(record)), report);
      await writeFile(join(staged, RECORD_FILE), JSON.stringify(record, null, 2));
      await rename(staged, directory);
    } catch (error) {
      await rm(staged, { recursive: true, force: true }).catch(() => undefined);
      throw error;
    }
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


async function isDirectory(path: string): Promise<boolean> {
  return stat(path).then((entry) => entry.isDirectory()).catch(() => false);
}

async function isRegularFile(path: string): Promise<boolean> {
  return stat(path).then((entry) => entry.isFile()).catch(() => false);
}
