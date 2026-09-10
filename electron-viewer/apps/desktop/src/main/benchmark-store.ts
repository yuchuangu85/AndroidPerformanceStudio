import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { BenchmarkRun } from '@aps/benchmark-regression';
import type { BenchmarkRunSummary } from '../shared/ipc.js';

const INDEX_FILE = 'index.json';

/** Stores benchmark runs and indexes the environment needed for comparability. */
export class BenchmarkStore {
  private readonly directory: string;

  constructor(directory: string) {
    this.directory = directory;
  }

  pathFor(id: string): string {
    return join(this.directory, id + '.json');
  }

  async add(run: BenchmarkRun): Promise<BenchmarkRunSummary> {
    await mkdir(this.directory, { recursive: true });
    const summary = summarizeBenchmarkRun(run);
    await writeFile(this.pathFor(run.id), JSON.stringify(run));
    const records = (await this.list()).filter((existing) => existing.id !== run.id);
    await writeFile(join(this.directory, INDEX_FILE), JSON.stringify([summary, ...records], null, 2));
    return summary;
  }

  async list(): Promise<BenchmarkRunSummary[]> {
    try {
      const parsed: unknown = JSON.parse(await readFile(join(this.directory, INDEX_FILE), 'utf8'));
      if (!Array.isArray(parsed)) return [];
      return parsed.filter(isSummary);
    } catch {
      return [];
    }
  }

  async load(id: string): Promise<BenchmarkRun | undefined> {
    try {
      const parsed = JSON.parse(await readFile(this.pathFor(id), 'utf8')) as BenchmarkRun;
      if (typeof parsed.id !== 'string' || !Array.isArray(parsed.cases)) return undefined;
      return parsed;
    } catch {
      return undefined;
    }
  }
}

export function summarizeBenchmarkRun(run: BenchmarkRun): BenchmarkRunSummary {
  return {
    id: run.id,
    sourceFile: run.sourceFile,
    caseCount: run.cases.length,
    importedAtEpochMillis: run.importedAtEpochMillis,
    warningCount: run.warnings.length,
    ...(run.device.model !== undefined ? { deviceModel: run.device.model } : {}),
    ...(run.device.apiLevel !== undefined ? { apiLevel: run.device.apiLevel } : {}),
    ...(run.device.abi !== undefined ? { abi: run.device.abi } : {}),
    ...(run.build.variant !== undefined ? { variant: run.build.variant } : {}),
  };
}

function isSummary(value: unknown): value is BenchmarkRunSummary {
  if (value === null || typeof value !== 'object') return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record['id'] === 'string' &&
    typeof record['sourceFile'] === 'string' &&
    typeof record['caseCount'] === 'number' &&
    typeof record['importedAtEpochMillis'] === 'number'
  );
}
