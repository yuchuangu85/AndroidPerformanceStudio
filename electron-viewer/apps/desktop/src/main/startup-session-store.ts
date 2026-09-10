import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { StartupSession } from '@aps/startup-profiler';
import type { StartupSessionSummary } from '../shared/ipc.js';

const INDEX_FILE = 'index.json';

/** Stores startup sessions and indexes a summary of their measured statistics. */
export class StartupSessionStore {
  private readonly directory: string;

  constructor(directory: string) {
    this.directory = directory;
  }

  pathFor(id: string): string {
    return join(this.directory, id + '.json');
  }

  async add(session: StartupSession): Promise<StartupSessionSummary> {
    await mkdir(this.directory, { recursive: true });
    const summary = summarizeStartupSession(session);
    await writeFile(this.pathFor(session.id), JSON.stringify(session));
    const records = (await this.list()).filter((existing) => existing.id !== session.id);
    await writeFile(join(this.directory, INDEX_FILE), JSON.stringify([summary, ...records], null, 2));
    return summary;
  }

  async list(): Promise<StartupSessionSummary[]> {
    try {
      const parsed: unknown = JSON.parse(await readFile(join(this.directory, INDEX_FILE), 'utf8'));
      if (!Array.isArray(parsed)) return [];
      return parsed.filter(isSummary);
    } catch {
      return [];
    }
  }

  async load(id: string): Promise<StartupSession | undefined> {
    try {
      const parsed = JSON.parse(await readFile(this.pathFor(id), 'utf8')) as StartupSession;
      if (typeof parsed.id !== 'string' || !Array.isArray(parsed.runs)) return undefined;
      return parsed;
    } catch {
      return undefined;
    }
  }
}

export function summarizeStartupSession(session: StartupSession): StartupSessionSummary {
  const statistics = session.statistics.totalTimeMs;
  return {
    id: session.id,
    packageName: session.packageName,
    capturedAtEpochMillis: session.createdAtEpochMillis,
    measuredRuns: session.statistics.measuredRuns,
    ...(statistics.medianMs !== undefined ? { medianTotalTimeMs: statistics.medianMs } : {}),
    ...(statistics.p90Ms !== undefined ? { p90TotalTimeMs: statistics.p90Ms } : {}),
    p90LowResolution: statistics.p90LowResolution,
  };
}

function isSummary(value: unknown): value is StartupSessionSummary {
  if (value === null || typeof value !== 'object') return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record['id'] === 'string' &&
    typeof record['packageName'] === 'string' &&
    typeof record['capturedAtEpochMillis'] === 'number' &&
    typeof record['measuredRuns'] === 'number' &&
    typeof record['p90LowResolution'] === 'boolean'
  );
}
